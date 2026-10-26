package pl.kolejka.karoo.domain.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.kolejka.karoo.domain.geo.LatLon
import pl.kolejka.karoo.domain.geo.SpatialIndex
import pl.kolejka.karoo.domain.model.Settings
import pl.kolejka.karoo.domain.model.Station
import pl.kolejka.karoo.domain.model.TargetGroups
import pl.kolejka.karoo.domain.text.NameShortener
import pl.kolejka.karoo.domain.text.TileLayout
import pl.kolejka.karoo.domain.text.TileModel
import pl.kolejka.karoo.domain.text.TileText
import pl.kolejka.karoo.domain.time.ServiceTime
import pl.kolejka.karoo.domain.timetable.InMemoryTimetable
import pl.kolejka.karoo.domain.timetable.InMemoryTimetable.StopData
import pl.kolejka.karoo.domain.timetable.InMemoryTimetable.TripData
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/** Pierwszy wiersz (jeden cel) — większość testów ma tylko Kaliską. */
private val TileState.Nearby.ride get() = rows[0].ride
private val TileState.Nearby.walk get() = rows[0].walk

/** Tryb awaryjny „z pozycji" (SPEC 6.10). */
class NearbyPlannerTest {
    private val day = LocalDate.of(2026, 10, 26) // poniedziałek
    private val pos = LatLon(51.85, 19.40)

    /** Punkt [north] m na północ (ujemne = południe) od [pos]. */
    private fun north(m: Double) = LatLon(pos.lat + m / 111_320.0, pos.lon)
    private fun station(id: Int, name: String, at: LatLon) = Station(id, name, name.lowercase(), at, "ŁKA", null)

    private val kaliska = station(1000, "Łódź Kaliska", LatLon(51.7569, 19.4300))
    private val zgierz = station(1100, "Zgierz", north(1_923.0)) // ~2,5 km po drogach
    private val polnoc = station(1200, "Zgierz Południe", north(-4_000.0)) // ~5,2 km po drogach
    private val far = station(1300, "Sieradz", LatLon(51.596, 18.730))
    private val targets = TargetGroups(listOf(setOf(1000)))
    private val stations = listOf(kaliska, zgierz, polnoc, far)

    private fun s(h: Int, m: Int) = h * 3600 + m * 60
    private fun at(h: Int, m: Int, date: LocalDate = day): Instant = LocalDateTime.of(date, LocalTime.of(h, m)).atZone(ServiceTime.ZONE).toInstant()
    private val data = DataStatus(validTo = day.plusDays(10), generatedAt = at(3, 0))

    private fun trip(id: Int, from: Int, dep: Int, arr: Int, bus: Boolean = false, dates: Set<LocalDate> = setOf(day, day.plusDays(1))) =
        TripData(id, dates, listOf(StopData(from, dep), StopData(1000, arr)), agency = "ŁKA", isBus = bus)

    private val baseTrips = listOf(
        trip(1, 1100, s(18, 20), s(18, 40)), // Zgierz — blisko, ale późno
        trip(2, 1200, s(17, 35), s(17, 55)), // dalsza stacja — wcześniejszy przyjazd
        trip(3, 1200, s(19, 0), s(19, 20)),
    )

