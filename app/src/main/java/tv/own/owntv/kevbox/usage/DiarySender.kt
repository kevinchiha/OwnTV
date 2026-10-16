// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.usage

import android.util.Log
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import tv.own.owntv.kevbox.errors.DeviceId
import tv.own.owntv.kevbox.session.KevBoxAuth
import tv.own.owntv.kevbox.supabase.ApiResponse
import tv.own.owntv.kevbox.supabase.Fetch
import tv.own.owntv.kevbox.supabase.SupabaseApi

/** How often queued stretches go out (AC-7). */
const val DIARY_SEND_TICK_MS: Long = 15 * 60_000L

/** The most stretches in one record_live_stretches call (AC-7). */
const val DIARY_BATCH_MAX = 200

/** The most calls one trigger (a tick or [DiarySender.sendNow]) makes. */
private const val DIARY_CALLS_PER_TRIGGER = 10

/**
 * Sends the diary queue to record_live_stretches, up to [DIARY_BATCH_MAX] stretches a call. A 2xx
 * answer removes exactly the stretches sent; any failure keeps them all for the next trigger, never
 * a tighter retry (AC-7). Signed out, it sends nothing. One mutex, so overlapping triggers wait and a
 * batch never goes out twice. Nothing here logs but an exception's class name.
 */
class DiarySender(
    private val queue: DiaryQueue,
    private val auth: KevBoxAuth,
    private val api: SupabaseApi,
    private val deviceId: DeviceId,
    private val appVersion: String,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val zone: () -> ZoneId,
) {
    private val lock = Mutex()

    /** The 15-minute tick and the member watch. Returns at once; the work runs on [scope]. */
    fun start() {
        scope.launch(Dispatchers.IO) {
            // A member signing in: stretches queued for anyone else go now, before any send (AC-8).
            guarded {
                auth.userId.collect { userId ->
                    if (userId != null) guarded { queue.dropNotRecordedBy(userId) }
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(DIARY_SEND_TICK_MS)
                guarded { sendRun() }
            }
        }
    }

    /** The app went to the background: send now. Returns at once. */
    fun sendNow() {
        scope.launch(Dispatchers.IO) { guarded { sendRun() } }
    }

    /** Exactly one call, or none when signed out or nothing is queued. Tests drive it. */
    internal suspend fun sendOnce() {
        lock.withLock { sendLocked() }
    }

    /** One trigger: after a 2xx, sends again at once while this member's stretches remain. */
    private suspend fun sendRun() = lock.withLock {
        repeat(DIARY_CALLS_PER_TRIGGER) {
            if (!sendLocked()) return@withLock
        }
    }

    /** Call with [lock] held. True when the call answered 2xx and more of this member's stretches wait. */
    private suspend fun sendLocked(): Boolean {
        val member = auth.userId.value ?: return false
        queue.dropNotRecordedBy(member)
        val batch = queue.oldest(DIARY_BATCH_MAX, member)
        if (batch.isEmpty()) return false
        val sent = try {
            val zoneNow = zone()
            val entries = JSONArray().apply { batch.forEach { put(it.toEntry(zoneNow)) } }
            val tvNow = playedAtText(now(), zoneNow)
            val result = auth.signedInCall { token ->
                // The token is read after the batch was picked: a sign-out and another member's
                // sign-in in between would hand over theirs. Then nothing goes out (AC-8).
                if (auth.userId.value != member) return@signedInCall ApiResponse.NoNetwork
                api.recordLiveStretches(token, deviceId.get(), appVersion, tvNow, entries)
            }
            result is Fetch.Ok && result.value.code in HTTP_SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!sent) return false
        queue.remove(batch.map { it.id })
        return queue.oldest(1, member).isNotEmpty()
    }

    /** The app scope has no exception handler: anything thrown here would crash the app. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "diary send failed: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val LOG_TAG = "KevBoxUsage"
        val HTTP_SUCCESS = 200..299
    }
}
