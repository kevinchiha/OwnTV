// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.brand.AppIconSwitcher
import tv.own.owntv.features.settings.StageSettingRow
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.mpxSp
import tv.own.owntv.ui.theme.stageText

/**
 * More › Restart app: the title, one line on what it does, and Restart now. [entry] is what ▶ on the
 * More sheet focuses: the button. OK on the sheet item only shows this page, so a stray OK while
 * scrolling the sheet never restarts anything.
 *
 * Restart now goes through Core's icon-change restart with the icon already in use: Core's
 * AppRestartActivity, in a process of its own, ends this one and opens the app again from its
 * launcher entry, as a force stop and a fresh open would.
 */
@Composable
fun KevBoxRestartMorePage(entry: FocusRequester, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier.fillMaxSize()) {
        // The same title style as the other More pages (42/800, −1).
        Text(
            stringResource(R.string.kevbox_restart_app),
            style = stageText(42, 800, (-1).mpxSp),
            color = StageColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            stringResource(R.string.kevbox_restart_app_body),
            style = stageText(20, 500),
            color = StageColors.Muted,
            modifier = Modifier.padding(top = 14.mpx),
        )
        Box(Modifier.padding(top = 26.mpx).focusRequester(entry).focusGroup()) {
            StageSettingRow(
                icon = OwnTVIcon.REFRESH,
                title = stringResource(R.string.kevbox_restart_now),
                desc = null,
                value = null,
                onClick = { context.findActivity()?.let { AppIconSwitcher.restartWith(it, AppIconSwitcher.applied(it)) } },
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
