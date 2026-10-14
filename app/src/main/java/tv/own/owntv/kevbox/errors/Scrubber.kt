// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.errors

/**
 * Cleans every text field before it leaves the TV: addresses, logins, tokens, emails and the
 * member's own credentials come out, replaced by fixed markers.
 */
object Scrubber {
    const val MESSAGE_MAX = 160

    private const val URL_MARK = "[url]"
    private const val REMOVED_MARK = "[removed]"
    private const val BEARER_MARK = "Bearer [token]"
    private const val TOKEN_MARK = "[token]"
    private const val EMAIL_MARK = "[email]"

    /** Shorter secrets are skipped: a one-digit password would blank every digit in a message. */
    private const val SECRET_MIN = 4

    private val URL = Regex("""[a-zA-Z][a-zA-Z0-9+.-]*://\S+""")
    private val QUERY_SECRET =
        Regex("""\b(access_token|refresh_token|username|password|token)=[^&\s]+""", RegexOption.IGNORE_CASE)
    private val JSON_TOKEN = Regex("""("(?:access_token|refresh_token)"\s*:\s*)("(?:[^"\\]|\\.)*")""")
    private val BEARER = Regex("""\bBearer\s+\S+""", RegexOption.IGNORE_CASE)
    private val JWT = Regex("""eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

    /** Scheme, optional login, then the host (a name or a bracketed IPv6 address). */
    private val HOST = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://(?:[^@/?#\s]*@)?(\[[0-9A-Fa-f:.]+\]|[A-Za-z0-9._-]+)(?:[:/?#]|$)""")

    /** Null in, null out. [max] cuts the result after scrubbing; null keeps the full length. */
    fun text(input: String?, secrets: Collection<String>, max: Int? = null): String? {
        if (input == null) return null
        var s = input
        s = URL.replace(s, URL_MARK)
        s = QUERY_SECRET.replace(s) { it.groupValues[1] + "=" + REMOVED_MARK }
        s = JSON_TOKEN.replace(s) { it.groupValues[1] + "\"" + REMOVED_MARK + "\"" }
        s = BEARER.replace(s, BEARER_MARK)
        s = JWT.replace(s, TOKEN_MARK)
        s = EMAIL.replace(s, EMAIL_MARK)
        val kept = secrets.filter { it.length >= SECRET_MIN }.distinct().sortedByDescending { it.length }
        if (kept.isNotEmpty()) {
            val any = Regex(kept.joinToString("|") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
            s = any.replace(s) { REMOVED_MARK }
        }
        return if (max == null) s else s.take(max.coerceAtLeast(0))
    }

    /** The host of an address, lowercased, or null; never a path, port, query or credential. */
    fun host(address: String?): String? {
        val match = address?.trim()?.let(HOST::find) ?: return null
        return match.groupValues[1].lowercase()
    }
}
