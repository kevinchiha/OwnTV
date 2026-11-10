// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.vpn

private const val RESOLV_RETRY = "resolv-retry"
private const val RESOLV_RETRY_INFINITE = "infinite"
private const val RESOLV_RETRY_SECONDS = "resolv-retry 25"
private const val DATA_CIPHERS = "data-ciphers"
private const val DATA_CIPHERS_FALLBACK = "data-ciphers-fallback"
private const val DATA_CIPHERS_LINE = "data-ciphers AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305:AES-256-CBC"
private const val DATA_CIPHERS_FALLBACK_LINE = "data-ciphers-fallback AES-256-CBC"
private const val CIPHER = "cipher"
private const val AUTH_BLOCK_OPEN = "<auth-user-pass>"
private const val AUTH_BLOCK_CLOSE = "</auth-user-pass>"

/**
 * Pure. The config the engine receives: Premiumize's file adapted to the app's retry rules.
 *
 * - An inline `<auth-user-pass>…</auth-user-pass>` block goes (the login comes from the profile); the
 *   bare `auth-user-pass` option stays.
 * - `resolv-retry infinite` becomes `resolv-retry 25`, so the controller's 30-second rules decide
 *   when a server is given up (AC-30).
 * - With no `data-ciphers` line, `data-ciphers` and `data-ciphers-fallback` lines are added after the
 *   `cipher` line (or at the end): Premiumize files say `cipher AES-256-CBC`, which an OpenVPN 2.6+
 *   core no longer negotiates by itself.
 *
 * Every other line, the `<ca>` block and `verify-x509-name` stay byte for byte, and each line keeps
 * its line ending; added lines use the file's first line ending.
 */
fun prepareOvpn(raw: String): String {
    val lines = splitKeepingEndings(raw)
    val newline = lines.firstOrNull { it.ending.isNotEmpty() }?.ending ?: "\n"

    val kept = ArrayList<Line>(lines.size + 2)
    var inBlock: String? = null
    var inAuthBlock = false
    var hasDataCiphers = false
    var hasFallback = false
    var cipherIndex = -1
    for (line in lines) {
        val text = line.text.trim()
        if (inAuthBlock) {
            if (text.contains(AUTH_BLOCK_CLOSE)) inAuthBlock = false
            continue
        }
        if (inBlock != null) {
            kept += line
            if (text == "</$inBlock>") inBlock = null
            continue
        }
        if (text.startsWith(AUTH_BLOCK_OPEN)) {
            inAuthBlock = !text.contains(AUTH_BLOCK_CLOSE)
            continue
        }
        val blockTag = openingTag(text)
        if (blockTag != null) {
            kept += line
            if (!text.contains("</$blockTag>")) inBlock = blockTag
            continue
        }
        val words = text.split(' ', '\t').filter { it.isNotEmpty() }
        val option = words.firstOrNull()
        when {
            words.size == 2 && option == RESOLV_RETRY && words[1] == RESOLV_RETRY_INFINITE -> {
                kept += Line(line.text.replace(text, RESOLV_RETRY_SECONDS), line.ending)
                continue
            }
            option == DATA_CIPHERS -> hasDataCiphers = true
            option == DATA_CIPHERS_FALLBACK -> hasFallback = true
            option == CIPHER -> cipherIndex = kept.size
        }
        kept += line
    }

    if (!hasDataCiphers) {
        val added = buildList {
            add(DATA_CIPHERS_LINE)
            if (!hasFallback) add(DATA_CIPHERS_FALLBACK_LINE)
        }
        val at = if (cipherIndex >= 0) cipherIndex + 1 else firstBlockIndex(kept)
        if (at > 0 && kept[at - 1].ending.isEmpty()) kept[at - 1] = Line(kept[at - 1].text, newline)
        kept.addAll(at, added.map { Line(it, newline) })
    }
    return kept.joinToString("") { it.text + it.ending }
}

private class Line(val text: String, val ending: String)

/** Lines with their own terminator ("\r\n", "\n" or none for a last line without one). */
private fun splitKeepingEndings(raw: String): List<Line> {
    val out = ArrayList<Line>()
    var start = 0
    while (start < raw.length) {
        val nl = raw.indexOf('\n', start)
        if (nl < 0) {
            out += Line(raw.substring(start), "")
            break
        }
        val textEnd = if (nl > start && raw[nl - 1] == '\r') nl - 1 else nl
        out += Line(raw.substring(start, textEnd), raw.substring(textEnd, nl + 1))
        start = nl + 1
    }
    return out
}

/** The tag of an inline block such as `<ca>`, or null when [text] does not open one. */
private fun openingTag(text: String): String? {
    if (!text.startsWith("<") || text.startsWith("</")) return null
    val end = text.indexOf('>')
    if (end <= 1) return null
    return text.substring(1, end)
}

/** Where options end: before the first inline block, or the end of the file. */
private fun firstBlockIndex(lines: List<Line>): Int {
    val i = lines.indexOfFirst { openingTag(it.text.trim()) != null }
    return if (i < 0) lines.size else i
}
