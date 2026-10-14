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
 * True when a check-in should go out now: signed in, and none sent yet for this member in this
 * process, or the last one was [CHECKIN_INTERVAL_MS] or more ago. A last time later than [nowMs]
 * means the wall clock moved back, so it counts as due rather than stalling until the clock catches
 * up. Both times are wall-clock milliseconds.
 */
fun checkinDue(lastSentAtMs: Long?, nowMs: Long, signedIn: Boolean): Boolean =
    signedIn && (lastSentAtMs == null || lastSentAtMs > nowMs || nowMs - lastSentAtMs >= CHECKIN_INTERVAL_MS)

/**
 * The usage check-in: at start, whenever a member signs in, and every 6 hours, a signed-in TV
 * sends its device id, app version, model and Android version. A failed call is not retried; the
 * next due time sends again. Nothing here logs.
 *
 * A short tick, not a 6-hour delay, drives the timer: the last-sent time is only set once the
 * server answers, so a delay as long as the interval would always land just short of it and send
 * every 12 hours instead. Each tick asks [checkinDue], which compares wall-clock times, so time the
 * TV spends asleep counts too.
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

    /** The member [lastSentAtMs] was sent for: a different member is due at once. */
    private var lastSentFor: String? = null

    /** Returns at once; the work runs on [scope]. */
    fun start() {
        scope.launch(Dispatchers.IO) {
            // A StateFlow hands over its current value first, so this also covers "at start".
            launch { auth.userId.collect { if (it != null) checkInIfDue() } }
            while (isActive) {
                delay(TICK_MS)
                checkInIfDue()
            }
        }
    }

    /** Each app open: sends when a check-in is due. */
    fun onForeground() {
        scope.launch(Dispatchers.IO) { checkInIfDue() }
    }

    /** One check: sends the check-in when [checkinDue] says so. Internal so tests drive the clock. */
    internal suspend fun checkInIfDue() = lock.withLock {
        val member = auth.userId.value
        val last = if (member != null && member == lastSentFor) lastSentAtMs else null
        if (!checkinDue(last, now(), signedIn = member != null)) return@withLock
        val result = try {
            auth.signedInCall { token ->
                api.recordLiveCheckin(token, deviceId.get(), appVersion, TvInfo.model, TvInfo.android)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Fetch.Failed
        }
        if (result is Fetch.Ok && result.value.code in HTTP_SUCCESS) {
            lastSentAtMs = now()
            lastSentFor = member
        }
    }

    private companion object {
        val HTTP_SUCCESS = 200..299

        /** How often the timer asks [checkinDue]; a check-in goes out at most this late. */
        const val TICK_MS = 15 * 60_000L
    }
}
