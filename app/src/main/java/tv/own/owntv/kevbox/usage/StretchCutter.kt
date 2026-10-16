// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.usage

import tv.own.owntv.core.parser.XtEpgEntry
import tv.own.owntv.kevbox.errors.Scrubber

/**
 * How long play may stop (a rebuffer) and still count as the same stretch. The Live engine leaves
 * `State.PLAYING` on every rebuffer; without this, a box viewer on a weak line would make stretches
 * under 120 s that the server counts as browsing. It never moves a stretch's end past its last play.
 */
const val STALL_GRACE_MS: Long = 10_000L

/** How often the open stretch is saved, so a killed app loses at most this much (AC-6). */
const val OPEN_SAVE_MS: Long = 60_000L

/**
 * One look at Live TV for the diary. [failed] and [catchup] stay apart from [playing]: a failure or
 * catch-up closes a stretch at once, a stop gets the stall grace. [streamUrl] tells channels apart
 * and gives the host; it never leaves the TV.
 */
data class DiaryLook(
    val playing: Boolean,
    val failed: Boolean,
    val catchup: Boolean,
    val channelKey: String?,
    val inBox: Boolean,
    val channel: String?,
    val category: String?,
    val streamUrl: String?,
)

/**
 * Pure. Feed every look and tick; returns the stretches that finished.
 *
 * A stretch opens when a look plays live (playing, no failure, not catch-up, a channel) and closes
 * on a channel change, a failure, catch-up, the background, a box/full-screen switch, its
 * programme's end, or a title appearing for a stretch that opened blank (AC-2). Where play goes on,
 * the next one opens at that same moment. A stop is held as a stall: play again on the same channel
 * and surface within [STALL_GRACE_MS] continues the stretch, anything else closes it at the stall's
 * start, with the last press seen then. Stretches under [STRETCH_MIN_MS] are never returned (AC-3).
 *
 * Every time here is one clock, the caller's; [newId] names each stretch.
 */
class StretchCutter(private val newId: () -> String) {
    private class Open(
        val id: String,
        val startedAtMs: Long,
        val channelKey: String,
        val inBox: Boolean,
        val channel: String?,
        val category: String?,
        val programme: String?,
        val programmeStopMs: Long?,
        val providerHost: String?,
        val proxy: Boolean,
    )

    private var open: Open? = null
    private var stalledAtMs: Long? = null
    private var stallPressMs: Long = 0L

    /** True while a stretch is open, stalled or not. */
    val isOpen: Boolean get() = open != null

    /** The open stretch's id, or null. */
    val openId: String? get() = open?.id

    fun onLook(look: DiaryLook, programme: XtEpgEntry?, nowMs: Long, lastPressMs: Long, proxy: Boolean): List<Stretch> {
        val out = ArrayList<Stretch>(1)
        val plays = look.playing && !look.failed && !look.catchup && look.channelKey != null
        val current = open
        if (current != null) {
            val stall = stalledAtMs
            when {
                // Not a stall: a failure or catch-up closes at once, with no grace.
                look.failed || look.catchup -> close(out, nowMs, lastPressMs)
                stall != null -> {
                    val back = plays && sameSpot(current, look) && nowMs - stall <= STALL_GRACE_MS
                    if (back) {
                        stalledAtMs = null
                    } else if (plays || nowMs - stall >= STALL_GRACE_MS) {
                        close(out, nowMs, lastPressMs)
                    } else {
                        return out
                    }
                }
                !plays -> {
                    stalledAtMs = nowMs
                    stallPressMs = lastPressMs
                    return out
                }
                !sameSpot(current, look) -> close(out, nowMs, lastPressMs)
            }
            val still = open
            if (still != null) {
                val stop = still.programmeStopMs
                val ended = stop != null && nowMs >= stop
                val titled = still.programme == null && titleOf(programme, nowMs) != null
                if (ended || titled) close(out, nowMs, lastPressMs)
            }
        }
        if (open == null && plays) openAt(look, programme, nowMs, proxy)
        return out
    }

    /** The app went to the background: the open stretch closes now, or at its stall's start. */
    fun onBackground(nowMs: Long, lastPressMs: Long): List<Stretch> {
        val out = ArrayList<Stretch>(1)
        close(out, nowMs, lastPressMs)
        return out
    }

    /** The open stretch as if it closed now (at its stall's start when stalled); it stays open. */
    fun open(nowMs: Long, lastPressMs: Long): Stretch? {
        val current = open ?: return null
        val stall = stalledAtMs
        val end = stall ?: nowMs
        val press = if (stall != null) stallPressMs else lastPressMs
        if (end - current.startedAtMs < STRETCH_MIN_MS) return null
        return current.finished(end, press)
    }

    private fun sameSpot(current: Open, look: DiaryLook) =
        look.channelKey == current.channelKey && look.inBox == current.inBox

    private fun openAt(look: DiaryLook, programme: XtEpgEntry?, nowMs: Long, proxy: Boolean) {
        val title = titleOf(programme, nowMs)
        open = Open(
            id = newId(),
            startedAtMs = nowMs,
            channelKey = look.channelKey ?: return,
            inBox = look.inBox,
            channel = look.channel,
            category = look.category,
            programme = title,
            programmeStopMs = if (title != null) programme?.stopMs else null,
            providerHost = Scrubber.host(look.streamUrl),
            proxy = proxy,
        )
        stalledAtMs = null
    }

    /** Closes the open stretch at [nowMs], or at its stall's start with the press seen then. */
    private fun close(out: MutableList<Stretch>, nowMs: Long, lastPressMs: Long) {
        val current = open ?: return
        val stall = stalledAtMs
        val end = stall ?: nowMs
        val press = if (stall != null) stallPressMs else lastPressMs
        open = null
        stalledAtMs = null
        if (end - current.startedAtMs >= STRETCH_MIN_MS) out += current.finished(end, press)
    }

    private fun Open.finished(endedAtMs: Long, pressMs: Long) = Stretch(
        id = id,
        startedAtMs = startedAtMs,
        endedAtMs = endedAtMs,
        inBox = inBox,
        lastPressAtMs = minOf(pressMs, endedAtMs),
        channel = channel,
        category = category,
        programme = programme,
        providerHost = providerHost,
        proxy = proxy,
    )

    /** The entry's title when it is airing at [nowMs] and not blank (AC-4); otherwise null. */
    private fun titleOf(entry: XtEpgEntry?, nowMs: Long): String? =
        entry?.takeIf { it.stopMs > nowMs }?.title?.trim()?.takeIf { it.isNotEmpty() }
}
