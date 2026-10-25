package pl.kolejka.karoo.domain.text

/** Skróty nazw stacji na kafelek (SPEC 7.1). */
object NameShortener {
    // Granice słów jako (?<!\p{L}) / (?!\p{L}) zamiast \b: od JDK 19 \b nie liczy „Ś"/„Ł" jako
    // liter, a flaga (?U) nie działa na Androidzie. Lookaroundy z \p{L} działają w obu.
    private const val B = "(?<!\\p{L})"
    private const val E = "(?!\\p{L})"
    private val replacements = listOf(
        "^Warszawa$E" to "W-wa",
        "^Łódź$E" to "Ł.",
        "${B}Mazowiecki(ego)?$E" to "Maz.",
        "${B}Mazowiecka$E" to "Maz.",
        "${B}Główn(y|a)$E" to "Gł.",
        "${B}Zachodni(a)?$E" to "Zach.",
        "${B}Wschodni(a)?$E" to "Wsch.",
        "${B}Śródmieście$E" to "Śródm.",
    ).map { (re, rep) -> Regex(re) to rep }

    /** Skraca standardowe człony; gdy dalej za długo — obcina i dodaje „…". */
    fun short(name: String, maxLen: Int): String {
        var s = name
        for ((re, rep) in replacements) {
            if (s.length <= maxLen) break
            s = s.replace(re, rep)
        }
        return ellipsize(s, maxLen)
    }

    /**
     * Wariant „mały" (~5 znaków): pierwszy znaczący człon nazwy, bez „Warszawa" / „Łódź"
     * („Warszawa Gdańska" → „Gdań.", „Mińsk Mazowiecki" → „Mińsk").
     */
    fun tiny(name: String, maxLen: Int = 5): String {
        val words = name.split(' ').filter { it.isNotBlank() }
        val core = if (words.size > 1 && words[0] in CITY_PREFIXES) words[1] else words.firstOrNull() ?: name
        return if (core.length <= maxLen) core else core.take(maxLen - 1) + "."
    }

    private val CITY_PREFIXES = setOf("Warszawa", "Łódź")

    fun ellipsize(s: String, maxLen: Int): String =
        if (s.length <= maxLen) s else s.take((maxLen - 1).coerceAtLeast(1)).trimEnd() + "…"
}
