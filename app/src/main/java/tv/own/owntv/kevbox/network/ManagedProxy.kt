// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.network

import kotlinx.coroutines.flow.first
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
 * KevBoxCoordinator calls [apply] after every successful playlist fetch (sign-in, the 15-minute
 * check, a Realtime change, coming back to the app) and after the member picks a playlist, and
 * [clear] on sign-out. A
 * failed fetch calls nothing, so the proxy stays as it was. The app's own Proxy screen is hidden:
 * kevbox.dev is the only place it changes. KevBox's own server calls never use it (SupabaseApi).
 */
class ManagedProxy(private val target: ProxyTarget) {

    /**
     * Saves the proxy for the playlist on screen. True when it saved a different proxy: the route
     * under a playing channel moved, so the coordinator re-tunes it (0.6 AC-40).
     */
    suspend fun apply(rows: List<PlaylistRow>, chosenClientNumber: String?): Boolean {
        val want = proxyFor(rows, chosenClientNumber)
        // Written only when it changes: each write restarts what reads the setting.
        if (normalized(target.current()) == want) return false
        target.save(want)
        return true
    }

    /** Sign-out: no proxy. Playback stops anyway, so nothing is re-tuned. */
    suspend fun clear() {
        apply(emptyList(), null)
    }

    companion object {
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

        /** A stored proxy that is off counts as [PlaylistProxy.OFF], whatever its old fields hold. */
        internal fun normalized(p: PlaylistProxy): PlaylistProxy = if (p.enabled) p else PlaylistProxy.OFF
    }
}
