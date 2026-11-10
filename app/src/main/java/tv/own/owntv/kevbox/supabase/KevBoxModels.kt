// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.supabase

/** One row of the member_playlist table: the IPTV line the admin set up for this member. */
data class PlaylistRow(
    val clientNumber: String,
    val name: String,
    val serverUrl: String,
    val username: String,
    val password: String,
    /** The HTTP proxy kevbox.dev set for this customer; [PlaylistProxy.OFF] when none. */
    val proxy: PlaylistProxy = PlaylistProxy.OFF,
    /** The VPN kevbox.dev set for this customer; [PlaylistVpn.OFF] when none. */
    val vpn: PlaylistVpn = PlaylistVpn.OFF,
)

/** The [PlaylistVpn.server] value that lets the TV pick the server itself. */
const val VPN_AUTO = "auto"

/**
 * The VPN kevbox.dev set on this row (migration 0023): [OFF] unless it is enabled with a server,
 * username and password that are not blank. [server] is a host from table vpn_server or [VPN_AUTO];
 * with [VPN_AUTO], [backupServer] is null. [changedAt] is vpn_changed_at as the server sent it: the
 * TV only compares it with the last one it saw and never parses it.
 */
data class PlaylistVpn(
    val enabled: Boolean,
    val server: String,
    val backupServer: String?,
    val username: String,
    val password: String,
    val changedAt: String?,
) {
    // The login stays out of any text this row turns into, so a stray log line cannot carry it.
    override fun toString(): String =
        "PlaylistVpn(enabled=$enabled, server=$server, backupServer=$backupServer, changedAt=$changedAt)"

    companion object {
        val OFF = PlaylistVpn(enabled = false, server = "", backupServer = null, username = "", password = "", changedAt = null)
    }
}

/** One row of the vpn_server table: a Premiumize host, its label, its `.ovpn` text and its backup. */
data class VpnServer(
    val host: String,
    val label: String,
    val config: String,
    val backupHost: String?,
    val sortOrder: Int,
)

/**
 * A customer's HTTP proxy as kevbox.dev stores it beside the playlist (migration 0014). The TV applies
 * the visible playlist's proxy to Core's global proxy at each playlist check (ManagedProxy).
 */
data class PlaylistProxy(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
) {
    companion object {
        val OFF = PlaylistProxy(enabled = false, host = "", port = 0, username = "", password = "")
    }
}

/** What get_access_verdict says about the signed-in member. */
enum class AccessVerdict { ALLOWED, NO_ROW, LOCKED }

sealed interface SignInResult {
    data class Success(val email: String) : SignInResult
    data object WrongCredentials : SignInResult
    data object NoNetwork : SignInResult
    data object Failed : SignInResult
}

/** The outcome of an authenticated read. A failed call is never reported as data. */
sealed interface Fetch<out T> {
    data class Ok<T>(val value: T) : Fetch<T>

    /** Network, timeout, 5xx, malformed body or RPC error. */
    data object Failed : Fetch<Nothing>

    /** The server refused the refresh token. */
    data object SessionRejected : Fetch<Nothing>

    /** No stored session. */
    data object SignedOut : Fetch<Nothing>
}
