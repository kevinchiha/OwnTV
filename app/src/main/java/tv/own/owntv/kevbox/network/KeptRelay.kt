// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.network

import android.content.SharedPreferences

const val RELAY_PREFS_NAME = "kevbox_relay"

/**
 * The last encrypted address applied, beside Core's saved proxy.
 *
 * Core's saved proxy says `127.0.0.1:<port>` while the relay carries the traffic, and Core reads it
 * at every process start. This store is what lets [ManagedProxy.resume] start the relay again at
 * that point, before any playlist check and even while kevbox.dev cannot be reached (AC-25).
 * Writes use `commit()`: ManagedProxy saves here before it writes Core's setting, so a process
 * killed between the two never leaves Core on `127.0.0.1` with nothing kept.
 */
class KeptRelay(private val prefs: SharedPreferences) {

    /** Null when none is kept, or the kept host is blank or the port outside 1-65535. */
    fun load(): RelayTarget? = try {
        val host = prefs.getString(KEY_HOST, null)?.takeIf { it.isNotBlank() }
        val port = prefs.getInt(KEY_PORT, 0).takeIf { it in 1..MAX_PORT }
        if (host == null || port == null) null else RelayTarget(host, port, prefs.getString(KEY_ADDR, null))
    } catch (_: ClassCastException) {
        // A value of another type under one of the keys: nothing usable is kept.
        null
    }

    fun save(target: RelayTarget) {
        val edit = prefs.edit().putString(KEY_HOST, target.host).putInt(KEY_PORT, target.port)
        if (target.addr == null) edit.remove(KEY_ADDR) else edit.putString(KEY_ADDR, target.addr)
        edit.commit()
    }

    fun clear() {
        prefs.edit().remove(KEY_HOST).remove(KEY_PORT).remove(KEY_ADDR).commit()
    }

    private companion object {
        const val KEY_HOST = "relay_host"
        const val KEY_PORT = "relay_port"
        const val KEY_ADDR = "relay_addr"
        const val MAX_PORT = 65535
    }
}
