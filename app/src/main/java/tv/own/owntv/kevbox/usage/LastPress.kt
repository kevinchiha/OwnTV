// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.usage

/**
 * The latest press, by the wall clock; the app start counts as one. Safe from the main thread.
 *
 * MainActivity.dispatchKeyEvent calls [press] on every key down its window gets, and DiaryWatcher
 * calls it when MainActivity starts (AC-5). Building one counts as the first press, since the process
 * starts with the app.
 */
class LastPress(private val now: () -> Long) {
    @Volatile
    private var lastMs: Long = now()

    fun press() {
        lastMs = now()
    }

    fun at(): Long = lastMs
}
