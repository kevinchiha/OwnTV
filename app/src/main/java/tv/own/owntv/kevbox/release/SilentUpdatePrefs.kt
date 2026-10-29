// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.release

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import androidx.core.content.edit

/**
 * What the silent updater remembers between runs:
 * - the version it gave up installing silently (Android wanted a prompt, the version failed
 *   [MAX_FAILURES] times, the APK came with another key, or the box may not install apps). That
 *   brings back Core's startup update card until the installed version reaches it;
 * - the newest release a check read, for the 7-day rule in [SilentUpdateRules.fallbackNeeded];
 * - how often the latest failing version failed (a newer one starts again from zero), and whether
 *   that version must install without its profile.
 */
class SilentUpdatePrefs(private val prefs: SharedPreferences) {
    enum class Outcome { SUCCESS, REFUSED, FAILED }

    /** The newest version the box gave up installing silently, or null. */
    val gaveUpVersion: String?
        get() = prefs.getString(KEY_GAVE_UP_VERSION, null)

    /** True when Core's startup card should run; see [SilentUpdateRules.fallbackNeeded]. */
    fun fallbackNeeded(installedVersion: String, boxNowMs: Long): Boolean = SilentUpdateRules.fallbackNeeded(
        gaveUpVersion = gaveUpVersion,
        seenVersion = prefs.getString(KEY_SEEN_VERSION, null),
        seenPublishedAtMs = prefs.getLong(KEY_SEEN_PUBLISHED_AT, 0L),
        installedVersion = installedVersion,
        boxNowMs = boxNowMs,
    )

    /** The latest release a check read. Written on every successful check. */
    fun seen(version: String, publishedAtMs: Long) {
        val same = prefs.getString(KEY_SEEN_VERSION, null) == version &&
            prefs.getLong(KEY_SEEN_PUBLISHED_AT, 0L) == publishedAtMs
        if (same) return
        prefs.edit {
            putString(KEY_SEEN_VERSION, version)
            putLong(KEY_SEEN_PUBLISHED_AT, publishedAtMs)
        }
    }

    /** The box stops trying [version] silently and leaves it to Core's card. An older version never replaces a newer one. */
    fun giveUp(version: String) {
        val current = gaveUpVersion
        if (current != null && !SilentUpdateRules.isNewer(version, current)) return
        prefs.edit { putString(KEY_GAVE_UP_VERSION, version) }
    }

    /**
     * One more failed install of [version]. A failure with the profile ([withProfile]) does not
     * count: the next install leaves the profile out. A failure without it counts, and the
     * [MAX_FAILURES]th gives the version up.
     */
    fun failed(version: String, withProfile: Boolean) {
        if (withProfile) {
            prefs.edit { putString(KEY_NO_PROFILE_VERSION, version) }
            return
        }
        val count = if (prefs.getString(KEY_FAILED_VERSION, null) == version) prefs.getInt(KEY_FAILED_COUNT, 0) else 0
        prefs.edit {
            putString(KEY_FAILED_VERSION, version)
            putInt(KEY_FAILED_COUNT, count + 1)
        }
        if (count + 1 >= MAX_FAILURES) giveUp(version)
    }

    /** True once the box gave [version] up: it is not downloaded again. A newer version is still tried. */
    fun skips(version: String): Boolean = gaveUpVersion == version

    /** True when an install of [version] with its profile failed, so the next one goes without. */
    fun skipsProfile(version: String): Boolean = prefs.getString(KEY_NO_PROFILE_VERSION, null) == version

    companion object {
        const val FILE = "kevbox_silent_update"
        const val MAX_FAILURES = 3
        private const val KEY_GAVE_UP_VERSION = "gave_up_version"
        private const val KEY_SEEN_VERSION = "seen_version"
        private const val KEY_SEEN_PUBLISHED_AT = "seen_published_at"
        private const val KEY_FAILED_VERSION = "failed_version"
        private const val KEY_FAILED_COUNT = "failed_count"
        private const val KEY_NO_PROFILE_VERSION = "no_profile_version"

        fun open(context: Context) = SilentUpdatePrefs(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

        fun outcomeOf(status: Int): Outcome = when (status) {
            PackageInstaller.STATUS_SUCCESS -> Outcome.SUCCESS
            PackageInstaller.STATUS_PENDING_USER_ACTION -> Outcome.REFUSED
            else -> Outcome.FAILED
        }
    }
}
