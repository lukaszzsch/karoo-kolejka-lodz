# SPEC — „Pociąg do Warszawy" (rozszerzenie Hammerhead Karoo)

> Dokument dla Claude Code. Czytaj w całości przed pierwszą zmianą w repo.
> Projekt na własny użytek (sideload), repo publiczne. Budowany według najlepszych praktyk: **stabilność i energooszczędność mają priorytet nad liczbą funkcji.**
> Stan wiedzy: październik 2026, karoo-ext **1.1.9** (najnowszy release), wymaga KOS ≥ 1.634.2440.
> Wersja specu: 2 (po decyzjach właściciela z 24.10.2026).

---

## 0. TL;DR

Rozszerzenie karoo-ext, które:

1. Przy starcie urządzenia sprawdza, czy jest nowszy rozkład **KM + SKM Warszawa + WKD**, i jeśli tak — pobiera **mały, wstępnie przetworzony plik SQLite** (nie surowy GTFS) i zapisuje go lokalnie.
2. Po wybraniu (lub zmianie) trasy sprawdza, czy **meta** leży ≤ 1 km od stacji. Jeśli tak — bierze ETA z Karoo, dolicza dojazd do stacji + zapas i znajduje pociągi do **stacji docelowych** (domyślnie: Warszawa Gdańska → Wileńska → Śródmieście; lista konfigurowalna, dowolnej długości).
3. Od razu po wybraniu trasy wysyła **powiadomienie** z podsumowaniem („Meta przy stacji X, pociągi: …").
4. Pokazuje **kafelek** (graficzne pole danych) w kilku rozmiarach: od jednego pociągu po listę 3 pociągów z wymaganą prędkością („zdążę?"), peronem i oznaczeniem ostatniego pociągu.
5. Kliknięcie kafelka otwiera aplikację: **Meta** (z przyciskiem „Prowadź do stacji"), **Po drodze**, **Wszystkie stacje** (lista + wyszukiwarka). Bez trasy kafelek mówi „Brak trasy", a kliknięcie otwiera rozkład.

---

## 1. Decyzje architektoniczne

### 1.1 Dane: preprocessing w chmurze, nie na Karoo

Źródła (Mikołaj Kuranowski, mkuran.pl):
- `https://mkuran.pl/gtfs/polish_trains.zip` — zawiera m.in. **Koleje Mazowieckie i SKM Warszawa**; aktualizowany codziennie. (Osobny feed KM został wycofany na rzecz tego pliku.)
- `https://mkuran.pl/gtfs/wkd.zip` — **WKD**, licencja CC0.

`polish_trains.zip` ma **~29,5 MB spakowany / ~111 MB rozpakowany** (cała Polska). Pobieranie i parsowanie tego na Karoo = duży transfer (bez Wi-Fi Karoo idzie przez mostek Bluetooth z telefonem, który ma bardzo niską przepustowość), duże zużycie CPU/RAM/baterii, ryzyko OOM.

**Decyzja:** codzienny job w GitHub Actions filtruje dane i publikuje:
- `manifest.json` (~300 B) — wersja, sha256, rozmiar, zakres dat,
- `timetable-<version>.sqlite.gz` (szacunkowo 1–3 MB).

Karoo przy starcie pobiera tylko manifest; plik danych — wyłącznie gdy wersja się zmieniła.

### 1.2 Jedno publiczne repozytorium

Kod i dane w **jednym publicznym repo**:
- GitHub Actions na standardowych runnerach w repo publicznym są **bezpłatne**.
- Dane publikowane na gałęzi **`gh-pages` jako orphan, force-push jednym commitem** (historia nie rośnie o kilka MB dziennie) → serwowane przez GitHub Pages pod stałym URL, bez tokena.
- Codzienny push na `gh-pages` jest jednocześnie „aktywnością repo" — GitHub wyłącza zaplanowane workflowy w repo publicznym po 60 dniach bez aktywności. Gdyby mimo to cron się wyłączył, aplikacja pokaże `DataStale` (sekcja 5.1).
- APK publikowany w GitHub Releases → umożliwia aktualizację z poziomu Karoo (sekcja 4.3).

### 1.3 Przechowywanie na urządzeniu

Gotowy plik SQLite otwierany **read-only** (`SQLiteDatabase.OPEN_READONLY`) — bez Room, bez migracji, bez parsowania. Podmiana atomowa: pobranie do pliku tymczasowego → sha256 → `PRAGMA integrity_check` → `schema_version` → `rename`. Poprzednia wersja zostaje jako fallback, dopóki nowa nie otworzy się poprawnie.

Ustawienia użytkownika (w tym lista stacji docelowych) w DataStore.

### 1.4 Sieć

Preferuj HTTP przez `KarooSystemService` (`OnHttpResponse` — „najlepsze dostępne połączenie Karoo", działa też przez mostek z telefonem). **Faza 0 sprawdza**, czy ten kanał przenosi pliki kilku MB. Jeśli nie — plik danych pobierany bezpośrednio (`HttpURLConnection`) tylko przy Wi-Fi, manifest zawsze przez Karoo.

### 1.5 Brak Google Play Services

Karoo nie ma certyfikacji Google Play. Zero GMS, Firebase, Crashlytics. Logi i crashe do lokalnego pliku z rotacją.

### 1.6 Stos

Kotlin, coroutines + Flow, Jetpack Compose (aplikacja), Glance/RemoteViews (kafelek), kotlinx.serialization, DataStore. minSdk/targetSdk wg `karoo-ext-template`. DI: ręczny `AppContainer` (prościej i lżej niż Hilt; nie mieszać).

---

## 2. Repozytorium

```
/
├── SPEC.md
├── CLAUDE.md                 # skrót reguł z sekcji 13
├── CHANGELOG.md
├── docs/device-findings.md   # wynik fazy 0
├── pipeline/                 # Python 3.12
│   ├── build_timetable.py
│   ├── tests/ (+ fixtures/)
│   └── requirements.txt
├── .github/workflows/
│   ├── timetable.yml         # cron codzienny → gh-pages
│   └── android.yml           # build + testy, release APK na tag
└── app/                      # start z karoo-ext-template
    └── src/main/java/.../
        ├── extension/        # KarooExtension, DataTypeImpl, KarooSource (real)
        ├── data/             # updater, TimetableRepository (SQLite), ustawienia
        ├── domain/           # czysty Kotlin: geo, ETA, wybór pociągów, stany
        ├── sim/              # tylko debug: FakeKarooSource, odtwarzanie GPX
        ├── ui/               # Compose: Meta / Po drodze / Wszystkie / Ustawienia
        └── glance/           # layouty kafelka
```

- `domain/` bez importów `android.*` — testowalny na JVM.
- Cały dostęp do Karoo za interfejsem `KarooSource` (`navigationState: Flow`, `eta: Flow`, `distanceToDestination: Flow`, `rideState: Flow`, `location: Flow`, `dispatch(effect)`), z implementacją rzeczywistą i symulowaną (sekcja 10).
- karoo-ext: `io.hammerhead:karoo-ext:1.1.9` z GitHub Packages (`https://maven.pkg.github.com/hammerheadnav/karoo-ext`). **GitHub Packages zawsze wymaga uwierzytelnienia** — token `read:packages` w `~/.gradle/gradle.properties` (`gpr.user`, `gpr.key`), w CI jako sekret; nigdy w repo (repo jest publiczne!).

---

## 3. Pipeline danych (`pipeline/build_timetable.py`)

### 3.1 Wejście
- `polish_trains.zip` i `wkd.zip` (warunkowe pobranie `If-Modified-Since`/ETag; oba niezmienione → koniec bez publikacji).

### 3.2 Filtrowanie i scalanie
1. `agency.txt` z `polish_trains` → agencje **KM** i **SKM Warszawa** znalezione po nazwie (zaloguj ID; brak którejś → błąd, bez publikacji). Z `wkd.zip` → wszystko. Prefiksuj ID źródłem (`pt:`, `wkd:`), żeby nie było kolizji.
2. `routes` → `trips` → `stop_times` → `stops` tylko dla tych agencji.
3. Kursy zastępcze autobusowe (route_type 3) zachowaj z flagą `is_bus` (UI: „ZKA").
4. Stacje: perony mapuj na stację nadrzędną (`parent_station`). Aplikacja operuje na stacjach.
5. **Perony / tory**: sprawdź, gdzie dane je niosą — `platform_code` na stopach-peronach, ewentualnie niestandardowa kolumna w `stop_times`. Zaloguj pokrycie (% stop_times z peronem) per przewoźnik. Jeżeli w `polish_trains` brak peronów dla SKM, a są w `warsaw.zip` (WarsawGTFS ma perony kolejowe z ręcznie kuratorowanych danych) — rozważ dociągnięcie, ale dopiero po pomiarze pokrycia; to decyzja do odnotowania w CHANGELOG.
6. Stacje o tej samej nazwie bazowej, ale różnych przewoźników (np. „Warszawa Śródmieście" PKP i „Warszawa Śródmieście WKD") to **różne stacje** — nie scalaj automatycznie. Zamiast tego pipeline emituje `station_group` dla par ≤ 400 m z pokrewną nazwą (patrz 3.4) — używane przy stacjach docelowych.

### 3.3 Kalendarz
Rozwiń `calendar.txt` + `calendar_dates.txt` na okno **dziś−1 … dziś+14** (Europe/Warsaw). Dzień −1 jest potrzebny dla kursów po północy (czasy GTFS > 24:00:00).

### 3.4 Schemat wyjściowy (SQLite)

```sql
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
-- schema_version, feed_version, generated_at, valid_from, valid_to,
-- source_attribution, default_targets (JSON: [[ids grupy 1],[ids grupy 2],...])

CREATE TABLE station(
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL,          -- „Warszawa Gdańska"
  name_norm TEXT NOT NULL,     -- „warszawa gdanska" (bez diakrytyków, lower)
  lat REAL NOT NULL, lon REAL NOT NULL,
  agencies TEXT NOT NULL,      -- 'KM,SKM' / 'WKD'
  group_id INTEGER             -- wspólne dla stacji-bliźniaków (Śródmieście / Śródmieście WKD)
);

CREATE TABLE trip(
  id INTEGER PRIMARY KEY,
  agency TEXT NOT NULL,        -- 'KM' | 'SKM' | 'WKD'
  route_short_name TEXT,       -- 'S2', 'R7', …
  headsign TEXT,
  is_bus INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE trip_date(trip_id INTEGER, service_date INTEGER,
  PRIMARY KEY(trip_id, service_date)) WITHOUT ROWID;     -- yyyymmdd

CREATE TABLE stop_time(
  trip_id INTEGER NOT NULL,
  seq INTEGER NOT NULL,
  station_id INTEGER NOT NULL,
  arr_s INTEGER NOT NULL,      -- sekundy od „noon minus 12h" dnia kursowania; mogą być > 86400
  dep_s INTEGER NOT NULL,
  platform TEXT,               -- np. '2' (NULL gdy brak danych)
  track TEXT,                  -- np. '4'  (NULL gdy brak danych)
  PRIMARY KEY(trip_id, seq)
) WITHOUT ROWID;
CREATE INDEX stop_time_station_dep ON stop_time(station_id, dep_s);
```

**Zmiana względem v1:** brak prekomputowanej tabeli połączeń do Warszawy, bo lista stacji docelowych jest konfigurowalna na urządzeniu. Zapytanie na urządzeniu (sekcja 5.3) to jeden SELECT z self-joinem `stop_time` po `trip_id` z `seq` większym — przy ~kilkuset odjazdach dziennie ze stacji to milisekundy.

`default_targets` w `meta`: pipeline wyszukuje po nazwie i zapisuje rozwiązane ID dla domyślnej listy:
1. Warszawa Gdańska,
2. Warszawa Wileńska,
3. Warszawa Śródmieście **+ Warszawa Śródmieście WKD** (jedna grupa, ten sam priorytet — dzięki temu WKD działa „od razu" bez zmian w ustawieniach).

Test pipeline'u sprawdza, że wszystkie zostały znalezione (uwaga na pomylenie „Warszawa Śródmieście" z „Warszawa Śródmieście WKD" — muszą być obie, w jednej grupie).

### 3.5 Publikacja
- `manifest.json`: `{ schema_version, version, sha256, size_bytes, url, generated_at, valid_from, valid_to }`.
- Orphan `gh-pages`, force-push jednym commitem (`manifest.json` + aktualny plik danych). GitHub Pages serwuje pod stałym URL.
- Atrybucja (warunki ponownego wykorzystania PKP PLK i Kolei Mazowieckich; WKD CC0) w `meta.source_attribution`, widoczna w aplikacji („O danych").
- Cron: `30 1 * * *` (UTC) ≈ 02:30–03:30 w Warszawie.

### 3.6 Testy pipeline'u
- Fixture GTFS: kurs po północy, wyjątek w `calendar_dates`, peron z `parent_station` i `platform_code`, autobus ZKA, kurs WKD, para stacji-bliźniaków.
- Walidacja wyniku przed publikacją: niepuste tabele, domyślne cele znalezione, `valid_to ≥ dziś+7`, rozmiar < 10 MB, pokrycie peronów zalogowane. Błąd → brak publikacji (stary manifest zostaje).

---

## 4. Integracja z Karoo (karoo-ext 1.1.9)

### 4.1 API, którego używamy

| Cel | API |
|---|---|
| Serwis | `KarooExtension("pociag", BuildConfig.VERSION_NAME)` + `extension_info.xml` (`io.hammerhead.karooext.EXTENSION_INFO`) |
| Kafelek | `DataTypeImpl` z `graphical="true"`; `startView(...)` emituje `RemoteViews` (Glance) |
| Połączenie | `KarooSystemService(context).connect { }` / `disconnect()` |
| Trasa | `addConsumer<OnNavigationState>` → `NavigatingRoute(routePolyline, routeDistance, name, reversed, rejoinPolyline, rejoinDistance, …)`, `NavigatingToDestination(destination: Symbol.POI, polyline, …)`, `Idle` |
| ETA | strumień `DataType.Type.TIME_OF_ARRIVAL`; pomocniczo `TIME_TO_DESTINATION`, `DISTANCE_TO_DESTINATION` (pola zawierają też `NAVIGATION_STATE`, `ON_ROUTE`, `REROUTING_ENABLED`) |
| Prędkość | `SPEED`, `SMOOTHED_3S_AVERAGE_SPEED`, `AVERAGE_SPEED` (tylko gdy kafelek „zdążę?" aktywny) |
| Stan jazdy | `addConsumer<RideState>` |
| Pozycja | `OnLocationChanged` (tylko gdy aplikacja otwarta, np. „najbliższa stacja" bez trasy) |
| Powiadomienie | `SystemNotification(id, message, subText, header, style, action, actionIntent)` |
| Prowadź do stacji | `LaunchPinDrop(Symbol.POI(...))` — Karoo otwiera pinezkę z opcją nawigacji |
| HTTP | `OnHttpResponse` (MakeHttpRequest) |

**Faza 0 weryfikuje na urządzeniu (logami, nie z pamięci):**
- jednostki i format `TIME_OF_ARRIVAL` / `TIME_TO_DESTINATION` / `DISTANCE_TO_DESTINATION`,
- format `routePolyline` (Google Encoded Polyline? precyzja 5 czy 6?),
- wpływ flagi `reversed` na polyline (który punkt jest metą!),
- kiedy strumień ETA ma wartość (przed startem nagrywania? tylko podczas nawigacji?) i jak się zachowuje przy zjechaniu z trasy (rejoin),
- czy `OnHttpResponse` przenosi pliki kilku MB,
- czy `SystemNotification.actionIntent` otwiera naszą aktywność,
- czy serwis jest uruchamiany po starcie systemu (jeśli nie — `BOOT_COMPLETED` receiver, który tylko zleca sprawdzenie aktualizacji).

### 4.2 Znane pułapki (z issues i forów innych rozszerzeń) → wymagania

| # | Problem zgłoszony przez innych | Co robimy |
|---|---|---|
| P1 | `OnNavigationState` nie emituje `NavigatingRoute`, gdy nawigacja startuje **po** subskrypcji (Karoo 2, 1.1.9, issue #81). Obejście autora: usunąć i ponownie dodać konsumenta przy wejściu na trasę. | Obserwuj `NAVIGATION_STATE`/`ON_ROUTE` w strumieniu `DISTANCE_TO_DESTINATION`; przy zmianie → `removeConsumer` + ponowny `addConsumer<OnNavigationState>`. Re-subskrypcja również po reconnect. |
| P2 | Pola graficzne czasem renderują się z tytułem mimo prośby; widok bywa nałożony na „No sensor". | Zawsze emituj własny widok stanu (także „ładowanie", „brak trasy"), nigdy pusty. |
| P3 | Klikalne pola przechwytują long-press → nie da się ich podmienić w profilu / aplikacji jazdy. | `ViewConfig.preview == true` → pole nieklikalne. Ustawienie „Kafelek otwiera aplikację" (domyślnie ON). |
| P4 | Menu po kliknięciu pola koliduje z gestem przewijania stron. | Klik otwiera bezpośrednio właściwą zakładkę (deep link), bez menu pośrednich. |
| P5 | Odświeżanie pól na niewidocznych stronach marnuje energię. | Widok aktualizowany tylko gdy aktywny i tylko gdy zmieni się wynik (sekcja 7.3). |
| P6 | Serwis czasem nie restartuje się po aktualizacji APK. | README: po aktualizacji zrestartuj Karoo. Serwis odporny na wielokrotne `onCreate`/`onDestroy`. |
| P7 | ETA liczone z prędkości z pauzami było błędne; brakowało odcinka poza trasą (RouteGraph). | Bazuj na systemowym `TIME_OF_ARRIVAL`; fallback bez pauz; dolicz odcinek meta→stacja. |
| P8 | Brak Google Play Services. | Zero GMS/Firebase. |

### 4.3 Aktualizacja aplikacji z Karoo (tanie, bo repo publiczne)

karoo-ext przewiduje manifest aplikacji (`KarooAppManifest`: `label`, `packageName`, `latestApkUrl`, `latestVersion`, `latestVersionCode`, `releaseNotes` …) wskazywany w metadanych. Workflow `android.yml` przy tagu `v*` publikuje APK w Releases i aktualizuje `app-manifest.json` na `gh-pages`. Efekt: Karoo pokazuje „Update" w bibliotece rozszerzeń, bez kabla. (Faza 6.)

---

## 5. Zmiany trasy i cykl życia nawigacji (wymaganie krytyczne)

Rozszerzenie musi poprawnie działać, gdy użytkownik **wybiera, zmienia, odwraca lub wyłącza trasę**, także w trakcie jazdy.

### 5.1 Maszyna stanów

```
            ┌──────────── Idle ◄─────────────┐
            │  (kafelek: „Brak trasy",        │
            │   klik → zakładka Wszystkie)    │
            ▼                                 │
   RouteLoaded(routeKey) ──► Computing ──► Ready(result)
            ▲                    │              │
            └── nowa trasa ──────┴── zmiana ────┘
```

- `routeKey` = hash z (`routePolyline`, `reversed`, typ nawigacji, dla `NavigatingToDestination` — współrzędne celu). **Nie** wchodzi do niego `rejoinPolyline` (rejoin to chwilowy objazd do tej samej trasy, meta się nie zmienia).
- Każda zmiana `routeKey` → **anulowanie** bieżących obliczeń (`flatMapLatest`/`collectLatest`), wyczyszczenie stanu „Po drodze", nowe obliczenia, nowe powiadomienie (5.3).
- Debounce 500 ms na zmianach nawigacji (Karoo potrafi wyemitować kilka stanów pod rząd przy wyborze trasy).
- Cache wyników geo (kandydaci na metę, stacje po drodze) w LRU (rozmiar 4) po `routeKey` — powrót do poprzedniej trasy bez przeliczania.
- `Idle` → stan `NoRoute` natychmiast; ETA-strumienie odsubskrybowane (oszczędność).
- Reroute / zjechanie z trasy: meta bez zmian; ETA bierzemy z Karoo, które uwzględnia objazd. Gdy Karoo chwilowo nie podaje ETA (`StreamState` ≠ Streaming) — trzymaj ostatni wynik z oznaczeniem „≈" maks. 2 min, potem fallback z 6.2.
- Zmiana `reversed` = nowa trasa (inna meta).
- Restart serwisu w trakcie jazdy → stan odtwarzany z bieżących zdarzeń Karoo (żadnego zapisywania stanu trasy na dysk).

### 5.2 Testy sekwencji (obowiązkowe w `domain`/`extension`, na FakeKarooSource)
`Idle → A → B → Idle → A` (cache hit dla A), szybkie przełączanie `A ↔ B` 5× w 2 s (jedno obliczenie końcowe), `A → A reversed`, `A` z rejoinem (bez przeliczania), `NavigatingToDestination` (pinezka) → `NavigatingRoute`, zdarzenie nawigacji gubione przy starcie (P1).

### 5.3 Powiadomienie po wybraniu trasy

Po pierwszym `Ready` dla nowego `routeKey` (raz na trasę, dedup po `routeKey`):
- meta przy stacji: `SystemNotification(header = "Pociąg do Warszawy", message = "Meta: Mińsk Maz. (0,4 km)", subText = "17:12 · 17:42 · 18:12 → W-wa Wileńska")`, kliknięcie → aplikacja, zakładka Meta,
- meta bez stacji, ale są stacje po drodze: „Meta 2,4 km od stacji. Po drodze: 3 stacje" → zakładka Po drodze,
- brak stacji w ogóle: bez powiadomienia,
- ustawienie do wyłączenia powiadomień (domyślnie ON).

---

## 6. Logika domenowa (`domain/`, czysty Kotlin, pełne testy)

### 6.1 Stacje docelowe (konfigurowalne)

- Lista **grup** w kolejności preferencji, dowolnej długości (1, 2, 3, 5…). Grupa = 1+ stacji traktowanych jako ten sam cel (np. Śródmieście + Śródmieście WKD).
- Domyślnie `meta.default_targets` (3 grupy, sekcja 3.4).
- Edycja w ustawieniach: dodaj stację z wyszukiwarki, usuń, zmień kolejność, „przywróć domyślne". Bez adresu domu ani lokalizacji — tylko stacje.
- Etykieta celu danego kursu: najwyżej położona grupa, którą kurs obsługuje **po** stacji odjazdu.
- Stacja odjazdu sama należy do którejś grupy → stan `AlreadyAtTarget`.

### 6.2 ETA mety

1. Systemowe `TIME_OF_ARRIVAL` (gdy `StreamState.Streaming`).
2. Fallback: `now + pozostały_dystans / v`, gdzie `pozostały_dystans` = `DISTANCE_TO_DESTINATION` lub `routeDistance` (przed startem), `v` = średnia w ruchu (bez pauz) lub prędkość domyślna z ustawień (25 km/h). Wynik oznaczony „≈".

### 6.3 Wybór stacji i pociągów na mecie

1. **Meta** = ostatni punkt w kierunku jazdy (`reversed` wg fazy 0); dla `NavigatingToDestination` = współrzędne celu.
2. **Kandydaci** = stacje ≤ `R_meta` (domyślnie 1000 m). Siatka przestrzenna (~0,02°) + haversine.
3. Dla kandydata: `t_ready = ETA_meta + dist(meta, stacja) / v_dojazd + zapas` (`v_dojazd` = 15 km/h, `zapas` = 5 min; ustawialne).
4. Zapytanie: odjazdy ze stacji w oknie `[t_ready − 30 min, t_ready + 3 h]`, których kurs później zatrzymuje się w którejś grupie celów; dla każdego: odjazd, przyjazd, etykieta celu, linia, `is_bus`, peron/tor.
5. **Główny pociąg** = pierwszy z odjazdem ≥ `t_ready`. Wybór kandydata: najwcześniejszy przyjazd do celu; remis → bliższa stacja.
6. Tryb preferencji (ustawienie): **Najszybciej** (domyślny) lub **Ścisła preferencja** (najwyższa grupa, chyba że jej pociąg jest > X min, domyślnie 30, później niż najszybsza opcja).

### 6.4 „Zdążę?" — kilka pociągów i wymagana prędkość

Problem właściciela: średnia rośnie po zjeździe z lasu na asfalt, więc ETA Karoo bywa pesymistyczne. Dlatego zamiast jednej odpowiedzi pokazujemy **rozkład szans**:

Dla każdego pociągu z okna liczymy **wymaganą średnią prędkość** na pozostałym dystansie:
`v_req = (pozostały_dystans_trasy + dist(meta, stacja)) / (odjazd − zapas − now)`
(mianownik ≤ 0 → „nie do złapania").

Klasyfikacja:
- **PEWNY** — odjazd ≥ `t_ready` (wg ETA Karoo),
- **DO ZŁAPANIA** — odjazd < `t_ready`, ale `v_req ≤ v_max` (ustawienie, domyślnie 30 km/h),
- **ODPADA** — pozostałe.

Wynik to lista: najbliższy **DO ZŁAPANIA** (jeśli jest) + główny **PEWNY** + następny **PEWNY**. Kafelek w szerokim wariancie pokazuje wszystkie trzy (sekcja 7.1).

**Histereza** (żeby kafelek nie skakał między pociągami przy wahaniach ETA):
- główny pociąg zmienia się na późniejszy dopiero, gdy jego zapas < −1 min przez ≥ 2 kolejne minuty,
- powrót do wcześniejszego dopiero, gdy jego zapas ≥ +2 min,
- `v_req` wygładzone (średnia krocząca 60 s), wyświetlane w pełnych km/h.

### 6.5 Ostatni pociąg

- Główny pociąg jest ostatnim do celów w danym dniu kursowania → znacznik **OSTATNI** (kafelek, aplikacja).
- Brak jakiegokolwiek pociągu ≥ `t_ready` do końca dnia (`LastTrainMissed`):
  1. pokaż ostatni pociąg z mety z `v_req` (może jednak „DO ZŁAPANIA"),
  2. przejrzyj **stacje po drodze** (6.6) i znajdź tę, z której ostatni pociąg do celu jest osiągalny (`ETA_stacji + zapas ≤ odjazd`); wybierz najdalszą wzdłuż trasy (najmniej skracasz),
  3. kafelek: „Ostatni z mety 22:47 — nie zdążysz. Zjedź: Tłuszcz 21:58 (km 64)", aplikacja z przyciskiem „Prowadź do stacji" (`LaunchPinDrop`).
- Analogicznie, gdy główny pociąg jest OSTATNI i jego zapas < 10 min → podpowiedź alternatywnej stacji po drodze jako „plan bezpieczny".

### 6.6 Stacje po drodze

1. Prefiltr: bbox trasy + 1 km. Dla stacji: minimalna odległość do odcinków polyline (lokalna projekcja równoodległościowa) i **pozycja wzdłuż trasy** w punkcie najbliższym.
2. Zachowaj ≤ `R_trasa` (domyślnie 1000 m), poza kandydatami mety. Pętla obok tej samej stacji → wystąpienie najbliższe **przed** bieżącą pozycją (nie zawsze pierwsze — błąd naprawiany w RouteGraph).
3. ETA stacji: `now + (pozycja_stacji − pozycja_bieżąca) / v`, gdzie `v = DISTANCE_TO_DESTINATION / TIME_TO_DESTINATION` (spójne z Karoo), `pozycja_bieżąca = routeDistance − DISTANCE_TO_DESTINATION`. Minięte stacje — na dole, wyszarzone.
4. Liczone raz na `routeKey` (`Dispatchers.Default`), cache LRU.

### 6.7 Czas

- `java.time`, strefa **Europe/Warsaw** na sztywno.
- Czasy GTFS względem „południa minus 12 h" dnia kursowania → poprawne dni zmiany czasu i kursy po północy.
- Testy: zmiana czasu (marzec/październik), kurs 23:50→00:20, ETA po północy, ostatni pociąg po północy.

### 6.8 Polyline
Własny dekoder Google Encoded Polyline z parametrem precyzji (+ testy). Uproszczenie Douglas-Peucker (20 m) przed obliczeniami geo.

### 6.9 Stany wyniku (każdy ma widok kafelka)
`NoRoute`, `Computing`, `NoStationNearFinish(odległośćDoNajbliższej, stacjePoDrodze)`, `NoTrainsToday`, `LastTrainMissed(...)`, `AlreadyAtTarget`, `DataMissing`, `DataStale(wiek)`, `Ok(trains, flags)`.

---

### 6.10 Tryb awaryjny „z pozycji" (wariant łódzki)

Wersja „Pociąg do Łodzi" (`BuildConfig.NEARBY_MODE = true`) służy do awaryjnego powrotu z
treningu: bez trasy i bez nawigacji, z bieżącej pozycji. `NearbyPlanner`:

1. Stacje w promieniu `nearbyRadiusM` (20 km) od pozycji, najwyżej 8 najbliższych.
   Stacja celu ≤ 1 km → `AlreadyAtTarget`; brak stacji → `NoStationNearby(najbliższa)`.
2. Dystans po drogach = linia prosta × `roadFactor` (1,3).
3. Dwa warianty dojazdu: rowerem `emergencyRideKmh` (15 km/h — coś nie gra, jedziesz
   ostrożnie) i pieszo z rowerem `walkKmh` (5 km/h). Gotowość = teraz + dojazd + `bufferMin`.
4. Na każdej stacji pierwszy pociąg do celu odjeżdżający po gotowości (horyzont 20 h, czyli
   także „jutro rano"); autobusy ZKA pomijane (`allowBus = false` — z rowerem zwykle się nie da).
5. Wygrywa najwcześniejszy przyjazd do celu, remis → bliższa stacja. Najbliższa stacja nie
   zawsze wygrywa.
6. Histereza: poprzedni wybór zostaje, dopóki nowy nie dowozi ≥ 5 min wcześniej.
7. Flagi: `OSTATNI` (po nim do 03:00 nic już nie jedzie), `jutro` (odjazd po najbliższej 03:00).

Stany: `Nearby(ride, walk)`, `NoPosition`, `NoStationNearby`, `NoTrainsToday`, plus wspólne
(`DataMissing`, `DataStale`, `AlreadyAtTarget`). Kafelek: wiersz 🚲 i wiersz 🚶; gdy pieszo
wychodzi ten sam pociąg — tylko adnotacja „pieszo też zdążysz".

Rozkład dla wariantu łódzkiego: `build_timetable.py --region lodz` — ŁKA + PolRegio (bez
PKP Intercity: rower tylko z rezerwacją), cel Łódź Kaliska, tylko kursy z wysiadaniem na
celu, przycięte po nim (PolRegio jeździ po całym kraju — bez filtra baza > 10 MB).

## 7. Kafelek (graficzne pole danych)

### 7.1 Warianty wg rozmiaru (`ViewConfig.gridSize` / `viewSize`)

**Mały** (1 pole w siatce):
```
17:42→18:31
Mińsk→Gdań.   ≈
```

**Średni** (pół szerokości / 2 wysokości):
```
17:42 → 18:31   P2
Mińsk Maz. → W-wa Gdańska
KM R2 · zapas 12 min
```

**Szeroki** (pełna szerokość, ≥ 2 wysokości) — „zdążę?":
```
Mińsk Maz. → W-wa Gdańska           P2
▲ 17:12→18:01  potrzeba 27 km/h
● 17:42→18:31  zapas 12 min   OSTATNI
○ 18:12→19:01  zapas 42 min
```
(▲ DO ZŁAPANIA, ● główny PEWNY, ○ następny; „ZKA" dla autobusu; „≈" gdy ETA z fallbacku; peron/tor tylko gdy w danych.)

Zasady:
- Skróty nazw: „Warszawa" → „W-wa", „Mazowiecki" → „Maz.", ellipsis na końcu; w wariancie małym do ~6 znaków.
- Zapas < 5 min → wyróżnienie kolorem; OSTATNI → wyróżnienie; `LastTrainMissed` → tło ostrzegawcze.
- Wysoki kontrast, czytelność w słońcu, bez animacji.
- `preview == true`: statyczne przykładowe dane, nieklikalny (P3).

### 7.2 Stany inne niż `Ok`
- `NoRoute`: „Brak trasy · dotknij: rozkład" — **kliknięcie otwiera zakładkę Wszystkie stacje**.
- `NoStationNearFinish`: „Brak stacji ≤1 km od mety (najbliższa 2,4 km)"; jeśli są stacje po drodze — „Po drodze: 3 stacje" (klik → Po drodze).
- `NoTrainsToday`, `DataStale`, `DataMissing`, `Computing`: krótki tekst po polsku.

### 7.3 Odświeżanie (energia)

```
combine(routeResult, etaMinute, timetableVersion, targets, settings)
  .distinctUntilChanged()
  .conflate()
  .map { compute() }          // Dispatchers.Default
  .map { applyHysteresis() }
  .distinctUntilChanged()     // RemoteViews tylko przy zmianie wyniku
  .sample(10s)                // twardy limit
  .collect { emitter.updateView(render(it)) }
```
- `etaMinute` = ETA obcięte do pełnej minuty + `distinctUntilChanged`.
- Tik raz na minutę (przeliczenie zapasu/`v_req`) tylko gdy widok aktywny.
- Strumień prędkości subskrybowany tylko przy wariancie szerokim.
- Brak innych timerów i pollingu.
- Tryb awaryjny (6.10): zamiast ETA — `OnLocationChanged` (subskrypcja tylko przy aktywnym
  kafelku, `WhileSubscribed`) skwantowane do komórki ~300 × 300 m + ten sam tik minutowy.
  Przeliczenie (≤ 8 + 2 zapytania do bazy) tylko przy zmianie komórki lub minuty.

### 7.4 Kliknięcie
`PendingIntent` do `MainActivity` z `tab` zależnym od stanu (`Ok`→META, `NoRoute`→ALL, `NoStationNearFinish`→ON_ROUTE lub ALL) i `FLAG_ACTIVITY_SINGLE_TOP`. Ustawienie wyłączające klikalność (P3).

---

## 8. Aplikacja (Compose)

Ekrany pod 480×800, cele dotyku ≥ 48 dp, wysoki kontrast, bez animacji. Dane z tego samego repozytorium co kafelek (jedna instancja w procesie).

### 8.1 Zakładka „Meta" (aktywna tylko przy trasie z metą przy stacji)
- Nagłówek: stacja, odległość od mety, ETA mety (źródło: Karoo / ≈).
- **Przycisk „Prowadź do stacji"** → `LaunchPinDrop(POI stacji)`.
- Lista następnych 8 pociągów do celów: odjazd, przyjazd, cel, linia, peron/tor, ZKA, klasa (PEWNY / DO ZŁAPANIA + `v_req` / ODPADA wyszarzone), OSTATNI.
- Przełącznik między kandydatami, jeśli było ich kilka.

### 8.2 Zakładka „Po drodze" (widoczna, gdy są takie stacje)
- Stacje w kolejności wzdłuż trasy: nazwa, km, odległość od trasy, ETA.
- Rozwinięcie → 4 najbliższe pociągi od ETA tej stacji + „Prowadź do stacji".
- Stacja polecana przez 6.5 wyróżniona.

### 8.3 Zakładka „Wszystkie stacje" (zawsze dostępna; domyślna bez trasy)
- Wyszukiwarka na górze, filtr po `name_norm` (bez diakrytyków: „lodz" → „Łódź", „mins" → „Mińsk Mazowiecki"), dopasowanie od początku słów, debounce 150 ms, filtr w pamięci.
- Lista alfabetyczna z polską kolacją (`Collator(Locale("pl"))`), szybkie przewijanie literami.
- Ekran stacji: odjazdy do każdej grupy celów (odjazd → przyjazd, linia, peron), „dziś / jutro", auto-przewinięcie do „teraz", przycisk „Prowadź do stacji".

### 8.4 Ustawienia i „O danych"
- Stacje docelowe (edytor listy grup, 6.1).
- Promienie (meta, po drodze), prędkość dojazdu do stacji, zapas, prędkość fallback, `v_max` dla „do złapania", tryb preferencji, klikalność kafelka, powiadomienia.
- Zapis natychmiast przy zmianie (DataStore).
- „O danych": wersja, data generacji, ważność, „Sprawdź aktualizację", atrybucja źródeł, ostatnie 200 linii logu.

---

## 9. Aktualizacja danych przy starcie

1. `KarooExtension.onCreate` → `connect` → w tle `TimetableUpdater.checkOnce()`.
2. `checkOnce()`:
   - ostatnie udane sprawdzenie < 6 h temu → nic,
   - `manifest.json` (timeout 15 s, max 2 ponowienia z backoffem),
   - ta sama wersja → zapisz czas, koniec,
   - nowa wersja: jeśli trwa nagrywanie jazdy i brak Wi-Fi → odłóż do końca jazdy (chyba że brak jakichkolwiek danych),
   - pobierz → sha256 → integrity_check → schema_version → atomowa podmiana → emisja `timetableVersion`.
3. Brak sieci → stare dane; `DataStale`, gdy `valid_to` < dziś+1 lub dane > 3 dni.
4. Brak danych przy pierwszym uruchomieniu → `DataMissing` („Połącz z Wi-Fi").

Zadania w `CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler)` serwisu, anulowane w `onDestroy`. Wyjątek nigdy nie zabija serwisu.

---

## 10. Tryb symulacji (tylko build debug)

Cel: rozwijać i testować bez jeżdżenia, także na zwykłym emulatorze Androida (bez Karoo).

- `FakeKarooSource` implementuje `KarooSource`:
  - wczytuje GPX z `assets/sim/` lub z pliku → generuje `NavigatingRoute` (polyline zakodowana jak w Karoo, wg fazy 0),
  - odtwarza jazdę z zadaną prędkością (stała / profil „las 18 km/h → asfalt 30 km/h"), przyspieszenie czasu ×1…×60,
  - generuje `TIME_OF_ARRIVAL`, `DISTANCE_TO_DESTINATION`, `TIME_TO_DESTINATION`, `SPEED`, `RideState`,
  - skrypty scenariuszy: zmiana trasy w trakcie, odwrócenie, rejoin, `Idle`, utrata ETA, gubione zdarzenie (P1).
- Zegar wstrzykiwany (`Clock`) w całej domenie → „udawaj, że jest sobota 21:30".
- Ekran debug w aplikacji: wybór GPX, prędkości, zegara, scenariusza; podgląd wszystkich wariantów kafelka obok siebie.
- W buildzie release kod `sim/` nie jest wkompilowany (source set `debug`).

Przykładowe GPX w repo: trasa kończąca się przy stacji KM, przy stacji WKD, daleko od stacji, pętla przechodząca 2× obok tej samej stacji, trasa przez Warszawę.

---

## 11. Stabilność i energia — reguły twarde

1. Zero pracy w tle poza: jednorazowe sprawdzenie aktualizacji, subskrypcje potrzebne aktywnym widokom/aplikacji oraz `OnNavigationState` (lekki, potrzebny do powiadomienia).
2. Strumienie ETA/dystansu/prędkości tylko gdy jest trasa **i** (aktywny kafelek **lub** otwarta aplikacja **lub** oczekujące powiadomienie); współdzielone `shareIn(WhileSubscribed(5_000))`. W trybie awaryjnym (6.10) zamiast nich `OnLocationChanged`, tylko przy aktywnym kafelku.
3. Żadnych `GlobalScope`, `runBlocking` w ścieżkach UI/serwisu, żadnych pętli z `delay` poza minutowym tikiem z 7.3.
4. Baza otwierana raz, read-only; kursory w `use {}`.
5. Geometria raz na `routeKey`, cache LRU.
6. Każdy null/NaN/0/ujemna wartość z Karoo obsłużone.
7. Logi: plik z rotacją (2 × 256 KB), INFO; DEBUG tylko w debug.
8. Uprawnienia: `INTERNET`, `ACCESS_NETWORK_STATE` (+ `RECEIVE_BOOT_COMPLETED` tylko jeśli faza 0 tego wymaga).
9. R8/minify w release; reguły keep dla kotlinx.serialization i karoo-ext.

---

## 12. Plan prac (fazy dla Claude Code)

Każda faza kończy się zielonym `./gradlew test` (i `pytest`) oraz wpisem w `CHANGELOG.md`.

**Faza 0 — Spike na urządzeniu.** Minimalne rozszerzenie logujące wszystko z listy w 4.1 + próba `SystemNotification`, `LaunchPinDrop`, pobrania 2 MB. Wynik: `docs/device-findings.md`. Dalsze fazy opierają się na tym pliku, nie na zgadywaniu.

**Faza 1 — Pipeline.** KM + SKM + WKD, perony (z pomiarem pokrycia), grupy stacji, `default_targets`, testy, workflow, publikacja na `gh-pages`.

**Faza 2 — Dane na Androidzie.** Updater, `TimetableRepository`, ustawienia (DataStore), testy na fixture.

**Faza 3 — Domena.** Polyline, geo, ETA, wybór pociągów, „zdążę?" z histerezą, ostatni pociąg, stacje po drodze, maszyna stanów trasy (sekcja 5), czas. Testy wszystkich przypadków z sekcji 5–6.

**Faza 4 — Symulacja.** `KarooSource`, `FakeKarooSource`, GPX, zegar, ekran debug. (Wcześnie — przyspiesza fazy 5–7.)

**Faza 5 — Kafelek.** 3 warianty, wszystkie stany, preview, pipeline z 7.3, deep linki, obejście P1.

**Faza 6 — Aplikacja + powiadomienie.** Zakładki, „Prowadź do stacji", edytor celów, ustawienia, „O danych", powiadomienie po wyborze trasy, aktualizacja APK przez `KarooAppManifest`.

**Faza 7 — Utwardzanie na rowerze.** Trasy z GPX z sekcji 10 w realu; zmiany trasy w trakcie jazdy; brak sieci; restart Karoo; pomiar CPU serwisu przez 1 h jazdy (cel: brak zauważalnego wpływu na baterię).

### Backlog (świadomie później / nie teraz)
- **Zrobione w wariancie łódzkim (6.10):** kafelek z najbliższą stacją od bieżącej pozycji GPS.
- **Później:** `InRideAlert` („przechodzę na 18:12", „do odjazdu 15 min"); stacje jako symbole na mapie (`startMap` + `ShowSymbols`); `BonusAction` pod przycisk.
- **Gdyby rozszerzenie miało być udostępniane:** informacje o przewozie roweru (ZKA zwykle bez rowerów, WKD z płatnym biletem na rower, ew. pole `bikes_allowed`), wielojęzyczność, konfiguracja regionu.
- **Nie robimy:** opóźnienia na żywo.

---

## 13. Reguły dla Claude Code (skopiuj do CLAUDE.md)

- Nie zgaduj API karoo-ext — dokumentacja (hammerheadnav.github.io/karoo-ext) lub `docs/device-findings.md`.
- Logika w `domain/` bez `android.*`; każda zmiana tam = test. Czas zawsze przez wstrzykiwany `Clock`.
- Dostęp do Karoo tylko przez `KarooSource`.
- Każda zmiana w obsłudze nawigacji = uruchomienie testów sekwencji z 5.2.
- Bez nowych zależności bez uzasadnienia w commit message; żadnych GMS/Firebase.
- Bez nowego pollingu/timerów bez wpisu w sekcji 7.3/11.
- Teksty UI po polsku w `strings.xml`.
- Repo jest publiczne: żadnych sekretów w plikach; token GitHub Packages tylko w `~/.gradle/gradle.properties` i sekretach CI.
- Instalacja: `adb install -r` lub aktualizacja przez manifest (4.3); po aktualizacji zrestartuj Karoo (P6).

---

## 14. Kryteria akceptacji

1. Po włączeniu Karoo z Wi-Fi dane aktualne w < 60 s; bez sieci działa na starych danych i to sygnalizuje.
2. Wybór trasy kończącej się ≤ 1 km od stacji KM/SKM/WKD → w < 3 s kafelek pokazuje odjazd, przyjazd, stacje (i peron, jeśli jest w danych), a Karoo wyświetla powiadomienie.
3. Zmiana trasy w trakcie jazdy → kafelek i zakładki przeliczone dla nowej trasy w < 3 s, bez pozostałości po starej; powrót do poprzedniej trasy bez ponownego liczenia geometrii.
4. Wyłączenie nawigacji → „Brak trasy"; kliknięcie otwiera listę wszystkich stacji.
5. Przyspieszenie (symulacja las→asfalt) → pociąg „do złapania" pokazuje malejącą wymaganą prędkość i staje się „pewnym" bez skakania kafelka tam i z powrotem.
6. Po ostatnim pociągu z mety → komunikat z propozycją stacji po drodze i działający „Prowadź do stacji".
7. Lista stacji docelowych: 1, 2, 3 i 5 grup działa; „przywróć domyślne" przywraca Gdańską / Wileńską / Śródmieście(+WKD).
8. Wyszukiwarka znajduje stacje bez polskich znaków.
9. Long-press na kafelku w edytorze profilu działa (preview nieklikalny).
10. 8 h jazdy z aktywnym kafelkiem: zero crashy, brak wzrostu pamięci procesu.

---

## Źródła

- karoo-ext: https://github.com/hammerheadnav/karoo-ext , https://hammerheadnav.github.io/karoo-ext/
- karoo-ext issue #81: https://github.com/hammerheadnav/karoo-ext/issues/81
- GTFS (Polish Trains, WKD, Warszawa): https://mkuran.pl/gtfs/
- Rozmiar feedu Polish Trains: https://mobilitydatabase.org/feeds/gtfs/mdb-3191
- Headwind (changelogi): https://github.com/timklge/karoo-headwind/releases
- RouteGraph (changelogi): https://github.com/timklge/karoo-routegraph/releases
- Problemy z polami graficznymi (forum Hammerhead): https://support.hammerhead.io/hc/en-us/community/posts/35217997842331
- Cennik GitHub Actions 2026: https://github.blog/changelog/2025-12-16-coming-soon-simpler-pricing-and-a-better-experience-for-github-actions/
