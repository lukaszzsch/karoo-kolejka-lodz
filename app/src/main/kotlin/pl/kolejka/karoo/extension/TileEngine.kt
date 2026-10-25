package pl.kolejka.karoo.extension

import io.hammerhead.karooext.models.DataType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import pl.kolejka.karoo.BuildConfig
import pl.kolejka.karoo.data.SqliteTimetable
import pl.kolejka.karoo.domain.geo.LatLon
import pl.kolejka.karoo.domain.geo.Polyline
import pl.kolejka.karoo.domain.geo.RouteAnalyzer
import pl.kolejka.karoo.domain.geo.RouteGeometry
import pl.kolejka.karoo.domain.geo.SpatialIndex
import pl.kolejka.karoo.domain.karoo.KarooUnits
import pl.kolejka.karoo.domain.model.Settings
import pl.kolejka.karoo.domain.model.TargetGroups
import pl.kolejka.karoo.domain.plan.EtaEstimator
import pl.kolejka.karoo.domain.plan.KarooEtaSample
import pl.kolejka.karoo.domain.plan.NearbyPlanner
import pl.kolejka.karoo.domain.plan.RideProgress
import pl.kolejka.karoo.domain.plan.RouteContext
import pl.kolejka.karoo.domain.plan.TileStabilizer
import pl.kolejka.karoo.domain.plan.TileState
import pl.kolejka.karoo.domain.plan.TrainPlanner
import pl.kolejka.karoo.domain.plan.UpcomingStations
import pl.kolejka.karoo.domain.route.GeometryConfig
import pl.kolejka.karoo.domain.route.NavInput
import pl.kolejka.karoo.domain.route.RouteState
import pl.kolejka.karoo.domain.route.RouteTracker
import pl.kolejka.karoo.domain.text.TileModel
import pl.kolejka.karoo.log.FileLog
import java.time.Clock
import kotlin.math.roundToLong

