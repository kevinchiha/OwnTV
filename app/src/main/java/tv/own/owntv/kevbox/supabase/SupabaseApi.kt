// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.supabase

import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

sealed interface ApiResponse {
    data class Http(val code: Int, val body: String) : ApiResponse
    data object NoNetwork : ApiResponse
}

/**
 * The Supabase calls the TV makes, as plain OkHttp requests. It reports the raw status and
 * body; [tv.own.owntv.kevbox.session.KevBoxAuth] decides what they mean.
 *
 * [http] is the app's shared client. Core turns retryOnConnectionFailure off on it, which breaks
 * every call on TV boxes that resolve IPv6 first over a dead route, so this derives its own client
 * with retries back on.
 *
 * A blank or invalid [baseUrl] never reaches the network: every call answers [UNREACHABLE]. A TLS
 * failure answers the same, not NoNetwork: on a TV it usually means the clock is far off, and
 * "No internet connection" would send the member to check a network that works.
 * Nothing here logs: tokens, passwords, emails and bodies must stay off logcat.
 */
class SupabaseApi(http: OkHttpClient, baseUrl: String, private val anonKey: String) {
    // Always direct: the customer's proxy (ManagedProxy) is for the provider, never for KevBox's own
    // server. A proxy that is down must not stop the TV from reaching kevbox.dev to fix it.
    private val client = http.newBuilder()
        .proxy(java.net.Proxy.NO_PROXY)
        .proxyAuthenticator(okhttp3.Authenticator.NONE)
        .retryOnConnectionFailure(true)
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    // The football listings only add to Find channels and the goal toast's switch, so their read
    // gives up after a few seconds in all (connect, send and read together) instead of 15 s per step.
    private val listingClient = client.newBuilder()
        .callTimeout(LISTING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val root = baseUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() }?.toHttpUrlOrNull()

    suspend fun passwordGrant(email: String, password: String): ApiResponse {
        val body = JSONObject().put("email", email).put("password", password)
        return post(TOKEN_PATH, mapOf(GRANT_TYPE to "password"), body, accessToken = null)
    }

    suspend fun refreshGrant(refreshToken: String): ApiResponse {
        val body = JSONObject().put("refresh_token", refreshToken)
        return post(TOKEN_PATH, mapOf(GRANT_TYPE to "refresh_token"), body, accessToken = null)
    }

    suspend fun selectPlaylists(accessToken: String): ApiResponse {
        // Newest columns first. A server without migration 0023 refuses the VPN columns (PostgREST
        // 400, code 42703): ask again without them, so the VPN reads as off. One without 0014 also
        // refuses the proxy columns: ask a third time with neither, as before 0023 (AC-23).
        val selects = listOf(PLAYLIST_COLUMNS + PROXY_COLUMNS + VPN_COLUMNS, PLAYLIST_COLUMNS + PROXY_COLUMNS, PLAYLIST_COLUMNS)
        var response: ApiResponse = UNREACHABLE
        for (select in selects) {
            response = send(PLAYLIST_PATH, mapOf("select" to select, "order" to "name.asc"), accessToken) { get() }
            if (!isUndefinedColumn(response)) return response
        }
        return response
    }

    /**
     * The VPN servers (vpn_server, migration 0023) in sort order, or only [hosts] when given (null
     * means every row). A host with anything but `[a-z0-9.-]` is dropped before the call, so the
     * filter goes out as written; when none is left, nothing is asked and the answer is no rows,
     * never every row.
     */
    suspend fun selectVpnServers(accessToken: String, hosts: List<String>?): ApiResponse {
        val query = mutableMapOf("select" to VPN_SERVER_COLUMNS, "order" to "sort_order.asc")
        if (hosts != null) {
            val usable = hosts.filter { it.isNotEmpty() && it.all(::isHostChar) }
            if (usable.isEmpty()) return NO_ROWS
            query["host"] = "in.(" + usable.joinToString(",") + ")"
        }
        return send(VPN_SERVER_PATH, query, accessToken) { get() }
    }

    /**
     * One batch of VPN events (record_live_vpn_events), oldest first. The entries are never logged.
     * A server without the function answers 404; the caller keeps the batch.
     */
    suspend fun recordLiveVpnEvents(
        accessToken: String,
        deviceId: String,
        appVersion: String,
        entries: JSONArray,
    ): ApiResponse {
        val body = JSONObject()
            .put(P_DEVICE_ID, deviceId)
            .put(P_APP_VERSION, appVersion)
            .put(P_ENTRIES, entries)
        return post(LIVE_VPN_EVENTS_PATH, emptyMap(), body, accessToken)
    }

