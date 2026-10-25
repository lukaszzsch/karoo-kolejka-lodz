import datetime as dt
import gzip
import hashlib
import json
import shutil
import sqlite3
import sys
import zipfile
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_timetable as bt  # noqa: E402

FIXTURES = Path(__file__).parent / "fixtures"
TODAY = dt.date(2026, 10, 26)  # poniedziałek


def make_zip(src: Path, dest: Path) -> Path:
    with zipfile.ZipFile(dest, "w") as zf:
        for f in sorted(src.iterdir()):
            zf.write(f, f.name)
    return dest


@pytest.fixture
def feeds_dir(tmp_path):
    """Kopie fixture, żeby testy mogły je modyfikować."""
    for name in ("pt", "wkd"):
        shutil.copytree(FIXTURES / name, tmp_path / name)
    return tmp_path


def run(feeds_dir: Path, out: Path, *extra: str) -> int:
    pt = make_zip(feeds_dir / "pt", feeds_dir / "pt.zip")
    wkd = make_zip(feeds_dir / "wkd", feeds_dir / "wkd.zip")
    return bt.main(["--region", "warszawa", "--pt", str(pt), "--wkd", str(wkd), "--out", str(out), "--today", TODAY.isoformat(), *extra])


@pytest.fixture
def built(feeds_dir, tmp_path):
    out = tmp_path / "out"
    assert run(feeds_dir, out) == 0
    manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
    db = tmp_path / "timetable.sqlite"
    with gzip.open(out / manifest["url"], "rb") as src, open(db, "wb") as dst:
        shutil.copyfileobj(src, dst)
    con = sqlite3.connect(db)
    con.row_factory = sqlite3.Row
    yield con, manifest, out
    con.close()


def trip_id(con, first_dep):
    """ID kursu po czasie odjazdu z pierwszego przystanku (w fixture unikalne)."""
    row = con.execute(
        "SELECT trip_id FROM stop_time WHERE seq=0 AND dep_s=?", (first_dep,)
    ).fetchone()
    return row["trip_id"]


def hms(h, m, s=0):
    return h * 3600 + m * 60 + s


def station(con, name):
    return con.execute("SELECT * FROM station WHERE name=?", (name,)).fetchone()


def stop_times(con, tid):
    return con.execute("SELECT * FROM stop_time WHERE trip_id=? ORDER BY seq", (tid,)).fetchall()


def dates(con, tid):
    return [r[0] for r in con.execute("SELECT service_date FROM trip_date WHERE trip_id=? ORDER BY 1", (tid,))]


# --------------------------------------------------------------------------- filtrowanie


def test_only_km_skm_wkd(built):
    con, _, _ = built
    assert {r[0] for r in con.execute("SELECT DISTINCT agency FROM trip")} == {"KM", "SKM", "WKD"}
    assert station(con, "Kraków Główny") is None


def test_trip_outside_window_and_single_stop_trip_dropped(built):
    con, _, _ = built
    assert con.execute("SELECT count(*) FROM stop_time WHERE seq=0 AND dep_s=?", (hms(6, 0),)).fetchone()[0] == 1  # tylko W2
    assert con.execute("SELECT count(*) FROM stop_time WHERE dep_s=?", (hms(6, 10),)).fetchone()[0] == 0


def test_station_ids_stable_and_agencies(built):
    con, _, _ = built
    assert station(con, "Mińsk Mazowiecki")["id"] == 100
    assert station(con, "Mińsk Mazowiecki")["agencies"] == "KM"
    assert station(con, "Warszawa Zachodnia")["agencies"] == "KM,SKM"
    wkd = station(con, "Warszawa Śródmieście WKD")
    assert wkd["id"] >= bt.WKD_ID_BASE
    assert wkd["agencies"] == "WKD"


def test_name_norm_without_diacritics(built):
    con, _, _ = built
    assert station(con, "Mińsk Mazowiecki")["name_norm"] == "minsk mazowiecki"
    assert bt.normalize_name("Łódź Fabryczna") == "lodz fabryczna"


# --------------------------------------------------------------------------- kalendarz i czas


def test_calendar_with_removal_exception(built):
    con, _, _ = built
    t1 = trip_id(con, hms(17, 42))
    # pn–pt w oknie 25.10–09.11, bez 27.10 (exception_type=2)
    assert dates(con, t1) == [20261026, 20261028, 20261029, 20261030, 20261102,
                              20261103, 20261104, 20261105, 20261106, 20261109]


def test_trip_after_midnight(built):
    con, _, _ = built
    t2 = trip_id(con, hms(23, 50))
    assert dates(con, t2) == [20261031]  # tylko calendar_dates
    st = stop_times(con, t2)
    assert st[1]["arr_s"] == hms(24, 20) > 86400


def test_missing_times_interpolated(built):
    con, _, _ = built
    st = stop_times(con, trip_id(con, hms(8, 0)))
    assert [r["seq"] for r in st] == [0, 1, 2]
    assert st[1]["arr_s"] == st[1]["dep_s"] == hms(8, 10)


