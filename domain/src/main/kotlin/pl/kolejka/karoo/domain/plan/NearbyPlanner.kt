package pl.kolejka.karoo.domain.plan

import pl.kolejka.karoo.domain.geo.LatLon
import pl.kolejka.karoo.domain.geo.SpatialIndex
import pl.kolejka.karoo.domain.model.Settings
import pl.kolejka.karoo.domain.model.Station
import pl.kolejka.karoo.domain.model.TargetGroups
import pl.kolejka.karoo.domain.model.Train
import pl.kolejka.karoo.domain.time.ServiceTime
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Propozycja: dojedź/dojdź na [station] i wsiądź w [train]. */
data class NearbyOption(
    val station: Station,
    /** Odległość w linii prostej [m]. */
    val straightM: Double,
    /** Szacowana odległość po drogach [m] (linia prosta × Settings.roadFactor). */
    val roadM: Double,
    /** Chwila gotowości na peronie: dojazd + zapas (Settings.bufferMin). */
    val readyAt: Instant,
    val train: Train,
    val isLast: Boolean,
    /** Odjazd po najbliższej 03:00 — „dziś już nic". */
    val tomorrow: Boolean,
) {
    /** Odjazd minus gotowość; nieujemny z definicji wyboru. */
    val slack: Duration get() = Duration.between(readyAt, train.departure)
}

/** Wiersz kafelka: cel (grupa stacji) i dwa warianty dojazdu na stację odjazdu. */
data class NearbyRow(
    val group: Int,
    /** Stacja celu do etykiety (pierwsza z grupy). */
    val targetStationId: Int,
    val ride: NearbyOption?,
    val walk: NearbyOption?,
)

/**
 * Tryb awaryjny (SPEC 6.10): bez trasy, z bieżącej pozycji. Dla każdej stacji w promieniu
 * i każdej grupy celów liczy pierwszy pociąg, na który zdążysz — osobno rowerem i pieszo — i
 * wybiera ten z najwcześniejszym przyjazdem do tego celu (remis → bliższa stacja). Najbliższa
 * stacja nie zawsze wygrywa: lepiej podjechać 3 km dalej, niż czekać godzinę. Wynik: wiersz na
 * każdy cel, czyli przy trzech celach zawsze 6 opcji.
 *
 * Bezstanowy poza parametrem [previous] (histereza: zostajemy przy poprzednim wyborze, dopóki
 * nowy nie jest lepszy o ≥ [SWITCH_GAIN]). Wywoływać poza wątkiem UI (zapytania do bazy).
 */
