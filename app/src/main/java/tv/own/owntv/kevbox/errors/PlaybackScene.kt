// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.errors

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.own.owntv.core.database.entity.ChannelEntity

/**
 * What Live TV shows right now, kept by one effect in OwnTVShell so a playback failure can say which
 * channel, category and window it hit. [update] runs on the main thread and [snapshot] on the
 * watchers' threads, so the whole scene is swapped as one immutable value.
 */
class PlaybackScene {
    @Volatile
    private var current = SceneSnapshot(channel = null, category = null, streamUrl = null, inBox = false, catchup = false)

    private val _shown = MutableStateFlow(false)

    /**
     * True from the shell's first [update]. The playback watchers wait for it: by then the shell has
     * built both engines, so watching them never builds one in a process that shows no screen.
     */
    val shown: StateFlow<Boolean> = _shown.asStateFlow()

    fun update(channel: ChannelEntity?, category: String?, inBox: Boolean, catchup: Boolean) {
        current = SceneSnapshot(
            channel = channel?.name,
            category = category,
            streamUrl = channel?.streamUrl,
            inBox = inBox,
            catchup = catchup,
            channelEntity = channel,
        )
        _shown.value = true
    }

    fun snapshot(): SceneSnapshot = current
}
