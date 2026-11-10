// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.network

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.kevbox.supabase.PlaylistProxy
import tv.own.owntv.kevbox.supabase.PlaylistRow

/** Where the proxy goes: Core's one global proxy, which every stream, guide and image request uses. */
interface ProxyTarget {
    suspend fun current(): PlaylistProxy
    suspend fun save(proxy: PlaylistProxy)
}

class CoreProxyTarget(private val settings: SettingsRepository) : ProxyTarget {
    override suspend fun current(): PlaylistProxy = settings.proxyConfig.first().let {
        PlaylistProxy(it.enabled, it.host, it.port, it.username, it.password)
    }

    override suspend fun save(proxy: PlaylistProxy) =
        settings.saveProxy(proxy.enabled, proxy.host, proxy.port, proxy.username, proxy.password)
}

/**
 * Keeps Core's global proxy equal to the proxy kevbox.dev set for the playlist on screen.
 *
 * KevBoxCoordinator calls [resume] once at process start, [apply] after every successful playlist
 * fetch (sign-in, the 15-minute check, a Realtime change, coming back to the app) and after the
 * member picks a playlist, and [clear] on sign-out. A failed fetch calls nothing, so the proxy stays
 * as it was. The app's own Proxy screen is hidden: kevbox.dev is the only place it changes. KevBox's
 * own server calls never use it (SupabaseApi, PlaylistRealtime).
 *
 * When the proxy carries an encrypted address (0.7), Core's setting is `127.0.0.1:<port>` with the
 * playlist's login, [relay] carries every connection inside TLS to that address, and [kept] holds
 * the address so the relay can start again at the next process start. The plain proxy host is
 * never saved on that path (AC-13). A relay never runs without a kept address, and every path that
 * turns the relay off drops it.
 */
class ManagedProxy(
    private val target: ProxyTarget,
    private val relay: Relay,
    private val kept: KeptRelay,
) {
    // One at a time: a resume at start and an early playlist check must never interleave.
    private val lock = Mutex()

    /**
     * Saves the proxy for the playlist on screen. True when Core's setting or the relay's target
     * changed: the route under a playing channel moved, so the coordinator re-tunes it (0.6 AC-40,
     * AC-19). A re-check with the same answer writes nothing and returns false (AC-17).
     */
    suspend fun apply(rows: List<PlaylistRow>, chosenClientNumber: String?): Boolean = lock.withLock {
        val want = proxyFor(rows, chosenClientNumber)
        val tls = want.tls
        if (want.enabled && tls != null) {
            applyRelay(want, RelayTarget(tls.host, tls.port, tls.addr))
        } else {
            applyPlain(want.copy(tls = null))
        }
    }

    /** Sign-out: no relay, nothing kept, no proxy. Playback stops anyway, so nothing is re-tuned. */
    suspend fun clear() {
        lock.withLock { applyPlain(PlaylistProxy.OFF) }
    }

    /**
     * Process start: restarts the relay from the kept address before any playlist check, so Core's
     * saved `127.0.0.1` setting has a listener behind it even while kevbox.dev is unreachable
     * (AC-25). Never re-tunes a channel. A relay that cannot bind leaves the kept address for the
     * next try and turns Core's setting to [CLOSED]: another app may be listening on the old port.
     */
    suspend fun resume() {
        lock.withLock {
            val keptTarget = io { kept.load() } ?: return@withLock
            val stored = normalized(target.current())
            if (!stored.enabled || stored.host != RELAY_LOOPBACK) {
                // Core's setting is no longer the relay's, so the address kept for it goes too.
                io { kept.clear() }
                return@withLock
            }
            val port = startRelay(keptTarget)
            if (port == null) {
                save(stored, CLOSED)
                return@withLock
            }
            if (port != stored.port) target.save(stored.copy(port = port, tls = null))
        }
    }

    private suspend fun applyRelay(want: PlaylistProxy, relayTarget: RelayTarget): Boolean {
        // Kept first, with commit(), then Core's setting: a process killed between the two leaves
        // something for resume() to start (gap appwire-3).
        val keptChanged = io { kept.load() } != relayTarget
        if (keptChanged) io { kept.save(relayTarget) }
        val port = startRelay(relayTarget)
        val stored = normalized(target.current())
        // No port could be bound: fail closed, never the plain host and never the login against a
        // port the relay has not opened (AC-16). The address stays kept, so the next apply or
        // resume tries again.
        val core = port?.let { PlaylistProxy(true, RELAY_LOOPBACK, it, want.username, want.password) } ?: CLOSED
        val coreChanged = save(stored, core)
        return keptChanged || coreChanged
    }

    private suspend fun applyPlain(want: PlaylistProxy): Boolean {
        io { relay.stop() }
        val keptBefore = io { kept.load() }
        // Core's setting first: a process killed before the clear leaves an address that resume()
        // drops, because Core no longer points at the relay.
        val coreChanged = save(normalized(target.current()), want)
        if (keptBefore != null) io { kept.clear() }
        return coreChanged || keptBefore != null
    }

    /** Writes [want] only when it differs from [stored]: each write restarts what reads the setting. */
    private suspend fun save(stored: PlaylistProxy, want: PlaylistProxy): Boolean {
        if (stored == want) return false
        target.save(want)
        return true
    }

    /** The relay's port, or null when no port can be bound. */
    private suspend fun startRelay(relayTarget: RelayTarget): Int? = try {
        io { relay.start(relayTarget) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        /** Below 1024: no unprivileged app on the TV can listen there, so nothing answers it. */
        private const val CLOSED_PORT = 1

        /**
         * Core's setting while no relay port could be opened: the loopback on [CLOSED_PORT] with no
         * login. Playback fails closed, and neither the login nor any channel link (which carries
         * the customer's IPTV login) can reach another app listening on a loopback port (AC-16).
         */
        private val CLOSED = PlaylistProxy.OFF.copy(enabled = true, host = RELAY_LOOPBACK, port = CLOSED_PORT)

        /**
         * The chosen playlist's proxy; with no choice, the only playlist's. Anything else (no rows,
         * several and none chosen, a proxy switched off) is [PlaylistProxy.OFF]. A playlist whose VPN
         * is on gets no proxy either: the VPN wins (0.6 AC-25, ManagedVpn).
         */
        fun proxyFor(rows: List<PlaylistRow>, chosenClientNumber: String?): PlaylistProxy {
            val row = rows.firstOrNull { it.clientNumber == chosenClientNumber } ?: rows.singleOrNull()
            if (row?.vpn?.enabled == true) return PlaylistProxy.OFF
            return row?.proxy?.takeIf { it.enabled } ?: PlaylistProxy.OFF
        }

        /**
         * A stored proxy that is off counts as [PlaylistProxy.OFF], whatever its old fields hold. Core's
         * setting never carries an encrypted address, so the comparison leaves [PlaylistProxy.tls] out.
         */
        internal fun normalized(p: PlaylistProxy): PlaylistProxy = if (p.enabled) p.copy(tls = null) else PlaylistProxy.OFF
    }
}