class NearbyPlanner(
    private val planner: TrainPlanner,
    private val index: SpatialIndex,
    private val clock: Clock,
) {

    fun plan(
        position: LatLon?,
        settings: Settings,
        targets: TargetGroups,
        data: DataStatus?,
        previous: TileState.Nearby? = null,
    ): TileState {
        val now = clock.instant()
        if (data == null) return TileState.DataMissing
        val today = ServiceTime.localDate(now)
        if (data.validTo.isBefore(today)) {
            return TileState.DataStale(Duration.between(data.generatedAt, now).toDays())
        }
        val dataStale = data.validTo.isBefore(today.plusDays(1)) || Duration.between(data.generatedAt, now) > Duration.ofDays(3)
        if (position == null || !position.isValid) return TileState.NoPosition

        val near = index.within(position, settings.nearbyRadiusM)
        near.firstOrNull { (s, d) -> d <= AT_TARGET_M && targets.groupOf(s.id) != null }
            ?.let { return TileState.AlreadyAtTarget(it.first) }
        val candidates = near.take(MAX_CANDIDATES)
        if (candidates.none { (s, _) -> targets.groupOf(s.id) == null }) {
            return TileState.NoStationNearby(settings.nearbyRadiusM, index.within(position, FAR_SEARCH_M).firstOrNull()?.second)
        }

        val speeds = listOf(settings.emergencyRideKmh, settings.walkKmh)
        val minSpeed = speeds.min()
        val maxSpeed = speeds.max()
        val dayEnd = TrainPlanner.serviceDayEnd(now)

        val rows = targets.groups.mapIndexed { g, group ->
            val single = TargetGroups(listOf(group))
            // Stacja tego celu nie jest stacją odjazdu do niego; pozostałe cele — mogą być
            // (np. z Widzewa na Kaliską).
            val perStation = candidates.filter { (s, _) -> s.id !in group }.map { (station, straight) ->
                val road = straight * settings.roadFactor
                val earliest = ready(now, road, maxSpeed, settings)
                val latest = ready(now, road, minSpeed, settings)
                val trains = planner.trainsFrom(station.id, earliest, latest.plus(HORIZON), single)
                    .filter { settings.allowBus || !it.isBus }
                Triple(station, straight, trains)
            }

            fun pick(speedKmh: Double, prev: NearbyOption?): NearbyOption? {
                val opts = perStation.mapNotNull { (station, straight, trains) ->
                    val road = straight * settings.roadFactor
                    val readyAt = ready(now, road, speedKmh, settings)
                    trains.firstOrNull { !it.departure.isBefore(readyAt) }?.let {
                        NearbyOption(station, straight, road, readyAt, it, isLast = false, tomorrow = !it.departure.isBefore(dayEnd))
                    }
                }
                val best = opts.minWithOrNull(compareBy<NearbyOption>({ it.train.earliestArrival }, { it.roadM })) ?: return null
                val kept = prev?.let { p ->
                    opts.firstOrNull { it.station.id == p.station.id && it.train.key == p.train.key }
                }?.takeIf { !it.train.earliestArrival.isAfter(best.train.earliestArrival.plus(SWITCH_GAIN)) }
                val chosen = kept ?: best
                return chosen.copy(isLast = isLast(chosen, single, settings))
            }

            val prevRow = previous?.rows?.firstOrNull { it.group == g }
            NearbyRow(g, group.minOrNull() ?: -1, pick(settings.emergencyRideKmh, prevRow?.ride), pick(settings.walkKmh, prevRow?.walk))
        }
        if (rows.all { it.ride == null && it.walk == null }) {
            return TileState.NoTrainsToday(candidates.first { (s, _) -> targets.groupOf(s.id) == null }.first)
        }
        return TileState.Nearby(rows, dataStale)
    }

    private fun ready(now: Instant, roadM: Double, speedKmh: Double, settings: Settings): Instant =
        now.plusSeconds((roadM / (speedKmh / 3.6)).toLong())
            .plus(Duration.ofMinutes(settings.bufferMin.toLong()))

    /** OSTATNI: po tym pociągu z tej stacji do końca dnia (03:00) nic już do celu nie jedzie. */
    private fun isLast(o: NearbyOption, targets: TargetGroups, settings: Settings): Boolean {
        val dep = o.train.departure
        return planner.trainsFrom(o.station.id, dep.plusSeconds(1), TrainPlanner.serviceDayEnd(dep), targets)
            .none { settings.allowBus || !it.isBus }
    }

    companion object {
        /** Do tej odległości od stacji celu uznajemy, że już jesteś na miejscu. */
        const val AT_TARGET_M = 1_000.0

        /** Najwyżej tyle najbliższych stacji (każda × liczba celów = zapytanie do bazy). */
        const val MAX_CANDIDATES = 8

        /** Dalsze szukanie tylko do komunikatu „najbliższa X km". */
        const val FAR_SEARCH_M = 60_000.0

        /** Szukamy pociągów do tylu godzin po gotowości (obejmuje „jutro rano"). */
        val HORIZON: Duration = Duration.ofHours(20)

        /** Nowa propozycja musi dowozić tyle wcześniej, żeby zastąpić poprzednią. */
        val SWITCH_GAIN: Duration = Duration.ofMinutes(5)
    }
}
