package pl.kolejka.karoo.domain.text

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
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Wszystko, co pokazuje kafelek. */
data class TileModel(
    val state: TileState,
    val upcoming: List<UpcomingStation> = emptyList(),
    val stationName: (Int) -> String = { "#$it" },
    /** ETA mety i pozostały dystans — do linii 🚲 w nagłówku. */
    val finishEta: Eta? = null,
    val remainingM: Double? = null,
    /** Ustawienie „Kafelek otwiera aplikację" (P3). */
    val clickable: Boolean = true,
)

enum class Emphasis { NORMAL, WARN, ALERT, DIM }

/** Wielkość linii względem czcionki bazowej; renderer dobiera bazową do rozmiaru pola. */
enum class LineSize { LARGE, MEDIUM, SMALL }

data class TileLine(
    val text: String,
    val emphasis: Emphasis = Emphasis.NORMAL,
    val size: LineSize = LineSize.LARGE,
    /** Wyróżnione dopiski na końcu linii (np. „OSTATNI"), rysowane na czerwono. */
    val badge: String? = null,
) {
    val fullText: String get() = if (badge != null) "$text · $badge" else text
    val small: Boolean get() = size == LineSize.SMALL
}

/** Układ zależny od rozmiaru pola: pół szerokości / pełna szerokość / wysoki (≥ pół strony). */
enum class TileLayout { COMPACT, WIDE, TALL }

/**
 * Linie tekstu kafelka w kolejności ważności (SPEC 7.1–7.2). Renderer pokazuje tyle, ile się
 * zmieści. [compact] = wariant mały (pół szerokości).
 */
object TileText {
    private val HM = DateTimeFormatter.ofPattern("HH:mm").withZone(ServiceTime.ZONE)

    fun hm(i: Instant): String = HM.format(i)

    fun lines(m: TileModel, compact: Boolean): List<TileLine> = lines(m, if (compact) TileLayout.COMPACT else TileLayout.WIDE)

    fun lines(m: TileModel, layout: TileLayout): List<TileLine> {
        val s = m.state
        return if (layout == TileLayout.TALL && s is TileState.Ok) tall(s, m) else basic(m, layout == TileLayout.COMPACT, layout == TileLayout.TALL)
    }

    private fun basic(m: TileModel, compact: Boolean, tall: Boolean): List<TileLine> = when (val s = m.state) {
        TileState.NoRoute -> listOf(TileLine("Brak trasy"), TileLine("dotknij: rozkład", Emphasis.DIM))
        TileState.Computing -> listOf(TileLine("Liczę…", Emphasis.DIM))
        TileState.DataMissing -> listOf(TileLine("Brak rozkładu", Emphasis.WARN), TileLine("Połącz z Wi-Fi", Emphasis.DIM))
        is TileState.DataStale -> listOf(TileLine("Rozkład nieaktualny", Emphasis.WARN), TileLine("${s.ageDays} dni", Emphasis.DIM))
        is TileState.AlreadyAtTarget -> listOf(TileLine("Meta przy celu"), TileLine(short(s.station, compact)))
        is TileState.NoTrainsToday -> listOf(TileLine("$WARNING Dziś brak pociągów", Emphasis.WARN), TileLine("z ${short(s.station, compact)}")) + upcomingFor(m, compact, tall)
        is TileState.NoStationNearFinish -> buildList {
            val nearest = s.nearestM?.let { " (najbl. ${km(it)})" }.orEmpty()
            if (tall) {
                add(TileLine("Brak stacji ≤1 km od mety", Emphasis.DIM))
                s.nearestM?.let { add(TileLine("najbliższa ${km(it)}", Emphasis.DIM, size = LineSize.SMALL)) }
            } else {
                add(TileLine(if (compact) "Brak stacji${nearest}" else "Brak stacji ≤1 km od mety$nearest", Emphasis.DIM))
            }
            addAll(upcomingFor(m, compact, tall))
        }
        is TileState.LastTrainMissed -> buildList {
            val last = s.lastFromFinish?.train
            add(TileLine(if (last != null) "$WARNING Ostatni z ${short(s.station, compact)} ${hm(last.departure)} — nie zdążysz" else "$WARNING Nie zdążysz na ostatni", Emphasis.ALERT))
            s.alternative?.let { add(TileLine("Zjedź: ${short(it.station, compact)} ${hm(it.train.departure)} (km ${(it.alongM / 1000).roundToInt()})", Emphasis.ALERT)) }
            addAll(upcomingFor(m, compact, tall))
        }
        is TileState.Ok -> ok(s, m, compact)
        TileState.NoPosition -> listOf(TileLine("Szukam GPS…", Emphasis.DIM))
        is TileState.NoStationNearby -> listOf(
            TileLine("Brak stacji ≤${km(s.radiusM)}", Emphasis.WARN),
        ) + listOfNotNull(s.nearestM?.let { TileLine("najbliższa ${km(it)}", Emphasis.DIM, LineSize.SMALL) })
        is TileState.Nearby -> nearby(s, m, if (compact) TileLayout.COMPACT else if (tall) TileLayout.TALL else TileLayout.WIDE)
    }

