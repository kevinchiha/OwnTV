// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.vpn

import kotlinx.coroutines.flow.SharedFlow

/** What the engine reports about the tunnel it was asked to open. */
sealed interface EngineEvent {
    /** The tunnel is established. */
    data object Up : EngineEvent

    /** The tunnel was up and is no longer (a drop; the engine may still be reconnecting). */
    data object Down : EngineEvent

    /** The engine gave up on its own: login refused, config refused, or its process ended. */
    data object Failed : EngineEvent
}

/**
 * The OpenVPN engine behind one seam, so the VPN rules are written and tested against a fake.
 * [IcsVpnEngine] is the real one.
 */
interface VpnEngine {
    /** Starts connecting and returns at once. [config] is the server's raw `config` column. */
    fun start(host: String, config: String, username: String, password: String)

    /** Stops and tears the tunnel down; safe to call when idle. Emits nothing. */
    fun stop()

    val events: SharedFlow<EngineEvent>
}
