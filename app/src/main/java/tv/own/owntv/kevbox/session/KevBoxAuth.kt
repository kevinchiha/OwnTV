// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.session

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import tv.own.owntv.kevbox.supabase.AccessVerdict
import tv.own.owntv.kevbox.supabase.ApiResponse
import tv.own.owntv.kevbox.supabase.Fetch
import tv.own.owntv.kevbox.supabase.PlaylistRow
import tv.own.owntv.kevbox.supabase.SignInResult
import tv.own.owntv.kevbox.supabase.SupabaseApi

/**
 * The member's Supabase session on this TV: sign-in, silent refresh, the two reads and sign-out.
 *
 * Built over a store that already holds a session, it starts signed in without a call, so a
 * restart or reboot needs no password. A refused refresh is reported as SessionRejected and the
 * store is left alone: the caller's sign-out path clears it in one place. Nothing here logs.
 */
class KevBoxAuth(
    private val api: SupabaseApi,
    private val store: SessionStore,
    private val now: () -> Long,
) {
    private val _email = MutableStateFlow(store.load()?.email)

    /** The signed-in member's email; null when signed out. */
    val email: StateFlow<String?> = _email.asStateFlow()

    // Refresh tokens rotate: two refreshes racing on the same token would burn it.
    private val refreshLock = Mutex()

    suspend fun signIn(email: String, password: String): SignInResult {
        val response = api.passwordGrant(email, password)
        if (response is ApiResponse.NoNetwork) return SignInResult.NoNetwork
        val http = response as ApiResponse.Http
        return when {
            http.code == HTTP_OK -> {
                val session = parseSession(http.body, previous = null) ?: return SignInResult.Failed
                store.save(session)
                _email.value = session.email
                SignInResult.Success(session.email)
            }
            http.code == HTTP_BAD_REQUEST && isWrongCredentials(http.body) ->
                SignInResult.WrongCredentials
            else -> SignInResult.Failed
        }
    }

    suspend fun playlists(): Fetch<List<PlaylistRow>> =
        when (val result = authorized { api.selectPlaylists(it) }) {
            is Fetch.Ok -> parsePlaylists(result.value)?.let { Fetch.Ok(it) } ?: Fetch.Failed
            Fetch.Failed -> Fetch.Failed
            Fetch.SessionRejected -> Fetch.SessionRejected
            Fetch.SignedOut -> Fetch.SignedOut
        }

    suspend fun verdict(): Fetch<AccessVerdict> =
        when (val result = authorized { api.accessVerdict(it) }) {
            is Fetch.Ok -> parseVerdict(result.value)?.let { Fetch.Ok(it) } ?: Fetch.Failed
            Fetch.Failed -> Fetch.Failed
            Fetch.SessionRejected -> Fetch.SessionRejected
            Fetch.SignedOut -> Fetch.SignedOut
        }

    /** Best-effort server logout of this TV only, then clear the store. Works offline. */
    suspend fun signOut() {
        val stored = store.load()
        if (stored != null) {
            try {
                val session = (freshSession() as? Fetch.Ok)?.value ?: stored
                api.logout(session.accessToken)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The outcome is ignored on purpose: sign-out must not depend on the server.
            }
        }
        store.clear()
        _email.value = null
    }

    /** Runs [call] with a fresh access token; one refresh and one retry on a 401. */
    private suspend fun authorized(call: suspend (String) -> ApiResponse): Fetch<ApiResponse.Http> {
        val session = when (val fresh = freshSession()) {
            is Fetch.Ok -> fresh.value
            Fetch.Failed -> return Fetch.Failed
            Fetch.SessionRejected -> return Fetch.SessionRejected
            Fetch.SignedOut -> return Fetch.SignedOut
        }
        var response = call(session.accessToken)
        if (response is ApiResponse.Http && response.code == HTTP_UNAUTHORIZED) {
            val renewed = when (val refreshed = refresh(session)) {
                is Fetch.Ok -> refreshed.value
                Fetch.Failed -> return Fetch.Failed
                Fetch.SessionRejected -> return Fetch.SessionRejected
                Fetch.SignedOut -> return Fetch.SignedOut
            }
            response = call(renewed.accessToken)
        }
        return when (response) {
            ApiResponse.NoNetwork -> Fetch.Failed
            is ApiResponse.Http -> Fetch.Ok(response)
        }
    }

    /** The stored session, refreshed first when its access token expires within a minute. */
    private suspend fun freshSession(): Fetch<StoredSession> {
        val session = store.load() ?: return Fetch.SignedOut
        if (session.expiresAtMs - now() > REFRESH_MARGIN_MS) return Fetch.Ok(session)
        return refresh(session)
    }

    private suspend fun refresh(stale: StoredSession): Fetch<StoredSession> = refreshLock.withLock {
        val current = store.load() ?: return@withLock Fetch.SignedOut
        // Another caller already rotated the pair while this one waited: use the newer one.
        if (current.refreshToken != stale.refreshToken &&
            current.expiresAtMs - now() > REFRESH_MARGIN_MS
        ) {
            return@withLock Fetch.Ok(current)
        }
        val response = api.refreshGrant(current.refreshToken)
        if (response !is ApiResponse.Http) return@withLock Fetch.Failed
        when (response.code) {
            HTTP_OK -> {
                val renewed = parseSession(response.body, previous = current)
                    ?: return@withLock Fetch.Failed
                store.save(renewed)
                _email.value = renewed.email
                Fetch.Ok(renewed)
            }
            // Any other 400/401 is a gateway refusing the apikey, not the session: keep it, since
            // wiping sources over a key problem would destroy favourites for nothing.
            HTTP_BAD_REQUEST, HTTP_UNAUTHORIZED ->
                if (isRefreshRejected(response.body)) Fetch.SessionRejected else Fetch.Failed
            else -> Fetch.Failed
        }
    }

    /** A token response, or null when a field is missing. A refresh may omit the user. */
    private fun parseSession(body: String, previous: StoredSession?): StoredSession? {
        val json = jsonObject(body) ?: return null
        val accessToken = text(json, ACCESS_TOKEN) ?: return null
        val refreshToken = text(json, REFRESH_TOKEN) ?: return null
        val expiresIn = json.optLong(EXPIRES_IN, -1L).takeIf { it > 0 } ?: return null
        val user = json.optJSONObject(USER)
        val userId = user?.let { text(it, ID) } ?: previous?.userId ?: return null
        val email = user?.let { text(it, EMAIL) } ?: previous?.email ?: return null
        return StoredSession(
            userId = userId,
            email = email,
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtMs = now() + expiresIn * MS_PER_SECOND,
        )
    }

    private fun isWrongCredentials(body: String): Boolean {
        val json = jsonObject(body) ?: return false
        return json.optString(ERROR) == INVALID_GRANT ||
            json.optString(ERROR_CODE) == INVALID_CREDENTIALS
    }

    private fun isRefreshRejected(body: String): Boolean {
        val json = jsonObject(body) ?: return false
        return json.optString(ERROR) == INVALID_GRANT ||
            json.optString(ERROR_CODE) in REJECTED_REFRESH_CODES
    }

    private fun parsePlaylists(response: ApiResponse.Http): List<PlaylistRow>? {
        if (response.code != HTTP_OK) return null
        val array = try {
            JSONArray(response.body)
        } catch (_: JSONException) {
            return null
        }
        return (0 until array.length()).map { index ->
            val row = array.opt(index) as? JSONObject ?: return null
            PlaylistRow(
                clientNumber = row.opt(CLIENT_NUMBER) as? String ?: return null,
                name = row.opt(NAME) as? String ?: return null,
                serverUrl = row.opt(SERVER_URL) as? String ?: return null,
                username = row.opt(USERNAME) as? String ?: return null,
                password = row.opt(PASSWORD) as? String ?: return null,
            )
        }
    }

    private fun parseVerdict(response: ApiResponse.Http): AccessVerdict? {
        if (response.code != HTTP_OK) return null
        val body = response.body.trim()
        // org.json reads bare words as strings too; only a quoted JSON string counts.
        if (!body.startsWith('"')) return null
        val value = try {
            JSONTokener(body).nextValue() as? String
        } catch (_: JSONException) {
            null
        } ?: return null
        return AccessVerdict.entries.firstOrNull { it.name == value }
    }

    /** A non-empty string field, or null. optString would turn JSON null into "null". */
    private fun text(json: JSONObject, key: String): String? =
        (json.opt(key) as? String)?.takeIf { it.isNotEmpty() }

    private fun jsonObject(body: String): JSONObject? = try {
        JSONObject(body)
    } catch (_: JSONException) {
        null
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val REFRESH_MARGIN_MS = 60_000L
        const val MS_PER_SECOND = 1_000L

        const val ACCESS_TOKEN = "access_token"
        const val REFRESH_TOKEN = "refresh_token"
        const val EXPIRES_IN = "expires_in"
        const val USER = "user"
        const val ID = "id"
        const val EMAIL = "email"
        const val ERROR = "error"
        const val ERROR_CODE = "error_code"
        const val INVALID_GRANT = "invalid_grant"
        const val INVALID_CREDENTIALS = "invalid_credentials"
        val REJECTED_REFRESH_CODES = setOf(
            "refresh_token_not_found",
            "refresh_token_already_used",
            "session_not_found",
            "user_not_found",
        )

        const val CLIENT_NUMBER = "client_number"
        const val NAME = "name"
        const val SERVER_URL = "server_url"
        const val USERNAME = "username"
        const val PASSWORD = "password"
    }
}
