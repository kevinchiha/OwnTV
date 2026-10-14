// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.network

import android.content.SharedPreferences
import kotlinx.coroutines.flow.first
import tv.own.owntv.core.network.DnsConfig
import tv.own.owntv.core.network.DohPresets
import tv.own.owntv.core.settings.SettingsRepository

/**
 * Turns on Cloudflare DNS-over-HTTPS once per install, new or updated (Kevin, 2026-10-14), unless
 * the member already uses a DNS of their own. Some home routers' DNS block or hijack IPTV hosts;
 * DNS-over-HTTPS asks Cloudflare over HTTPS instead. It runs once: a member who later turns DNS off
 * in Settings › App › DNS keeps it off. If Cloudflare cannot be reached, Core falls back to the
 * system DNS for that lookup.
 */
class DnsDefault(private val settings: SettingsRepository, private val prefs: SharedPreferences) {

    suspend fun applyOnce() {
        if (prefs.getBoolean(KEY_APPLIED, false)) return
        val current = settings.dnsConfig.first()
        if (shouldSet(current)) settings.saveDns(enabled = true, host = "", port = DnsConfig.DNS_DEFAULT_PORT, dohUrl = DohPresets.CLOUDFLARE)
        prefs.edit().putBoolean(KEY_APPLIED, true).apply()
    }

    companion object {
        const val PREFS_NAME = "kevbox_dns_default"
        private const val KEY_APPLIED = "cloudflare_doh_applied"

        /** True unless a DNS of the member's own is on and usable. */
        fun shouldSet(current: DnsConfig): Boolean = !(current.plainUsable || current.dohUsable)
    }
}
