# Changelog

## [Unreleased]

### Stały podpis APK release (2026-10-26)

- Release podpisywany kluczem z sekretów repo (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
  `KEY_ALIAS`); aktualizacja przez `adb install -r` bez odinstalowywania. Bez sekretów — jak
  dotąd klucz debug (z ostrzeżeniem w logu Actions).

### Wariant łódzki — trzy stacje docelowe, 6 opcji (2026-10-26)

- Cele: Łódź Kaliska, Łódź Fabryczna, Łódź Widzew (`default_targets`); rozkład zawiera kursy
  do każdej z nich.
- `NearbyPlanner` liczy każdy cel osobno; `TileState.Nearby(rows)` — wiersz na cel z 🚲 i 🚶.
  Kafelek zawsze pokazuje wszystkie 6 opcji (brak = „—"), układ zależny od rozmiaru pola.
- Rowerem 30 km/h (było 15).

### Wariant łódzki — tryb awaryjny „z pozycji" (2026-10-25)

- Osobna aplikacja „Pociąg do Łodzi" (`pl.kolejka.karoo.lodz`, rozszerzenie `pociag_lodz`,
  wersja 0.3.0-lodz): może stać na Karoo obok wersji warszawskiej. Rozkład z
  `https://lukaszzsch.github.io/karoo-kolejka-lodz/manifest.json`.
- Pipeline: `--region lodz|warszawa` (domyślnie `lodz`). Łódź: ŁKA + PolRegio (bez PKP
  Intercity — rower tylko z rezerwacją), cel Łódź Kaliska, bez WKD; tylko kursy z wysiadaniem
  na Kaliskiej, przycięte po niej. Nazwy przewoźników dopasowywane bez wielkości liter.
  Region warszawski bez zmian (dotychczasowe testy przechodzą z `--region warszawa`).
- Domena: `NearbyPlanner` (SPEC 6.10) — stacje w 20 km od pozycji, warianty 🚲 30 km/h
  i 🚶 5 km/h, najwcześniejszy przyjazd do celu, histereza 5 min, flagi OSTATNI / jutro,
  bez autobusów ZKA. Nowe stany `Nearby`, `NoPosition`, `NoStationNearby`; widoki w
  `TileText`. 9 testów.
- `NameShortener`: „Łódź" jak „Warszawa" (Ł. Kaliska, Kali.); granice słów przez
  `(?<!\p{L})`/`(?!\p{L})` zamiast `\b` — od JDK 19 `\b` nie traktuje „Ś"/„Ł" jako liter.
- `KarooSource.location` (`OnLocationChanged`, karoo-ext ≥ 1.1.3); `TileEngine` w trybie
  awaryjnym przelicza przy zmianie komórki ~300 m lub minuty (SPEC 7.3, 11.2). Trasa jest
  ignorowana.

### Faza 6 — Ekrany aplikacji (2026-10-25, bez powiadomienia i aktualizacji APK)

