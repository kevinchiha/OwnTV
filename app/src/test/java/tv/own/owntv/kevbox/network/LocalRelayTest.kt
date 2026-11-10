// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.network

import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalRelayTest {
    private val cleanup = mutableListOf<Closeable>()
    private val relays = mutableListOf<LocalRelay>()

    @After
    fun tearDown() {
        relays.forEach { it.stop() }
        cleanup.asReversed().forEach { runCatching { it.close() } }
    }

    private fun <T : Closeable> keep(c: T): T = c.also { cleanup += it }

    private fun relay(lookup: (String) -> List<InetAddress> = { emptyList() }, trust: X509TrustManager = RelayFixtures.baseTrust()) =
        LocalRelay(trust, lookup, preferredPort = RelayFixtures.freePort()).also { relays += it }

    /** A lookup that records every call and answers with [answer]. */
    private class Lookup(private val answer: List<InetAddress> = emptyList()) : (String) -> List<InetAddress> {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun invoke(host: String): List<InetAddress> {
            calls += host
            return answer
        }
    }

    @Test(timeout = 60_000)
    fun `carries bytes both ways to the address, with the name as SNI and no lookup`() {
        val server = keep(RelayFixtures.TlsEcho(RelayFixtures.RELAY_TEST_P12))
        val lookup = Lookup()
        val port = relay(lookup).start(RelayTarget(RelayFixtures.HOST, server.port, "127.0.0.1"))

        val player = keep(RelayFixtures.player(port))
        RelayFixtures.assertRoundTrip(player, RelayFixtures.ALL_BYTES)
        assertEquals(listOf(RelayFixtures.HOST), server.serverNames.toList())

        // Every connection holds its threads for its whole life: 32 held open, a 33rd still works.
        repeat(32) { RelayFixtures.assertRoundTrip(keep(RelayFixtures.player(port)), "held $it\r\n".toByteArray()) }
        val extra = keep(RelayFixtures.player(port))
        RelayFixtures.assertRoundTrip(extra, RelayFixtures.ALL_BYTES)

        server.closeClients()
        assertTrue("player reads the end once the server closes", RelayFixtures.readsEnd(extra))
        assertEquals(emptyList<String>(), lookup.calls.toList())
    }

    @Test(timeout = 60_000)
    fun `a wrong name or an untrusted chain closes the player and tries nothing else`() {
        val decoy = keep(RelayFixtures.PlainCounter())
        for (p12 in listOf(RelayFixtures.WRONG_NAME_P12, RelayFixtures.OTHER_CA_P12)) {
            val server = keep(RelayFixtures.TlsEcho(p12))
            val lookup = Lookup(listOf(InetAddress.getByName("127.0.0.1")))
            val port = relay(lookup).start(RelayTarget(RelayFixtures.HOST, server.port, "127.0.0.1"))

            val player = keep(RelayFixtures.player(port))
            assertTrue("$p12: player is closed", RelayFixtures.readsEnd(player))
            assertEquals("$p12: one attempt only", 1, server.accepted.get())
            assertEquals(emptyList<String>(), lookup.calls.toList())
        }
        assertEquals("no fallback to a plain listener", 0, decoy.accepted.get())
    }

    @Test(timeout = 60_000)
    fun `no address or a bad one looks the name up`() {
        val server = keep(RelayFixtures.TlsEcho(RelayFixtures.RELAY_TEST_P12))
        // 127.0.0.2 refuses (the server listens on 127.0.0.1 only), so the next address is tried.
        val answer = listOf(InetAddress.getByName("127.0.0.2"), InetAddress.getByName("127.0.0.1"))
        for (addr in listOf(null, "999.1.1.1", "045.1.1.1", "1.2.3", "1.2.3.4.")) {
            val lookup = Lookup(answer)
            val r = relay(lookup)
            val port = r.start(RelayTarget(RelayFixtures.HOST, server.port, addr))
            RelayFixtures.assertRoundTrip(keep(RelayFixtures.player(port)), "addr $addr\r\n".toByteArray())
            assertEquals("addr $addr", listOf(RelayFixtures.HOST), lookup.calls.toList())
            r.stop()
        }

        assertArrayEquals(byteArrayOf(45, 146.toByte(), 255.toByte(), 87), ipv4Bytes("45.146.255.87"))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), ipv4Bytes("0.0.0.0"))
        val bad = listOf(
            null, "", " ", "999.1.1.1", "045.1.1.1", "1.2.3", "1.2.3.4.", ".1.2.3", "1..2.3", "1.2.3.4.5",
            " 1.2.3.4", "1.2.3.4 ", "+1.2.3.4", "-1.2.3.4", "0x1.2.3.4", "1.2.3.a", "256.1.1.1", "1.2.3.1000",
            "relay-test.invalid",
        )
        bad.forEach { assertNull("'$it'", ipv4Bytes(it)) }
    }

    @Test(timeout = 60_000)
    fun `listens on loopback, falls back from a busy port, and keeps its port`() {
        assertEquals(61129, RELAY_PORT)
        assertEquals("127.0.0.1", RELAY_LOOPBACK)
        val target = RelayTarget(RelayFixtures.HOST, 1, "127.0.0.1")

        val preferred = RelayFixtures.freePort()
        val free = LocalRelay(RelayFixtures.baseTrust(), { emptyList() }, preferred).also { relays += it }
        assertEquals(preferred, free.start(target))
        keep(Socket()).connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), preferred), 5_000)
        nonLoopbackIpv4()?.let { outside ->
            try {
                Socket().use { it.connect(InetSocketAddress(outside, preferred), 2_000) }
                fail("reachable from $outside")
            } catch (_: IOException) {
                // refused: loopback only
            }
        }

        val busyPort = RelayFixtures.freePort()
        keep(ServerSocket(busyPort, 50, InetAddress.getByName("127.0.0.1")))
        val busy = LocalRelay(RelayFixtures.baseTrust(), { emptyList() }, busyPort).also { relays += it }
        val taken = busy.start(target)
        assertNotEquals(busyPort, taken)
        assertEquals(taken, busy.start(target.copy(host = "other.invalid", port = 2)))
        busy.stop()
        busy.stop()
        assertEquals(taken, busy.start(target))
    }

    @Test(timeout = 60_000)
    fun `a new target and stop close the open connections`() {
        val server = keep(RelayFixtures.TlsEcho(RelayFixtures.RELAY_TEST_P12))
        val r = relay()
        val target = RelayTarget(RelayFixtures.HOST, server.port, "127.0.0.1")
        val port = r.start(target)

        val first = keep(RelayFixtures.player(port))
        RelayFixtures.assertRoundTrip(first, "one\r\n".toByteArray())
        assertEquals(port, r.start(target))
        RelayFixtures.assertRoundTrip(first, "same target keeps it\r\n".toByteArray())

        assertEquals(port, r.start(target.copy(addr = null)))
        assertTrue("old-target connection closed", RelayFixtures.readsEnd(first))

        r.start(target)
        val second = keep(RelayFixtures.player(port))
        RelayFixtures.assertRoundTrip(second, "two\r\n".toByteArray())
        r.stop()
        assertTrue("stop closes it", RelayFixtures.readsEnd(second))
    }

    private fun nonLoopbackIpv4(): InetAddress? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address }
}

