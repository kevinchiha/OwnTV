// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import tv.own.owntv.core.settings.RemoteShortcutAction
import tv.own.owntv.core.settings.RemoteShortcutPress
import tv.own.owntv.ui.components.LocalRemoteShortcuts

/**
 * The remote's paging keys (CH+ / CH− unless the member rebound them) in the guide's category column
 * (Kevin, 2026-10-16): a short press steps a whole stretch of categories ([CategoryJumps.groupStep]),
 * FR| to UK|, instead of upstream's fixed number of rows. A long press still goes to the top or the
 * bottom. The key handling copies upstream's [tv.own.owntv.ui.components.chNavPaging] (press length
 * read on release, other shortcuts passed on to the shell), so the two never disagree about a key.
 *
 * [onStep] gets -1 for a key bound to page toward the first row, +1 toward the last; [onEnd] gets true
 * for the first row. Nothing happens unless [enabled] and [isActive].
 */
internal fun Modifier.categoryGroupPaging(
    enabled: Boolean,
    isActive: () -> Boolean,
    onStep: (Int) -> Unit,
    onEnd: (toFirst: Boolean) -> Unit,
): Modifier = composed {
    val remote = LocalRemoteShortcuts.current
    if (!enabled || !remote.enabled) return@composed this
    var activeKeyCode by remember { mutableIntStateOf(android.view.KeyEvent.KEYCODE_UNKNOWN) }
    var pressedAt by remember { mutableLongStateOf(0L) }

    onPreviewKeyEvent { event ->
        val keyCode = event.nativeKeyEvent.keyCode
        val keyBindings = remote.bindings.filter { it.keyCode == keyCode }
        if (!isActive() || keyBindings.isEmpty()) return@onPreviewKeyEvent false
        when (event.type) {
            KeyEventType.KeyDown -> {
                if (activeKeyCode != keyCode) {
                    activeKeyCode = keyCode
                    pressedAt = event.nativeKeyEvent.eventTime
                }
                true
            }
            KeyEventType.KeyUp -> {
                if (activeKeyCode != keyCode) return@onPreviewKeyEvent false
                activeKeyCode = android.view.KeyEvent.KEYCODE_UNKNOWN
                val heldLong = event.nativeKeyEvent.eventTime - pressedAt >= LONG_PRESS_MS
                val short = keyBindings.firstOrNull { it.press == RemoteShortcutPress.SHORT }
                val binding = if (heldLong) keyBindings.firstOrNull { it.press == RemoteShortcutPress.LONG } ?: short else short
                when (binding?.action) {
                    null -> Unit
                    RemoteShortcutAction.PAGE_TOWARD_FIRST -> onStep(-1)
                    RemoteShortcutAction.PAGE_TOWARD_LAST -> onStep(+1)
                    RemoteShortcutAction.JUMP_TO_FIRST -> onEnd(true)
                    RemoteShortcutAction.JUMP_TO_LAST -> onEnd(false)
                    else -> remote.dispatch(binding.action)
                }
                true
            }
            else -> activeKeyCode == keyCode
        }
    }
}

/** Upstream's PAGING_LONG_PRESS_MS in ChNavPaging.kt (private there). */
private const val LONG_PRESS_MS = 600L
