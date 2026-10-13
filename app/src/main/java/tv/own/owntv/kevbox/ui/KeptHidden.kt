// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout

/**
 * Keeps a layout composed and measured but never places it. An unplaced layout is not drawn, gets no
 * position callbacks, and D-pad focus search skips it, yet its state, its effects and its lazy lists
 * stay as they were, so showing it again is a placement rather than a rebuild. The shell keeps the Live
 * TV grid this way behind full screen.
 */
val KeptHidden: Modifier = Modifier.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) { }
}
