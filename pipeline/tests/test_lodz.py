"""Region „lodz": ŁKA + PolRegio do Łodzi Kaliskiej, tylko kursy dowożące do celu."""
import gzip
import json
import shutil
import sqlite3
import sys
import zipfile
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_timetable as bt  # noqa: E402

FIXTURE = Path(__file__).parent / "fixtures" / "lodz"
TODAY = "2026-10-26"


def make_zip(src: Path, dest: Path) -> Path:
    with zipfile.ZipFile(dest, "w") as zf:
        for f in sorted(src.iterdir()):
            zf.write(f, f.name)
    return dest


def run(src: Path, tmp: Path) -> int:
    pt = make_zip(src, tmp / "pt.zip")
    # Bez --wkd: region łódzki nie pobiera WKD (domyślny URL nie może zostać użyty).
    return bt.main(["--region", "lodz", "--pt", str(pt), "--out", str(tmp / "out"), "--today", TODAY])


@pytest.fixture
def src(tmp_path):
    shutil.copytree(FIXTURE, tmp_path / "lodz")
    return tmp_path / "lodz"


@pytest.fixture
def con(src, tmp_path):
    assert run(src, tmp_path) == 0
    out = tmp_path / "out"
    manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
    db = tmp_path / "t.sqlite"
    with gzip.open(out / manifest["url"], "rb") as s, open(db, "wb") as d:
        shutil.copyfileobj(s, d)
    c = sqlite3.connect(db)
    c.row_factory = sqlite3.Row
    yield c
    c.close()


def names(con):
    return {r[0] for r in con.execute("SELECT name FROM station")}


def headsigns(con):
    return {r[0] for r in con.execute("SELECT headsign FROM trip")}


def test_default_region_is_lodz():
    assert bt.DEFAULT_REGION == "lodz"
    assert "PKP Intercity" not in bt.REGIONS["lodz"].agencies


def test_only_lka_and_polregio_no_intercity(con):
    assert {r[0] for r in con.execute("SELECT DISTINCT agency FROM trip")} == {"ŁKA", "PR"}
    assert con.execute("SELECT count(*) FROM trip").fetchone()[0] == 2  # L1, P1


def test_trips_not_reaching_kaliska_dropped(con):
    # L2 rusza z Kaliskiej, P2 jej nie mija, P3 nie pozwala tam wysiąść.
    assert "Kraków Główny" not in names(con)
    assert headsigns(con) == {"Łódź Kaliska", "Łódź Widzew"}


def test_trip_trimmed_after_target(con):
    tid = con.execute("SELECT id FROM trip WHERE agency='PR'").fetchone()[0]
    st = con.execute(
        "SELECT s.name FROM stop_time t JOIN station s ON s.id=t.station_id WHERE trip_id=? ORDER BY seq", (tid,)
    ).fetchall()
    assert [r[0] for r in st] == ["Sieradz", "Pabianice", "Łódź Kaliska"]
    assert "Łódź Widzew" not in names(con)


def test_default_target_kaliska(con):
    meta = dict(con.execute("SELECT key, value FROM meta").fetchall())
    assert json.loads(meta["default_targets"]) == [[1000]]
    assert "WKD" not in meta["source_attribution"]
    assert "mkuran.pl" in meta["source_attribution"]
    assert con.execute("SELECT agencies FROM station WHERE id=1000").fetchone()[0] == "ŁKA,PR"


def test_missing_polregio_fails(src, tmp_path):
    agency = src / "agency.txt"
    agency.write_text(agency.read_text(encoding="utf-8").replace("POLREGIO", "Regio"), encoding="utf-8")
    assert run(src, tmp_path) == 1


def test_trim_to_targets():
    t = lambda key, flags=0: [0, key, 0, 0, None, None, flags]  # noqa: E731
    k = {"pt:K"}
    assert bt.trim_to_targets([t("pt:A"), t("pt:K"), t("pt:B")], k) == [t("pt:A"), t("pt:K")]
    assert bt.trim_to_targets([t("pt:K"), t("pt:B")], k) is None
    assert bt.trim_to_targets([t("pt:A"), t("pt:K", bt.FLAG_NO_DROP_OFF)], k) is None
    # Pętla: przycinamy po ostatnim wystąpieniu celu.
    assert len(bt.trim_to_targets([t("pt:A"), t("pt:K"), t("pt:B"), t("pt:K"), t("pt:C")], k)) == 4
