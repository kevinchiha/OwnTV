// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.features.settings.StageSettingRow
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageConfirm
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/**
 * Settings › Account: the signed-in email, then Sign out. Sign out asks first; Cancel (or Back) closes
 * the question with no other effect, Sign out calls [onSignOut]. The rows only: the Settings page that
 * mounts this draws the title and handles Back.
 */
@Composable
fun AccountPage(email: String, onSignOut: () -> Unit, modifier: Modifier = Modifier) {
    var confirming by remember { mutableStateOf(false) }
    val signOut = stringResource(R.string.kevbox_sign_out)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.mpx)) {
        Text(
            email,
            style = stageText(24, 700),
            color = StageColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 12.mpx),
        )
        StageSettingRow(
            icon = OwnTVIcon.POWER,
            title = signOut,
            desc = null,
            value = null,
            onClick = { confirming = true },
        )
    }
    if (confirming) {
        StageConfirm(
            title = stringResource(R.string.kevbox_sign_out_confirm),
            body = null,
            confirm = signOut,
            onConfirm = {
                confirming = false
                onSignOut()
            },
            onCancel = { confirming = false },
            cancel = stringResource(R.string.kevbox_cancel),
            // A stray OK must not sign the TV out.
            focusCancel = true,
            eyebrow = null,
        )
    }
}
