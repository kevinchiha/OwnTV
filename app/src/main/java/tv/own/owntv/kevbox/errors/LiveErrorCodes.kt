// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.errors

/**
 * The codes a TV may send in an error report. The list is written out here, not read from
 * `tools/kevbox/live-error-codes.json` at run time; that file is the cross-repo copy the kevbox
 * admin reads, and a unit test keeps the two equal.
 */
object LiveErrorCodes {
    const val ACCOUNT_BANNED = "account_banned"
    const val ACCOUNT_DISABLED = "account_disabled"
    const val ACCOUNT_EXPIRED = "account_expired"
    const val ACCOUNT_WRONG_LOGIN = "account_wrong_login"
    const val AUDIO_FAILED = "audio_failed"
    const val BEHIND_LIVE_WINDOW = "behind_live_window"
    const val DECODER_FAILED = "decoder_failed"
    const val DRM_FAILED = "drm_failed"
    const val FORMAT_UNRECOGNISED = "format_unrecognised"
    const val GUIDE_DOWNLOAD_FAILED = "guide_download_failed"
    const val GUIDE_EMPTY = "guide_empty"
    const val GUIDE_PARSE_FAILED = "guide_parse_failed"
    const val GUIDE_UNREACHABLE = "guide_unreachable"
    const val MPV_LOAD_FAILED = "mpv_load_failed"
    const val NET_DNS = "net_dns"
    const val NET_OFFLINE = "net_offline"
    const val NET_REFUSED = "net_refused"
    const val NET_SSL = "net_ssl"
    const val NET_TIMEOUT = "net_timeout"
    const val OTHER = "other"
    const val PARSE_FAILED = "parse_failed"
    const val PLAYLIST_DOWNLOAD_FAILED = "playlist_download_failed"
    const val PLAYLIST_M3U_DISABLED = "playlist_m3u_disabled"
    const val PLAYLIST_MAX_CONNECTIONS = "playlist_max_connections"
    const val PLAYLIST_PARSE_FAILED = "playlist_parse_failed"
    const val PLAYLIST_RATE_LIMITED = "playlist_rate_limited"
    const val PLAYLIST_UNREACHABLE = "playlist_unreachable"
    const val STREAM_LOST = "stream_lost"

    /** Exactly the JSON file's `fixed` list. */
    val FIXED: Set<String> = setOf(
        ACCOUNT_BANNED, ACCOUNT_DISABLED, ACCOUNT_EXPIRED, ACCOUNT_WRONG_LOGIN,
        AUDIO_FAILED, BEHIND_LIVE_WINDOW, DECODER_FAILED, DRM_FAILED, FORMAT_UNRECOGNISED,
        GUIDE_DOWNLOAD_FAILED, GUIDE_EMPTY, GUIDE_PARSE_FAILED, GUIDE_UNREACHABLE,
        MPV_LOAD_FAILED, NET_DNS, NET_OFFLINE, NET_REFUSED, NET_SSL, NET_TIMEOUT, OTHER,
        PARSE_FAILED, PLAYLIST_DOWNLOAD_FAILED, PLAYLIST_M3U_DISABLED, PLAYLIST_MAX_CONNECTIONS,
        PLAYLIST_PARSE_FAILED, PLAYLIST_RATE_LIMITED, PLAYLIST_UNREACHABLE, STREAM_LOST,
    )

    private const val HTTP_PREFIX = "http_"

    /** The JSON file's `httpPattern`. */
    private val HTTP_PATTERN = Regex("^http_[1-9][0-9]{2}$")

    /** The code for an HTTP status: 403 gives `http_403`. */
    fun http(status: Int): String = HTTP_PREFIX + status

    /** True for a fixed code or an `http_<n>` code with a three-digit status. */
    fun isKnown(code: String): Boolean = code in FIXED || HTTP_PATTERN.matches(code)
}