/**
 * Łączy nawigację, strumienie Karoo, rozkład, ustawienia i planer w model kafelka (SPEC 7.3).
 * Nowy rozkład (po aktualizacji) lub zmiana ustawień budują potok od nowa. Strumienie
 * ETA/dystansu są subskrybowane tylko przy trasie i aktywnym kafelku (`WhileSubscribed`,
 * SPEC 11.2). Jedyny timer: tik minutowy przy trasie.
 *
 * Tryb awaryjny (BuildConfig.NEARBY_MODE, SPEC 6.10): zamiast nawigacji pozycja z Karoo.
 * Przeliczenie tylko przy przesunięciu o ~300 m albo na tiku minutowym — pozycja przychodzi
 * co sekundę, a każde przeliczenie to kilka zapytań do bazy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TileEngine(
    private val source: KarooSource,
    timetables: Flow<SqliteTimetable?>,
    settings: Flow<Settings>,
    private val clock: Clock,
    scope: CoroutineScope,
) {
    private val _routeContext = kotlinx.coroutines.flow.MutableStateFlow<RouteContext?>(null)

    /** Kontekst trasy z ostatniego obliczenia — dla ekranów aplikacji; null bez trasy. */
    val routeContext: StateFlow<RouteContext?> get() = _routeContext

    val model: StateFlow<TileModel> = combine(timetables, settings) { tt, s -> tt to s }
        .distinctUntilChanged()
        .flatMapLatest { (tt, s) -> if (tt == null) flowOf(TileModel(TileState.DataMissing)) else Pipeline(tt, s).models() }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), TileModel(TileState.Computing))

    /** Wszystko, co zależy od konkretnej wersji rozkładu i ustawień. */
    private inner class Pipeline(private val tt: SqliteTimetable, private val settings: Settings) {
        private val index = SpatialIndex(tt.stations)
        private val names: Map<Int, String> = tt.stations.associate { it.id to it.name }
        private val nameOf: (Int) -> String = { names[it] ?: "#$it" }
        private val targets = settings.targets?.let { TargetGroups(it) } ?: tt.defaultTargets
        private val planner = TrainPlanner(tt, clock)
        private val upcoming = UpcomingStations(planner, clock)
        private val tracker = RouteTracker(::analyze, Dispatchers.Default)
        private val stabilizer = TileStabilizer()
        private val nearby = NearbyPlanner(planner, index, clock)
        private var lastNearby: TileState.Nearby? = null
        private var lastKarooEta: KarooEtaSample? = null
        private var lastRemaining: Pair<Double, java.time.Instant>? = null
        private var lastLoggedState: String? = null
        private var lastLogAt = 0L

        fun models(): Flow<TileModel> = if (BuildConfig.NEARBY_MODE) nearbyModels() else routeModels()

        private fun nearbyModels(): Flow<TileModel> =
            combine(source.location.map<LatLon, LatLon?> { it }.onStart { emit(null) }, minuteTicker()) { p, minute -> p to minute }
                .distinctUntilChanged { a, b -> a.second == b.second && cell(a.first) == cell(b.first) }
                .conflate()
                .map { (p, _) -> computeNearby(p) }
                .flowOn(Dispatchers.Default)

        private fun computeNearby(position: LatLon?): TileModel = try {
            val state = nearby.plan(position, settings, targets, tt.dataStatus, lastNearby)
            lastNearby = state as? TileState.Nearby
            val kind = state.javaClass.simpleName
            if (kind != lastLoggedState || clock.millis() - lastLogAt > 300_000) {
                val desc = (state as? TileState.Nearby)?.let { n ->
                    "ride=${n.ride?.let { "${it.station.name} ${it.train.departure}" }} walk=${n.walk?.let { "${it.station.name} ${it.train.departure}" }}"
                }.orEmpty()
                FileLog.i("Kafelek (awaryjny): $kind $desc")
                lastLoggedState = kind
                lastLogAt = clock.millis()
            }
            TileModel(state, stationName = nameOf)
        } catch (e: Exception) {
            FileLog.e("Błąd obliczeń kafelka (awaryjny)", e)
            TileModel(TileState.Computing, stationName = nameOf)
        }

        private fun routeModels(): Flow<TileModel> =
            tracker.states(source.navigation, flowOf(GeometryConfig(settings.finishRadiusM, settings.routeRadiusM)))
                .flatMapLatest { rs ->
                    when (rs) {
                        RouteState.NoRoute, is RouteState.Invalid -> {
                            stabilizer.reset()
                            lastKarooEta = null
                            lastRemaining = null
                            _routeContext.value = null
                            flowOf(TileModel(TileState.NoRoute, stationName = nameOf))
                        }
                        is RouteState.Computing -> flowOf(TileModel(TileState.Computing, stationName = nameOf))
                        is RouteState.Ready -> combine(rawStreams(), minuteTicker()) { raw, _ -> raw }
                            // Przeliczaj tylko przy istotnej zmianie wejścia (SPEC 7.3).
                            .map { it to inputKey(it) }
                            .distinctUntilChanged { a, b -> a.second == b.second }
                            .conflate()
                            .map { (raw, _) -> compute(rs, raw) }
                            .flowOn(Dispatchers.Default)
                    }
                }

        private fun analyze(input: NavInput, cfg: GeometryConfig): RouteGeometry? {
            val g = when (input) {
                NavInput.Idle -> null
                // Faza 0 (25.10): przy reversed=true Karoo podaje polyline już w kierunku jazdy —
                // ostatni punkt to meta. Nie odwracamy.
                is NavInput.Route ->
                    RouteAnalyzer.analyze(Polyline.decode(input.polyline, PRECISION), index, cfg.finishRadiusM, cfg.routeRadiusM)
                is NavInput.Destination ->
                    RouteAnalyzer.analyze(Polyline.decode(input.polyline, PRECISION), index, cfg.finishRadiusM, cfg.routeRadiusM, destination = input.destination)
            }
            FileLog.i(
                "Geometria: meta=${g?.finish}, kandydaci=${g?.finishCandidates?.map { "${it.station.name}@${it.distanceM.toInt()}m" }}, " +
                    "po drodze=${g?.stationsOnRoute?.map { "${it.station.name}@km${(it.occurrencesM.first() / 1000).toInt()}" }}",
            )
            return g
        }

        private fun compute(rs: RouteState.Ready, raw: RawStreams): TileModel = try {
            val now = clock.instant()
            val routeDistance = (rs.input as? NavInput.Route)?.routeDistanceM ?: rs.geometry.lengthM
            // Karoo potrafi na chwilę zgubić pole dystansu (log 25.10) — przytrzymaj ostatnią wartość
            // jak ETA (SPEC 5.1), żeby kafel nie przeskakiwał między dystansem z Karoo a długością trasy.
            val remaining = KarooUnits.distanceM(raw.distanceToDestination)
                ?.also { lastRemaining = it to now }
                ?: lastRemaining?.takeIf { java.time.Duration.between(it.second, now) <= EtaEstimator.HOLD }?.first
            val karooEta = KarooUnits.etaFromTimeOfArrival(raw.timeOfArrival)
                ?: KarooUnits.etaFromTimeToDestination(raw.timeToDestination, now)
            if (karooEta != null) lastKarooEta = KarooEtaSample(karooEta, now)
            val progress = RideProgress(remaining, karooEta, lastKarooEta, KarooUnits.speedMps(raw.averageSpeed))
            val state = stabilizer.apply(
                planner.plan(rs.geometry, routeDistance, progress, settings, targets, tt.dataStatus),
                now,
            )
            val finishEta = EtaEstimator.finishEta(
                now, progress.karooEta, progress.lastKarooEta, remaining, routeDistance, progress.movingAvgSpeedMps, settings.fallbackSpeedKmh,
            )
            val next = upcoming.compute(rs.geometry, routeDistance, progress, finishEta, settings, targets, limit = 2)
            // Log przy zmianie typu stanu, a poza tym co 5 min.
            val kind = state.javaClass.simpleName
            if (kind != lastLoggedState || clock.millis() - lastLogAt > 300_000) {
                FileLog.i("Kafelek: $kind eta=$finishEta remaining=$remaining raw=$raw upcoming=${next.map { it.station.name }}")
                lastLoggedState = kind
                lastLogAt = clock.millis()
            }
            _routeContext.value = RouteContext(rs.geometry, routeDistance, progress.copy(remainingM = remaining), finishEta, settings, targets, state)
            // Bez DISTANCE_TO_DESTINATION (np. przed ruszeniem) dystans z długości trasy, jak przy stacjach po drodze.
            TileModel(state, next, nameOf, finishEta, remaining ?: routeDistance, clickable = settings.tileClickable)
        } catch (e: Exception) {
            FileLog.e("Błąd obliczeń kafelka", e)
            TileModel(TileState.Computing, stationName = nameOf)
        }
    }

    private fun rawStreams(): Flow<RawStreams> = combine(
        source.stream(DataType.Type.DISTANCE_TO_DESTINATION),
        source.stream(DataType.Type.TIME_OF_ARRIVAL),
        source.stream(DataType.Type.TIME_TO_DESTINATION),
        source.stream(DataType.Type.AVERAGE_SPEED),
    ) { d, toa, ttd, avg ->
        RawStreams(d.value(DataType.Field.DISTANCE_TO_DESTINATION), toa.value(), ttd.value(), avg.value())
    }

    private fun minuteTicker(): Flow<Long> = flow {
        while (true) {
            emit(clock.millis() / 60_000)
            delay(60_000 - clock.millis() % 60_000)
        }
    }

    /** Komórka ~300 × 300 m (na szerokości Łodzi): zmiana komórki = przeliczenie. */
    private fun cell(p: LatLon?): Pair<Long, Long>? =
        p?.let { (it.lat / CELL_DEG_LAT).roundToLong() to (it.lon / CELL_DEG_LON).roundToLong() }

    private fun inputKey(r: RawStreams) = listOf(
        clock.millis() / 60_000,
        r.distanceToDestination?.let { (it / 250).roundToLong() },
        r.timeOfArrival?.let { (it / 60_000).roundToLong() },
        r.timeToDestination?.let { (it / 30).roundToLong() },
        r.averageSpeed?.let { (it * 2).roundToLong() },
    )

    companion object {
        /** Ustalone w fazie 0 (docs/device-findings.md). */
        const val PRECISION = 5

        private const val CELL_DEG_LAT = 0.0027
        private const val CELL_DEG_LON = 0.0043
    }
}
