// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.vpn

import java.time.ZoneId
import org.json.JSONObject
import tv.own.owntv.kevbox.usage.playedAtText

/** The four things the TV reports about its VPN, with the word record_live_vpn_events stores. */
enum class VpnEventKind(val wire: String) {
    CONNECTED("connected"),
    FELL_BACK("fell_back"),
    PERMISSION_DENIED("permission_denied"),
    OTHER_VPN_ACTIVE("other_vpn_active"),
    ;

    companion object {
        fun ofWire(wire: String): VpnEventKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * One VPN event waiting to go out. [server] is the host [VpnEventKind.CONNECTED] reached, the hosts
 * [VpnEventKind.FELL_BACK] tried joined by `,`, or null for the other two kinds. [atMs] is the TV
 * clock. [id] stays on the TV: it lets the queue remove exactly the events a call carried.
 */
data class VpnEvent(
    val id: String,
    val kind: VpnEventKind,
    val server: String?,
    val atMs: Long,
    val clientNumber: String?,
)

/** The entry record_live_vpn_events reads: exactly kind, server, at and client_number. */
fun VpnEvent.toEntry(zone: ZoneId): JSONObject = JSONObject()
    .put(VpnEventKeys.KIND, kind.wire)
    .put(VpnEventKeys.SERVER, server ?: JSONObject.NULL)
    .put(VpnEventKeys.AT, playedAtText(atMs, zone))
    .put(VpnEventKeys.CLIENT_NUMBER, clientNumber ?: JSONObject.NULL)

internal object VpnEventKeys {
    const val KIND = "kind"
    const val SERVER = "server"
    const val AT = "at"
    const val CLIENT_NUMBER = "client_number"
}