    private fun isUndefinedColumn(response: ApiResponse): Boolean =
        response is ApiResponse.Http && response.code == HTTP_BAD_REQUEST && response.body.contains(UNDEFINED_COLUMN)

    private fun isHostChar(c: Char): Boolean = c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-'

    /**
     * The matches LiveOnSat lists (football, basketball, MMA, motor sport) with kickoff between
     * [fromIso] and [toIso], each with its sport and the provider channels kevbox.dev matched to it,
     * earliest first. Both bounds are ISO-8601 UTC instants ending in `Z`, as the query goes out
     * unencoded. The whole call times out after 4 s, which answers NoNetwork like any other timeout.
     */
    suspend fun selectFootballListings(accessToken: String, fromIso: String, toIso: String): ApiResponse {
        // One `and` filter: the query is a map, so two `kickoff` keys cannot both go out.
        val query = mapOf(
            "select" to LISTING_COLUMNS + SPORT_COLUMN,
            "and" to "(kickoff.gte.$fromIso,kickoff.lte.$toIso)",
            "order" to "kickoff.asc",
        )
        val response = send(LISTING_PATH, query, accessToken, listingClient) { get() }
        // A server without migration 0019 refuses the sport column (PostgREST 400, code 42703): ask
        // again without it, so the football listings still arrive and every row reads as soccer.
        if (isUndefinedColumn(response)) {
            return send(LISTING_PATH, query + ("select" to LISTING_COLUMNS), accessToken, listingClient) { get() }
        }
        return response
    }

    suspend fun accessVerdict(accessToken: String): ApiResponse =
        post(VERDICT_PATH, emptyMap(), JSONObject(), accessToken)

    suspend fun logout(accessToken: String): ApiResponse =
        post(LOGOUT_PATH, mapOf("scope" to "local"), body = null, accessToken = accessToken)

    /** One batch of error entries (record_live_errors). The entries are never logged. */
    suspend fun recordLiveErrors(
        accessToken: String,
        deviceId: String,
        appVersion: String,
        entries: JSONArray,
    ): ApiResponse {
        val body = JSONObject()
            .put(P_DEVICE_ID, deviceId)
            .put(P_APP_VERSION, appVersion)
            .put(P_ENTRIES, entries)
        return post(LIVE_ERRORS_PATH, emptyMap(), body, accessToken)
    }

    /** The usage check-in (record_live_checkin): which TV, which app version, which Android. */
    suspend fun recordLiveCheckin(
        accessToken: String,
        deviceId: String,
        appVersion: String,
        model: String,
        android: String,
    ): ApiResponse {
        val body = JSONObject()
            .put(P_DEVICE_ID, deviceId)
            .put(P_APP_VERSION, appVersion)
            .put(P_MODEL, model)
            .put(P_ANDROID, android)
        return post(LIVE_CHECKIN_PATH, emptyMap(), body, accessToken)
    }

    /**
     * One play note (record_live_play): which TV, which app version, when on the TV clock, and
     * whether the customer's proxy was in use. No channel, stream address or account goes out.
     */
    suspend fun recordLivePlay(
        accessToken: String,
        deviceId: String,
        appVersion: String,
        playedAt: String,
        proxy: Boolean,
    ): ApiResponse {
        val body = JSONObject()
            .put(P_DEVICE_ID, deviceId)
            .put(P_APP_VERSION, appVersion)
            .put(P_PLAYED_AT, playedAt)
            .put(P_PROXY, proxy)
        return post(LIVE_PLAY_PATH, emptyMap(), body, accessToken)
    }

    /**
     * One batch of finished stretches (record_live_stretches), with the TV's clock at send time so
     * the server can tell how far off that clock is. The entries are never logged.
     */
    suspend fun recordLiveStretches(
        accessToken: String,
        deviceId: String,
        appVersion: String,
        tvNow: String,
        entries: JSONArray,
    ): ApiResponse {
        val body = JSONObject()
            .put(P_DEVICE_ID, deviceId)
            .put(P_APP_VERSION, appVersion)
            .put(P_TV_NOW, tvNow)
            .put(P_ENTRIES, entries)
        return post(LIVE_STRETCHES_PATH, emptyMap(), body, accessToken)
    }

    private suspend fun post(
        path: String,
        query: Map<String, String>,
        body: JSONObject?,
        accessToken: String?,
    ): ApiResponse {
        val payload = (body?.toString() ?: "").toRequestBody(JSON_TYPE.toMediaType())
        return send(path, query, accessToken) { post(payload) }
    }

