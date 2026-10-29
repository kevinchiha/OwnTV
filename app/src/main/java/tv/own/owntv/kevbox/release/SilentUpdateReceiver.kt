// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.release

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import tv.own.owntv.kevbox.release.SilentUpdatePrefs.Outcome

/**
 * The result of a [SilentUpdater] session. Not exported: only the installer, through the session's
 * PendingIntent, reaches it.
 *
 * Success needs nothing. A request for user action never shows the prompt: the session is abandoned
 * and the box gives that version up, so Core's startup card offers it instead. Any other failure
 * counts against the version, or, when the install carried the profile, drops the profile next time.
 */
class SilentUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val version = intent.getStringExtra(SilentUpdater.EXTRA_VERSION)
        val withProfile = intent.getBooleanExtra(SilentUpdater.EXTRA_WITH_PROFILE, false)
        val prefs = SilentUpdatePrefs.open(context)
        when (SilentUpdatePrefs.outcomeOf(status)) {
            Outcome.SUCCESS -> Log.i(TAG, "installed $version")
            Outcome.REFUSED -> {
                if (sessionId != -1) {
                    runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
                }
                if (version != null) prefs.giveUp(version)
                Log.i(TAG, "Android wants a prompt for $version: left to the update card")
            }
            Outcome.FAILED -> {
                if (version != null) prefs.failed(version, withProfile)
                Log.w(TAG, "install of $version failed: status $status, profile $withProfile")
            }
        }
    }

    private companion object {
        const val TAG = "KevBoxSilentUpdate"
    }
}
