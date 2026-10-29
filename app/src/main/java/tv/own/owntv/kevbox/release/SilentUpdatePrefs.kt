// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.release

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import androidx.core.content.edit

/**
 * What the silent updater remembers between runs: whether Android ever asked for a prompt
 * ("silent refused", which brings back Core's startup update card) and which version keeps failing.
 * Only the latest failing version is counted; a newer one starts again from zero.
 */
class SilentUpdatePrefs(private val prefs: SharedPreferences) {
    enum class Outcome { SUCCESS, REFUSED, FAILED }

    val silentRefused: Boolean
        get() = prefs.getBoolean(KEY_REFUSED, false)

    /** One more failed install of [version]. */
    fun failed(version: String) {
        val count = if (prefs.getString(KEY_FAILED_VERSION, null) == version) prefs.getInt(KEY_FAILED_COUNT, 0) else 0
        prefs.edit {
            putString(KEY_FAILED_VERSION, version)
            putInt(KEY_FAILED_COUNT, count + 1)
        }
    }

    /** True once [version] failed [MAX_FAILURES] times, or Android wanted a prompt for it. */
    fun skips(version: String): Boolean =
        prefs.getString(KEY_REFUSED_VERSION, null) == version ||
            (prefs.getString(KEY_FAILED_VERSION, null) == version && prefs.getInt(KEY_FAILED_COUNT, 0) >= MAX_FAILURES)

    /**
     * Android wanted the user to confirm. With [version], that release is not downloaded again;
     * a newer one is still tried silently.
     */
    fun markRefused(version: String? = null) {
        prefs.edit {
            putBoolean(KEY_REFUSED, true)
            if (version != null) putString(KEY_REFUSED_VERSION, version)
        }
    }

    companion object {
        const val FILE = "kevbox_silent_update"
        const val MAX_FAILURES = 3
        private const val KEY_REFUSED = "silent_refused"
        private const val KEY_REFUSED_VERSION = "refused_version"
        private const val KEY_FAILED_VERSION = "failed_version"
        private const val KEY_FAILED_COUNT = "failed_count"

        fun open(context: Context) = SilentUpdatePrefs(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

        fun outcomeOf(status: Int): Outcome = when (status) {
            PackageInstaller.STATUS_SUCCESS -> Outcome.SUCCESS
            PackageInstaller.STATUS_PENDING_USER_ACTION -> Outcome.REFUSED
            else -> Outcome.FAILED
        }
    }
}
