package tv.own.owntv.kevbox.supabase

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Test

class SupabaseApiTest {

    /** Fails every call with the next error in line, the way a broken network or TLS would. */
    private class Failing(vararg errors: IOException) : Interceptor {
        private val queue = ArrayDeque(errors.toList())
        override fun intercept(chain: Interceptor.Chain): Response = throw queue.removeFirst()
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

    private companion object {
        const val BASE = "https://test.supabase.local"
    }
}
