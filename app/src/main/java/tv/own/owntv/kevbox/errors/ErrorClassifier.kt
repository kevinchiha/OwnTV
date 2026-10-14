// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.errors

import tv.own.owntv.player.PlayerErrors
import tv.own.owntv.player.PlayerFailureReason

/** The player that failed. [wire] is the value sent in a report. */
enum class Engine(val wire: String) { EXO("exo"), MPV("mpv") }

/**
 * One failure turned into a report code. [httpStatus] is the status the failure text named, if
 * any; [errorName] is the engine's own name for the failure (a Media3 constant or the first line
 * of the text), kept unscrubbed here; the reporter scrubs every field before it leaves the TV.
 */
data class Classified(val code: String, val httpStatus: Int?, val errorName: String?)

/** What an Xtream account check learned. [status] is one of [AccountStatus]. */
data class AccountResult(val status: String, val activeCons: Int?, val maxConnections: Int?)

object AccountStatus {
    const val ACTIVE = "active"; const val EXPIRED = "expired"; const val BANNED = "banned"
    const val DISABLED = "disabled"; const val WRONG_LOGIN = "wrong_login"; const val UNREACHABLE = "unreachable"
}

/**
 * Maps Core's failure text to a [LiveErrorCodes] code. Pure: no network, disk or clock.
 *
 * Words are matched on the lower-cased text, so Media3's upper-case constant names
 * (`ERROR_CODE_...`) and the engines' lower-case messages both match.
 */
object ErrorClassifier {
    private const val ERROR_NAME_MAX = 120

    /** A Media3 `PlaybackException` constant name at the start of the text. */
    private val MEDIA3_NAME = Regex("^ERROR_CODE_[A-Z0-9_]+")

    private val TIMEOUT_WORDS = listOf("error_code_io_network_connection_timeout", "timed out", "timeout")
    private val DNS_WORDS = listOf(
        "unable to resolve host", "unknownhostexception", "failed to resolve hostname", "no address associated",
    )
    private val SSL_WORDS = listOf("ssl", "certificate", "cert_", "handshake")
    private val REFUSED_WORDS = listOf(
        "connection refused", "connection reset", "connectexception", "error_code_io_network_connection_failed",
    )
    private val NETWORK_WORDS = TIMEOUT_WORDS + DNS_WORDS + SSL_WORDS + REFUSED_WORDS

    private val BEHIND_WORDS = listOf("error_code_behind_live_window", "behindlivewindow")
    private val FORMAT_WORDS = listOf(
        "error_code_parsing_container_unsupported", "unrecognizedinputformat",
        "none of the available extractors", "failed to recognize file format",
    )
    private val PARSE_WORDS = listOf("error_code_parsing_", "parserexception", "malformed")
    private val DECODER_WORDS = listOf("error_code_decoder_", "error_code_decoding_", "decoder", "mediacodec")
    private val AUDIO_WORDS = listOf("error_code_audio_track_", "audiosink", "audiotrack")
    private val DRM_WORDS = listOf("error_code_drm_", "drm")

    private val DECODER_REASONS = setOf(
        PlayerFailureReason.DECODER_BUSY,
        PlayerFailureReason.DECODER_TRANSIENT,
        PlayerFailureReason.DECODER_MEMORY,
        PlayerFailureReason.UNSUPPORTED_VIDEO,
    )

    private val PLAYLIST_PARSE_WORDS = listOf("begin_object", "begin_array", "expected a list", "malformed", "parse", "json")
    private val GUIDE_PARSE_WORDS = listOf("xml", "parse", "malformed", "unexpected")
    private val GUIDE_EMPTY_WORDS = listOf("empty", "no programmes")

    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val HTTP_M3U_DISABLED = 884

