package pl.kolejka.karoo.domain.plan

import pl.kolejka.karoo.domain.model.Station
import pl.kolejka.karoo.domain.model.Train
import java.time.Duration
import java.time.Instant

/** Klasa pociągu w „zdążę?" (SPEC 6.4). */
enum class TrainClass { SURE, CATCHABLE, MISSED }

data class TrainOption(
    val train: Train,
    val cls: TrainClass,
    /** Odjazd minus moment gotowości na stacji (ujemny = nie zdążę wg ETA Karoo). */
    val slack: Duration,
    /** Wymagana średnia prędkość [km/h]; null, gdy nie do złapania (mianownik ≤ 0). */
    val vReqKmh: Double?,
    val isLast: Boolean = false,
)

/** Propozycja zjazdu na stację po drodze (SPEC 6.5). */
data class Alternative(
    val station: Station,
    val alongM: Double,
    val etaStation: Instant,
    val train: Train,
)

/** Zakładka „Meta": kandydat, ETA mety, gotowość na peronie i pociągi (SPEC 8.1). */
data class FinishBoard(
    val candidate: pl.kolejka.karoo.domain.geo.FinishCandidate,
    val eta: Eta,
    val ready: Instant,
    val options: List<TrainOption>,
)

/**
 * Bieżący kontekst trasy dla ekranów aplikacji (SPEC 8): to samo, z czego liczony jest kafelek.
 * Aplikacja na jego podstawie liczy własne, dłuższe listy.
 */
data class RouteContext(
    val geometry: pl.kolejka.karoo.domain.geo.RouteGeometry,
    val routeDistanceM: Double,
    val progress: RideProgress,
    val finishEta: Eta,
    val settings: pl.kolejka.karoo.domain.model.Settings,
    val targets: pl.kolejka.karoo.domain.model.TargetGroups,
    val state: TileState,
)

/** Stany wyniku; każdy ma widok kafelka (SPEC 6.9). */
sealed interface TileState {
    data object NoRoute : TileState
    data object Computing : TileState
    data object DataMissing : TileState
    data class DataStale(val ageDays: Long) : TileState

    data class NoStationNearFinish(val nearestM: Double?, val stationsOnRoute: Int) : TileState
    data class AlreadyAtTarget(val station: Station) : TileState
    data class NoTrainsToday(val station: Station) : TileState

    data class LastTrainMissed(
        val station: Station,
        val lastFromFinish: TrainOption?,
        val alternative: Alternative?,
        val etaApproximate: Boolean,
    ) : TileState

    // ---- tryb awaryjny „z pozycji" (SPEC 6.10)

    /** Brak pozycji GPS (jeszcze nie ma fixa). */
    data object NoPosition : TileState

    /** Żadnej stacji w promieniu; [nearestM] — najbliższa w ogóle (jeśli jest w zasięgu szukania). */
    data class NoStationNearby(val radiusM: Double, val nearestM: Double?) : TileState

    /**
     * Najlepszy pociąg do celu z okolicy w dwóch wariantach dojazdu na stację: rowerem
     * i pieszo z rowerem. Null = w horyzoncie nic nie jedzie (lub nie zdążysz).
     */
    data class Nearby(
        val ride: NearbyOption?,
        val walk: NearbyOption?,
        val dataStale: Boolean = false,
    ) : TileState

    data class Ok(
        val station: Station,
        val stationDistanceM: Double,
        /** Wszystkie pociągi z okna, posortowane po odjeździe. */
        val options: List<TrainOption>,
        val mainIndex: Int,
        val etaApproximate: Boolean,
        /** „Plan bezpieczny", gdy główny pociąg jest OSTATNI i zapas < 10 min (SPEC 6.5). */
        val safePlan: Alternative? = null,
        /** Dane rozkładu starsze niż 3 dni lub kończą się jutro (SPEC 9.3). */
        val dataStale: Boolean = false,
    ) : TileState {
        val main: TrainOption get() = options[mainIndex]

        /** Najbliższy DO ZŁAPANIA przed głównym (najmniejsza wymagana prędkość). */
        val catchable: TrainOption?
            get() = options.subList(0, mainIndex).lastOrNull { it.cls == TrainClass.CATCHABLE }

        /** Następny pociąg po głównym. */
        val next: TrainOption? get() = options.getOrNull(mainIndex + 1)

        /** Lista dla szerokiego kafelka: ▲ do złapania, ● główny, ○ następny. */
        val display: List<TrainOption> get() = listOfNotNull(catchable, main, next)
    }
}
