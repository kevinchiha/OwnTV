package tv.own.owntv.kevbox.supabase

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SupabaseApiTest {

    /** Fails every call with the next error in line, the way a broken network or TLS would. */
    private class Failing(vararg errors: IOException) : Interceptor {
        private val queue = ArrayDeque(errors.toList())
        override fun intercept(chain: Interceptor.Chain): Response = throw queue.removeFirst()
    }

    /** Answers every call 204 and keeps the request and its body. */
    private class Recording : Interceptor {
        val requests = mutableListOf<Request>()
        val bodies = mutableListOf<String>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += request
            bodies += request.body?.let { body -> Buffer().also { body.writeTo(it) }.readUtf8() } ?: ""
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(204)
                .message("canned")
                .body("".toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun api(vararg errors: IOException) =
        SupabaseApi(OkHttpClient.Builder().addInterceptor(Failing(*errors)).build(), BASE, "anon-key")

    @Test
    fun `an SSLException maps to Failed, an unknown host or timeout to NoNetwork`() = runBlocking {
        val api = api(
            SSLHandshakeException("certificate not yet valid"),
            SSLPeerUnverifiedException("peer not verified"),
            UnknownHostException("no dns"),
            SocketTimeoutException("timeout"),
            ConnectException("refused"),
        )

        // Http(0, "") is what every caller reads as a failed call, never as "no internet".
        assertEquals(ApiResponse.Http(0, ""), api.passwordGrant("m@example.com", "pw"))
        assertEquals(ApiResponse.Http(0, ""), api.refreshGrant("r1"))
        assertEquals(ApiResponse.NoNetwork, api.passwordGrant("m@example.com", "pw"))
        assertEquals(ApiResponse.NoNetwork, api.selectPlaylists("a1"))
        assertEquals(ApiResponse.NoNetwork, api.accessVerdict("a1"))
    }

    @Test
    fun `recordLiveErrors posts the contract body`() = runBlocking {
        val server = Recording()
        val api = SupabaseApi(OkHttpClient.Builder().addInterceptor(server).build(), BASE, "anon-key")
        val entries = JSONArray().put(JSONObject().put("code", "net_timeout"))

        val response = api.recordLiveErrors("a1", "device-1", "1.2.3", entries)

        assertEquals(ApiResponse.Http(204, ""), response)
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/rest/v1/rpc/record_live_errors", request.url.encodedPath)
        assertEquals("Bearer a1", request.header("Authorization"))
        assertEquals("anon-key", request.header("apikey"))
        val sent = JSONObject(server.bodies.single())
        assertEquals(setOf("p_device_id", "p_app_version", "p_entries"), sent.keys().asSequence().toSet())
        assertEquals("device-1", sent.getString("p_device_id"))
        assertEquals("1.2.3", sent.getString("p_app_version"))
        assertEquals("net_timeout", sent.getJSONArray("p_entries").getJSONObject(0).getString("code"))
    }

    @Test
    fun `recordLiveCheckin posts the contract body`() = runBlocking {
        val server = Recording()
        val api = SupabaseApi(OkHttpClient.Builder().addInterceptor(server).build(), BASE, "anon-key")

        val response = api.recordLiveCheckin("a1", "device-1", "1.2.3", "SHIELD Android TV", "11")

        assertEquals(ApiResponse.Http(204, ""), response)
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/rest/v1/rpc/record_live_checkin", request.url.encodedPath)
        assertEquals("Bearer a1", request.header("Authorization"))
        assertEquals("anon-key", request.header("apikey"))
        val sent = JSONObject(server.bodies.single())
        assertEquals(
            setOf("p_device_id", "p_app_version", "p_model", "p_android"),
            sent.keys().asSequence().toSet(),
        )
        assertEquals("device-1", sent.getString("p_device_id"))
        assertEquals("1.2.3", sent.getString("p_app_version"))
        assertEquals("SHIELD Android TV", sent.getString("p_model"))
        assertEquals("11", sent.getString("p_android"))
    }

    @Test
    fun `recordLivePlay posts the contract body`() = runBlocking {
        val server = Recording()
        val api = SupabaseApi(OkHttpClient.Builder().addInterceptor(server).build(), BASE, "anon-key")

        val response = api.recordLivePlay("a1", "device-1", "1.2.3", "2026-10-15T22:21:00.000+03:00", proxy = true)

        assertEquals(ApiResponse.Http(204, ""), response)
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/rest/v1/rpc/record_live_play", request.url.encodedPath)
        assertEquals("Bearer a1", request.header("Authorization"))
        assertEquals("anon-key", request.header("apikey"))
        val sent = JSONObject(server.bodies.single())
        // Exactly these four: no channel, stream address or account rides along (AC-6).
        assertEquals(
            setOf("p_device_id", "p_app_version", "p_played_at", "p_proxy"),
            sent.keys().asSequence().toSet(),
        )
        assertEquals("device-1", sent.getString("p_device_id"))
        assertEquals("1.2.3", sent.getString("p_app_version"))
        assertEquals("2026-10-15T22:21:00.000+03:00", sent.getString("p_played_at"))
        assertEquals(true, sent.getBoolean("p_proxy"))
    }

    private companion object {
        const val BASE = "https://test.supabase.local"
    }
}
