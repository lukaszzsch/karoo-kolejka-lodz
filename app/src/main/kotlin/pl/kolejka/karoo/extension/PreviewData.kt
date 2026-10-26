package pl.kolejka.karoo.extension

import pl.kolejka.karoo.domain.geo.LatLon
import pl.kolejka.karoo.domain.model.Station
import pl.kolejka.karoo.domain.model.Train
import pl.kolejka.karoo.domain.plan.Eta
import pl.kolejka.karoo.domain.plan.NearbyOption
import pl.kolejka.karoo.domain.plan.NearbyRow
import pl.kolejka.karoo.domain.plan.TileState
import pl.kolejka.karoo.domain.plan.TrainClass
import pl.kolejka.karoo.domain.plan.TrainOption
import pl.kolejka.karoo.domain.plan.UpcomingStation
import pl.kolejka.karoo.domain.time.ServiceTime
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/** Statyczne dane do podglądu w edytorze profilu (SPEC 7.1: preview == true). */
object PreviewData {
    private val day = LocalDate.of(2026, 1, 5)
    private fun at(h: Int, m: Int) = ServiceTime.toInstant(day, h * 3600 + m * 60)
    private val minsk = Station(1, "Mińsk Mazowiecki", "minsk mazowiecki", LatLon(52.18, 21.57), "KM", null)
    private val sulejowek = Station(3, "Sulejówek Miłosna", "sulejowek milosna", LatLon(52.24, 21.24), "KM", null)
    private val debe = Station(4, "Dębe Wielkie", "debe wielkie", LatLon(52.20, 21.44), "KM", null)

    private fun train(id: Int, dep: LocalTime, arr: LocalTime, platform: String?) = Train(
        tripId = id, serviceDate = day, fromStationId = 1, departure = at(dep.hour, dep.minute),
        platform = platform, track = null, agency = "KM", routeShortName = "R2", isBus = false,
        labelGroup = 0, labelStationId = 2, labelArrival = at(arr.hour, arr.minute), earliestArrival = at(arr.hour, arr.minute),
    )

    val names: (Int) -> String = { if (it == 2) "Warszawa Gdańska" else "Mińsk Mazowiecki" }

    val state = TileState.Ok(
        station = minsk,
        stationDistanceM = 400.0,
        options = listOf(
            TrainOption(train(1, LocalTime.of(17, 12), LocalTime.of(18, 1), "2"), TrainClass.CATCHABLE, Duration.ofMinutes(-8), 27.0),
            TrainOption(train(2, LocalTime.of(17, 42), LocalTime.of(18, 31), "2"), TrainClass.SURE, Duration.ofMinutes(12), 18.0, isLast = true),
            TrainOption(train(3, LocalTime.of(18, 12), LocalTime.of(19, 1), "1"), TrainClass.SURE, Duration.ofMinutes(42), 12.0),
        ),
        mainIndex = 1,
        etaApproximate = false,
    )

    val finishEta = Eta(at(17, 10), approximate = false)
    const val REMAINING_M = 24_300.0

    // Tryb awaryjny (SPEC 6.10): 3 cele × 🚲/🚶.
    private val zgierz = Station(11, "Zgierz", "zgierz", LatLon(51.856, 19.406), "ŁKA,PR", null)
    private val zabieniec = Station(12, "Łódź Żabieniec", "lodz zabieniec", LatLon(51.786, 19.414), "ŁKA,PR", null)

    private fun lkaTrain(id: Int, dep: LocalTime, arr: LocalTime) =
        train(id, dep, arr, "1").copy(agency = "ŁKA", routeShortName = null)

    private fun opt(st: Station, roadM: Double, dep: LocalTime, arr: LocalTime, isLast: Boolean = false) =
        NearbyOption(st, roadM / 1.3, roadM, at(dep.hour, dep.minute).minusSeconds(600), lkaTrain(dep.toSecondOfDay(), dep, arr), isLast, tomorrow = false)

    val nearbyNames: (Int) -> String = { mapOf(21 to "Łódź Kaliska", 22 to "Łódź Fabryczna", 23 to "Łódź Widzew")[it] ?: "?" }

    val nearby = TileState.Nearby(
        rows = listOf(
            NearbyRow(0, 21, opt(zgierz, 2_500.0, LocalTime.of(17, 42), LocalTime.of(18, 1)), opt(zabieniec, 2_300.0, LocalTime.of(18, 7), LocalTime.of(18, 12))),
            NearbyRow(1, 22, opt(zgierz, 2_500.0, LocalTime.of(17, 50), LocalTime.of(18, 15)), opt(zgierz, 2_500.0, LocalTime.of(18, 20), LocalTime.of(18, 45))),
            NearbyRow(2, 23, opt(zabieniec, 2_300.0, LocalTime.of(17, 55), LocalTime.of(18, 18)), opt(zabieniec, 2_300.0, LocalTime.of(18, 55), LocalTime.of(19, 18), isLast = true)),
        ),
    )

    val upcoming = listOf(
        UpcomingStation(sulejowek, 21_000.0, 300.0, 9_400.0, at(16, 25), listOf(train(4, LocalTime.of(16, 34), LocalTime.of(17, 0), "1"))),
        UpcomingStation(debe, 28_000.0, 200.0, 16_400.0, at(16, 45), listOf(train(5, LocalTime.of(16, 58), LocalTime.of(17, 37), "2"))),
    )
}
