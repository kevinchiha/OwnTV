// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.network

import tv.own.owntv.core.network.ProxyConfig

/**
 * True when the TV's provider requests go through the proxy kevbox.dev set, right now.
 *
 * The one place error entries and play notes read the proxy flag. [config] is Core's stored proxy
 * setting, the same flow Core's `ProxyConfigHolder` routes every request and mpv load by, and
 * [ProxyConfig.usable] is the check it routes on. A read that throws passes the exception on: the
 * caller decides what a failed read means.
 */
class ProxyInUse(private val config: suspend () -> ProxyConfig) {
    suspend fun current(): Boolean = config().usable
}
