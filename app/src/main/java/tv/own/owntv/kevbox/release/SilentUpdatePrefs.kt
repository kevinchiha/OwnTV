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
 * - the newest release a check read, whether it had a silent block and that block's `after` on the
 *   box's own clock, for [SilentUpdateRules.fallbackNeeded];
 * - how often the latest failing version failed (a newer one starts again from zero), and whether
 *   that version must install without its profile;
 * - when a MainActivity last stopped, on the monotonic clock, for [SilentUpdateRules.idle]. It is
 *   kept here, not in memory, so a run in a fresh process still waits 15 minutes after a leave.
 */
class SilentUpdatePrefs(private val prefs: SharedPreferences) {
    enum class Outcome { SUCCESS, REFUSED, FAILED }

    /** The newest version the box gave up installing silently, or null. */
    val gaveUpVersion: String?
        get() = prefs.getString(KEY_GAVE_UP_VERSION, null)

    /** True when Core's startup card should run; see [SilentUpdateRules.fallbackNeeded]. */
    fun fallbackNeeded(installedVersion: String, boxNowMs: Long): Boolean = SilentUpdateRules.fallbackNeeded(
        gaveUpVersion = gaveUpVersion,
        // A record from before the silent block (Live 5.2.1.4 kept only the version and its publish
        // time) says nothing about a block, so it opens no card until the next check rewrites it.
        seenVersion = prefs.getString(KEY_SEEN_VERSION, null)?.takeIf { prefs.contains(KEY_SEEN_HAS_SILENT) },
        seenHasSilent = prefs.getBoolean(KEY_SEEN_HAS_SILENT, false),
        seenAfterBoxMs = seenAfterBoxMs(),
        installedVersion = installedVersion,
        boxNowMs = boxNowMs,
    )

    private fun seenAfterBoxMs(): Long? =
        if (prefs.contains(KEY_SEEN_AFTER)) prefs.getLong(KEY_SEEN_AFTER, 0L) else null

    /**
     * The newest release a check read, whether it had a silent block ([hasSilent]) and the block's
     * `after` moved to the box's own clock ([afterBoxMs], null without a block). Written on every
     * check that gets as far as the block; a later check that finds the block replaces a "no block"
     * record of the same version, and the other way round. An `after` that moved by less than
     * [AFTER_TOLERANCE_MS] is not written: the server's `Date` has whole seconds, so the box-clock
     * value shifts by up to a second on every check.
     */
    fun seen(version: String, hasSilent: Boolean, afterBoxMs: Long?) {
        val same = prefs.getString(KEY_SEEN_VERSION, null) == version &&
            prefs.contains(KEY_SEEN_HAS_SILENT) &&
            prefs.getBoolean(KEY_SEEN_HAS_SILENT, false) == hasSilent &&
            closeEnough(seenAfterBoxMs(), afterBoxMs) &&
            !prefs.contains(KEY_SEEN_PUBLISHED_AT)
        if (same) return
        prefs.edit {
            putString(KEY_SEEN_VERSION, version)
            putBoolean(KEY_SEEN_HAS_SILENT, hasSilent)
            if (afterBoxMs != null) putLong(KEY_SEEN_AFTER, afterBoxMs) else remove(KEY_SEEN_AFTER)
            // The publish time the 7-day rule kept before the silent block existed.
            remove(KEY_SEEN_PUBLISHED_AT)
        }
    }

    /** Both null, or both set and less than [AFTER_TOLERANCE_MS] apart (an overflowing gap is not). */
    private fun closeEnough(stored: Long?, new: Long?): Boolean {
        if (stored == null || new == null) return stored == new
        val moved = runCatching { Math.subtractExact(stored, new) }.getOrNull() ?: return false
        return moved > -AFTER_TOLERANCE_MS && moved < AFTER_TOLERANCE_MS
    }

    /** When a MainActivity last stopped (`SystemClock.elapsedRealtime`), or null when none did since this was kept. */
    val lastStopElapsedMs: Long?
        get() = if (prefs.contains(KEY_LAST_STOP)) prefs.getLong(KEY_LAST_STOP, 0L) else null

    /** A MainActivity stopped at [elapsedMs] on the monotonic clock. */
    fun stopped(elapsedMs: Long) {
        prefs.edit { putLong(KEY_LAST_STOP, elapsedMs) }
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
        const val AFTER_TOLERANCE_MS = 60_000L
        private const val KEY_GAVE_UP_VERSION = "gave_up_version"
        private const val KEY_SEEN_VERSION = "seen_version"
        private const val KEY_SEEN_HAS_SILENT = "seen_has_silent"
        private const val KEY_SEEN_AFTER = "seen_after_box_ms"
        private const val KEY_SEEN_PUBLISHED_AT = "seen_published_at"
        private const val KEY_FAILED_VERSION = "failed_version"
        private const val KEY_FAILED_COUNT = "failed_count"
        private const val KEY_NO_PROFILE_VERSION = "no_profile_version"
        private const val KEY_LAST_STOP = "last_stop_elapsed_ms"

        fun open(context: Context) = SilentUpdatePrefs(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

        fun outcomeOf(status: Int): Outcome = when (status) {
            PackageInstaller.STATUS_SUCCESS -> Outcome.SUCCESS
            PackageInstaller.STATUS_PENDING_USER_ACTION -> Outcome.REFUSED
            else -> Outcome.FAILED
        }
    }
}