- Zakładki (SPEC 8): **Meta** (stacja, odległość od mety, 🚲 ETA z Karoo / ≈, gotowość na
  peronie, „Prowadź do stacji", przełącznik kandydatów, 8 pociągów z tymi samymi znakami co
  kafel), **Po drodze** (stacje w kolejności trasy: km, odległość od trasy, 🚲 ETA i dystans,
  pierwszy pociąg; rozwinięcie → 4 pociągi i „Prowadź do stacji"; polecana stacja z SPEC 6.5
  wyróżniona; minięte na dole), **Stacje** (wyszukiwarka bez polskich znaków po początkach słów,
  debounce 150 ms, polska kolacja; ekran stacji: dziś/jutro, przewinięcie do „teraz",
  „Prowadź do stacji"), **Opcje** (edytor stacji docelowych: dodaj z wyszukiwarki — bliźniaki
  razem, ↑/↓/✕, przywróć domyślne; promienie, prędkości, zapas, tryb, klikalność kafla,
  powiadomienia; „O danych").
- Kliknięcie kafla otwiera zakładkę zależną od stanu (SPEC 7.4); przy wyłączonej klikalności
  kafel nie reaguje (P3).
- Jeden silnik i jedno połączenie z Karoo w procesie (`AppContainer`); aplikacja korzysta
  z kontekstu trasy silnika (`RouteContext`) i liczy dłuższe listy (`finishBoard`,
  `UpcomingStations` z kilkoma pociągami i minionymi stacjami). `KarooSource.navigateTo`
  (LaunchPinDrop).
- Domena: `StationSearch` + testy, testy tablicy mety i minionych stacji.
- Bez szybkiego przewijania literami (SPEC 8.3) — do dodania, jeśli lista okaże się niewygodna.

### Faza 2 — Dane na Androidzie (2026-10-25)

- `TimetableUpdater` (domena, czysty Kotlin): manifest z GitHub Pages, sprawdzanie najwyżej co
  6 h (chyba że wymuszone), pobranie → sha256 → rozpakowanie → walidacja → atomowa podmiana,
  poprzednia wersja zostaje jako zapas, 2 ponowienia z backoffem, odkładanie pobierania w
  trakcie jazdy bez Wi-Fi (gdy są już jakieś dane). 10 testów JVM.
- Android: manifest najpierw przez Karoo HTTP (działa przez mostek z telefonem), potem
  bezpośrednio; plik danych tylko bezpośrednio (`OnHttpResponse` ma limit 100 KB). Walidacja:
  `PRAGMA integrity_check` + `schema_version`.
- Wyzwalacze bez timerów: start serwisu (raz), koniec jazdy (gdy było odłożone), przycisk
  „Sprawdź aktualizację" w „O danych". Kafel i aplikacja przełączają się na nowy rozkład bez
  restartu (`AppContainer.timetable` jako StateFlow; stara baza zamykana po 60 s).
- Build debug zasiewa dane z wbudowanego pliku przy pierwszym uruchomieniu; release zaczyna od
  „Brak rozkładu" do pierwszego pobrania (SPEC 9.4).
- Ustawienia w DataStore (`SettingsRepository`), w tym lista grup stacji docelowych; kafel
  przelicza się po zmianie. Ekran ustawień — faza 6. **Nowa zależność:**
  `androidx.datastore:datastore-preferences` (SPEC 1.6).
- Dystans do mety przytrzymywany 2 min, gdy Karoo chwilowo go nie podaje — kafel przeskakiwał
  między dystansem z Karoo a długością trasy.
- Wersja aplikacji 0.2.0.
- Sprawdzone na Karoo 3: bez sieci — zasiew z assets, 3 nieudane próby, kafel działa dalej;
  z Wi-Fi — manifest przez Karoo HTTP, pobranie i podmiana w 2,6 s, kafel przeliczony na nowym
  rozkładzie bez restartu, poprzednia wersja zachowana jako zapas.

### Porządki po fazie 0 (2026-10-25)

- Usunięta sonda diagnostyczna (subskrybowała wszystkie strumienie cały czas) i jej pole.
- Ekran aplikacji: „O danych" (wersja i ważność rozkładu, źródła, ostatnie 200 linii logu,
  odczyt jednorazowy bez pollingu) zamiast ekranu prób. `AppContainer` — jedna instancja
  rozkładu na proces.
- Dystans do mety nie jest już odrzucany, gdy przekracza długość trasy (dojazd do trasy);
  dystans i ETA stacji po drodze uwzględniają ten dojazd.

### Po jeździe testowej (2026-10-25 wieczór)

- Faza 0: jednostki ustalone (TIME_OF_ARRIVAL epoch ms, TIME_TO_DESTINATION ms, dystans m,
  prędkość m/s) — `KarooUnits` bez zgadywania. Przy `reversed=true` polyline jest już w kierunku
  jazdy: usunięto błędne podwójne odwracanie (meta liczona na złym końcu trasy).
- Duży kafel (układ uzgodniony na urządzeniu): meta „skąd → dokąd" z 🚲 ETA i dystansem
  (bez dystansu z Karoo — długość trasy), pociągi z kółkami 🟠 do złapania / 🟢 główny /
  🔵 następny / 🔴 odpada, ⚠️ zamiast 🟢 przy zapasie < 5 min, czerwony dopisek OSTATNI,
  🚌 dla ZKA. Stacje po drodze małą czcionką: „skąd → dokąd", 🚲 ETA i dystans, pierwszy
  osiągalny pociąg. Czcionka zmniejszana iteracyjnie, aż wszystko się zmieści (szerokość
  liczona per linia, emoji = 2 znaki).

### Wersja testowa kafelka (2026-10-25, przed fazami 2 i 5)

- Pole „Pociąg do Warszawy": meta z 3 pociągami (▲/●/○) oraz 2 najbliższe stacje przed
  rowerzystą z pierwszym osiągalnym pociągiem. Podgląd w edytorze jest statyczny i nieklikalny.
- Rozkład wbudowany tylko w build debug (`app/src/debug/assets/`, poza repo), kopiowany do plików
  aplikacji. `SqliteTimetable` (read-only) to docelowa część fazy 2.
- `KarooSource` + `RealKarooSource` (z obejściem P1), `TileEngine` (RouteTracker → planer →
  histereza; tik minutowy, przeliczanie tylko przy istotnej zmianie wejść), `TileRenderer`
  (czcionka dobierana do wysokości pola).