    private suspend fun send(
        path: String,
        query: Map<String, String>,
        accessToken: String?,
        via: OkHttpClient = client,
        method: Request.Builder.() -> Request.Builder,
    ): ApiResponse {
        val base = root ?: return UNREACHABLE
        val url = base.newBuilder().addPathSegments(path).apply {
            // Every query value is a constant of this file, the football listings' `and` filter
            // around two ISO-8601 UTC timestamps ending in `Z` (no `+` to encode), or the VPN servers'
            // `in.(...)` filter over hosts of `[a-z0-9.-]` only, so it goes out as written (commas kept).
            query.forEach { (name, value) -> addEncodedQueryParameter(name, value) }
        }.build()
        val request = Request.Builder().url(url)
            .header(APIKEY_HEADER, anonKey)
            .apply { if (accessToken != null) header(AUTH_HEADER, "Bearer $accessToken") }
            .method()
            .build()
        val call = via.newCall(request)
        return withContext(Dispatchers.IO) {
            // The blocking execute() runs inside a cancellable continuation whose cancel handler
            // cancels the call. OkHttp ignores thread interrupts during a socket read, but a
            // cancelled call closes its socket, so a cancelled caller (a search dropped for a newer
            // one) does not wait for the request to finish. The cancelled call's IOException is
            // dropped: the caller gets a CancellationException, never NoNetwork.
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                cont.resume(exchange(call))
            }
        }
    }

    private fun exchange(call: okhttp3.Call): ApiResponse = try {
        call.execute().use { response ->
            ApiResponse.Http(response.code, response.body.string())
        }
    } catch (_: SSLException) {
        UNREACHABLE
    } catch (_: IOException) {
        // Connection refused, unknown host, timeout, reset: all mean no usable network.
        ApiResponse.NoNetwork
    }

    private companion object {
        /** No HTTP exchange happened, and no network outage explains it. Callers read it as failed. */
        val UNREACHABLE = ApiResponse.Http(0, "")

        /** What a filter that names no usable host answers, without a call. */
        private val NO_ROWS = ApiResponse.Http(200, "[]")
        const val TIMEOUT_SECONDS = 15L
        const val LISTING_TIMEOUT_SECONDS = 4L
        const val TOKEN_PATH = "auth/v1/token"
        const val LOGOUT_PATH = "auth/v1/logout"
        const val PLAYLIST_PATH = "rest/v1/member_playlist"
        const val PLAYLIST_COLUMNS = "client_number,name,server_url,username,password"
        const val PROXY_COLUMNS = ",proxy_enabled,proxy_host,proxy_port,proxy_username,proxy_password"
        const val VPN_COLUMNS = ",vpn_enabled,vpn_server,vpn_backup_server,vpn_username,vpn_password,vpn_changed_at"
        const val VPN_SERVER_PATH = "rest/v1/vpn_server"
        const val VPN_SERVER_COLUMNS = "host,label,config,backup_host,sort_order"
        const val LISTING_PATH = "rest/v1/football_listing"
        const val LISTING_COLUMNS = "kickoff,competition,home,away,channels"
        const val SPORT_COLUMN = ",sport"
        const val HTTP_BAD_REQUEST = 400
        const val UNDEFINED_COLUMN = "42703"
        const val VERDICT_PATH = "rest/v1/rpc/get_access_verdict"
        const val GRANT_TYPE = "grant_type"
        const val LIVE_ERRORS_PATH = "rest/v1/rpc/record_live_errors"
        const val LIVE_CHECKIN_PATH = "rest/v1/rpc/record_live_checkin"
        const val LIVE_PLAY_PATH = "rest/v1/rpc/record_live_play"
        const val LIVE_STRETCHES_PATH = "rest/v1/rpc/record_live_stretches"
        const val LIVE_VPN_EVENTS_PATH = "rest/v1/rpc/record_live_vpn_events"
        const val P_TV_NOW = "p_tv_now"
        const val P_PLAYED_AT = "p_played_at"
        const val P_PROXY = "p_proxy"
        const val P_DEVICE_ID = "p_device_id"
        const val P_APP_VERSION = "p_app_version"
        const val P_ENTRIES = "p_entries"
        const val P_MODEL = "p_model"
        const val P_ANDROID = "p_android"
        const val APIKEY_HEADER = "apikey"
        const val AUTH_HEADER = "Authorization"
        const val JSON_TYPE = "application/json"
    }
}
