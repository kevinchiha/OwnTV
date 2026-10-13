// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R
import tv.own.owntv.features.settings.SettingValue
import tv.own.owntv.features.settings.StageSettingRow
import tv.own.owntv.features.setup.WizardAction
import tv.own.owntv.features.setup.WizardFrame
import tv.own.owntv.features.setup.WizardStep
import tv.own.owntv.kevbox.supabase.PlaylistRow
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageTag
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent

// Every row here reads only PlaylistRow.name and PlaylistRow.clientNumber: a member never sees the
// server URL, username or password the row carries.

/**
 * First run, more than one playlist: "Choose your playlist" and one row per playlist by name, in the
 * order given. OK on a row calls [onChoose] with its client number; Back calls [onBack].
 */
@Composable
fun ChoosePlaylistScreen(rows: List<PlaylistRow>, onChoose: (clientNumber: String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { first.requestFocus() } }
    Box(modifier) {
        WizardFrame(
            step = WizardStep.PLAYLIST,
            title = stringResource(R.string.kevbox_choose_your_playlist),
            sub = null,
            back = WizardAction(stringResource(R.string.common_back), onBack),
            next = null,
        ) {
            rows.forEachIndexed { i, row ->
                StageSettingRow(
                    icon = OwnTVIcon.PLAYLIST,
                    title = row.name,
                    desc = null,
                    value = null,
                    onClick = { onChoose(row.clientNumber) },
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
    }
}

/**
 * Settings › Choose playlist: the same rows, the one the TV plays now marked "Current". Choosing the
 * current row does nothing; any other calls [onChoose]. The rows only: the Settings page that mounts
 * this draws the title and handles Back.
 */
@Composable
fun ChoosePlaylistPage(rows: List<PlaylistRow>, currentClientNumber: String?, onChoose: (clientNumber: String) -> Unit, modifier: Modifier = Modifier) {
    val a = stageAccent
    val currentLabel = stringResource(R.string.kevbox_current_playlist)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.mpx)) {
        rows.forEach { row ->
            val current = row.clientNumber == currentClientNumber
            StageSettingRow(
                icon = OwnTVIcon.PLAYLIST,
                title = row.name,
                desc = null,
                value = if (current) SettingValue.Custom { StageTag(currentLabel, tint = a.accent) } else null,
                onClick = { if (!current) onChoose(row.clientNumber) },
            )
        }
    }
}
