// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.network

import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import okhttp3.internal.tls.OkHostnameVerifier

const val RELAY_PORT = 61129
const val RELAY_LOOPBACK = "127.0.0.1"

/** Where each connection goes: [addr] when it is a valid IPv4 address, else [host] through DNS. [host] is always the TLS server name and the name checked. */
data class RelayTarget(val host: String, val port: Int, val addr: String?)

interface Relay {
    /** Listens (binding once) and sends every new connection to [target]; open connections to a different target are closed. Returns the port. Throws IOException when no port can be bound. */
    fun start(target: RelayTarget): Int

    /** Closes the listener and every open connection. Safe when stopped. */
    fun stop()
}

/**
 * The encrypted proxy's local end (0.7). OkHttp and mpv only speak plain HTTP proxies, so Core's
 * proxy setting points here, at `127.0.0.1:<port>`, and every connection the players open is
 * carried, byte for byte, inside TLS to the proxy on persovps. The proxy request and its login
 * travel inside the TLS exactly as the player wrote them; the relay adds and removes nothing.
 *
 * It never falls back (AC-16): a failed lookup, connect, handshake, chain or host-name check
 * closes the player's connection and tries nothing else, because a fallback would show an
 * inspecting internet company exactly what the encryption hides.
 */
class LocalRelay(
    trust: X509TrustManager,
    private val lookup: (String) -> List<InetAddress>,
    private val preferredPort: Int = RELAY_PORT,
) : Relay {

    private val tls: SSLSocketFactory = SSLContext.getInstance(TLS).apply { init(null, arrayOf(trust), null) }.socketFactory

    // Unbounded on purpose: every connection holds its threads for its whole life, and Core sends
    // the stream, the guide, logos and idle keep-alive connections through this one proxy. A
    // capped pool would leave a new connection (the channel's included) waiting in a queue.
    private val pool: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, THREAD_NAME).apply { isDaemon = true }
    }

    private val lock = Any()
    private var server: ServerSocket? = null
    private var target: RelayTarget? = null
    private var lastPort: Int? = null
    private val open = mutableSetOf<Connection>()

    override fun start(target: RelayTarget): Int = synchronized(lock) {
        val listening = server
        if (listening != null) {
            if (this.target != target) {
                this.target = target
                closeAllLocked()
            }
            return listening.localPort
        }
        val bound = bind()
        server = bound
        this.target = target
        lastPort = bound.localPort
        Thread({ acceptLoop(bound) }, THREAD_NAME).apply { isDaemon = true }.start()
        bound.localPort
    }

    override fun stop() = synchronized(lock) {
        server?.let { quietly(it) }
        server = null
        target = null
        closeAllLocked()
    }

    /** The port held earlier in this process, then [preferredPort], then any free port. */
    private fun bind(): ServerSocket {
        val loopback = InetAddress.getByName(RELAY_LOOPBACK)
        var failure: IOException? = null
        for (port in listOfNotNull(lastPort, preferredPort, 0).distinct()) {
            val socket = ServerSocket()
            try {
                // A restart must not lose the port to connections still in TIME_WAIT.
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(loopback, port))
                return socket
            } catch (e: IOException) {
                quietly(socket)
                failure = e
            }
        }
        throw failure ?: IOException(NO_PORT)
    }

    private fun acceptLoop(listening: ServerSocket) {
        while (true) {
            val player = try {
                listening.accept()
            } catch (_: IOException) {
                return // closed by stop()
            }
            val connection: Connection
            val to: RelayTarget
            synchronized(lock) {
                val current = target
                if (server !== listening || current == null) {
                    quietly(player)
                    return
                }
                connection = Connection(player)
                to = current
                open += connection
            }
            try {
                pool.execute { serve(connection, to) }
            } catch (e: RuntimeException) {
                close(connection)
            }
        }
    }

    private fun serve(connection: Connection, to: RelayTarget) {
        var upstream: SSLSocket? = null
        try {
            upstream = connect(connection, to)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "relay: connection refused ${e.javaClass.simpleName}")
        } finally {
            // No second target, no plain port, no direct connection (AC-16).
            if (upstream == null) close(connection)
        }
        if (upstream == null) return
        try {
            pool.execute { copy(connection, upstream.inputStream, connection.player.getOutputStream()) }
        } catch (e: RuntimeException) {
            close(connection)
            return
        }
        copy(connection, connection.player.getInputStream(), upstream.outputStream)
    }

    /** TCP to the address (or each looked-up one in turn), then TLS with [RelayTarget.host] as the server name, chain and name checked. */
    private fun connect(connection: Connection, to: RelayTarget): SSLSocket {
        val bytes = ipv4Bytes(to.addr)
        val candidates = if (bytes != null) listOf(InetAddress.getByAddress(to.host, bytes)) else lookup(to.host)
        var plain: Socket? = null
        var failure: IOException? = null
        for (address in candidates) {
            val socket = Socket()
            connection.attach(socket)
            try {
                socket.connect(InetSocketAddress(address, to.port), CONNECT_TIMEOUT_MS)
                plain = socket
                break
            } catch (e: IOException) {
                quietly(socket)
                failure = e
            }
        }
        if (plain == null) throw failure ?: IOException(NO_ADDRESS)

        val ssl = tls.createSocket(plain, to.host, to.port, true) as SSLSocket
        connection.attach(ssl)
        ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(SNIHostName(to.host)) }
        ssl.soTimeout = HANDSHAKE_TIMEOUT_MS
        ssl.startHandshake()
        // A trust manager alone does not check the host name on Android.
        if (!OkHostnameVerifier.verify(to.host, ssl.session)) throw IOException(WRONG_NAME)
        ssl.soTimeout = 0
        return ssl
    }

    /** Copies until this direction ends (EOF or error), then closes both sides (AC-15). */
    private fun copy(connection: Connection, from: InputStream, to: OutputStream) {
        val buffer = ByteArray(BUFFER_BYTES)
        try {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                to.write(buffer, 0, n)
                to.flush()
            }
        } catch (_: IOException) {
            // Either side closed or failed: the end of this connection either way.
        } finally {
            close(connection)
        }
    }

    private fun close(connection: Connection) {
        synchronized(lock) { open -= connection }
        connection.close()
    }

    private fun closeAllLocked() {
        open.forEach { it.close() }
        open.clear()
    }

    /** The player's socket and whatever upstream socket was opened for it, closed together. */
    private class Connection(val player: Socket) {
        private val sockets = mutableListOf<Closeable>(player)
        private var closed = false

        /** Ties [socket] to this connection; one attached after the close is closed at once. */
        fun attach(socket: Closeable) {
            val late = synchronized(this) {
                if (!closed) sockets += socket
                closed
            }
            if (late) quietly(socket)
        }

        fun close() {
            val all = synchronized(this) {
                if (closed) return
                closed = true
                sockets.toList()
            }
            all.asReversed().forEach { quietly(it) }
        }
    }

    private companion object {
        const val LOG_TAG = "KevBoxRelay"
        const val THREAD_NAME = "kevbox-relay"
        const val TLS = "TLS"
        const val NO_PORT = "no port"
        const val NO_ADDRESS = "no address"
        const val WRONG_NAME = "host name mismatch"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val HANDSHAKE_TIMEOUT_MS = 15_000
        const val BUFFER_BYTES = 16 * 1024
    }
}

private fun quietly(c: Closeable) {
    try {
        c.close()
    } catch (_: IOException) {
        // Already closed or broken: nothing left to release.
    }
}

/** Pure. [text] as four bytes when it is a dotted-quad IPv4 address, else null. Never looks anything up. */
fun ipv4Bytes(text: String?): ByteArray? {
    if (text.isNullOrEmpty()) return null
    val parts = text.split('.')
    if (parts.size != 4) return null
    val out = ByteArray(4)
    for ((i, part) in parts.withIndex()) {
        if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
        if (part.length > 1 && part[0] == '0') return null
        val value = part.toInt()
        if (value > 255) return null
        out[i] = value.toByte()
    }
    return out
}
