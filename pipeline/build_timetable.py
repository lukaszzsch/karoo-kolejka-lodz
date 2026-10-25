#!/usr/bin/env python3
"""Buduje mały plik SQLite z rozkładem dla rozszerzenia Karoo (region `--region`).

Regiony (`REGIONS`): `lodz` — ŁKA + PolRegio do Łodzi Kaliskiej (domyślny), `warszawa` —
KM + SKM Warszawa + WKD. Wejście: GTFS `polish_trains.zip` i (dla Warszawy) `wkd.zip` (mkuran.pl). Wyjście w katalogu `--out`:
`manifest.json` oraz `timetable-<version>.sqlite.gz`. Szczegóły: SPEC.md, sekcja 3.

Schemat SQLite (schema_version = 1) — jak w SPEC 3.4, z odstępstwami:
- `station.id` jest **stabilne między wersjami** (ustawienia na urządzeniu przechowują ID stacji
  docelowych): dla polish_trains to numeryczne ID stacji nadrzędnej PLK, dla WKD
  10 000 000 + crc32(stop_id) mod 10 000 000.
- `stop_time.flags`: bit 0 = brak wsiadania (pickup_type=1), bit 1 = brak wysiadania
  (drop_off_type=1). Odjazdy szukamy z `flags & 1 = 0`, przyjazdy do celu z `flags & 2 = 0`.
- `station_group` łączy stacje o tej samej nazwie bazowej (bez sufiksu „WKD" i nawiasów)
  w promieniu `GROUP_RADIUS_M` (600 m — „Warszawa Śródmieście" i „Warszawa Śródmieście WKD"
  dzieli ~525 m, więc 400 m z SPEC nie wystarcza).

Kod korzysta wyłącznie z biblioteki standardowej.
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import gzip
import hashlib
import io
import json
import logging
import math
import os
import re
import shutil
import sqlite3
import sys
import tempfile
import unicodedata
import urllib.error
import urllib.request
import zipfile
import zlib
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from zoneinfo import ZoneInfo

log = logging.getLogger("build_timetable")

SCHEMA_VERSION = 1
TZ = ZoneInfo("Europe/Warsaw")

PT_URL = "https://mkuran.pl/gtfs/polish_trains.zip"
WKD_URL = "https://mkuran.pl/gtfs/wkd.zip"



@dataclass(frozen=True)
class Region:
    """Konfiguracja regionu: przewoźnicy, cele i dodatkowe feedy.

    `agencies`: nazwa przewoźnika w agency.txt polish_trains → kod w bazie (dopasowanie bez
    wielkości liter). `only_via_targets`: zostają tylko kursy z wysiadaniem na stacji celu,
    przycięte po ostatnim takim przystanku — konieczne, gdy przewoźnik jeździ po całym kraju
    (PolRegio), bo inaczej baza przekroczy MAX_DB_BYTES.
    """
    agencies: dict
    targets: list
    with_wkd: bool
    only_via_targets: bool
    attribution: tuple = ()


REGIONS = {
    "warszawa": Region(
        agencies={"Koleje Mazowieckie": "KM", "SKM Warszawa": "SKM"},
        targets=[
            ["Warszawa Gdańska"],
            ["Warszawa Wileńska"],
            ["Warszawa Śródmieście", "Warszawa Śródmieście WKD"],
        ],
        with_wkd=True,
        only_via_targets=False,
        attribution=("Data: Warszawska Kolej Dojazdowa (CC0), GTFS: Mikołaj Kuranowski (https://mkuran.pl/gtfs/)",),
    ),
    # Awaryjny powrót do Łodzi: bez PKP Intercity (rower tylko z rezerwacją).
    "lodz": Region(
        agencies={"Łódzka Kolej Aglomeracyjna": "ŁKA", "PolRegio": "PR"},
        targets=[["Łódź Kaliska"]],
        with_wkd=False,
        only_via_targets=True,
        attribution=("GTFS: Mikołaj Kuranowski (https://mkuran.pl/gtfs/)",),
    ),
}
DEFAULT_REGION = "lodz"

WINDOW_BEFORE_DAYS = 1
WINDOW_AFTER_DAYS = 14
MIN_VALID_AHEAD_DAYS = 7
MAX_DB_BYTES = 10 * 1024 * 1024
GROUP_RADIUS_M = 600.0
WKD_ID_BASE = 10_000_000
FALLBACK_ID_BASE = 20_000_000
# Dane opublikowane nie dawniej niż tyle dni temu i niezmienione źródła → bez publikacji.
REBUILD_WHEN_VALID_AHEAD_BELOW_DAYS = 12

FLAG_NO_PICKUP = 1
FLAG_NO_DROP_OFF = 2


class BuildError(Exception):
    """Błąd danych — nic nie jest publikowane."""


# --------------------------------------------------------------------------- pomocnicze


def read_csv(zf: zipfile.ZipFile, name: str, required: bool = True):
    try:
        raw = zf.open(name)
    except KeyError:
        if required:
            raise BuildError(f"Brak pliku {name} w {zf.filename}")
        return
    with raw, io.TextIOWrapper(raw, encoding="utf-8-sig", newline="") as text:
        for row in csv.DictReader(text):
            yield {k.strip(): (v.strip() if v is not None else "") for k, v in row.items() if k}


def parse_gtfs_time(value: str) -> int | None:
    if not value:
        return None
    h, m, s = value.split(":")
    return int(h) * 3600 + int(m) * 60 + int(s)


def parse_date(value: str) -> dt.date:
    return dt.date(int(value[0:4]), int(value[4:6]), int(value[6:8]))


def date_int(d: dt.date) -> int:
    return d.year * 10000 + d.month * 100 + d.day


def normalize_name(name: str) -> str:
    # „ł" nie rozkłada się w NFKD, więc zamieniamy ręcznie.
    s = name.replace("ł", "l").replace("Ł", "L")
    s = unicodedata.normalize("NFKD", s)
    s = "".join(c for c in s if not unicodedata.combining(c))
    return " ".join(s.lower().split())


def base_name(name_norm: str) -> str:
    s = re.sub(r"\s*\(.*?\)\s*", " ", name_norm)
    s = re.sub(r"\s+wkd$", "", s.strip())
    return " ".join(s.split())


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6_371_000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


_PLATFORM_CODE = re.compile(r"^(\S+)\s+t\.\s*(\S+)$")


def split_platform_code(code: str) -> tuple[str | None, str | None]:
    """„2 t. 3" → ("2", "3"); „5" → ("5", None)."""
    if not code:
        return None, None
    m = _PLATFORM_CODE.match(code)
    if m:
        return m.group(1), m.group(2)
    return code, None


# --------------------------------------------------------------------------- model


@dataclass
class Stop:
    name: str
    lat: float
    lon: float
    station_key: str  # klucz stacji nadrzędnej (z prefiksem źródła)
    platform: str | None
    track: str | None


@dataclass
class Trip:
    key: str
    agency: str
    route_short_name: str
    headsign: str
    is_bus: bool
    dates: list[int]
    stop_times: list[tuple] = field(default_factory=list)  # (seq, station_key, arr, dep, platform, track, flags)


@dataclass
class Station:
    key: str
    id: int
    name: str
    lat: float
    lon: float
    agencies: set[str] = field(default_factory=set)
    group_id: int | None = None


@dataclass
class Feed:
    prefix: str
    trips: list[Trip]
    stations: dict[str, Station]
    feed_version: str
    attribution: list[str]


# --------------------------------------------------------------------------- wczytywanie


def service_dates(zf: zipfile.ZipFile, wanted: set[str], window: tuple[dt.date, dt.date]) -> dict[str, set[int]]:
    start, end = window
    result: dict[str, set[int]] = defaultdict(set)
    for row in read_csv(zf, "calendar.txt", required=False) or ():
        sid = row["service_id"]
        if sid not in wanted:
            continue
        days = [row[d] == "1" for d in ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")]
        d = max(parse_date(row["start_date"]), start)
        last = min(parse_date(row["end_date"]), end)
        while d <= last:
            if days[d.weekday()]:
                result[sid].add(date_int(d))
            d += dt.timedelta(days=1)
    for row in read_csv(zf, "calendar_dates.txt", required=False) or ():
        sid = row["service_id"]
        if sid not in wanted:
            continue
        d = parse_date(row["date"])
        if not (start <= d <= end):
            continue
        if row["exception_type"] == "1":
            result[sid].add(date_int(d))
        elif row["exception_type"] == "2":
            result[sid].discard(date_int(d))
    return result


def station_id_for(prefix: str, raw_id: str) -> int:
    if prefix == "pt" and raw_id.isdigit() and int(raw_id) < WKD_ID_BASE:
        return int(raw_id)
    base = WKD_ID_BASE if prefix == "wkd" else FALLBACK_ID_BASE
    return base + zlib.crc32(raw_id.encode()) % 10_000_000


def load_stops(zf: zipfile.ZipFile, prefix: str) -> tuple[dict[str, Stop], dict[str, dict]]:
    rows = {r["stop_id"]: r for r in read_csv(zf, "stops.txt")}
    stops: dict[str, Stop] = {}
    for sid, r in rows.items():
        if r.get("location_type", "0") not in ("", "0"):
            continue  # stacje (1), wejścia (2) itd. same nie mają odjazdów
        parent = r.get("parent_station", "")
        if not parent:
            # WKD: przystanki ZKA „wocho_bus_d0" → stacja „wocho".
            m = re.match(r"^(.+?)_bus(_d\d+)?$", sid)
            if m and m.group(1) in rows:
                parent = m.group(1)
        station_raw = parent if parent in rows else sid
        platform, track = split_platform_code(r.get("platform_code", ""))
        stops[sid] = Stop(
            name=r["stop_name"],
            lat=float(r["stop_lat"]),
            lon=float(r["stop_lon"]),
            station_key=f"{prefix}:{station_raw}",
            platform=platform,
            track=track,
        )
    return stops, rows


def fill_missing_times(times: list[list]) -> None:
    """Uzupełnia puste arrival/departure (dozwolone w GTFS poza punktami czasowymi)."""
    for t in times:
        if t[2] is None:
            t[2] = t[3]
        if t[3] is None:
            t[3] = t[2]
    known = [i for i, t in enumerate(times) if t[2] is not None]
    if not known:
        raise BuildError("Kurs bez żadnych czasów")
    for a, b in zip(known, known[1:]):
        for i in range(a + 1, b):
            frac = (i - a) / (b - a)
            v = round(times[a][3] + (times[b][2] - times[a][3]) * frac)
            times[i][2] = times[i][3] = v
    if known[0] != 0 or known[-1] != len(times) - 1:
        raise BuildError("Kurs bez czasu na pierwszym lub ostatnim przystanku")


def trim_to_targets(times: list[list], target_keys: set[str]) -> list[list] | None:
    """Przycina kurs po ostatnim przystanku-celu z wysiadaniem; None, gdy kurs nie dowozi do celu.

    Cel na pierwszym przystanku się nie liczy (nie ma skąd do niego dojechać).
    """
    last = None
    for i, t in enumerate(times):
        if i > 0 and t[1] in target_keys and not t[6] & FLAG_NO_DROP_OFF:
            last = i
    return times[: last + 1] if last is not None else None


def load_feed(path: Path, prefix: str, window: tuple[dt.date, dt.date], region: Region) -> Feed:
    log.info("Wczytywanie %s (%s)", path, prefix)
    with zipfile.ZipFile(path) as zf:
        agencies = list(read_csv(zf, "agency.txt"))
        if prefix == "pt":
            agency_map: dict[str, str] = {}
            for name, code in region.agencies.items():
                ids = [a.get("agency_id", "") for a in agencies if a["agency_name"].casefold() == name.casefold()]
                if not ids:
                    raise BuildError(f"Brak przewoźnika „{name}\" w agency.txt")
                for aid in ids:
                    agency_map[aid] = code
                log.info("Przewoźnik %s → agency_id %s", name, ", ".join(ids))
        else:
            agency_map = {a.get("agency_id", ""): "WKD" for a in agencies}
        single_agency = agencies[0].get("agency_id", "") if len(agencies) == 1 else None

        routes = {}
        for r in read_csv(zf, "routes.txt"):
            aid = r.get("agency_id") or single_agency or ""
            if aid not in agency_map:
                continue
            rtype = int(r["route_type"])
            routes[r["route_id"]] = (agency_map[aid], r.get("route_short_name", ""), rtype == 3 or 700 <= rtype <= 799)

        trip_rows = [t for t in read_csv(zf, "trips.txt") if t["route_id"] in routes]
        dates = service_dates(zf, {t["service_id"] for t in trip_rows}, window)
        trips: dict[str, Trip] = {}
        for t in trip_rows:
            d = dates.get(t["service_id"])
            if not d:
                continue
            agency, short, is_bus = routes[t["route_id"]]
            trips[t["trip_id"]] = Trip(
                key=f"{prefix}:{t['trip_id']}",
                agency=agency,
                route_short_name=short,
                headsign=t.get("trip_headsign", ""),
                is_bus=is_bus,
                dates=sorted(d),
            )
        log.info("%s: %d kursów w oknie (z %d dla przewoźników)", prefix, len(trips), len(trip_rows))

        stops, stop_rows = load_stops(zf, prefix)
        raw_times: dict[str, list[list]] = defaultdict(list)
        for st in read_csv(zf, "stop_times.txt"):
            tid = st["trip_id"]
            if tid not in trips:
                continue
            stop = stops.get(st["stop_id"])
            if stop is None:
                raise BuildError(f"stop_times odwołuje się do nieznanego przystanku {st['stop_id']}")
            platform = st.get("platform") or stop.platform
            track = st.get("track") or (stop.track if not st.get("platform") else None)
            flags = 0
            if st.get("pickup_type") == "1":
                flags |= FLAG_NO_PICKUP
            if st.get("drop_off_type") == "1":
                flags |= FLAG_NO_DROP_OFF
            raw_times[tid].append([
                int(st["stop_sequence"]), stop.station_key,
                parse_gtfs_time(st.get("arrival_time", "")), parse_gtfs_time(st.get("departure_time", "")),
                platform or None, track or None, flags,
            ])

        target_names = {n for group in region.targets for n in group}
        target_keys = {
            s.station_key for s in stops.values()
            if stop_rows[s.station_key.split(":", 1)[1]]["stop_name"] in target_names
        }
        stations: dict[str, Station] = {}
        dropped = 0
        for tid, times in list(raw_times.items()):
            times.sort(key=lambda x: x[0])
            fill_missing_times(times)
            if region.only_via_targets:
                trimmed = trim_to_targets(times, target_keys)
                if trimmed is None:
                    del raw_times[tid]
                    dropped += 1
                    continue
                times[:] = trimmed
            trip = trips[tid]
            trip.stop_times = [(i, *t[1:]) for i, t in enumerate(times)]
            for t in times:
                key = t[1]
                if key not in stations:
                    raw = key.split(":", 1)[1]
                    r = stop_rows[raw]
                    stations[key] = Station(
                        key=key, id=station_id_for(prefix, raw), name=r["stop_name"],
                        lat=float(r["stop_lat"]), lon=float(r["stop_lon"]),
                    )
                stations[key].agencies.add(trip.agency)
        if region.only_via_targets:
            log.info("%s: odrzucono %d kursów niedojeżdżających do celu", prefix, dropped)
        # Kursy z mniej niż dwoma przystankami są bezużyteczne.
        kept = [t for t in trips.values() if len(t.stop_times) >= 2]

        feed_info = next(iter(read_csv(zf, "feed_info.txt", required=False) or ()), {})
        attribution = [
            a["organization_name"] + (f" ({a['attribution_url']})" if a.get("attribution_url") else "")
            for a in read_csv(zf, "attributions.txt", required=False) or ()
        ]
    return Feed(prefix, kept, stations, feed_info.get("feed_version", ""), attribution)


# --------------------------------------------------------------------------- scalanie


def assign_groups(stations: list[Station]) -> list[tuple[str, str, float]]:
    """Ustawia group_id dla stacji-bliźniaków; zwraca listę par do zalogowania."""
    by_base: dict[str, list[Station]] = defaultdict(list)
    for s in stations:
        by_base[base_name(normalize_name(s.name))].append(s)
    parent: dict[int, int] = {}

    def find(x: int) -> int:
        while parent.get(x, x) != x:
            x = parent[x]
        return x

    pairs = []
    for group in by_base.values():
        for i, a in enumerate(group):
            for b in group[i + 1:]:
                d = haversine_m(a.lat, a.lon, b.lat, b.lon)
                if d <= GROUP_RADIUS_M:
                    ra, rb = find(a.id), find(b.id)
                    parent[max(ra, rb)] = min(ra, rb)
                    pairs.append((a.name, b.name, d))
    members: dict[int, list[Station]] = defaultdict(list)
    for s in stations:
        members[find(s.id)].append(s)
    for root, ms in members.items():
        if len(ms) > 1:
            for s in ms:
                s.group_id = root
    return pairs


def resolve_default_targets(stations: list[Station], region: Region) -> list[list[int]]:
    by_name: dict[str, list[Station]] = defaultdict(list)
    for s in stations:
        by_name[s.name].append(s)
    result = []
    for group in region.targets:
        ids = []
        for name in group:
            found = by_name.get(name)
            if not found:
                raise BuildError(f"Nie znaleziono domyślnej stacji docelowej „{name}\"")
            if len(found) > 1:
                raise BuildError(f"Niejednoznaczna stacja docelowa „{name}\": {[s.id for s in found]}")
            ids.append(found[0].id)
        result.append(ids)
    return result


# --------------------------------------------------------------------------- SQLite

SCHEMA = """
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE station(
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL,
  name_norm TEXT NOT NULL,
  lat REAL NOT NULL, lon REAL NOT NULL,
  agencies TEXT NOT NULL,
  group_id INTEGER
);
CREATE TABLE trip(
  id INTEGER PRIMARY KEY,
  agency TEXT NOT NULL,
  route_short_name TEXT,
  headsign TEXT,
  is_bus INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE trip_date(trip_id INTEGER, service_date INTEGER,
  PRIMARY KEY(trip_id, service_date)) WITHOUT ROWID;
CREATE TABLE stop_time(
  trip_id INTEGER NOT NULL,
  seq INTEGER NOT NULL,
  station_id INTEGER NOT NULL,
  arr_s INTEGER NOT NULL,
  dep_s INTEGER NOT NULL,
  platform TEXT,
  track TEXT,
  flags INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(trip_id, seq)
) WITHOUT ROWID;
CREATE INDEX stop_time_station_dep ON stop_time(station_id, dep_s);
"""



@dataclass
class BuildResult:
    db_path: Path
    meta: dict[str, str]
    platform_coverage: dict[str, float]


def build_database(feeds: list[Feed], today: dt.date, out_path: Path, region: Region) -> BuildResult:
    agency_codes = list(region.agencies.values()) + (["WKD"] if region.with_wkd else [])
    agency_order = {code: i for i, code in enumerate(agency_codes)}
    stations_by_key: dict[str, Station] = {}
    for f in feeds:
        stations_by_key.update(f.stations)
    stations = sorted(stations_by_key.values(), key=lambda s: s.id)
    seen: dict[int, str] = {}
    for s in stations:
        if s.id in seen:
            raise BuildError(f"Kolizja ID stacji {s.id}: {seen[s.id]} / {s.key}")
        seen[s.id] = s.key

    for a, b, d in assign_groups(stations):
        log.info("Grupa stacji: %s ↔ %s (%.0f m)", a, b, d)
    targets = resolve_default_targets(stations, region)

    trips = sorted((t for f in feeds for t in f.trips), key=lambda t: t.key)
    valid_from = today - dt.timedelta(days=WINDOW_BEFORE_DAYS)
    last_date: dict[str, int] = {}
    with_platform: dict[str, int] = defaultdict(int)
    total: dict[str, int] = defaultdict(int)

    if out_path.exists():
        out_path.unlink()
    con = sqlite3.connect(out_path)
    try:
        con.execute("PRAGMA journal_mode=OFF")
        con.executescript(SCHEMA)
        con.executemany(
            "INSERT INTO station VALUES (?,?,?,?,?,?,?)",
            [
                (s.id, s.name, normalize_name(s.name), round(s.lat, 6), round(s.lon, 6),
                 ",".join(sorted(s.agencies, key=agency_order.get)), s.group_id)
                for s in stations
            ],
        )
        for trip_id, t in enumerate(trips, start=1):
            con.execute("INSERT INTO trip VALUES (?,?,?,?,?)",
                        (trip_id, t.agency, t.route_short_name or None, t.headsign or None, int(t.is_bus)))
            con.executemany("INSERT INTO trip_date VALUES (?,?)", [(trip_id, d) for d in t.dates])
            con.executemany(
                "INSERT INTO stop_time VALUES (?,?,?,?,?,?,?,?)",
                [(trip_id, seq, stations_by_key[key].id, arr, dep, plat, track, flags)
                 for seq, key, arr, dep, plat, track, flags in t.stop_times],
            )
            last_date[t.agency] = max(last_date.get(t.agency, 0), t.dates[-1])
            if not t.is_bus:
                total[t.agency] += len(t.stop_times)
                with_platform[t.agency] += sum(1 for st in t.stop_times if st[4])

        for agency in agency_codes:
            if agency not in last_date:
                raise BuildError(f"Brak kursów przewoźnika {agency} w oknie dat")
        valid_to_int = min(last_date.values())
        valid_to = parse_date(str(valid_to_int))
        meta = {
            "schema_version": str(SCHEMA_VERSION),
            "feed_version": " | ".join(f"{f.prefix}:{f.feed_version}" for f in feeds),
            "generated_at": dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat(),
            "valid_from": valid_from.isoformat(),
            "valid_to": valid_to.isoformat(),
            "source_attribution": "\n".join(
                [a for f in feeds for a in f.attribution]
                + list(region.attribution)
            ),
            "default_targets": json.dumps(targets),
        }
        con.executemany("INSERT INTO meta VALUES (?,?)", sorted(meta.items()))
        con.commit()
        con.execute("VACUUM")
        con.execute("ANALYZE")
        con.commit()
    finally:
        con.close()

    coverage = {a: (with_platform[a] / total[a] if total[a] else 0.0) for a in sorted(total)}
    for a, c in coverage.items():
        log.info("Pokrycie peronów %s: %.1f%% (%d stop_times, bez ZKA)", a, c * 100, total[a])
    return BuildResult(out_path, meta, coverage)


def validate(result: BuildResult, today: dt.date) -> None:
    con = sqlite3.connect(f"file:{result.db_path}?mode=ro", uri=True)
    try:
        if con.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise BuildError("integrity_check nie przeszedł")
        for table in ("station", "trip", "trip_date", "stop_time"):
            n = con.execute(f"SELECT count(*) FROM {table}").fetchone()[0]
            log.info("Tabela %s: %d wierszy", table, n)
            if n == 0:
                raise BuildError(f"Pusta tabela {table}")
        targets = json.loads(result.meta["default_targets"])
        for group in targets:
            for sid in group:
                if not con.execute("SELECT 1 FROM station WHERE id=?", (sid,)).fetchone():
                    raise BuildError(f"Stacja docelowa {sid} nie istnieje w bazie")
    finally:
        con.close()
    valid_to = dt.date.fromisoformat(result.meta["valid_to"])
    if valid_to < today + dt.timedelta(days=MIN_VALID_AHEAD_DAYS):
        raise BuildError(f"valid_to {valid_to} < dziś+{MIN_VALID_AHEAD_DAYS}")
    size = result.db_path.stat().st_size
    log.info("Rozmiar bazy: %.2f MB", size / 1e6)
    if size > MAX_DB_BYTES:
        raise BuildError(f"Baza za duża: {size} B")


# --------------------------------------------------------------------------- publikacja


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def write_outputs(result: BuildResult, out_dir: Path, sources: dict, today: dt.date) -> dict:
    out_dir.mkdir(parents=True, exist_ok=True)
    db_sha = sha256_file(result.db_path)
    version = f"{today:%Y%m%d}-{db_sha[:8]}"
    gz_name = f"timetable-{version}.sqlite.gz"
    gz_path = out_dir / gz_name
    # mtime=0 i brak nazwy pliku w nagłówku → ten sam plik daje ten sam .gz.
    with open(result.db_path, "rb") as src, open(gz_path, "wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=0) as gz:
            shutil.copyfileobj(src, gz)
    manifest = {
        "schema_version": SCHEMA_VERSION,
        "version": version,
        "sha256": sha256_file(gz_path),
        "size_bytes": gz_path.stat().st_size,
        "url": gz_name,  # względem URL manifestu
        "generated_at": result.meta["generated_at"],
        "valid_from": result.meta["valid_from"],
        "valid_to": result.meta["valid_to"],
        "sources": sources,
    }
    (out_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    log.info("Zapisano %s (%.2f MB) i manifest.json", gz_name, manifest["size_bytes"] / 1e6)
    return manifest


# --------------------------------------------------------------------------- pobieranie


def fetch(url: str, dest: Path, previous: dict | None) -> tuple[bool, dict]:
    """Pobiera `url` do `dest`. Zwraca (czy_zmieniony, {etag, last_modified}).

    Gdy serwer odpowie 304, a `dest` istnieje lub nie jest potrzebny, nic nie pobiera.
    """
    headers = {"User-Agent": "karoo-pociag-pipeline"}
    if previous:
        if previous.get("etag"):
            headers["If-None-Match"] = previous["etag"]
        if previous.get("last_modified"):
            headers["If-Modified-Since"] = previous["last_modified"]
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=120) as resp, open(dest, "wb") as f:
            shutil.copyfileobj(resp, f)
            info = {"etag": resp.headers.get("ETag"), "last_modified": resp.headers.get("Last-Modified")}
        log.info("Pobrano %s (%.1f MB)", url, dest.stat().st_size / 1e6)
        return True, info
    except urllib.error.HTTPError as e:
        if e.code == 304:
            log.info("Bez zmian: %s", url)
            return False, dict(previous or {})
        raise


def resolve_input(arg: str, cache_dir: Path, name: str, previous: dict | None, force: bool) -> tuple[Path | None, bool, dict]:
    if not re.match(r"^https?://", arg):
        return Path(arg), True, {}
    dest = cache_dir / name
    changed, info = fetch(arg, dest, None if force else previous)
    return (dest if changed else None), changed, info


# --------------------------------------------------------------------------- main


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--pt", default=PT_URL, help="ścieżka lub URL polish_trains.zip")
    p.add_argument("--wkd", default=WKD_URL, help="ścieżka lub URL wkd.zip (tylko region z WKD)")
    p.add_argument("--region", choices=sorted(REGIONS), default=DEFAULT_REGION, help="region: przewoźnicy i stacje docelowe")
    p.add_argument("--out", type=Path, required=True, help="katalog wyjściowy")
    p.add_argument("--cache", type=Path, default=None, help="katalog na pobrane pliki")
    p.add_argument("--today", type=dt.date.fromisoformat, default=None, help="data „dziś\" (Europe/Warsaw)")
    p.add_argument("--previous-manifest", type=Path, default=None, help="poprzednio opublikowany manifest.json")
    p.add_argument("--force", action="store_true", help="buduj nawet bez zmian w źródłach")
    args = p.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")

    today = args.today or dt.datetime.now(TZ).date()
    window = (today - dt.timedelta(days=WINDOW_BEFORE_DAYS), today + dt.timedelta(days=WINDOW_AFTER_DAYS))
    previous = None
    if args.previous_manifest and args.previous_manifest.exists():
        previous = json.loads(args.previous_manifest.read_text(encoding="utf-8"))
    prev_sources = (previous or {}).get("sources", {})
    stale = True
    if previous and previous.get("schema_version") == SCHEMA_VERSION:
        prev_valid_to = dt.date.fromisoformat(previous["valid_to"])
        stale = prev_valid_to < today + dt.timedelta(days=REBUILD_WHEN_VALID_AHEAD_BELOW_DAYS)

    tmp = Path(tempfile.mkdtemp(prefix="timetable-"))
    cache = args.cache or tmp
    cache.mkdir(parents=True, exist_ok=True)
    try:
        region = REGIONS[args.region]
        log.info("Region: %s", args.region)
        inputs = {"pt": args.pt}
        if region.with_wkd:
            inputs["wkd"] = args.wkd
        paths: dict[str, Path | None] = {}
        sources: dict[str, dict] = {}
        any_changed = False
        for prefix, arg in inputs.items():
            path, changed, info = resolve_input(arg, cache, f"{prefix}.zip", prev_sources.get(arg), args.force or stale)
            paths[prefix], sources[arg] = path, info
            any_changed |= changed
        if not any_changed and not args.force:
            log.info("Źródła bez zmian, poprzednie dane aktualne — brak publikacji")
            set_output("publish", "false")
            return 0
        for prefix, arg in inputs.items():
            if paths[prefix] is None:  # 304, ale inne źródło się zmieniło → pobierz bezwarunkowo
                paths[prefix], _, sources[arg] = resolve_input(arg, cache, f"{prefix}.zip", None, True)

        feeds = [load_feed(paths[prefix], prefix, window, region) for prefix in inputs]
        result = build_database(feeds, today, tmp / "timetable.sqlite", region)
        validate(result, today)
        write_outputs(result, args.out, {k: v for k, v in sources.items() if v}, today)
        set_output("publish", "true")
        return 0
    except BuildError as e:
        log.error("Błąd danych, brak publikacji: %s", e)
        set_output("publish", "false")
        return 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def set_output(name: str, value: str) -> None:
    path = os.environ.get("GITHUB_OUTPUT")
    if path:
        with open(path, "a", encoding="utf-8") as f:
            f.write(f"{name}={value}\n")


if __name__ == "__main__":
    sys.exit(main())
