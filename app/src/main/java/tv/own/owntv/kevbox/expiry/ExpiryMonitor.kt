// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.expiry

import android.content.SharedPreferences
import java.time.LocalDate
import kotlin.math.ceil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.own.owntv.core.repository.SourceTestResult

/** The subscription popup the TV should show, if any. */
sealed interface ExpiryPopup {
    data object Expired : ExpiryPopup
    data class Expiring(val days: Int) : ExpiryPopup
}

/** Remembers the calendar day the member last saw the "expiring" popup. */
interface ExpiryPrefs {
    /** ISO date (yyyy-MM-dd), or null when the popup never showed. */
    var lastWarnedDay: String?
}

/** [ExpiryPrefs] in [prefs], which the caller opens as the [PREFS_NAME] file. */
class PrefsExpiryPrefs(private val prefs: SharedPreferences) : ExpiryPrefs {
    override var lastWarnedDay: String?
        get() = prefs.getString(LAST_WARNED_DAY_KEY, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(LAST_WARNED_DAY_KEY) else putString(LAST_WARNED_DAY_KEY, value)
            }.apply()
        }

    companion object {
        /** SharedPreferences file that holds the day. */
        const val PREFS_NAME = "kevbox_live_expiry"

        private const val LAST_WARNED_DAY_KEY = "last_warned_day"
    }
}

private const val DAY_MS = 86_400_000L
private const val WARN_DAYS = 7
private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
private const val REUSE_WINDOW_MS = 10 * 60 * 1000L

/** Whole days left, rounded up, clamped to 1..7: 36 hours gives 2, one hour gives 1. */
fun daysLeftRoundedUp(expiryMs: Long, nowMs: Long): Int {
    val days = ceil((expiryMs - nowMs).toDouble() / DAY_MS).toInt()
    return days.coerceIn(1, WARN_DAYS)
}

/**
 * Asks the provider about the line's subscription through Core's source test and decides which
 * popup and player message to show. When to call it is the caller's business; the rules live here.
 *
 * Only `Ok` and `Expired` move the state. An outage (`Unreachable`), a rejected password
 * (`AuthFailed`) or a vanished source (`null`) keep whatever the last answer said, so a paying
 * member is never told to renew because the panel was down. Nothing about "expired" is stored:
 * each app start tests afresh, so a renewal clears both messages at the first `Ok`.
 */
class ExpiryMonitor(
    private val test: suspend (sourceId: Long) -> SourceTestResult?,
    private val now: () -> Long,
    private val today: () -> LocalDate,
    private val prefs: ExpiryPrefs,
) {
    private val _popup = MutableStateFlow<ExpiryPopup?>(null)
    val popup: StateFlow<ExpiryPopup?> = _popup.asStateFlow()

    private val _channelShowsExpired = MutableStateFlow(false)

    /** True while the player should say the subscription ran out instead of a generic error. */
    val channelShowsExpired: StateFlow<Boolean> = _channelShowsExpired.asStateFlow()

    /** The last `Ok` or `Expired` answer, tied to the source it was about. */
    private data class Decisive(val sourceId: Long, val result: SourceTestResult, val atMs: Long)

    private val mutex = Mutex()
    private var lastDecisive: Decisive? = null
    private var lastTestSourceId: Long? = null
    private var lastTestAtMs: Long? = null

    /** The member pressed OK on the Expired popup during this app start. */
    private var expiredDismissed = false

    suspend fun checkAtStart(sourceId: Long?) {
        if (sourceId == null) return
        mutex.withLock { runTest(sourceId) }
    }

    /** A timer tick. Called again with [playing] false when playback stops, to run a check held back while playing. */
    suspend fun tick(sourceId: Long?, playing: Boolean) {
        if (sourceId == null) return
        mutex.withLock {
            val lastAt = lastTestAtMs
            val due = lastAt == null || lastTestSourceId != sourceId || now() - lastAt >= CHECK_INTERVAL_MS
            if (due && !playing) runTest(sourceId)
        }
    }

    suspend fun onChannelFailed(sourceId: Long?) {
        if (sourceId == null) return
        mutex.withLock {
            val last = lastDecisive
            if (last != null && last.sourceId == sourceId && now() - last.atMs < REUSE_WINDOW_MS) {
                apply(last.result)
            } else {
                runTest(sourceId)
            }
        }
    }

    fun dismissPopup() {
        if (_popup.value == ExpiryPopup.Expired) expiredDismissed = true
        _popup.value = null
    }

    /** Sign-out, or the visible source or its credentials changed: forget everything about the old line. */
    fun reset() {
        lastDecisive = null
        lastTestSourceId = null
        lastTestAtMs = null
        expiredDismissed = false
        _popup.value = null
        _channelShowsExpired.value = false
    }

    private suspend fun runTest(sourceId: Long) {
        val result = test(sourceId)
        val at = now()
        lastTestSourceId = sourceId
        lastTestAtMs = at
        when (result) {
            is SourceTestResult.Ok, is SourceTestResult.Expired -> {
                lastDecisive = Decisive(sourceId, result, at)
                apply(result)
            }
            SourceTestResult.AuthFailed, is SourceTestResult.Unreachable, null -> Unit
        }
    }

    private fun apply(result: SourceTestResult) {
        when (result) {
            is SourceTestResult.Expired -> {
                _channelShowsExpired.value = true
                if (!expiredDismissed) _popup.value = ExpiryPopup.Expired
            }
            is SourceTestResult.Ok -> {
                _channelShowsExpired.value = false
                if (_popup.value == ExpiryPopup.Expired) _popup.value = null
                val expiryMs = result.expiryMs ?: return
                val nowMs = now()
                if (expiryMs - nowMs >= WARN_DAYS * DAY_MS) return
                val day = today().toString()
                if (prefs.lastWarnedDay == day) return
                _popup.value = ExpiryPopup.Expiring(daysLeftRoundedUp(expiryMs, nowMs))
                prefs.lastWarnedDay = day
            }
            else -> Unit
        }
    }
}
