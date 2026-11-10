// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.session

import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import tv.own.owntv.kevbox.supabase.AccessVerdict
import tv.own.owntv.kevbox.supabase.ApiResponse
import tv.own.owntv.kevbox.supabase.Fetch
import tv.own.owntv.kevbox.supabase.PlaylistProxy
import tv.own.owntv.kevbox.supabase.PlaylistRow
import tv.own.owntv.kevbox.supabase.PlaylistVpn
import tv.own.owntv.kevbox.supabase.SignInResult
import tv.own.owntv.kevbox.supabase.SupabaseApi
import tv.own.owntv.kevbox.supabase.VpnServer

/**
 * The member's Supabase session on this TV: sign-in, silent refresh, the reads and sign-out.
 *
 * Built over a store that already holds a session, it starts signed in without a call, so a
 * restart or reboot needs no password. A refused refresh is reported as SessionRejected and the
 * store is left alone: the caller's sign-out path clears it in one place. Nothing here logs.
 *
 * The starting member comes from [initial], which the app reads off the main thread (see
 * [SessionPreload]). Until it ends, [loaded] is false and [email] and [userId] are null without
 * meaning "signed out": a reader that would act on "signed out" waits with [awaitLoaded] first.
 */
class KevBoxAuth(
    private val api: SupabaseApi,
    private val store: SessionStore,
    initial: SessionPreload = SessionPreload.now(store),
    private val now: () -> Long,
) {
    private val _email = MutableStateFlow<String?>(null)
    private val _userId = MutableStateFlow<String?>(null)
    private val _sessionVersion = MutableStateFlow(0L)
    private val _loaded = MutableStateFlow(false)

    // Guards the member fields against the start-up read ending late: a sign-in, refresh or
    // sign-out that already set them wins over what the read found before it.
    private val memberLock = Any()
    private var memberSet = false

    init {
        // One read: two could disagree (a decrypt failing the second time) and split the signed-in state.
        initial.whenDone { session ->
            synchronized(memberLock) {
                if (!memberSet) {
                    _email.value = session?.email
                    _userId.value = session?.userId
                }
                _loaded.value = true
            }
        }
    }

    /** False until the stored session has been read; [email] and [userId] mean nothing before. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Returns once the stored session has been read. */
    suspend fun awaitLoaded() {
        loaded.first { it }
    }

    /** The signed-in member's email; null when signed out. */
    val email: StateFlow<String?> = _email.asStateFlow()

    /** The signed-in member's Supabase user id; null when signed out. Moves with [email]. */
    val userId: StateFlow<String?> = _userId.asStateFlow()

    /**
     * Goes up by one each time the stored session changes: sign-in, a refresh that rotates the
     * tokens, sign-out. The email and user id stay the same across a refresh, so this is the only
     * signal that the tokens the error scrubber must hide have changed.
     */
    val sessionVersion: StateFlow<Long> = _sessionVersion.asStateFlow()

    // Every store write takes this lock. Refresh tokens rotate, so two refreshes racing on the same
    // token would burn it; and a refresh still waiting on the server must not write its session
    // back over a sign-out or over the next member's sign-in.
    private val refreshLock = Mutex()

    /**
     * Never throws: a Keystore that fails to seal the session (common on cheap TV firmware) reports
     * Failed. The email goes out trimmed and lowercased, since TV keyboards add spaces and capitals;
     * the password goes out exactly as typed.
     */
    suspend fun signIn(email: String, password: String): SignInResult = try {
        passwordSignIn(email.trim().lowercase(), password)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        SignInResult.Failed
    }

    private suspend fun passwordSignIn(email: String, password: String): SignInResult {
        val response = api.passwordGrant(email, password)
        if (response is ApiResponse.NoNetwork) return SignInResult.NoNetwork
        val http = response as ApiResponse.Http
        return when {
            http.code == HTTP_OK -> {
                val session = parseSession(http.body, previous = null) ?: return SignInResult.Failed
                val saved = refreshLock.withLock {
                    trySave(session).also {
                        if (it) {
                            setMember(session.email, session.userId)
                            _sessionVersion.value++
                        }
                    }
                }
                if (saved) SignInResult.Success(session.email) else SignInResult.Failed
            }
            http.code == HTTP_BAD_REQUEST && isWrongCredentials(http.body) ->
                SignInResult.WrongCredentials
            else -> SignInResult.Failed
        }
    }

    suspend fun playlists(): Fetch<List<PlaylistRow>> =
        when (val result = signedInCall { api.selectPlaylists(it) }) {
            is Fetch.Ok -> parsePlaylists(result.value)?.let { Fetch.Ok(it) } ?: Fetch.Failed
            Fetch.Failed -> Fetch.Failed
            Fetch.SessionRejected -> Fetch.SessionRejected
            Fetch.SignedOut -> Fetch.SignedOut
        }

    /**
     * The VPN servers kevbox.dev offers, in sort order: only [hosts] when given, every row when null.
     * A row with a blank host or config is skipped; a body that is not an array of rows is Failed.
     */
    suspend fun vpnServers(hosts: List<String>?): Fetch<List<VpnServer>> =
        when (val result = signedInCall { api.selectVpnServers(it, hosts) }) {
            is Fetch.Ok -> parseVpnServers(result.value)?.let { Fetch.Ok(it) } ?: Fetch.Failed
            Fetch.Failed -> Fetch.Failed
            Fetch.SessionRejected -> Fetch.SessionRejected
            Fetch.SignedOut -> Fetch.SignedOut
        }

    suspend fun verdict(): Fetch<AccessVerdict> =
        when (val result = signedInCall { api.accessVerdict(it) }) {
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
        // Under the lock: a refresh in flight finishes first, then this clear wins.
        refreshLock.withLock {
            store.clear()
            setMember(null, null)
            _sessionVersion.value++
        }
    }

    /**
     * Runs [call] with a fresh access token; one refresh and one retry on a 401. NoNetwork reads as
     * Failed. The error reporter and the usage check-in send through this too.
     */
    suspend fun signedInCall(call: suspend (accessToken: String) -> ApiResponse): Fetch<ApiResponse.Http> {
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

    /**
     * A fresh access token, refreshed first when it expires within a minute. For the Realtime socket,
     * which outlives any one [signedInCall]. The caller must never log it.
     */
    suspend fun accessToken(): Fetch<String> = when (val fresh = freshSession()) {
        is Fetch.Ok -> Fetch.Ok(fresh.value.accessToken)
        Fetch.Failed -> Fetch.Failed
        Fetch.SessionRejected -> Fetch.SessionRejected
        Fetch.SignedOut -> Fetch.SignedOut
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
                // A Keystore failure here reads as a failed fetch, never a crash.
                if (!trySave(renewed)) return@withLock Fetch.Failed
                setMember(renewed.email, renewed.userId)
                _sessionVersion.value++
                Fetch.Ok(renewed)
            }
            // Any other 400/401 is a gateway refusing the apikey, not the session: keep it, since
            // wiping sources over a key problem would destroy favourites for nothing.
            HTTP_BAD_REQUEST, HTTP_UNAUTHORIZED ->
                if (isRefreshRejected(response.body)) Fetch.SessionRejected else Fetch.Failed
            else -> Fetch.Failed
        }
    }

    private fun setMember(email: String?, userId: String?) = synchronized(memberLock) {
        memberSet = true
        _email.value = email
        _userId.value = userId
    }

    /**
     * Saves [session]; false when the store throws. Keystore failures arrive as ProviderException,
     * KeyStoreException, InvalidKeyException and more, so any exception counts. The store seals the
     * blob before it writes, so a failed save leaves the previous contents in place.
     */
    private fun trySave(session: StoredSession): Boolean = try {
        store.save(session)
        true
    } catch (_: Exception) {
        false
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
                proxy = parseProxy(row),
                vpn = parseVpn(row),
            )
        }
    }

    /**
     * The row's proxy columns. A server without them (before migration 0014), a null column or a
     * proxy switched off all read as [PlaylistProxy.OFF]; so does one without a usable address.
     */
    private fun parseProxy(row: JSONObject): PlaylistProxy {
        if (row.opt(PROXY_ENABLED) != true) return PlaylistProxy.OFF
        val host = (row.opt(PROXY_HOST) as? String)?.trim().orEmpty()
        val port = (row.opt(PROXY_PORT) as? Number)?.toInt() ?: 0
        if (host.isEmpty() || port !in 1..65535) return PlaylistProxy.OFF
        return PlaylistProxy(
            enabled = true,
            host = host,
            port = port,
            username = (row.opt(PROXY_USERNAME) as? String)?.trim().orEmpty(),
            password = (row.opt(PROXY_PASSWORD) as? String).orEmpty(),
        )
    }

    /**
     * The row's VPN columns. A server without them (before migration 0023), a null column, a VPN
     * switched off, or one missing its server, username or password all read as [PlaylistVpn.OFF].
     * The password is kept exactly as stored, like the proxy password. Hosts are lowercased, as
     * host names compare and as the server read's filter accepts them.
     */
    private fun parseVpn(row: JSONObject): PlaylistVpn {
        if (row.opt(VPN_ENABLED) != true) return PlaylistVpn.OFF
        val server = (row.opt(VPN_SERVER) as? String)?.trim().orEmpty().lowercase(Locale.ROOT)
        val username = (row.opt(VPN_USERNAME) as? String)?.trim().orEmpty()
        val password = (row.opt(VPN_PASSWORD) as? String).orEmpty()
        if (server.isEmpty() || username.isEmpty() || password.isBlank()) return PlaylistVpn.OFF
        return PlaylistVpn(
            enabled = true,
            server = server,
            backupServer = (row.opt(VPN_BACKUP_SERVER) as? String)?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() },
            username = username,
            password = password,
            changedAt = row.opt(VPN_CHANGED_AT) as? String,
        )
    }

    private fun parseVpnServers(response: ApiResponse.Http): List<VpnServer>? {
        if (response.code != HTTP_OK) return null
        val array = try {
            JSONArray(response.body)
        } catch (_: JSONException) {
            return null
        }
        return (0 until array.length()).mapNotNull { index ->
            val row = array.opt(index) as? JSONObject ?: return null
            val host = (row.opt(HOST) as? String)?.trim().orEmpty().lowercase(Locale.ROOT)
            val config = (row.opt(CONFIG) as? String).orEmpty()
            if (host.isEmpty() || config.isBlank()) return@mapNotNull null
            VpnServer(
                host = host,
                label = (row.opt(LABEL) as? String).orEmpty(),
                config = config,
                backupHost = (row.opt(BACKUP_HOST) as? String)?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() },
                sortOrder = (row.opt(SORT_ORDER) as? Number)?.toInt() ?: 0,
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
        const val PROXY_ENABLED = "proxy_enabled"
        const val PROXY_HOST = "proxy_host"
        const val PROXY_PORT = "proxy_port"
        const val PROXY_USERNAME = "proxy_username"
        const val PROXY_PASSWORD = "proxy_password"
        const val VPN_ENABLED = "vpn_enabled"
        const val VPN_SERVER = "vpn_server"
        const val VPN_BACKUP_SERVER = "vpn_backup_server"
        const val VPN_USERNAME = "vpn_username"
        const val VPN_PASSWORD = "vpn_password"
        const val VPN_CHANGED_AT = "vpn_changed_at"
        const val HOST = "host"
        const val LABEL = "label"
        const val CONFIG = "config"
        const val BACKUP_HOST = "backup_host"
        const val SORT_ORDER = "sort_order"
    }
}
