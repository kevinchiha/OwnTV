// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.vpn

import tv.own.owntv.kevbox.supabase.PlaylistRow

/**
 * The VPN twin of ManagedProxy: hands the VPN kevbox.dev set for the playlist on screen to the
 * [VpnController].
 *
 * KevBoxCoordinator calls [apply] right after ManagedProxy's, after every successful playlist check
 * and after the member picks a playlist, and [clear] on sign-out. A failed check calls neither, so
 * the VPN carries on as it was (AC-25).
 *
 * Koin uses the public constructor; tests use the internal one, with the controller as functions.
 */
class ManagedVpn internal constructor(
    private val wanted: (VpnWant?) -> Unit,
    private val signedOut: () -> Unit,
) {
    constructor(controller: VpnController) : this(controller::wanted, controller::signedOut)

    fun apply(rows: List<PlaylistRow>, chosenClientNumber: String?) = wanted(vpnFor(rows, chosenClientNumber))

    fun clear() = signedOut()

    companion object {
        /**
         * The chosen playlist's VPN; with no choice, the only playlist's (as ManagedProxy.proxyFor).
         * Anything else (no rows, several and none chosen, a VPN switched off) is null (AC-25).
         */
        fun vpnFor(rows: List<PlaylistRow>, chosenClientNumber: String?): VpnWant? {
            val row = rows.firstOrNull { it.clientNumber == chosenClientNumber } ?: rows.singleOrNull()
            val vpn = row?.vpn?.takeIf { it.enabled } ?: return null
            return VpnWant(
                clientNumber = row.clientNumber,
                server = vpn.server,
                backup = vpn.backupServer,
                username = vpn.username,
                password = vpn.password,
                changedAt = vpn.changedAt,
            )
        }
    }
}