    fun playback(engine: Engine, raw: String?, reason: PlayerFailureReason?, online: Boolean): Classified {
        val name = errorName(raw)
        val status = statusIn(raw)
        if (status != null) return Classified(LiveErrorCodes.http(status), status, name)
        val l = raw?.lowercase().orEmpty()
        val code = when {
            !online -> LiveErrorCodes.NET_OFFLINE
            isGiveUp(l) -> LiveErrorCodes.STREAM_LOST
            l.hasAny(BEHIND_WORDS) -> LiveErrorCodes.BEHIND_LIVE_WINDOW
            l.hasAny(TIMEOUT_WORDS) -> LiveErrorCodes.NET_TIMEOUT
            l.hasAny(DNS_WORDS) -> LiveErrorCodes.NET_DNS
            l.hasAny(SSL_WORDS) || reason == PlayerFailureReason.SSL -> LiveErrorCodes.NET_SSL
            l.hasAny(REFUSED_WORDS) || reason == PlayerFailureReason.NETWORK -> LiveErrorCodes.NET_REFUSED
            l.hasAny(FORMAT_WORDS) || reason == PlayerFailureReason.FORMAT -> LiveErrorCodes.FORMAT_UNRECOGNISED
            l.hasAny(PARSE_WORDS) -> LiveErrorCodes.PARSE_FAILED
            l.hasAny(DECODER_WORDS) || reason in DECODER_REASONS -> LiveErrorCodes.DECODER_FAILED
            l.hasAny(AUDIO_WORDS) || reason == PlayerFailureReason.AUDIO -> LiveErrorCodes.AUDIO_FAILED
            l.hasAny(DRM_WORDS) || reason == PlayerFailureReason.DRM -> LiveErrorCodes.DRM_FAILED
            engine == Engine.MPV -> LiveErrorCodes.MPV_LOAD_FAILED
            else -> LiveErrorCodes.OTHER
        }
        return Classified(code, null, name)
    }

    fun playlist(message: String?, online: Boolean): Classified {
        val status = statusIn(message)
        val l = message?.lowercase().orEmpty()
        val code = when {
            status == HTTP_TOO_MANY_REQUESTS -> LiveErrorCodes.PLAYLIST_RATE_LIMITED
            status == HTTP_M3U_DISABLED -> LiveErrorCodes.PLAYLIST_M3U_DISABLED
            !online || l.hasAny(NETWORK_WORDS) -> LiveErrorCodes.PLAYLIST_UNREACHABLE
            l.hasAny(PLAYLIST_PARSE_WORDS) -> LiveErrorCodes.PLAYLIST_PARSE_FAILED
            else -> LiveErrorCodes.PLAYLIST_DOWNLOAD_FAILED
        }
        return Classified(code, status, firstLine(message))
    }

    fun guide(message: String?, online: Boolean): Classified {
        val status = statusIn(message)
        val l = message?.lowercase().orEmpty()
        val code = when {
            !online || l.hasAny(NETWORK_WORDS) -> LiveErrorCodes.GUIDE_UNREACHABLE
            l.hasAny(GUIDE_PARSE_WORDS) -> LiveErrorCodes.GUIDE_PARSE_FAILED
            l.hasAny(GUIDE_EMPTY_WORDS) -> LiveErrorCodes.GUIDE_EMPTY
            else -> LiveErrorCodes.GUIDE_DOWNLOAD_FAILED
        }
        return Classified(code, status, firstLine(message))
    }

    /**
     * Applies an account check result (AC-13); [playlist] enables playlist_max_connections.
     *
     * `unreachable` keeps the original code on purpose (a stated deviation from AC-13): the check
     * learned nothing about the account, and `account_unreachable` would hide a real 403 behind
     * the panel's API being down.
     */
    fun withAccount(c: Classified, account: AccountResult?, playlist: Boolean): Classified {
        val replaced = when (account?.status) {
            AccountStatus.EXPIRED -> LiveErrorCodes.ACCOUNT_EXPIRED
            AccountStatus.BANNED -> LiveErrorCodes.ACCOUNT_BANNED
            AccountStatus.DISABLED -> LiveErrorCodes.ACCOUNT_DISABLED
            AccountStatus.WRONG_LOGIN -> LiveErrorCodes.ACCOUNT_WRONG_LOGIN
            AccountStatus.ACTIVE -> if (playlist && atConnectionLimit(account)) LiveErrorCodes.PLAYLIST_MAX_CONNECTIONS else null
            else -> null
        }
        return if (replaced == null) c else c.copy(code = replaced)
    }

    private fun atConnectionLimit(a: AccountResult): Boolean {
        val max = a.maxConnections ?: return false
        val active = a.activeCons ?: return false
        return max > 0 && active >= max
    }

    /** Core's give-up after a playing live channel stopped and every reconnect failed. */
    private fun isGiveUp(l: String): Boolean =
        ("gave up after" in l && "reconnects" in l) || "no data for" in l

    /** The status the text names, when it is a three-digit status from 100 to 999. */
    private fun statusIn(text: String?): Int? =
        text?.let(PlayerErrors::httpStatusIn)?.takeIf { it in 100..999 }

    /** A leading Media3 constant name, otherwise the first line (mpv's message included). */
    private fun errorName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val media3 = MEDIA3_NAME.find(raw.trimStart())?.value
        return media3?.take(ERROR_NAME_MAX) ?: firstLine(raw)
    }

    private fun firstLine(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return text.trim().lineSequence().first().trim().take(ERROR_NAME_MAX)
    }

    private fun String.hasAny(words: List<String>): Boolean = words.any { it in this }
}