/** Test-only certificates (tools/kevbox/relay/make-test-certs.sh) and loopback servers. */
internal object RelayFixtures {
    const val HOST = "relay-test.invalid"
    const val RELAY_TEST_P12 = "relay-test.p12"
    const val WRONG_NAME_P12 = "wrong-name.p12"
    const val OTHER_CA_P12 = "other-ca.p12"
    const val ROOT_PEM = "test-root-ca.pem"
    const val OTHER_CA_PEM = "other-ca.pem"
    private val PASSWORD = "test".toCharArray()

    val ALL_BYTES: ByteArray = ByteArray(256) { it.toByte() } + "\r\n\r\nCONNECT x HTTP/1.1\r\n\r\n".toByteArray()

    fun resource(name: String): ByteArray =
        requireNotNull(RelayFixtures::class.java.getResourceAsStream("/relay/$name")) { name }.use { it.readBytes() }

    fun pem(name: String): String = String(resource(name))

    fun certificates(pem: String): List<X509Certificate> =
        CertificateFactory.getInstance("X.509").generateCertificates(pem.byteInputStream()).map { it as X509Certificate }

    fun trustOf(roots: List<X509Certificate>): X509TrustManager {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        roots.forEachIndexed { i, c -> store.setCertificateEntry("root$i", c) }
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** The tests' stand-in for Android's roots plus Core's: the test root alone. */
    fun baseTrust(): X509TrustManager = trustOf(certificates(pem(ROOT_PEM)))

    fun keyStore(p12: String): KeyStore =
        KeyStore.getInstance("PKCS12").apply { load(resource(p12).inputStream(), PASSWORD) }

    /** The chain a server for [p12] sends: leaf first, then any intermediate. */
    fun chain(p12: String): Array<X509Certificate> {
        val store = keyStore(p12)
        return store.getCertificateChain(store.aliases().toList().first()).map { it as X509Certificate }.toTypedArray()
    }

    fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    fun player(port: Int): Socket = Socket().apply {
        connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 5_000)
        soTimeout = 10_000
    }