- Jednostki ETA nie są jeszcze znane: `KarooUnits` przyjmuje tylko interpretacje dające sensowną
  prędkość, w innym razie fallback z „≈". Przy `reversed=true` punkty trasy są odwracane — do
  potwierdzenia w fazie 0.
- Domena: `UpcomingStations`, `KarooUnits`, `TileText` (+ testy, w tym scenariusz trasy do
  Siedlec). Razem 69 testów.
- Autotest na Karoo 3: rozkład otwiera się, zapytanie dla Siedlec zwraca pociągi w < 0,2 s.

### Faza 0 — pierwsze pomiary na Karoo 3 (2026-10-24, w domu, bez GPS-u)

- Potwierdzone: serwis startuje sam po bootcie, polyline ma precyzję 5, powiadomienie i kafelek
  otwierają aplikację, `LaunchPinDrop` działa, `OnHttpResponse` odrzuca 0,5 MB błędem
  `RESPONSE_TOO_LARGE`, a `HttpURLConnection` na Wi-Fi daje ~8 Mbit/s. Szczegóły w
  `docs/device-findings.md`.
- Naprawione: crash sondy po `RESPONSE_TOO_LARGE` (`first()` na pustym flow). Aplikacja dzieli
  proces z rozszerzeniem, więc padał też serwis. Scope ekranu ma teraz `CoroutineExceptionHandler`.
- Naprawione: fałszywy alarm watchdoga P1. `NavigatingRoute` przychodzi ~1,5 s przed zmianą
  `NAVIGATION_STATE`, więc okno to teraz ±5 s (`NavResubscribeWatchdog` z wstrzykiwanym
  `TimeSource`, 2 nowe testy).

### Faza 3 — Domena (2026-10-24)

- Moduł `:domain` to czysty Kotlin/JVM, bez zależności od Androida. Zakaz `android.*` w domenie
  pilnuje więc kompilator. **Odstępstwo od SPEC 2**: osobny moduł Gradle zamiast pakietu
  `domain/` w `app`.
- `geo/`: dekoder i enkoder Google Encoded Polyline z precyzją 5/6, `detectPrecision` (dobór
  precyzji po `routeDistance`), Douglas-Peucker 20 m (iteracyjnie), siatka przestrzenna 0,02°,
  `RouteAnalyzer`. Ten ostatni wyznacza kandydatów na metę, stacje po drodze z pozycją wzdłuż
  trasy (wszystkie przejścia pętli) i najbliższą stację. Projekcja jest liczona lokalnie wokół
  każdej stacji, a dystans skumulowany z haversine, więc na trasie 200 km nie narasta błąd.
- `time/ServiceTime`: czasy względem „południa minus 12 h", okna zapytań obejmują 2 dni
  kursowania wstecz (feed ma czasy do ~42 h).
- `plan/`: `EtaEstimator` (Karoo → ostatnia wartość przez 2 min → fallback ze średniej lub
  25 km/h), `TrainPlanner` (t_ready, PEWNY / DO ZŁAPANIA / ODPADA, v_req, tryby
  NAJSZYBCIEJ / ŚCISŁA, OSTATNI, `LastTrainMissed` z najdalszą osiągalną stacją po drodze,
  „plan bezpieczny"), `TileStabilizer` (histereza −1 min / 2 min / +2 min, średnia krocząca
  v_req 60 s). Wszystkie stany z SPEC 6.9.
- `route/`: `RouteTracker` (debounce 500 ms, `flatMapLatest`, LRU 4, Idle natychmiast,
  rejoin poza kluczem) i `NavResubscribeWatchdog` (obejście P1).
- `text/NameShortener`: skróty nazw na kafelek.
- `timetable/`: interfejs `Timetable` i `InMemoryTimetable` (testy, później symulacja).
- 62 testy JVM, w tym wszystkie sekwencje z SPEC 5.2 i przypadki czasu z SPEC 6.7.
- „Koniec dnia" dla OSTATNIEGO pociągu to najbliższa 03:00 czasu lokalnego.

### Faza 0 — Spike na urządzeniu (2026-10-24, kod gotowy, pomiary czekają na Karoo)

- Projekt Gradle: AGP 8.6.1, Kotlin 2.0.21, Gradle 8.7, compileSdk 35 / targetSdk 34 /
  minSdk 23, pakiet `pl.kolejka.karoo`.
- karoo-ext 1.1.9 jest rozwiązywany najpierw z `mavenLocal` (zbudowany ze źródeł tagu `1.1.9`),
  potem z GitHub Packages (token w `~/.gradle/gradle.properties`). CI (`android.yml`) buduje
  bibliotekę ze źródeł, więc nie potrzebuje sekretu.
