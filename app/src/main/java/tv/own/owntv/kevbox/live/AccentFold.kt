// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import java.text.Normalizer

/*
 * Searching shows without caring about accents: "telematin" finds "Télématin", and the other way round
 * (Kevin, 2026-10-15). SQLite's LIKE ignores only the case of A to Z, so the lookup uses a GLOB pattern
 * instead, where every letter is a set of its accented and capital forms ([accentGlob]). The guide's
 * orange outline compares titles with the accents folded away on both sides ([titleMatches]).
 *
 * A search of several words matches word by word, not as one phrase: every word must appear somewhere
 * in the title, in any order (Kevin, 2026-10-15). One GLOB per word does this in SQLite ([accentGlobs]).
 */

private val combiningMarks = Regex("\\p{Mn}+")

/** [text] without accents and in lower case: "Télématin" -> "telematin". Letters that do not split into a
 *  base and an accent (æ, ø, ß) stay as they are. */
fun foldAccents(text: String): String =
    combiningMarks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(java.util.Locale.ROOT)

/** The words of a search, split at spaces only, so "canal+" keeps its plus. */
fun queryWords(query: String): List<String> = query.trim().split(whitespace).filter { it.isNotEmpty() }

private val whitespace = Regex("\\s+")

/** One [accentGlob] per word of [query]: a title must match them all. */
fun accentGlobs(query: String): List<String> = queryWords(query).map(::accentGlob)

/** The longest word of [query] ("" for none): the one that keeps a database read smallest. */
fun longestWord(query: String): String = queryWords(query).maxByOrNull { it.length }.orEmpty()

/**
 * True when [title] contains every word of [query], in any order, ignoring case and accents on both:
 * "man city" finds "Manchester City" (Kevin, 2026-10-15). Used for channel names too.
 */
fun titleMatches(title: String, query: String): Boolean {
    val folded = foldAccents(title)
    return queryWords(query).all { folded.contains(foldAccents(it)) }
}

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
