// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.release

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SilentBlockFetchTest {
    private val url = "https://github.com/kevinchiha/OwnTV/releases/download/v5.2.1.3/kevbox-silent.json"
    private val kevin = "0f8fad5b-d9cb-469f-a165-70867728950e"
    private val valid = """{"after": 1793276852000, "earlyUsers": ["$kevin"]}"""

    /** Answers every request with [code] and [body], or throws [error] instead. */
    private fun client(
        code: Int = 200,
        body: () -> ResponseBody = { valid.toResponseBody(JSON) },
        error: Exception? = null,
    ) = OkHttpClient.Builder().addInterceptor(
        Interceptor { chain ->
            error?.let { throw it }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("canned")
                .body(body())
                .build()
        },
    ).build()

    /** A body that does not say its length, as a chunked answer does. */
    private fun unsized(text: String) = object : ResponseBody() {
        override fun contentType(): MediaType = JSON
        override fun contentLength() = -1L
        override fun source(): BufferedSource = Buffer().writeUtf8(text)
    }

    private fun fetch(http: OkHttpClient, at: String = url) = SilentBlockFetch.fetch(http, at, "KevBoxLive")

    @Test
    fun `a valid file is read`() {
        assertEquals(SilentBlockFetch.Read(SilentBlock(1_793_276_852_000L, listOf(kevin))), fetch(client()))
        assertEquals(
            SilentBlockFetch.Read(SilentBlock(1_793_276_852_000L, listOf(kevin))),
            fetch(client(body = { unsized(valid) })),
        )
    }

    @Test
    fun `a file that arrived but does not parse is read as no block`() {
        assertEquals(SilentBlockFetch.Read(null), fetch(client(body = { """{"after": "x"}""".toResponseBody(JSON) })))
        assertEquals(SilentBlockFetch.Read(null), fetch(client(body = { "<html>oops</html>".toResponseBody(JSON) })))
        assertEquals(SilentBlockFetch.Read(null), fetch(client(body = { "".toResponseBody(JSON) })))
    }

    @Test
    fun `an HTTP error is a failed fetch, not a missing block`() {
        assertEquals(SilentBlockFetch.Failed, fetch(client(code = 404)))
        assertEquals(SilentBlockFetch.Failed, fetch(client(code = 429)))
        assertEquals(SilentBlockFetch.Failed, fetch(client(code = 503)))
    }

    @Test
    fun `no answer or any other exception is a failed fetch`() {
        assertEquals(SilentBlockFetch.Failed, fetch(client(error = IOException("reset"))))
        assertEquals(SilentBlockFetch.Failed, fetch(client(error = SocketTimeoutException("slow"))))
        assertEquals(SilentBlockFetch.Failed, fetch(client(error = IllegalStateException("odd"))))
        assertEquals(SilentBlockFetch.Failed, fetch(client(body = { throw IllegalArgumentException("odd body") })))
    }

    @Test
    fun `a URL OkHttp cannot read is a failed fetch`() {
        assertEquals(SilentBlockFetch.Failed, fetch(client(), at = "not a url"))
        assertEquals(SilentBlockFetch.Failed, fetch(client(), at = ""))
    }

    @Test
    fun `a file over 64 KB is a failed fetch, whether or not it says its size`() {
        val big = " ".repeat(SilentBlockFetch.MAX_BYTES) + valid
        assertEquals(SilentBlockFetch.Failed, fetch(client(body = { big.toResponseBody(JSON) })))
        assertEquals(SilentBlockFetch.Failed, fetch(client(body = { unsized(big) })))
        // Exactly 64 KB still arrives whole.
        val padded = valid + " ".repeat(SilentBlockFetch.MAX_BYTES - valid.length)
        assertEquals(
            SilentBlockFetch.Read(SilentBlock(1_793_276_852_000L, listOf(kevin))),
            fetch(client(body = { unsized(padded) })),
        )
    }

    @Test
    fun `a cancel is passed on`() {
        assertThrows(CancellationException::class.java) { fetch(client(error = CancellationException("stop"))) }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
