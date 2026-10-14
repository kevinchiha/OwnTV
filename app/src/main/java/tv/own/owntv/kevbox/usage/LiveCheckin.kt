// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.usage

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.own.owntv.kevbox.errors.DeviceId
import tv.own.owntv.kevbox.errors.TvInfo
import tv.own.owntv.kevbox.session.KevBoxAuth
import tv.own.owntv.kevbox.supabase.Fetch
import tv.own.owntv.kevbox.supabase.SupabaseApi

/** How often a signed-in TV tells the server it is in use (AC-36). */
const val CHECKIN_INTERVAL_MS: Long = 6 * 60 * 60 * 1000L

/**
 * True when a check-in should go out now: signed in, and none sent yet in this process or the last
 * one was [CHECKIN_INTERVAL_MS] or more ago. Both times are wall-clock milliseconds.
 */
fun checkinDue(lastSentAtMs: Long?, nowMs: Long, signedIn: Boolean): Boolean =
    signedIn && (lastSentAtMs == null || nowMs - lastSentAtMs >= CHECKIN_INTERVAL_MS)

/**
 * The usage check-in: at start, whenever a member signs in, and every 6 hours, a signed-in TV
 * sends its device id, app version, model and Android version. A failed call is not retried; the
 * next due time sends again. Nothing here logs.
 */
class LiveCheckin(
    private val auth: KevBoxAuth,
    private val api: SupabaseApi,
    private val deviceId: DeviceId,
    private val appVersion: String,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {
    private val lock = Mutex()
    private var lastSentAtMs: Long? = null

    /** Returns at once; the work runs on [scope]. */
    fun start() {
        scope.launch(Dispatchers.IO) {
            // A StateFlow hands over its current value first, so this also covers "at start".
            launch { auth.userId.collect { if (it != null) checkInIfDue() } }
            while (isActive) {
                delay(CHECKIN_INTERVAL_MS)
                checkInIfDue()
            }
        }
    }

    /**
     * Each app open. The 6-hour delay stops while the TV sleeps, so this compares wall-clock times
     * and sends when a check-in is due.
     */
    fun onForeground() {
        scope.launch(Dispatchers.IO) { checkInIfDue() }
    }

    private suspend fun checkInIfDue() = lock.withLock {
        if (!checkinDue(lastSentAtMs, now(), signedIn = auth.userId.value != null)) return@withLock
        val result = try {
            auth.signedInCall { token ->
                api.recordLiveCheckin(token, deviceId.get(), appVersion, TvInfo.model, TvInfo.android)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Fetch.Failed
        }
        if (result is Fetch.Ok && result.value.code in HTTP_SUCCESS) lastSentAtMs = now()
    }

    private companion object {
        val HTTP_SUCCESS = 200..299
    }
}
