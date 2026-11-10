// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.vpn

import android.content.SharedPreferences

/** The preferences file the VPN keeps its fall-back in. */
const val VPN_PREFS_NAME = "kevbox_vpn"

/**
 * The last fall-back (AC-31): when the cycle gave up, for which server and which vpn_changed_at.
 * It lives in preferences so a restart inside the 3 hours does not start a cycle early (AC-32).
 * The time is the wall clock on purpose: it has to mean the same thing after a restart. The runner
 * writes it when [VpnRules] asks with [VpnAction.SaveFallback].
 */
class FallbackStore(private val prefs: SharedPreferences) {

    fun atMs(): Long? = if (prefs.contains(KEY_AT)) prefs.getLong(KEY_AT, 0L) else null

    fun server(): String? = prefs.getString(KEY_SERVER, null)

    fun changedAt(): String? = prefs.getString(KEY_CHANGED_AT, null)

    fun save(atMs: Long, server: String, changedAt: String?) {
        val edit = prefs.edit().putLong(KEY_AT, atMs).putString(KEY_SERVER, server)
        if (changedAt == null) edit.remove(KEY_CHANGED_AT) else edit.putString(KEY_CHANGED_AT, changedAt)
        edit.apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_AT).remove(KEY_SERVER).remove(KEY_CHANGED_AT).apply()
    }

    private companion object {
        const val KEY_AT = "fallback_at_ms"
        const val KEY_SERVER = "fallback_server"
        const val KEY_CHANGED_AT = "fallback_changed_at"
    }
}