    /**
     * Tryb awaryjny: wiersz na każdy cel (Kaliska / Fabryczna / Widzew), w każdym 🚲 rowerem
     * i 🚶 pieszo z rowerem — przy trzech celach zawsze 6 opcji; brak pociągu = „—".
     * Wąski i szeroki kafel: jedna linia na cel. Wysoki: nagłówek celu + linia na wariant
     * (stacja, odjazd → przyjazd, dystans, OSTATNI).
     */
    private fun nearby(s: TileState.Nearby, m: TileModel, layout: TileLayout): List<TileLine> {
        val approx = if (s.dataStale) " ≈" else ""
        return when (layout) {
            TileLayout.TALL -> s.rows.flatMap { r ->
                listOf(TileLine("→ ${NameShortener.short(m.stationName(r.targetStationId), 18)}$approx", size = LineSize.MEDIUM)) +
                    nearbyTall(BIKE, r.ride) + nearbyTall(WALK, r.walk)
            }
            TileLayout.WIDE -> s.rows.map { r ->
                val t = NameShortener.tiny(m.stationName(r.targetStationId), 6)
                TileLine("$t: $BIKE ${wide(r.ride)} · $WALK ${wide(r.walk)}$approx", rowEmphasis(r))
            }
            TileLayout.COMPACT -> s.rows.map { r ->
                val t = NameShortener.tiny(m.stationName(r.targetStationId), 4)
                TileLine("$t $BIKE${compactTime(r.ride)} $WALK${compactTime(r.walk)}", rowEmphasis(r))
            }
        }
    }

    private fun nearbyTall(mark: String, o: NearbyOption?): List<TileLine> {
        if (o == null) return listOf(TileLine("$mark —", Emphasis.DIM))
        val t = o.train
        val text = "$mark ${NameShortener.short(o.station.name, 12)} ${day(o)}${hm(t.departure)}→${hm(t.labelArrival)} · ${km(o.roadM)}" +
            (if (t.isBus) " $BUS" else "")
        return listOf(TileLine(text, nearbyEmphasis(o), badge = "OSTATNI".takeIf { o.isLast }))
    }

    /** „Zgierz 17:42" / „—". */
    private fun wide(o: NearbyOption?): String =
        o?.let { "${NameShortener.tiny(it.station.name, 7)} ${day(it)}${hm(it.train.departure)}" } ?: "—"

    private fun compactTime(o: NearbyOption?): String = o?.let { (if (it.tomorrow) "j" else "") + hm(it.train.departure) } ?: "—"

    private fun rowEmphasis(r: NearbyRow) = when {
        r.ride == null && r.walk == null -> Emphasis.DIM
        listOfNotNull(r.ride, r.walk).any { it.tomorrow || it.isLast } -> Emphasis.WARN
        else -> Emphasis.NORMAL
    }

    private fun day(o: NearbyOption) = if (o.tomorrow) "jutro " else ""

    private fun nearbyEmphasis(o: NearbyOption) = when {
        o.tomorrow -> Emphasis.WARN
        o.isLast -> Emphasis.WARN
        else -> Emphasis.NORMAL
    }