    private fun planner(now: Instant, trips: List<TripData>) = NearbyPlanner(
        TrainPlanner(InMemoryTimetable(stations, trips), Clock.fixed(now, ZoneOffset.UTC)),
        SpatialIndex(stations),
        Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun plan(
        now: Instant,
        trips: List<TripData> = baseTrips,
        position: LatLon? = pos,
        // Testy liczone dla 15 km/h niezależnie od domyślnej prędkości rowerem.
        settings: Settings = Settings(emergencyRideKmh = 15.0),
        previous: TileState.Nearby? = null,
    ) = planner(now, trips).plan(position, settings, targets, data, previous)

    @Test
    fun `ride picks earliest arrival, not the nearest station`() {
        val st = plan(at(17, 0)) as TileState.Nearby
        val ride = st.ride!!
        assertEquals("Zgierz Południe", ride.station.name)
        assertEquals(at(17, 35), ride.train.departure)
        assertEquals(5_200.0, ride.roadM, 50.0)
        // 5,2 km przy 15 km/h ≈ 21 min + 5 min zapasu → gotowy ~17:26
        assertEquals(9L, ride.slack.toMinutes())
        assertFalse(ride.tomorrow)
    }

    @Test
    fun `walk on foot takes the nearer station when the far train is missed`() {
        val walk = (plan(at(17, 0)) as TileState.Nearby).walk!!
        // Pieszo: Zgierz gotowy ~17:35 → 18:20 (18:40 w Kaliskiej); Południe dopiero na 19:00.
        assertEquals("Zgierz", walk.station.name)
        assertEquals(at(18, 20), walk.train.departure)
    }

    @Test
    fun `last train and tomorrow morning`() {
        val trips = listOf(
            trip(1, 1100, s(22, 30), s(22, 50)),
            trip(2, 1100, s(4, 50), s(5, 10), dates = setOf(day.plusDays(1))),
        )
        val st = plan(at(22, 0), trips) as TileState.Nearby
        assertEquals(at(22, 30), st.ride!!.train.departure)
        assertTrue(st.ride!!.isLast)
        assertFalse(st.ride!!.tomorrow)
        assertEquals(at(4, 50, day.plusDays(1)), st.walk!!.train.departure)
        assertTrue(st.walk!!.tomorrow)

        val names: (Int) -> String = { if (it == 1000) "Łódź Kaliska" else "#$it" }
        val tall = TileText.lines(TileModel(st, stationName = names), TileLayout.TALL).map { it.fullText }
        assertEquals(tall.toString(), "→ Łódź Kaliska", tall[0])
        assertTrue(tall.toString(), tall[1].startsWith("${TileText.BIKE} Zgierz 22:30→22:50"))
        assertTrue(tall.toString(), tall[1].endsWith("OSTATNI"))
        assertTrue(tall.toString(), tall[2].startsWith("${TileText.WALK} Zgierz jutro 04:50→05:10"))
        val wide = TileText.lines(TileModel(st, stationName = names), TileLayout.WIDE).map { it.text }
        assertEquals(listOf("Kalis.: ${TileText.BIKE} Zgierz 22:30 · ${TileText.WALK} Zgierz jutro 04:50"), wide)
    }

    @Test
    fun `replacement bus skipped unless allowed`() {
        val trips = listOf(trip(1, 1100, s(17, 30), s(17, 50), bus = true), trip(2, 1100, s(18, 30), s(18, 50)))
        val noBus = plan(at(17, 0), trips) as TileState.Nearby
        assertEquals(at(18, 30), noBus.ride!!.train.departure)
        val withBus = plan(at(17, 0), trips, settings = Settings(emergencyRideKmh = 15.0, allowBus = true)) as TileState.Nearby
        assertEquals(at(17, 30), withBus.ride!!.train.departure)
        assertTrue(withBus.ride!!.train.isBus)
    }

    @Test
    fun `three targets always give six options, missing ones as dash`() {
        val fabryczna = station(1600, "Łódź Fabryczna", LatLon(51.769, 19.467))
        val widzew = station(1300, "Łódź Widzew", LatLon(51.757, 19.516))
        val all = stations.filter { it.id != 1300 } + fabryczna + widzew
        val trips = listOf(
            trip(1, 1100, s(18, 0), s(18, 30)), // → Kaliska
            TripData(2, setOf(day), listOf(StopData(1100, s(17, 50)), StopData(1600, s(18, 15))), agency = "ŁKA"), // → Fabryczna
        )
        val now = at(17, 0)
        val p = NearbyPlanner(TrainPlanner(InMemoryTimetable(all, trips), Clock.fixed(now, ZoneOffset.UTC)), SpatialIndex(all), Clock.fixed(now, ZoneOffset.UTC))
        val three = TargetGroups(listOf(setOf(1000), setOf(1600), setOf(1300)))
        val st = p.plan(pos, Settings(emergencyRideKmh = 15.0), three, data) as TileState.Nearby
        assertEquals(listOf(1000, 1600, 1300), st.rows.map { it.targetStationId })
        assertEquals(at(18, 0), st.rows[0].ride!!.train.departure)
        assertEquals(at(17, 50), st.rows[1].ride!!.train.departure)
        assertEquals(null, st.rows[2].ride)

        val names = all.associate { it.id to it.name }
        val m = TileModel(st, stationName = { names[it] ?: "#$it" })
        val B = TileText.BIKE
        val W = TileText.WALK
        assertEquals(
            listOf("Kal. ${B}18:00 ${W}18:00", "Fab. ${B}17:50 ${W}17:50", "Wid. ${B}— ${W}—"),
            TileText.lines(m, TileLayout.COMPACT).map { it.text },
        )
        val tall = TileText.lines(m, TileLayout.TALL).map { it.text }
        assertEquals(9, tall.size)
        assertEquals(listOf("→ Łódź Kaliska", "→ Łódź Fabryczna", "→ Łódź Widzew"), listOf(tall[0], tall[3], tall[6]))
        assertEquals(listOf("$B —", "$W —"), tall.subList(7, 9))
    }

    @Test
    fun `hysteresis keeps previous choice unless new one is 5 min better`() {
        val trips = listOf(
            trip(1, 1100, s(18, 0), s(18, 30)),
            trip(2, 1200, s(17, 50), s(18, 27)), // tylko 3 min wcześniej w Kaliskiej
        )
        val first = plan(at(17, 0), trips) as TileState.Nearby
        assertEquals("Zgierz Południe", first.ride!!.station.name)
        val zgierzOpt = first.ride!!.copy(station = zgierz, train = (plan(at(17, 0), listOf(trips[0])) as TileState.Nearby).ride!!.train)
        val prev = first.copy(rows = listOf(first.rows[0].copy(ride = zgierzOpt)))
        val kept = plan(at(17, 0), trips, previous = prev) as TileState.Nearby
        assertEquals("Zgierz", kept.ride!!.station.name)

        val better = listOf(trips[0], trip(2, 1200, s(17, 45), s(18, 10)))
        val switched = plan(at(17, 0), better, previous = prev) as TileState.Nearby
        assertEquals("Zgierz Południe", switched.ride!!.station.name)
    }

    @Test
    fun `at target, no station, no position, no trains`() {
        assertTrue(plan(at(17, 0), position = LatLon(51.7600, 19.4300)) is TileState.AlreadyAtTarget)
        assertTrue(plan(at(17, 0), position = null) is TileState.NoPosition)
        val none = plan(at(17, 0), position = LatLon(51.30, 19.40))
        assertTrue(none.toString(), none is TileState.NoStationNearby)
        assertNotNull((none as TileState.NoStationNearby).nearestM)
        val noTrains = plan(at(17, 0), trips = emptyList())
        assertTrue(noTrains.toString(), noTrains is TileState.NoTrainsToday)
    }

    @Test
    fun `data missing and stale`() {
        val p = planner(at(17, 0), baseTrips)
        assertTrue(p.plan(pos, Settings(), targets, null) is TileState.DataMissing)
        assertTrue(p.plan(pos, Settings(), targets, DataStatus(day.minusDays(1), at(3, 0, day.minusDays(10)))) is TileState.DataStale)
        val stale = p.plan(pos, Settings(), targets, DataStatus(day, at(3, 0))) as TileState.Nearby
        assertTrue(stale.dataStale)
    }

    @Test
    fun `lodz names shortened`() {
        assertEquals("Kali.", NameShortener.tiny("Łódź Kaliska"))
        assertEquals("Ł. Kaliska", NameShortener.short("Łódź Kaliska", 10))
        assertEquals("Zgierz", NameShortener.short("Zgierz", 10))
    }
}
