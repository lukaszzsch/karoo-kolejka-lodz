# Pociąg do Łodzi — rozszerzenie Hammerhead Karoo

Rozszerzenie [karoo-ext](https://github.com/hammerheadnav/karoo-ext) na **awaryjny powrót
pociągiem z treningu**. Bez trasy, z bieżącej pozycji: szuka stacji w promieniu 20 km
i pokazuje najbliższy pociąg ŁKA / PolRegio do **Łodzi Kaliskiej** — w dwóch wariantach:
🚲 rowerem (15 km/h) i 🚶 pieszo z rowerem (5 km/h). Wybiera najwcześniejszy przyjazd do
Kaliskiej, a nie zawsze najbliższą stację. Bez PKP Intercity (rower tylko z rezerwacją) i bez
autobusów zastępczych. Szczegóły: [SPEC.md](SPEC.md), sekcja 6.10.

Powstało z „Pociąg do Warszawy" (ten sam kod, `NEARBY_MODE = false` i `--region warszawa`
dają wersję warszawską). Projekt na własny użytek (sideload).

## Stan

- [x] Faza 1 — pipeline danych (`pipeline/`, codzienny workflow → `gh-pages`)
- [~] Faza 0 — spike na urządzeniu: kod gotowy, pomiary na Karoo do zrobienia ([procedura](docs/device-findings.md))
- [x] Faza 3 — domena (`domain/`, czysty Kotlin, 62 testy)
- [ ] Fazy 2, 4–7 — dane na Androidzie, symulacja, kafelek, aplikacja, testy na rowerze

## Budowanie

Wymaga JDK 17 i Android SDK (`local.properties` → `sdk.dir=…`). karoo-ext 1.1.9 bierze się z
`mavenLocal`, gdzie trafia po zbudowaniu ze źródeł:

```bash
git clone --depth 1 --branch 1.1.9 https://github.com/hammerheadnav/karoo-ext.git /tmp/karoo-ext
(cd /tmp/karoo-ext && ./gradlew :lib:publishToMavenLocal -Pgpr.user=x -Pgpr.key=x)
./gradlew test :app:assembleDebug
```

Drugie źródło to GitHub Packages z tokenem `read:packages`. Token wpisz w
`~/.gradle/gradle.properties` jako `gpr.user` / `gpr.key`, nigdy w repo.

## Pipeline

```bash
cd pipeline
uv run --python 3.12 --with-requirements requirements.txt pytest
uv run --python 3.12 python build_timetable.py --out dist                 # region lodz
uv run --python 3.12 python build_timetable.py --out dist --region warszawa
```

## Instalacja na Karoo

`adb install -r app-release.apk`. **Po każdej aktualizacji zrestartuj Karoo**, bo serwis
rozszerzenia nie zawsze sam się restartuje.

## Dane

Rozkład: Łódzka Kolej Aglomeracyjna, PolRegio (PKP PLK, warunki ponownego wykorzystania
informacji sektora publicznego). Feed GTFS przygotowuje [Mikołaj Kuranowski](https://mkuran.pl/gtfs/).
