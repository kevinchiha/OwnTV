package tv.own.owntv.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
// KevBox: the imports only the removed Home drawing used are gone (HomeViewModel, AsyncImage, koinViewModel, …).
import tv.own.owntv.R
import tv.own.owntv.core.home.TrendingHomeItem
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/**
 * Settings › Appearance's LIVE PREVIEW: a small Live TV guide of the member's own channels,
 * at a third of the screen's size. Nothing focusable, but it draws with the live accent, focus colour,
 * glass and font, so a change shows here before the row is left.
 */
// KevBox: the doc above described upstream's small Home; KevBox Live draws the mini guide (AC-34).
@Composable
internal fun SettingsLivePreview() {
    // KevBox: no HomeViewModel and no trending movie here; KevBox Live has no Home. The preview is a small
    // Live TV guide of the member's own channels (spec AC-34), drawn by KevBoxMiniGuidePreview below.
    val ink = tv.own.owntv.ui.theme.LocalStagePalette.current.ink
    SettingPanelHeading(stringResource(R.string.settings_quick_live_preview))
    // KevBox: the mini guide in place of the Home frame (the old 1920 × 1080 Home drawing was here).
    tv.own.owntv.kevbox.ui.KevBoxMiniGuidePreview()
    Text(
        stringResource(R.string.settings_live_preview_note), style = stageText(15, 500), color = StageColors.Muted,
        modifier = Modifier.padding(top = 12.mpx, start = 2.mpx),
    )
    Box(Modifier.padding(top = 22.mpx, bottom = 18.mpx).fillMaxWidth().height(1.mpx).background(ink.copy(alpha = 0.1f)))
}

// KevBox: titleOf, which only the removed Home drawing called, is gone.
internal fun backdropOf(item: TrendingHomeItem): String? =
    tv.own.owntv.core.metadata.MetadataImages.backdrop(item.snapshot.backdropPath, size = "w780")
        ?: when (item) {
            is TrendingHomeItem.Movie -> item.movie.backdropUrl ?: item.movie.posterUrl
            is TrendingHomeItem.Series -> item.series.backdropUrl ?: item.series.posterUrl
        }