    /**
     * Wysoki kafel (wąski: ~255 dp na Karoo 3): meta (skąd → dokąd, 🚲 ETA i dystans), trzy
     * pociągi (🟠🟢🔵🔴 godziny + szczegóły mniejszą czcionką), potem stacje po drodze w całości
     * małą czcionką (skąd → dokąd, 🚲 ETA i dystans, pierwszy osiągalny pociąg).
     */
    private fun tall(s: TileState.Ok, m: TileModel): List<TileLine> = buildList {
        val main = s.main.train
        add(TileLine(fromTo(s.station.name, m.stationName(main.labelStationId)), size = LineSize.MEDIUM))
        add(TileLine(ride(m.finishEta?.instant, m.remainingM, s.etaApproximate || s.dataStale), Emphasis.DIM, LineSize.SMALL))
        for (o in s.display) {
            val r = row(o, o === s.main)
            val t = o.train
            add(TileLine("${r.mark} ${hm(t.departure)} → ${hm(t.labelArrival)}", r.emphasis))
            val details = listOfNotNull(BUS.takeIf { t.isBus }, platformText(t), r.detail)
            add(TileLine(details.joinToString(" · "), r.emphasis.takeIf { it != Emphasis.NORMAL } ?: Emphasis.DIM, LineSize.SMALL, badge = "OSTATNI".takeIf { o.isLast }))
        }
        s.safePlan?.let {
            add(TileLine("$WARNING Plan B: ${NameShortener.short(it.station.name, 14)}", Emphasis.WARN, LineSize.SMALL))
            add(TileLine("${hm(it.train.departure)} → ${hm(it.train.labelArrival)}${platformText(it.train)?.let { p -> " · $p" }.orEmpty()}", Emphasis.WARN, LineSize.SMALL))
        }
        addAll(upcomingTall(m))
    }

    private fun upcomingTall(m: TileModel): List<TileLine> = m.upcoming.flatMap { u ->
        val t = u.train
        listOf(
            TileLine(fromTo(u.station.name, t?.let { m.stationName(it.labelStationId) }), Emphasis.NORMAL, LineSize.SMALL),
            TileLine(ride(u.eta, u.distanceM, approximate = false), Emphasis.DIM, LineSize.SMALL),
            if (t != null) {
                val bus = if (t.isBus) "$BUS " else ""
                TileLine("$bus${hm(t.departure)} → ${hm(t.labelArrival)}${platformText(t)?.let { " · $it" }.orEmpty()}", Emphasis.NORMAL, LineSize.SMALL)
            } else {
                TileLine("brak pociągu", Emphasis.DIM, LineSize.SMALL)
            },
        )
    }

    private fun fromTo(from: String, to: String?): String =
        if (to == null) NameShortener.short(from, 18) else "${NameShortener.short(from, 12)} → ${NameShortener.short(to, 12)}"

    /** „🚲 14:38 · 52 km" (≈ gdy ETA z fallbacku). */
    fun ride(eta: Instant?, distanceM: Double?, approximate: Boolean): String {
        val parts = listOfNotNull(eta?.let { (if (approximate) "≈" else "") + hm(it) }, distanceM?.let { km(it) })
        return if (parts.isEmpty()) "$BIKE —" else "$BIKE ${parts.joinToString(" · ")}"
    }

    private val TIGHT_SLACK: java.time.Duration = java.time.Duration.ofMinutes(5)

    const val BIKE = "\uD83D\uDEB2" // 🚲
    const val BUS = "\uD83D\uDE8C" // 🚌 komunikacja zastępcza
    const val WALK = "\uD83D\uDEB6" // 🚶 pieszo z rowerem
    const val WARNING = "\u26A0\uFE0F" // ⚠️
    const val CATCHABLE = "\uD83D\uDFE0" // 🟠 do złapania, jeśli przyspieszysz
    const val SURE = "\uD83D\uDFE2" // 🟢 zdążysz wg ETA
    const val NEXT = "\uD83D\uDD35" // 🔵 następny
    const val MISSED = "\uD83D\uDD34" // 🔴 nie zdążysz

