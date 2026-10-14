// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.errors

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import tv.own.owntv.kevbox.session.KevBoxAuth
import tv.own.owntv.kevbox.supabase.ApiResponse
import tv.own.owntv.kevbox.supabase.Fetch
import tv.own.owntv.kevbox.supabase.SupabaseApi

/** What one batch send came to, as the queue settles it. */
sealed interface SendOutcome {
    data object Sent : SendOutcome
    data object Retry : SendOutcome
    data object Rejected : SendOutcome
    data object SignedOut : SendOutcome
}

/**
 * The server's answer to a batch. Some 4xx answers are never the batch's fault, so they retry: 401
 * (the session), 404 (the function is not on the server yet, as during a rollout), 408 (timeout),
 * 425 (too early) and 429 (rate limit). Any other 4xx means the server refused the entries (AC-17).
 */
fun outcomeOf(result: Fetch<ApiResponse.Http>): SendOutcome = when (result) {
    is Fetch.Ok -> when (result.value.code) {
        in 200..299 -> SendOutcome.Sent
        in RETRY_4XX -> SendOutcome.Retry
        in 400..499 -> SendOutcome.Rejected
        else -> SendOutcome.Retry
    }
    Fetch.Failed -> SendOutcome.Retry
    Fetch.SignedOut, Fetch.SessionRejected -> SendOutcome.SignedOut
}

private val RETRY_4XX = setOf(401, 404, 408, 425, 429)

/** When batches go out (AC-17). Pure functions: the clock comes in as a parameter. */
object SendPolicy {
    const val FIRST_WAIT_MS = 60_000L
    const val SEND_AT_COUNT = 20
    const val BATCH_MAX = 50
    const val MAX_REJECTIONS = 3
    const val QUEUE_MAX = 500
    const val MERGE_WINDOW_MS = 300_000L

    /** After a refused session: wait this long, or until the member changes (AC-18). */
    const val SIGNED_OUT_PAUSE_MS = 15 * 60_000L

    private const val MINUTE_MS = 60_000L

    /** 1 min, 5 min, 15 min, then 30 min for every failure after that. */
    fun backoffMs(failures: Int): Long = when {
        failures <= 1 -> MINUTE_MS
        failures == 2 -> 5 * MINUTE_MS
        failures == 3 -> 15 * MINUTE_MS
        else -> 30 * MINUTE_MS
    }

    /**
     * When the next batch should go out, by the same clock as the times passed in; null with nothing
     * unsent. A due time at or before now means send now: with [SEND_AT_COUNT] waiting it answers
     * [oldestUnsentAtMs], which is already past.
     */
    fun nextSendAtMs(oldestUnsentAtMs: Long?, unsentCount: Int, failures: Int, lastFailureAtMs: Long?): Long? {
        if (oldestUnsentAtMs == null || unsentCount <= 0) return null
        if (failures > 0 && lastFailureAtMs != null) return lastFailureAtMs + backoffMs(failures)
        if (unsentCount >= SEND_AT_COUNT) return oldestUnsentAtMs
        return oldestUnsentAtMs + FIRST_WAIT_MS
    }

    /**
     * [nextSendAtMs] as of [nowMs], where a time later than now counts as now. The wall clock can
     * move back (a network time fix on a TV that booted with a wrong date); without this, a failure
     * or an entry stamped before the fix would hold every send until the clock caught up again.
     */
    fun planAtMs(nowMs: Long, oldestUnsentAtMs: Long?, unsentCount: Int, failures: Int, lastFailureAtMs: Long?): Long? =
        nextSendAtMs(oldestUnsentAtMs?.coerceAtMost(nowMs), unsentCount, failures, lastFailureAtMs?.coerceAtMost(nowMs))

    /**
     * Whether a signed-out pause set to end at [pausedUntilMs] is over at [nowMs]. A pause ending
     * further off than [SIGNED_OUT_PAUSE_MS] means the clock moved back, so it is over too.
     */
    fun pauseOver(nowMs: Long, pausedUntilMs: Long): Boolean =
        nowMs >= pausedUntilMs || pausedUntilMs - nowMs > SIGNED_OUT_PAUSE_MS
}

/**
 * Sends the queue to record_live_errors in batches, one call per batch, on one background thread.
 * With no member signed in it sends nothing and the entries wait (AC-18). Nothing here logs: the
 * entries, the token and the server's answer stay off logcat.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ErrorSender(
    private val queue: ErrorQueue,
    private val auth: KevBoxAuth,
    private val api: SupabaseApi,
    private val deviceId: DeviceId,
    private val appVersion: String,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    // Only the loop on [dispatcher] touches these.
    private var failures = 0
    private var lastFailureAtMs: Long? = null
    private var pausedUntilMs: Long? = null
    private var pausedFor: String? = null

    /** Returns at once; the loop runs on [scope], on one background thread. */
    fun start() {
        scope.launch(dispatcher) {
            launch { auth.userId.collect { wake.trySend(Unit) } }
            loop()
        }
    }

    /** An entry was added: plan the next send again. Safe from any thread. */
    fun kick() {
        wake.trySend(Unit)
    }

    private suspend fun CoroutineScope.loop() {
        while (isActive) {
            val userId = auth.userId.value
            if (userId == null) {
                // Signed out: a pause for a refused session ends here, so the same member signing
                // back in sends at once instead of waiting it out.
                clearPause()
                wake.receive()
                continue
            }
            val paused = pausedUntilMs
            if (paused != null) {
                if (pausedFor == userId && !SendPolicy.pauseOver(now(), paused)) {
                    sleep(paused - now())
                    continue
                }
                // The pause ran out or a different member signed in: start afresh.
                clearPause()
            }
            queue.dropNotRecordedBy(userId)
            val dueAt = SendPolicy.planAtMs(now(), queue.oldestUnsentAtMs(), queue.unsentCount(), failures, lastFailureAtMs)
            if (dueAt == null) {
                wake.receive()
                continue
            }
            val wait = dueAt - now()
            if (wait > 0) {
                sleep(wait)
                continue
            }
            sendBatch(userId)
        }
    }

    private fun clearPause() {
        if (pausedUntilMs == null) return
        pausedUntilMs = null
        pausedFor = null
        failures = 0
        lastFailureAtMs = null
    }

    /** Waits [ms], or less when a kick or a sign-in change arrives. */
    private suspend fun sleep(ms: Long) {
        withTimeoutOrNull(ms) { wake.receive() }
    }

    private suspend fun sendBatch(userId: String) {
        val batch = queue.takeBatch(SendPolicy.BATCH_MAX)
        if (batch.isEmpty()) return
        val ids = batch.map { it.id }
        val entries = JSONArray().apply { batch.forEach { put(it.entry.toJson()) } }
        val result = try {
            auth.signedInCall { token -> api.recordLiveErrors(token, deviceId.get(), appVersion, entries) }
        } catch (e: CancellationException) {
            queue.settle(ids, SendOutcome.Retry)
            throw e
        } catch (_: Exception) {
            Fetch.Failed
        }
        val outcome = outcomeOf(result)
        queue.settle(ids, outcome)
        when (outcome) {
            SendOutcome.Sent -> {
                failures = 0
                lastFailureAtMs = null
            }
            SendOutcome.Retry, SendOutcome.Rejected -> {
                failures++
                lastFailureAtMs = now()
            }
            SendOutcome.SignedOut -> {
                pausedUntilMs = now() + SendPolicy.SIGNED_OUT_PAUSE_MS
                pausedFor = userId
            }
        }
    }
}
