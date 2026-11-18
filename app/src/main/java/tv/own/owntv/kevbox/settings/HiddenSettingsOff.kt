// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import tv.own.owntv.core.settings.SettingsRepository

/**
 * Keeps off the upstream v5.2.2 switches KevBox Live hides (Kevin, 2026-11-18): Live TV auto frame rate
 * and picture-in-picture when leaving the app. Hidden alone is not enough: until Core migrates it, the
 * Live TV frame-rate switch reads the old single Auto frame rate switch, which members could turn on
 * before, and a hidden switch left on could never be turned off. Watching (not a one-off write) also
 * catches a later migration or restore that turns one back on.
 */
class HiddenSettingsOff(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    @Volatile private var started = false

    /** Once, from OwnTVApp. */
    fun start() {
        if (started) return
        started = true
        keepOff(settings.autoFrameRateLive) { settings.setAutoFrameRateLive(false) }
        keepOff(settings.pipEnabled) { settings.setPipEnabled(false) }
    }

    private fun keepOff(flow: Flow<Boolean>, turnOff: suspend () -> Unit) {
        scope.launch {
            flow.collect { on ->
                if (!on) return@collect
                try {
                    turnOff()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The next emission or app start tries again.
                }
            }
        }
    }
}