- `PociagExtension` (id `pociag`) z `DeviceProbe`: loguje nawigację (polyline w precyzji 5 i 6,
  `reversed`, rejoin, cel pinezki), strumienie ETA/dystansu/prędkości, `RideState`, wykrywa P1.
  Pole graficzne „Pociąg: diagnostyka" pokazuje surowe wartości i loguje `ViewConfig`. Ekran
  aplikacji ma przyciski do prób: powiadomienie, pinezka, HTTP przez Karoo, pobieranie
  bezpośrednie, pozycja.
- `FileLog`: plik z rotacją 2 × 256 KB w `Android/data/pl.kolejka.karoo/files/logs/`, zapisuje
  też crashe.
- **Ustalenie ze źródeł karoo-ext:** `OnHttpResponse` ma limit 100 KB na treść odpowiedzi.
  Plik danych (~1,7 MB) będzie pobierany wyłącznie przez `HttpURLConnection`, a manifest może iść
  przez Karoo. To zmienia SPEC 1.4, gdzie pytanie było otwarte. Szczegóły w
  `docs/device-findings.md`.
- Release (R8) ma 2,3 MB i jest podpisany kluczem debug do czasu fazy 6.

### Faza 1 — Pipeline danych (2026-10-24)

- `pipeline/build_timetable.py`: filtruje KM + SKM Warszawa (z `polish_trains.zip`) i WKD
  (z `wkd.zip`), rozwija kalendarz na okno dziś−1…dziś+14 (Europe/Warsaw) i zapisuje SQLite
  według SPEC 3.4. Tylko biblioteka standardowa Pythona; zależność testowa: `pytest`.
- `.github/workflows/timetable.yml`: cron `30 1 * * *`, testy → budowa → orphan force-push
  na `gh-pages` (`manifest.json`, `timetable-<version>.sqlite.gz`, `.nojekyll`, zachowany
  `app-manifest.json`).
- Pobieranie warunkowe (ETag/Last-Modified zapisane w `manifest.sources`). Bez publikacji tylko
  wtedy, gdy oba źródła są niezmienione **i** opublikowane dane sięgają ≥ dziś+12. W przeciwnym
  razie okno dat przesuwa się codziennie.
- Pierwszy przebieg na prawdziwych danych (24.10.2026): 359 stacji, 5136 kursów, 96 400
  stop_times, baza 4,58 MB, plik `.gz` 1,66 MB, czas budowy ~3 s.

**Pomiary i decyzje**

- **Perony/tory**: `polish_trains` ma kolumny `platform` i `track` w `stop_times`. Pokrycie
  (bez ZKA): KM 100%, SKM 100%, WKD 0% (feed WKD ich nie zawiera). Gdy `stop_times` nie ma
  peronu, używamy `platform_code` przystanku („2 t. 3" → peron 2, tor 3).
  **Nie dociągamy `warsaw.zip`**, bo nie jest potrzebny.
- Feed `polish_trains` nie ma `calendar.txt`, tylko `calendar_dates.txt`. Obsługujemy oba pliki.
- **Odstępstwa od schematu z SPEC 3.4** (`schema_version = 1`):
  - `station.id` jest stabilne między wersjami, bo ustawienia przechowują ID stacji
    docelowych: dla PLK to numeryczne ID stacji nadrzędnej, dla WKD 10 000 000 + crc32.
  - Nowa kolumna `stop_time.flags`: bit 0 = brak wsiadania, bit 1 = brak wysiadania (z
    `pickup_type`/`drop_off_type` = 1).
  - `seq` jest przenumerowane od 0, kolejno w obrębie kursu.
  - W `manifest.url` jest nazwa pliku **względna** wobec URL manifestu, a manifest ma
    dodatkowe pole `sources`.
- **Grupy stacji**: promień 600 m zamiast 400 m. „Warszawa Śródmieście" i „Warszawa Śródmieście
  WKD" dzieli ~525 m. Nazwa pokrewna = ta sama nazwa po usunięciu sufiksu „WKD" i nawiasów.
  Grupy z prawdziwych danych: Śródmieście↔Śródmieście WKD, Zachodnia↔Zachodnia (Peron 9)↔Zachodnia
  WKD, Ochota↔Ochota WKD, Aleje Jerozolimskie (PKP)↔Aleje Jerozolimskie (WKD).
- Przystanki ZKA WKD (`wocho_bus_d0` „Plac Zawiszy") są mapowane na stację kolejową po prefiksie
  ID. Bez odpowiednika (np. „PKP Pruszków") zostają osobną stacją.
- Dwie stacje „Warszawa Aleje Jerozolimskie" (PKP i WKD) mają identyczną nazwę, więc UI powinno
  pokazywać przewoźnika obok nazwy.
- Feed WKD ma `feed_version` z 22.08.2026, ale jego `calendar_dates` sięga 12.12.2026.
