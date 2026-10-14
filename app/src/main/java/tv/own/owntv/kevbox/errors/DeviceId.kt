// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.errors

import android.content.SharedPreferences
import android.os.Build
import java.util.UUID

/**
 * This TV's id in error reports and usage check-ins: a random UUID made on first use and kept in its
 * own private prefs file. Never ANDROID_ID, so it says nothing about the box outside this app, and
 * clearing the app's data makes a new one.
 */
class DeviceId(private val prefs: SharedPreferences) {
    private val lock = Any()

    /** The id; the first call makes and stores it. One lock, so two first callers get the same id. */
    fun get(): String = synchronized(lock) {
        prefs.getString(KEY, null)?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY, it).apply() }
    }

    companion object {
        const val PREFS_NAME = "kevbox_device"
        private const val KEY = "device_id"
    }
}

/** The TV model and Android version, cut to the lengths the server stores; empty when the build has none. */
object TvInfo {
    private const val MODEL_MAX = 64
    private const val ANDROID_MAX = 16

    val model: String get() = Build.MODEL.orEmpty().take(MODEL_MAX)
    val android: String get() = Build.VERSION.RELEASE.orEmpty().take(ANDROID_MAX)
}
