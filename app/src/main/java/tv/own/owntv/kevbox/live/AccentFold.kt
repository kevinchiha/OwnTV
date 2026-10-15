// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import java.text.Normalizer

/*
 * Searching shows without caring about accents: "telematin" finds "Télématin", and the other way round
 * (Kevin, 2026-10-15). SQLite's LIKE ignores only the case of A to Z, so the lookup uses a GLOB pattern
 * instead, where every letter is a set of its accented and capital forms ([accentGlob]). The guide's
 * orange outline compares titles with the accents folded away on both sides ([titleMatches]).
 */

private val combiningMarks = Regex("\\p{Mn}+")

/** [text] without accents and in lower case: "Télématin" -> "telematin". Letters that do not split into a
 *  base and an accent (æ, ø, ß) stay as they are. */
fun foldAccents(text: String): String =
    combiningMarks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(java.util.Locale.ROOT)

/** True when [title] contains [query], ignoring case and accents on both. */
fun titleMatches(title: String, query: String): Boolean = foldAccents(title).contains(foldAccents(query))

/** Every Latin letter with an accent, grouped under its plain lower-case letter: 'e' -> é è ê ë É È Ê Ë ... */
private val accentedForms: Map<Char, List<Char>> by lazy {
    ('\u00C0'..'\u024F')
        .mapNotNull { c -> foldAccents(c.toString()).singleOrNull()?.takeIf { it != c }?.let { it to c } }
        .groupBy({ it.first }, { it.second })
}

/**
 * A SQLite GLOB pattern that finds [query] anywhere in a title, in any case and with or without accents:
 * "té" -> "*[tT][eEéÉèÈ...]*". GLOB's own special characters in the query are matched literally.
 */
fun accentGlob(query: String): String = buildString {
    append('*')
    for (c in query) {
        val base = foldAccents(c.toString()).singleOrNull() ?: c
        val forms = (listOf(base, base.uppercaseChar()) + accentedForms[base].orEmpty()).distinct()
        when {
            forms.size > 1 -> forms.joinTo(this, "", "[", "]")
            c == '*' || c == '?' || c == '[' -> append('[').append(c).append(']')
            else -> append(c)
        }
    }
    append('*')
}
