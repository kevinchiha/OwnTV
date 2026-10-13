// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.kevbox.expiry.ExpiryPopup
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.stage.StagePopup
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/**
 * The subscription notice: "has expired" or "expires in N days", with one OK that calls [onDismiss]
 * (Back does the same). Draws nothing while [popup] is null.
 */
@Composable
fun ExpiryPopupHost(popup: ExpiryPopup?, onDismiss: () -> Unit) {
    if (popup == null) return
    val message = when (popup) {
        ExpiryPopup.Expired -> stringResource(R.string.kevbox_expired)
        // Two plain strings, not plurals: in locales with no "one" form a plural would read "1 days".
        is ExpiryPopup.Expiring ->
            if (popup.days == 1) stringResource(R.string.kevbox_expiring_one)
            else stringResource(R.string.kevbox_expiring_other, popup.days)
    }
    val ok = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { ok.requestFocus() } }
    StagePopup(
        onDismiss = onDismiss,
        title = null,
        eyebrow = null,
        width = 760.mpx,
        buttons = {
            StageButton(
                stringResource(R.string.kevbox_ok), onClick = onDismiss, height = 56.mpx, textSize = 19, tinted = true,
                modifier = Modifier.focusRequester(ok),
            )
        },
    ) {
        Text(message, style = stageText(28, 800), color = StageColors.Text)
    }
}
