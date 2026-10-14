// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.refresh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Settings › Playlists › Update now. In order: ask kevbox.dev for the member's line ([checkPlaylist]),
 * download the channel list of the playlist on screen ([syncChannels], which returns once that sync
 * has finished), then its guide ([syncGuides]). The guide waits for the channels because Core keeps
 * only programmes of channels it already has. Each step runs even when the one before it failed:
 * the guide is worth fetching again even if the channel download did not work.
 *
 * Runs in the app's scope, so leaving Settings does not stop it. A press while one runs does nothing.
 */
class PlaylistUpdater(
    private val checkPlaylist: suspend () -> Unit,
    private val currentSourceId: suspend () -> Long?,
    private val syncChannels: suspend (sourceId: Long) -> Unit,
    private val syncGuides: suspend (sourceId: Long) -> Unit,
    private val scope: CoroutineScope,
) {
    private val _running = MutableStateFlow(false)

    /** True from a press until the guide download has been asked for. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun updateNow() {
        if (!_running.compareAndSet(expect = false, update = true)) return
        scope.launch {
            try {
                quietly { checkPlaylist() }
                var id: Long? = null
                quietly { id = currentSourceId() }
                id?.let { sourceId ->
                    quietly { syncChannels(sourceId) }
                    quietly { syncGuides(sourceId) }
                }
            } finally {
                _running.value = false
            }
        }
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The next step still runs; the next press tries this one again.
        }
    }
}
