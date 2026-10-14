package tv.own.owntv.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.core.home.TrendingHomeItem
import tv.own.owntv.features.home.HomeViewModel
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageProgress
import tv.own.owntv.ui.stage.drawInnerRing
import tv.own.owntv.ui.stage.drawOuterRing
import tv.own.owntv.ui.stage.stageBackground
import tv.own.owntv.ui.stage.stageGlass
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.dissolveEdges
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * Settings › Appearance's LIVE PREVIEW (P9-04, G9): a small Home drawn from the first Trending title, at a
 * third of the screen's size. Not a second Home — no data of its own, nothing focusable — but it draws
 * with the live accent, focus colour, glass and font, so a change shows here before the row is left.
 */
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

private fun titleOf(item: TrendingHomeItem?): String? =
    item?.snapshot?.let { it.localizedTitle.ifBlank { it.canonicalTitle } }

internal fun backdropOf(item: TrendingHomeItem): String? =
    tv.own.owntv.core.metadata.MetadataImages.backdrop(item.snapshot.backdropPath, size = "w780")
        ?: when (item) {
            is TrendingHomeItem.Movie -> item.movie.backdropUrl ?: item.movie.posterUrl
            is TrendingHomeItem.Series -> item.series.backdropUrl ?: item.series.posterUrl
        }
