// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.supabase

import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
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
        val query = mapOf("select" to PLAYLIST_COLUMNS + PROXY_COLUMNS, "order" to "name.asc")
        val response = send(PLAYLIST_PATH, query, accessToken) { get() }
        // A server without migration 0014 refuses the proxy columns (PostgREST 400, code 42703):
        // ask again without them, so the playlists still arrive and the proxy reads as off.
        if (response is ApiResponse.Http && response.code == HTTP_BAD_REQUEST && response.body.contains(UNDEFINED_COLUMN)) {
            return send(PLAYLIST_PATH, mapOf("select" to PLAYLIST_COLUMNS, "order" to "name.asc"), accessToken) { get() }
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
        method: Request.Builder.() -> Request.Builder,
    ): ApiResponse {
        val base = root ?: return UNREACHABLE
        val url = base.newBuilder().addPathSegments(path).apply {
            // Every query value is a constant of this file, so it goes out as written (commas kept).
            query.forEach { (name, value) -> addEncodedQueryParameter(name, value) }
        }.build()
        val request = Request.Builder().url(url)
            .header(APIKEY_HEADER, anonKey)
            .apply { if (accessToken != null) header(AUTH_HEADER, "Bearer $accessToken") }
            .method()
            .build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    ApiResponse.Http(response.code, response.body.string())
                }
            } catch (_: SSLException) {
                UNREACHABLE
            } catch (_: IOException) {
                // Connection refused, unknown host, timeout, reset: all mean no usable network.
                ApiResponse.NoNetwork
            }
        }
    }

    private companion object {
        /** No HTTP exchange happened, and no network outage explains it. Callers read it as failed. */
        val UNREACHABLE = ApiResponse.Http(0, "")
        const val TIMEOUT_SECONDS = 15L
        const val TOKEN_PATH = "auth/v1/token"
        const val LOGOUT_PATH = "auth/v1/logout"
        const val PLAYLIST_PATH = "rest/v1/member_playlist"
        const val PLAYLIST_COLUMNS = "client_number,name,server_url,username,password"
        const val PROXY_COLUMNS = ",proxy_enabled,proxy_host,proxy_port,proxy_username,proxy_password"
        const val HTTP_BAD_REQUEST = 400
        const val UNDEFINED_COLUMN = "42703"
        const val VERDICT_PATH = "rest/v1/rpc/get_access_verdict"
        const val GRANT_TYPE = "grant_type"
        const val LIVE_ERRORS_PATH = "rest/v1/rpc/record_live_errors"
        const val LIVE_CHECKIN_PATH = "rest/v1/rpc/record_live_checkin"
        const val LIVE_PLAY_PATH = "rest/v1/rpc/record_live_play"
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
