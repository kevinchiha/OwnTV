// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.usage

/**
 * The latest press, by the wall clock ([at]) and by the monotonic clock ([elapsedAt]); the app start
 * counts as one. Safe from the main thread.
 *
 * MainActivity.dispatchKeyEvent calls [press] on every key down its window gets, and DiaryWatcher
 * calls it when MainActivity starts (AC-5). Building one counts as the first press, since the process
 * starts with the app. [elapsed] comes first so `LastPress { wallClock }` still sets [now].
 */
class LastPress(
    private val elapsed: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val now: () -> Long,
) {
    @Volatile
    private var lastMs: Long = now()

    @Volatile
    private var lastElapsedMs: Long = elapsed()

    fun press() {
        lastMs = now()
        lastElapsedMs = elapsed()
    }

    fun at(): Long = lastMs

    /** The latest press on the [elapsed] clock, for rules a wall-clock jump must not fool. */
    fun elapsedAt(): Long = lastElapsedMs
}
