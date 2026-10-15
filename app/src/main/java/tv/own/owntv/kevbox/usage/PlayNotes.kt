// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.usage

import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.own.owntv.kevbox.errors.DeviceId
import tv.own.owntv.kevbox.session.KevBoxAuth
import tv.own.owntv.kevbox.supabase.Fetch
import tv.own.owntv.kevbox.supabase.SupabaseApi

/** At most one play note per member per hour, unless a failure was recorded since (AC-3, AC-4). */
const val PLAY_NOTE_GAP_MS: Long = 60 * 60_000L

/** How often a pending note that failed to send tries again (AC-5). */
const val PLAY_NOTE_TICK_MS: Long = 15 * 60_000L

/**
 * Pure: whether a 30-second play may send a note now. Due when nothing was sent in this process,
 * when a failure was recorded since the last note, when the last note's time is later than [nowMs]
 * (the wall clock moved back), or when [PLAY_NOTE_GAP_MS] or more has passed.
 */
fun playNoteDue(lastSentAtMs: Long?, failureSinceLastSent: Boolean, nowMs: Long): Boolean =
    lastSentAtMs == null ||
        failureSinceLastSent ||
        lastSentAtMs > nowMs ||
        nowMs - lastSentAtMs >= PLAY_NOTE_GAP_MS

// Spelled out: OffsetDateTime.toString() drops ":00" seconds, and the server's live_error_ts
// rejects that text and stores now() instead of the TV's time.
private val PLAYED_AT_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)

/** Pure: the TV clock as `yyyy-MM-dd'T'HH:mm:ss.SSSXXX` in [zone], Locale.ROOT. */
fun playedAtText(epochMs: Long, zone: ZoneId): String =
    PLAYED_AT_FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(zone))

/**
 * One note waiting to go out. Only [playedAt] and [proxy] reach the server, beside the device id
 * and app version. [userId] keeps a note from going out under another member, and
 * [failuresAtBuild] is the failure count when the note was built; neither is sent.
 */
data class PlayNote(val playedAt: String, val proxy: Boolean, val userId: String, val failuresAtBuild: Long)

/**
 * Play notes: when a live channel has played for 30 seconds, the TV tells the server it is
 * watching, at most once an hour per member. A recorded failure lifts the hour limit for the next
 * play, so the server sees the TV watching again soon after an error.
 *
 * One note waits at a time, in memory only; a newer play replaces it. It goes again on the
 * 15-minute tick and on each app open, never in a tight loop. The only log line is an exception's
 * class name.
 */
class PlayNotes(
    private val auth: KevBoxAuth,
    private val api: SupabaseApi,
    private val deviceId: DeviceId,
    private val appVersion: String,
    private val proxyInUse: suspend () -> Boolean,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val zone: () -> ZoneId,
) {
    private val lock = Mutex()
    private var pending: PlayNote? = null
    private var lastSentAtMs: Long? = null

    /** The member [lastSentAtMs] was sent for: a different member's first note is never held back. */
    private var lastSentFor: String? = null

    /** Goes up on every recorded failure; read without the lock so [onFailureRecorded] never waits. */
    private val failures = AtomicLong(0)

    /** The failure count a sent note cleared up to; the flag is set while [failures] is past it. */
    private var failuresCleared = 0L

    /** The 15-minute retry tick. Returns at once; the work runs on [scope]. */
    fun start() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(PLAY_NOTE_TICK_MS)
                guarded { sendPendingNow() }
            }
        }
    }

    /**
     * A live channel just reached 30 seconds. Returns at once. The note's time and failure count are
     * read now, and the proxy flag before waiting on a slow send, so a note never carries a later time.
     */
    fun onPlayed() {
        val at = now()
        val failuresNow = failures.get()
        scope.launch(Dispatchers.IO) {
            guarded {
                val playedAt = playedAtText(at, zone())
                val proxy = proxyFlag()
                played(at, playedAt, proxy, failuresNow)
            }
        }
    }

    /** A failure was recorded: the next 30-second play sends at once, whatever the hour says. */
    fun onFailureRecorded() {
        failures.incrementAndGet()
    }

    /** Each app open: the pending note, if any, goes again. Returns at once. */
    fun onForeground() {
        scope.launch(Dispatchers.IO) { guarded { sendPendingNow() } }
    }

    /** Sends the pending note, if any. Internal so tests drive it. */
    internal suspend fun sendPendingNow() = lock.withLock { sendLocked() }

    private suspend fun played(at: Long, playedAt: String, proxy: Boolean, failuresNow: Long) = lock.withLock {
        val member = auth.userId.value ?: return@withLock
        val last = if (member == lastSentFor) lastSentAtMs else null
        if (!playNoteDue(last, failureSinceLastSent = failuresNow != failuresCleared, nowMs = at)) return@withLock
        pending = PlayNote(playedAt, proxy, member, failuresNow)
        sendLocked()
    }

    /**
     * The app scope has no exception handler, so anything thrown here would crash the app. A note
     * that fails this way is lost; the next play builds another. The class name only reaches the log.
     */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "play note failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun proxyFlag(): Boolean = try {
        proxyInUse()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /** Call with [lock] held. */
    private suspend fun sendLocked() {
        val note = pending ?: return
        // Signed out: the note waits. Another member: it was never theirs, so it goes nowhere.
        val member = auth.userId.value ?: return
        if (note.userId != member) {
            pending = null
            return
        }
        val result = try {
            auth.signedInCall { token ->
                api.recordLivePlay(token, deviceId.get(), appVersion, note.playedAt, note.proxy)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Fetch.Failed
        }
        if (result is Fetch.Ok && result.value.code in HTTP_SUCCESS) {
            pending = null
            lastSentAtMs = now()
            lastSentFor = member
            // A failure recorded after this note was built still lifts the limit for the next play.
            if (failures.get() == note.failuresAtBuild) failuresCleared = note.failuresAtBuild
        }
    }

    private companion object {
        const val LOG_TAG = "KevBoxUsage"
        val HTTP_SUCCESS = 200..299
    }
}
