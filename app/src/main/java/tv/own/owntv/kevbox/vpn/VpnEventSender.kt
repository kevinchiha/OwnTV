// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.vpn

import android.util.Log
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import tv.own.owntv.kevbox.errors.DeviceId
import tv.own.owntv.kevbox.session.KevBoxAuth
import tv.own.owntv.kevbox.supabase.ApiResponse
import tv.own.owntv.kevbox.supabase.Fetch
import tv.own.owntv.kevbox.supabase.SupabaseApi

/** The most events in one record_live_vpn_events call. */
const val VPN_BATCH_MAX = 50

/** The most calls one trigger (a [VpnEventSender.kick] or a retry) makes. */
private const val VPN_CALLS_PER_TRIGGER = 10

private const val MINUTE_MS = 60_000L

/** The waits after the first, second, third and fourth failed trigger in a row; the last repeats. */
private val VPN_RETRY_STEPS_MS = listOf(1 * MINUTE_MS, 2 * MINUTE_MS, 5 * MINUTE_MS, 15 * MINUTE_MS)

/**
 * Pure: how long the retry tick waits before trying again, after [failures] failed triggers in a
 * row: 1, 2, 5 and 15 minutes, then every 15 minutes. With no failure yet (events left from an
 * earlier run, or queued while signed out) it waits the first step.
 */
fun vpnRetryDelayMs(failures: Int): Long =
    VPN_RETRY_STEPS_MS[(failures - 1).coerceIn(0, VPN_RETRY_STEPS_MS.lastIndex)]

/**
 * Sends the VPN event queue to record_live_vpn_events, up to [VPN_BATCH_MAX] events a call, oldest
 * first. A 2xx removes exactly the events sent; any other answer, no network, a refused session or a
 * throw keeps them all for the next try (AC-35). Signed out, it sends nothing. It never waits for the
 * tunnel: an event recorded while the tunnel is broken fails to send and goes out on a later try.
 * A sign-in sends at once, so events waiting for a member go out without waiting for some later
 * VPN change.
 *
 * One mutex, so overlapping triggers wait and a batch never goes out twice. Nothing here logs but an
 * exception's class name. [now] matches the other senders' shape; each event already carries its
 * own time, so nothing here reads the clock.
 */
class VpnEventSender(
    private val queue: VpnEventQueue,
    private val auth: KevBoxAuth,
    private val api: SupabaseApi,
    private val deviceId: DeviceId,
    private val appVersion: String,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val zone: () -> ZoneId,
) {
    private enum class Outcome { NOTHING, SENT_MORE, SENT_ALL, FAILED }

    private val lock = Mutex()

    /** Failed triggers in a row; set under [lock]. */
    @Volatile
    private var failures = 0

    /** Tells the retry tick that a kick ended, so it measures its wait from the latest outcome. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** The retry tick, and a send on every sign-in. Returns at once; the work runs on [scope]. */
    fun start() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                // Each pass guarded on its own: one that throws ends that pass, never the retries.
                if (!guarded { retryPass() }) delay(vpnRetryDelayMs(failures))
            }
        }
        scope.launch(Dispatchers.IO) {
            // Signed out, the tick waits with nothing to send: a sign-in is what wakes it.
            guarded { auth.userId.filterNotNull().collect { kick() } }
        }
    }

    /** Waits for the retry time or a kick's end, then sends when the time came first. */
    private suspend fun retryPass() {
        val wait = if (hasPending()) vpnRetryDelayMs(failures) else null
        val woken = if (wait == null) {
            wake.receive()
        } else {
            withTimeoutOrNull(wait) { wake.receive() }
        }
        if (woken == null) trigger()
    }

    /** Sends now: Task 4 calls it after every add and when the tunnel state settles. Returns at once. */
    fun kick() {
        scope.launch(Dispatchers.IO) {
            guarded { trigger() }
            wake.trySend(Unit)
        }
    }

    /** Exactly one call, or none when signed out or nothing is queued. Tests drive it. */
    internal suspend fun sendOnce() {
        lock.withLock { record(sendLocked()) }
    }

    /** One trigger: after a 2xx, sends again at once while this member's events remain. */
    private suspend fun trigger() = lock.withLock {
        repeat(VPN_CALLS_PER_TRIGGER) {
            if (record(sendLocked()) != Outcome.SENT_MORE) return@withLock
        }
    }

    /** Call with [lock] held: keeps the failure count that sets the retry wait. */
    private fun record(outcome: Outcome): Outcome {
        when (outcome) {
            Outcome.FAILED -> failures++
            Outcome.SENT_MORE, Outcome.SENT_ALL -> failures = 0
            Outcome.NOTHING -> Unit
        }
        return outcome
    }

    private fun hasPending(): Boolean {
        val member = auth.userId.value ?: return false
        return queue.oldest(1, member).isNotEmpty()
    }

    /** Call with [lock] held. */
    private suspend fun sendLocked(): Outcome {
        val member = auth.userId.value ?: return Outcome.NOTHING
        queue.dropNotRecordedBy(member)
        val batch = queue.oldest(VPN_BATCH_MAX, member)
        if (batch.isEmpty()) return Outcome.NOTHING
        val sent = try {
            val zoneNow = zone()
            val entries = JSONArray().apply { batch.forEach { put(it.toEntry(zoneNow)) } }
            val result = auth.signedInCall { token ->
                // The token is read after the batch was picked: a sign-out and another member's
                // sign-in in between would hand over theirs. Then nothing goes out.
                if (auth.userId.value != member) return@signedInCall ApiResponse.NoNetwork
                api.recordLiveVpnEvents(token, deviceId.get(), appVersion, entries)
            }
            result is Fetch.Ok && result.value.code in HTTP_SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!sent) return Outcome.FAILED
        queue.remove(batch.map { it.id })
        return if (queue.oldest(1, member).isNotEmpty()) Outcome.SENT_MORE else Outcome.SENT_ALL
    }

    /** The app scope has no exception handler: anything thrown here would crash the app. False when [block] threw. */
    private suspend fun guarded(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(LOG_TAG, "vpn event send failed: ${e.javaClass.simpleName}")
        false
    }

    private companion object {
        const val LOG_TAG = "KevBoxVpn"
        val HTTP_SUCCESS = 200..299
    }
}
