// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.release

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectClientTest {
    /** A loopback HTTP origin that answers each request 200 `ok` and keeps its request line. */
    private class Origin : AutoCloseable {
        val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val requestLines: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val thread = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: IOException) { break }
                client.use {
                    val reader = it.getInputStream().bufferedReader()
                    requestLines += reader.readLine().orEmpty()
                    do {
                        val header = reader.readLine()
                    } while (!header.isNullOrEmpty())
                    it.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray(),
                    )
                    it.getOutputStream().flush()
                }
            }
        }

        override fun close() {
            socket.close()
            thread.join(1_000)
        }
    }

    /** A loopback socket that stands in for Core's proxy: it counts each connection and drops it. */
    private class ProxyRecorder : AutoCloseable {
        val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val connections = AtomicInteger(0)
        private val thread = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: IOException) { break }
                connections.incrementAndGet()
                client.close()
            }
        }

        override fun close() {
            socket.close()
            thread.join(1_000)
        }
    }

    @Test(timeout = 20_000)
    fun `the silent update client never touches Core's proxy`() = runBlocking {
        Origin().use { origin ->
            ProxyRecorder().use { recorder ->
                val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getLoopbackAddress(), recorder.socket.localPort))
                val authenticatorCalls = AtomicInteger(0)
                val authenticator = Authenticator { _, response ->
                    authenticatorCalls.incrementAndGet()
                    response.request.newBuilder().header("Proxy-Authorization", "Basic eDp5").build()
                }
                val selector = object : ProxySelector() {
                    override fun select(uri: URI?): List<Proxy> = listOf(proxy)
                    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
                }
                // Core may set its proxy either way on the shared client; neither may reach the update.
                val shared = listOf(
                    OkHttpClient.Builder().proxySelector(selector).proxyAuthenticator(authenticator).build(),
                    OkHttpClient.Builder().proxy(proxy).proxyAuthenticator(authenticator).build(),
                )
                val url = "http://127.0.0.1:${origin.socket.localPort}/releases/latest"

                for (http in shared) {
                    val code = withContext(Dispatchers.IO) {
                        directClient(http).newCall(Request.Builder().url(url).build()).execute().use { it.code }
                    }
                    assertEquals(200, code)
                }

                assertEquals(listOf("GET /releases/latest HTTP/1.1", "GET /releases/latest HTTP/1.1"), origin.requestLines.toList())
                assertEquals(0, recorder.connections.get())
                assertEquals(0, authenticatorCalls.get())
            }
        }
    }

    @Test
    fun `retries come back on and the shared client's other settings stay`() {
        val interceptor = Interceptor { it.proceed(it.request()) }
        val shared = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(9, TimeUnit.SECONDS)
            .addInterceptor(interceptor)
            .build()

        val direct = directClient(shared)

        assertTrue(direct.retryOnConnectionFailure)
        assertEquals(Proxy.NO_PROXY, direct.proxy)
        assertSame(Authenticator.NONE, direct.proxyAuthenticator)
        assertEquals(7_000, direct.connectTimeoutMillis)
        assertEquals(8_000, direct.readTimeoutMillis)
        assertEquals(9_000, direct.writeTimeoutMillis)
        assertEquals(listOf(interceptor), direct.interceptors)
        assertSame(shared.dns, direct.dns)
        assertSame(shared.sslSocketFactory, direct.sslSocketFactory)
    }
}
