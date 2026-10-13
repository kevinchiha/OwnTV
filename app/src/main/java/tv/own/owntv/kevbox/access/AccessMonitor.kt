// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.access

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.own.owntv.kevbox.supabase.AccessVerdict
import tv.own.owntv.kevbox.supabase.Fetch

/** Remembers the last verdict the server gave, so a restart without network keeps the lock. */
interface VerdictPrefs {
    var last: AccessVerdict?
}

/** [VerdictPrefs] in [prefs], which the caller opens as the [PREFS_NAME] file. */
class PrefsVerdictPrefs(private val prefs: SharedPreferences) : VerdictPrefs {
    override var last: AccessVerdict?
        get() = prefs.getString(VERDICT_KEY, null)?.let { name ->
            AccessVerdict.entries.firstOrNull { it.name == name }
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(VERDICT_KEY) else putString(VERDICT_KEY, value.name)
            }.commit()
        }

    companion object {
        /** SharedPreferences file that holds the verdict. */
        const val PREFS_NAME = "kevbox_live_access"

        private const val VERDICT_KEY = "verdict"
    }
}

/**
 * Turns get_access_verdict() into a lock flag. Only a real answer moves it: a failed call, a
 * rejected session or a signed-out TV keep the last state. An unlock needs no new sign-in.
 */
class AccessMonitor(
    private val fetch: suspend () -> Fetch<AccessVerdict>,
    private val prefs: VerdictPrefs,
) {
    private val _locked = MutableStateFlow(prefs.last == AccessVerdict.LOCKED)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    suspend fun check() {
        val result = fetch()
        if (result !is Fetch.Ok) return
        prefs.last = result.value
        _locked.value = result.value == AccessVerdict.LOCKED
    }

    /** Sign-out: forget the saved verdict and unlock. */
    fun reset() {
        prefs.last = null
        _locked.value = false
    }
}
