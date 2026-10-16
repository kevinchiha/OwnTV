// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.delay

/**
 * Eats the rest of a held OK after Hold OK did something that moves focus, such as taking a row out of
 * a list. Without it the still-held OK reaches the row focus moved to: Compose's clickable treats a
 * repeat KeyDown on a newly focused item as a fresh press, and the release then clicks it (the same
 * trap as the Jump to panel on Kevin's TV, 2026-10-14, see CategoryJumpPanel's longPressMenuGuard).
 *
 * Unlike upstream's longPressMenuGuard, which arms once when a popup opens, this one is armed on demand
 * with [arm], as often as needed. It lets OK through again on its release, or once no OK has arrived for
 * [QUIET_MS], in case the release never reaches this list.
 */
@Stable
class HeldOkSwallow {
    internal var armed by mutableStateOf(false)
    internal var lastOkAt = 0L

    /** Call from the long-press handler, while OK is still down. */
    fun arm() {
        lastOkAt = SystemClock.uptimeMillis()
        armed = true
    }
}

@Composable
fun rememberHeldOkSwallow(): HeldOkSwallow = remember { HeldOkSwallow() }

/** Put on the list's root, so it sees OK before any row does. */
fun Modifier.swallowHeldOk(state: HeldOkSwallow): Modifier = composed {
    LaunchedEffect(state.armed) {
        while (state.armed) {
            delay(POLL_MS)
            if (SystemClock.uptimeMillis() - state.lastOkAt > QUIET_MS) state.armed = false
        }
    }
    onPreviewKeyEvent { e ->
        if (!state.armed || (e.key != Key.DirectionCenter && e.key != Key.Enter && e.key != Key.NumPadEnter)) {
            return@onPreviewKeyEvent false
        }
        when (e.type) {
            KeyEventType.KeyDown -> state.lastOkAt = SystemClock.uptimeMillis()
            KeyEventType.KeyUp -> state.armed = false
            else -> Unit
        }
        true
    }
}

/** Upstream's longPressMenuGuard numbers: a held key repeats far faster than this. */
private const val QUIET_MS = 300L
private const val POLL_MS = 100L