    fun assertRoundTrip(player: Socket, bytes: ByteArray) {
        player.getOutputStream().apply { write(bytes); flush() }
        val back = ByteArray(bytes.size)
        var read = 0
        while (read < bytes.size) {
            val n = player.getInputStream().read(back, read, bytes.size - read)
            if (n < 0) fail("ended after $read of ${bytes.size} bytes")
            read += n
        }
        assertArrayEquals(bytes, back)
    }

    /** True when the socket was closed on us (EOF or reset); false when it only stayed silent. */
    fun readsEnd(player: Socket): Boolean = try {
        player.getInputStream().read() < 0
    } catch (_: SocketTimeoutException) {
        false
    } catch (_: SocketException) {
        true
    }

    /** A TLS echo server on 127.0.0.1 presenting [p12]; records each requested server name. */
    class TlsEcho(p12: String) : Closeable {
        private val listener: SSLServerSocket
        private val clients = CopyOnWriteArrayList<Socket>()
        val serverNames = CopyOnWriteArrayList<String>()
        val accepted = AtomicInteger()
        val port: Int get() = listener.localPort

        init {
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore(p12), PASSWORD) }
            val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
            listener = context.serverSocketFactory.createServerSocket(0, 50, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
            Thread({ acceptLoop() }, "tls-echo").apply { isDaemon = true }.start()
        }

        private fun acceptLoop() {
            while (true) {
                val socket = try { listener.accept() as SSLSocket } catch (_: IOException) { return }
                accepted.incrementAndGet()
                clients += socket
                Thread({ echo(socket) }, "tls-echo-client").apply { isDaemon = true }.start()
            }
        }

        private fun echo(socket: SSLSocket) {
            try {
                socket.startHandshake()
                (socket.session as ExtendedSSLSession).requestedServerNames
                    .filterIsInstance<SNIHostName>().forEach { serverNames += it.asciiName }
                val buffer = ByteArray(8192)
                while (true) {
                    val n = socket.inputStream.read(buffer)
                    if (n < 0) break
                    socket.outputStream.write(buffer, 0, n)
                    socket.outputStream.flush()
                }
            } catch (_: IOException) {
                // the relay refused us or went away
            } finally {
                runCatching { socket.close() }
            }
        }

        fun closeClients() = clients.forEach { runCatching { it.close() } }

        override fun close() {
            runCatching { listener.close() }
            closeClients()
        }
    }

    /** A plain listener that only counts connections: the decoy no relay may fall back to. */
    class PlainCounter : Closeable {
        private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val accepted = AtomicInteger()

        init {
            Thread({
                while (true) {
                    val s = try { listener.accept() } catch (_: IOException) { return@Thread }
                    accepted.incrementAndGet()
                    runCatching { s.close() }
                }
            }, "plain-decoy").apply { isDaemon = true }.start()
        }

        override fun close() = listener.close()
    }
}