    private fun ok(s: TileState.Ok, m: TileModel, compact: Boolean): List<TileLine> {
        val main = s.main.train
        val approx = if (s.etaApproximate || s.dataStale) " ≈" else ""
        if (compact) {
            return listOf(
                TileLine("${hm(main.departure)}→${hm(main.labelArrival)}", if (s.main.slack.toMinutes() < 5) Emphasis.WARN else Emphasis.NORMAL),
                TileLine("${NameShortener.tiny(s.station.name)}→${NameShortener.tiny(m.stationName(main.labelStationId))}$approx"),
            ) + s.next?.let { listOf(TileLine("$NEXT ${hm(it.train.departure)}", Emphasis.DIM)) }.orEmpty()
        }
        val platform = platformText(main)?.let { "  $it" }.orEmpty()
        return buildList {
            add(TileLine("${NameShortener.short(s.station.name, 14)} → ${NameShortener.short(m.stationName(main.labelStationId), 14)}$platform$approx"))
            for (o in s.display) add(rowLine(o, o === s.main))
            s.safePlan?.let { add(TileLine("$WARNING Plan B: ${NameShortener.short(it.station.name, 14)} ${hm(it.train.departure)}", Emphasis.WARN)) }
            addAll(upcoming(m, compact = false))
        }
    }

    private data class Row(val mark: String, val detail: String, val emphasis: Emphasis)

    private fun row(o: TrainOption, isMain: Boolean): Row {
        val mark = when (o.cls) {
            // Główny bywa trzymany przez histerezę mimo ujemnego zapasu — znak mówi prawdę o klasie.
            // Zapas < 5 min: zdążysz wg ETA, ale na styk — ⚠️ zamiast 🟢.
            TrainClass.SURE -> if (!isMain) NEXT else if (o.slack < TIGHT_SLACK) WARNING else SURE
            TrainClass.CATCHABLE -> CATCHABLE
            TrainClass.MISSED -> MISSED
        }
        val detail = when {
            !isMain && o.cls == TrainClass.CATCHABLE -> "potrzeba ${o.vReqKmh?.roundToInt() ?: "?"} km/h"
            o.cls == TrainClass.MISSED && !isMain -> "odpada"
            else -> "zapas ${o.slack.toMinutes()} min"
        }
        val emphasis = when {
            isMain && o.slack.toMinutes() < 5 -> Emphasis.WARN
            o.isLast -> Emphasis.WARN
            o.cls == TrainClass.MISSED -> Emphasis.DIM
            else -> Emphasis.NORMAL
        }
        return Row(mark, detail, emphasis)
    }

    private fun rowLine(o: TrainOption, isMain: Boolean): TileLine {
        val t = o.train
        val r = row(o, isMain)
        val flags = listOfNotNull(BUS.takeIf { t.isBus }, "OSTATNI".takeIf { o.isLast }).joinToString(" ")
        return TileLine("${r.mark} ${hm(t.departure)}→${hm(t.labelArrival)}  ${r.detail}${if (flags.isNotEmpty()) "  $flags" else ""}", r.emphasis)
    }

    private fun upcomingFor(m: TileModel, compact: Boolean, tall: Boolean) = if (tall) upcomingTall(m) else upcoming(m, compact)

    private fun upcoming(m: TileModel, compact: Boolean): List<TileLine> =
        m.upcoming.map { u ->
            val name = if (compact) NameShortener.tiny(u.station.name) else NameShortener.short(u.station.name, 14)
            val train = u.train?.let { "${hm(it.departure)}${platformText(it)?.let { p -> " $p" }.orEmpty()}" } ?: "—"
            TileLine("$name ~${hm(u.eta)}: $train", Emphasis.DIM)
        }

    fun platformText(t: Train): String? = t.platform?.let { p -> "P$p" + (t.track?.let { "/t$it" }.orEmpty()) }

    private fun short(st: Station, compact: Boolean) = if (compact) NameShortener.tiny(st.name) else NameShortener.short(st.name, 16)

    fun km(m: Double): String = when {
        m < 1000 -> "${(m / 10).roundToInt() * 10} m"
        m < 10_000 -> "%.1f km".format(java.util.Locale("pl"), m / 1000)
        else -> "${(m / 1000).roundToInt()} km"
    }
}
