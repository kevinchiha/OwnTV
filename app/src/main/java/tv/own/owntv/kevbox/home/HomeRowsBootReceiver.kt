// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.tvprovider.media.tv.TvContractCompat

/**
 * Fills the TV home screen's rows at boot and when the launcher asks for this app's channels
 * (`android.media.tv.action.INITIALIZE_PROGRAMS`), with the app closed. Copies NuvioTV's
 * TvChannelBootReceiver; the work itself is one [HomeRowsWorker] run.
 */
class HomeRowsBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            TvContractCompat.ACTION_INITIALIZE_PROGRAMS,
            -> HomeRowsWorker.runNow(context)
        }
    }
}
