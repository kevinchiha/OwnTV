// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.supabase

/** One row of the member_playlist view: the IPTV line the admin set up for this member. */
data class PlaylistRow(
    val clientNumber: String,
    val name: String,
    val serverUrl: String,
    val username: String,
    val password: String,
)

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