def test_pickup_drop_off_flags(built):
    con, _, _ = built
    st = stop_times(con, trip_id(con, hms(17, 42)))
    assert [r["flags"] for r in st] == [bt.FLAG_NO_DROP_OFF, 0, 0, bt.FLAG_NO_PICKUP]


# --------------------------------------------------------------------------- perony, ZKA


def test_platform_from_stop_times_and_platform_code_fallback(built):
    con, _, _ = built
    st = stop_times(con, trip_id(con, hms(17, 42)))
    assert (st[0]["platform"], st[0]["track"]) == ("1", "2")  # z platform_code „1 t. 2"
    assert (st[1]["platform"], st[1]["track"]) == ("2", "2")  # z kolumn stop_times
    assert st[0]["station_id"] == 100  # peron → stacja nadrzędna


def test_split_platform_code():
    assert bt.split_platform_code("2 t. 3") == ("2", "3")
    assert bt.split_platform_code("1/2 t. 3") == ("1/2", "3")
    assert bt.split_platform_code("5") == ("5", None)
    assert bt.split_platform_code("") == (None, None)


def test_bus_replacement(built):
    con, _, _ = built
    t3 = trip_id(con, hms(10, 0))
    trip = con.execute("SELECT * FROM trip WHERE id=?", (t3,)).fetchone()
    assert trip["is_bus"] == 1 and trip["route_short_name"] == "Z2"
    st = stop_times(con, t3)
    assert [r["station_id"] for r in st] == [100, 200]
    assert st[0]["platform"] is None


def test_wkd_trip_and_bus_stop_mapped_to_station(built):
    con, _, _ = built
    w1 = trip_id(con, hms(7, 0))
    assert con.execute("SELECT agency, route_short_name FROM trip WHERE id=?", (w1,)).fetchone()[:] == ("WKD", "WKD")
    w2 = trip_id(con, hms(6, 0))
    st = stop_times(con, w2)
    assert st[1]["station_id"] == station(con, "Warszawa Ochota WKD")["id"]  # wocho_bus_d0 → wocho
    assert st[0]["station_id"] == station(con, "PKP Pruszków")["id"]  # brak stacji kolejowej → własna
    assert station(con, "Plac Zawiszy") is None


# --------------------------------------------------------------------------- grupy i cele


def test_twin_stations_grouped_not_merged(built):
    con, _, _ = built
    srod, srod_wkd = station(con, "Warszawa Śródmieście"), station(con, "Warszawa Śródmieście WKD")
    assert srod["id"] != srod_wkd["id"]
    assert srod["group_id"] is not None and srod["group_id"] == srod_wkd["group_id"]
    assert station(con, "Warszawa Zachodnia")["group_id"] == station(con, "Warszawa Zachodnia WKD")["group_id"]
    assert station(con, "Warszawa Zachodnia")["group_id"] != srod["group_id"]
    assert station(con, "Mińsk Mazowiecki")["group_id"] is None


def test_default_targets(built):
    con, _, _ = built
    meta = dict(con.execute("SELECT key, value FROM meta").fetchall())
    srod_wkd = station(con, "Warszawa Śródmieście WKD")["id"]
    assert json.loads(meta["default_targets"]) == [[300], [200], [400, srod_wkd]]


def test_missing_default_target_fails_without_output(feeds_dir, tmp_path):
    stops = feeds_dir / "pt" / "stops.txt"
    stops.write_text(stops.read_text(encoding="utf-8").replace("Warszawa Gdańska", "Gdańsk"), encoding="utf-8")
    out = tmp_path / "out"
    assert run(feeds_dir, out) == 1
    assert not (out / "manifest.json").exists()


def test_missing_agency_fails(feeds_dir, tmp_path):
    agency = feeds_dir / "pt" / "agency.txt"
    agency.write_text(agency.read_text(encoding="utf-8").replace("SKM Warszawa", "SKM"), encoding="utf-8")
    assert run(feeds_dir, tmp_path / "out") == 1


# --------------------------------------------------------------------------- walidacja i manifest


def test_meta_and_manifest(built):
    con, manifest, out = built
    meta = dict(con.execute("SELECT key, value FROM meta").fetchall())
    assert meta["schema_version"] == "1"
    assert meta["valid_from"] == "2026-10-25"
    assert meta["valid_to"] == "2026-11-09"
    assert "Koleje Mazowieckie" in meta["source_attribution"] and "CC0" in meta["source_attribution"]
    gz = out / manifest["url"]
    assert manifest["sha256"] == hashlib.sha256(gz.read_bytes()).hexdigest()
    assert manifest["size_bytes"] == gz.stat().st_size
    assert manifest["version"].startswith("20261026-")
    assert manifest["valid_to"] == meta["valid_to"]
    assert con.execute("PRAGMA integrity_check").fetchone()[0] == "ok"


def test_short_validity_fails(feeds_dir, tmp_path):
    cal = feeds_dir / "wkd" / "calendar_dates.txt"
    lines = cal.read_text(encoding="utf-8").splitlines()
    cal.write_text("\n".join(lines[:6]) + "\n", encoding="utf-8")  # WKD tylko do 29.10
    assert run(feeds_dir, tmp_path / "out") == 1

