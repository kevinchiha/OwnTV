// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.ui.components.BrandMark
import tv.own.owntv.ui.components.rememberAppliedIcon
import tv.own.owntv.ui.stage.stageBackground
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/** Signed in, but the admin has not given this member a playlist yet. */
@Composable
fun NoPlaylistScreen(modifier: Modifier = Modifier) {
    StatusFrame(stringResource(R.string.kevbox_no_playlist), modifier)
}

/**
 * The admin locked this member's access. The screen takes focus and keeps every key to itself except
 * Back, so nothing behind it can be reached with the remote.
 */
@Composable
fun AccessLockedScreen(modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { focus.requestFocus() } }
    StatusFrame(
        stringResource(R.string.kevbox_access_locked),
        modifier
            .focusRequester(focus)
            .onPreviewKeyEvent { it.key != Key.Back }
            .focusable(),
    )
}

/** The whole screen: the Stage background, the KevBox Live mark (Core's `owntv_mark_*`) and one line. */
@Composable
private fun StatusFrame(message: String, modifier: Modifier) {
    Box(modifier.fillMaxSize().stageBackground(stageAccent.accent), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(36.mpx)) {
            // The mark as the launcher draws it: the icon drawables Task 3 replaces with KevBox Live's.
            BrandMark(rememberAppliedIcon(), 180.mpx, followAccent = false)
            Text(
                message,
                style = stageText(34, 800),
                color = StageColors.Text,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 120.mpx),
            )
        }
    }
}
