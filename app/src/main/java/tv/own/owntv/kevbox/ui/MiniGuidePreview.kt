// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.CustomCategoryDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.ContentOrderEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.core.live.GuideReader
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.core.live.parseLiveKey
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.features.epg.GuideChannelLabel
import tv.own.owntv.features.epg.GuideGridDefaults
import tv.own.owntv.features.epg.GuideProgrammeBlock
import tv.own.owntv.features.epg.GuideRuler
import tv.own.owntv.features.epg.ProgrammeStripCanvas
import tv.own.owntv.features.epg.guideNowLine
import tv.own.owntv.features.epg.programmeEyebrow
import tv.own.owntv.features.live.LivePlate
import tv.own.owntv.features.live.ProviderTags
import tv.own.owntv.kevbox.live.GuideSidebar
import tv.own.owntv.kevbox.live.LiveOnly
import tv.own.owntv.ui.components.BackgroundPicture
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageLiveBadge
import tv.own.owntv.ui.stage.drawInnerRing
import tv.own.owntv.ui.stage.stageBackground
import tv.own.owntv.ui.stage.stageGlass
import tv.own.owntv.ui.stage.stageSelectedBar
import tv.own.owntv.ui.theme.LocalBackground
import tv.own.owntv.ui.theme.LocalBlurredBackdrop
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/** One row of the mini guide: the channel, the name it shows, and its programmes in the window. */
internal data class MiniGuideRow(val channel: ChannelEntity, val name: String, val programmes: List<EpgProgrammeEntity>)

/**
 * What the Appearance previews draw: the category column ([categories], [selected] lit), up to
 * [MiniGuide.ROWS] channel rows, and the time window. [sample] is true for the fixed stand-in shown
 * before the member's own channels have loaded (or when there are none).
 */
internal data class MiniGuideData(
    val categories: List<String>,
    val selected: Int,
    val rows: List<MiniGuideRow>,
    val windowStart: Long,
    val windowEnd: Long,
    val sample: Boolean,
)

/** Which channels the mini guide reads: the Live TV category the member last had open. */
internal sealed interface MiniGuideQuery {
    data class Folder(val categoryId: Long) : MiniGuideQuery
    data object All : MiniGuideQuery
    data object Favorites : MiniGuideQuery
    data object History : MiniGuideQuery
    data object Catchup : MiniGuideQuery
    data class Custom(val id: String) : MiniGuideQuery
}

internal object MiniGuide {
    const val ROWS: Int = 6
    private const val HALF_HOUR_MS = 30 * 60_000L
    private const val SPAN_MS = 210 * 60_000L

    /** The stored Live TV category, read with Core's own parser; anything it cannot read is All. */
    fun query(lastLiveCategory: String): MiniGuideQuery = when (val key = parseLiveKey(lastLiveCategory.trim())) {
        is LiveKey.Folder -> MiniGuideQuery.Folder(key.id)
        is LiveKey.Custom -> MiniGuideQuery.Custom(key.id)
        LiveKey.Favorites -> MiniGuideQuery.Favorites
        LiveKey.History -> MiniGuideQuery.History
        LiveKey.Catchup -> MiniGuideQuery.Catchup
        LiveKey.All, null -> MiniGuideQuery.All
    }

    /** From the half hour at or before [now], three and a half hours on. */
    fun window(now: Long): Pair<Long, Long> {
        val start = now - Math.floorMod(now, HALF_HOUR_MS)
        return start to start + SPAN_MS
    }

    /** One row per channel, in the channels' order; a channel with no guide gets an empty list. */
    fun rows(
        channels: List<ChannelEntity>,
        programmes: Map<Long, List<EpgProgrammeEntity>>,
        name: (ChannelEntity) -> String,
    ): List<MiniGuideRow> = channels.map { MiniGuideRow(it, name(it), programmes[it.id].orEmpty()) }
}

/**
 * Reads the member's own mini guide: the active profile's live channels in the category Live TV last
 * showed, with the profile's hides and renames and the guide on the user's clock.
 *
 * [fixedLabel] names the built-in categories (All channels, Favourites, …), which are string resources
 * the caller resolves. [customCategories] reads a custom group's channels; without it a custom group
 * falls back to All.
 */
internal class MiniGuideLoader(
    private val settings: SettingsRepository,
    private val sourceDao: SourceDao,
    private val channelDao: ChannelDao,
    private val categoryDao: CategoryDao,
    private val guideReader: GuideReader,
    private val customize: CustomizationStore,
    private val customCategories: CustomCategoryDao? = null,
    private val fixedLabel: (MiniGuideQuery) -> String = { String() },
) {
    suspend fun load(now: Long): MiniGuideData? = withContext(Dispatchers.IO) { read(now) }

    private suspend fun read(now: Long): MiniGuideData? {
        val active = activeProfileSources(settings, sourceDao).first()
        val pid = active.profileId
        val liveIds = active.liveSourceIds
        if (pid < 0 || liveIds.isEmpty()) return null
        val cust = customize.observe(pid, MediaType.LIVE).first()
        var query = MiniGuide.query(settings.lastLiveCategory.first())
        var channels = channelsFor(query, pid, liveIds, cust)
        // A folder a re-sync removed (or an emptied favourites list) shows All rather than nothing.
        if (channels.isEmpty() && query != MiniGuideQuery.All) {
            query = MiniGuideQuery.All
            channels = channelsFor(query, pid, liveIds, cust)
        }
        if (channels.isEmpty()) return null
        val (from, to) = MiniGuide.window(now)
        val programmes = guideReader.slice(channels, cust, settings.epgOffsetMinutes.first(), from, to)
        val folders = categoryDao.observe(liveIds, MediaType.LIVE).first()
            .filter { CustomizeKeys.category(it) !in cust.hiddenCategories }
        val folderLabels = folders.map { cust.categoryNames[CustomizeKeys.category(it)] ?: LiveOnly.categoryName(it.name).name }
        val folderIndex = (query as? MiniGuideQuery.Folder)?.let { q -> folders.indexOfFirst { it.id == q.categoryId } } ?: -1
        val (categories, selected) =
            if (folderIndex >= 0) folderLabels to folderIndex
            else (listOf(labelOf(query, cust)) + folderLabels) to 0
        return MiniGuideData(
            categories = categories,
            selected = selected,
            rows = MiniGuide.rows(channels, programmes) { ProviderTags.parse(it.name).name },
            windowStart = from,
            windowEnd = to,
            sample = false,
        )
    }

    private fun labelOf(query: MiniGuideQuery, cust: SectionCustomizations): String =
        (query as? MiniGuideQuery.Custom)?.let { q -> cust.categoryNames[q.id] ?: cust.customCategories.firstOrNull { it.id == q.id }?.name }
            ?: fixedLabel(query)

    /** The calls Live TV and the guide make for each category, then the profile's hides and renames. */
    private suspend fun channelsFor(
        query: MiniGuideQuery,
        pid: Long,
        liveIds: List<Long>,
        cust: SectionCustomizations,
    ): List<ChannelEntity> {
        // Read a few spare rows so hidden channels do not leave the preview short.
        val limit = MiniGuide.ROWS * 4
        val raw = when (query) {
            is MiniGuideQuery.Folder ->
                channelDao.channelsAfterCategory(query.categoryId, Int.MIN_VALUE, Long.MIN_VALUE, limit).filter { it.sourceId in liveIds }
            MiniGuideQuery.Favorites -> channelDao.snapshotFavoritesManual(pid, ContentOrderEntity.FAV_CONTEXT, liveIds, limit)
            MiniGuideQuery.History -> channelDao.recentlyWatched(pid, limit).first().filter { it.sourceId in liveIds }
            MiniGuideQuery.Catchup -> channelDao.snapshotCatchup(liveIds, limit)
            is MiniGuideQuery.Custom -> customCategories?.snapshotChannels(pid, query.id, liveIds, limit).orEmpty()
            MiniGuideQuery.All -> channelDao.snapshotAll(liveIds, limit)
        }
        return raw
            .filter { CustomizeKeys.channel(it) !in cust.hiddenItems }
            .map { ch -> cust.itemNames[CustomizeKeys.channel(ch)]?.let { ch.copy(name = it) } ?: ch }
            .take(MiniGuide.ROWS)
    }
}

/**
 * The last guide the loader read, kept outside composition so a preview that comes back (after Popup
 * size or an editor) draws the member's channels at once instead of the sample.
 */
internal object MiniGuideMemory {
    @Volatile var last: MiniGuideData? = null

    /**
     * Runs [load] and returns what the preview shows next. A guide is kept and shown. An empty load
     * (another playlist with no synced channels) forgets the kept guide and shows [sample], so the
     * old playlist's channels never linger. A load that fails leaves things as they were.
     * Cancellation is rethrown, never swallowed.
     */
    suspend fun refresh(sample: MiniGuideData, load: suspend () -> MiniGuideData?): MiniGuideData {
        val loaded = try {
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return last ?: sample
        }
        last = loaded
        return loaded ?: sample
    }
}

/** The sample at once (or the last loaded guide), then the member's own channels once they are read. */
@Composable
internal fun rememberMiniGuideData(): MiniGuideData {
    val sample = miniGuideSample()
    val all = stringResource(R.string.content_category_all_channels)
    val favourites = stringResource(R.string.content_category_favorites)
    val history = stringResource(R.string.content_category_history)
    val catchup = stringResource(R.string.content_catchup)
    val settings = koinInject<SettingsRepository>()
    val sourceDao = koinInject<SourceDao>()
    val channelDao = koinInject<ChannelDao>()
    val categoryDao = koinInject<CategoryDao>()
    val guideReader = koinInject<GuideReader>()
    val customize = koinInject<CustomizationStore>()
    val customCategories = koinInject<CustomCategoryDao>()
    val loader = remember(all, favourites, history, catchup) {
        MiniGuideLoader(settings, sourceDao, channelDao, categoryDao, guideReader, customize, customCategories) { q ->
            when (q) {
                MiniGuideQuery.Favorites -> favourites
                MiniGuideQuery.History -> history
                MiniGuideQuery.Catchup -> catchup
                else -> all
            }
        }
    }
    val data by produceState(MiniGuideMemory.last ?: sample, loader, sample) {
        value = MiniGuideMemory.refresh(sample) { loader.load(System.currentTimeMillis()) }
    }
    return data
}

/** Six made-up channels from the kevbox_preview_sample strings, each on something now and next. */
@Composable
private fun miniGuideSample(): MiniGuideData {
    val category = stringResource(R.string.kevbox_preview_sample_category)
    val names = listOf(
        stringResource(R.string.kevbox_preview_sample_channel_1),
        stringResource(R.string.kevbox_preview_sample_channel_2),
        stringResource(R.string.kevbox_preview_sample_channel_3),
        stringResource(R.string.kevbox_preview_sample_channel_4),
        stringResource(R.string.kevbox_preview_sample_channel_5),
        stringResource(R.string.kevbox_preview_sample_channel_6),
    )
    val titles = listOf(
        stringResource(R.string.kevbox_preview_sample_programme_1),
        stringResource(R.string.kevbox_preview_sample_programme_2),
        stringResource(R.string.kevbox_preview_sample_programme_3),
        stringResource(R.string.kevbox_preview_sample_programme_4),
        stringResource(R.string.kevbox_preview_sample_programme_5),
        stringResource(R.string.kevbox_preview_sample_programme_6),
    )
    return remember(category, names, titles) {
        val (from, to) = MiniGuide.window(System.currentTimeMillis())
        val minute = 60_000L
        val channels = names.mapIndexed { i, name ->
            ChannelEntity(id = -(i + 1L), sourceId = 0, name = name, streamUrl = String(), number = i + 1)
        }
        // Staggered starts so the rows do not line up: each channel's programme, then the next one's.
        val programmes = channels.mapIndexed { i, ch ->
            val firstEnd = from + (40 + i * 15) * minute
            ch.id to listOf(
                EpgProgrammeEntity(id = -(i * 2 + 1L), sourceId = 0, epgChannelId = String(), startMs = from - 20 * minute, stopMs = firstEnd, title = titles[i]),
                EpgProgrammeEntity(id = -(i * 2 + 2L), sourceId = 0, epgChannelId = String(), startMs = firstEnd, stopMs = to, title = titles[(i + 1) % titles.size]),
            )
        }.toMap()
        MiniGuideData(
            categories = listOf(category),
            selected = 0,
            rows = MiniGuide.rows(channels, programmes) { it.name },
            windowStart = from,
            windowEnd = to,
            sample = true,
        )
    }
}

/**
 * The Appearance preview: Live TV's guide at a third of the screen, drawn the Stage way from [data].
 * The menu capsule (Live TV lit), the category column, the top box with the first channel and what it
 * is showing, the ruler, the rows and the now line. Nothing is focusable and no player starts; theme,
 * glass, accent and font all come from the Stage tokens, so a change shows here at once.
 */
@Composable
internal fun KevBoxMiniGuidePreview(modifier: Modifier = Modifier, data: MiniGuideData = rememberMiniGuideData()) {
    val a = stageAccent
    val rim = StageColors.Ink.copy(alpha = 0.1f)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(18.mpx))
            .drawBehind { drawInnerRing(rim, 1.mpx.toPx(), 18.mpx.toPx()) },
    ) {
        // The 1920 × 1080 frame, scaled down to the box: every size below is the real screen's.
        val scale = maxWidth / 1920.mpx
        Box(
            Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .requiredSize(1920.mpx, 1080.mpx)
                .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) },
        ) {
            val background = LocalBackground.current
            if (background.showsPicture) BackgroundPicture(background, LocalBlurredBackdrop.current, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().stageBackground(a.accent))
            MiniMenu()
            MiniCategories(data, Modifier.padding(start = MENU_ROOM.mpx, top = 44.mpx).width(GuideSidebar.COLUMN_WIDTH_MPX.mpx))
            MiniGrid(data, Modifier.padding(start = GRID_START.mpx, end = 64.mpx).fillMaxWidth())
        }
    }
}

/** Where the category column starts (the menu's room) and where the guide starts, of 1920. */
private const val MENU_ROOM = 112
private const val GRID_START = MENU_ROOM + GuideSidebar.COLUMN_WIDTH_MPX + 24

/** The main menu at rest: a glass capsule of Search, Live TV (lit) and More. */
@Composable
private fun MiniMenu() {
    val a = stageAccent
    Column(
        Modifier.padding(start = 26.mpx, top = 230.mpx).width(60.mpx).stageGlass(30.mpx).padding(vertical = 18.mpx),
        verticalArrangement = Arrangement.spacedBy(22.mpx),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        listOf(OwnTVIcon.SEARCH, OwnTVIcon.LIVE_TV, OwnTVIcon.MORE).forEachIndexed { i, icon ->
            OwnTVIcon(icon, if (i == 1) a.accent else StageColors.Muted, Modifier.size(26.mpx))
        }
    }
}

/** The category column: up to [CATEGORY_ROWS] names around the current one, which has the accent bar. */
@Composable
private fun MiniCategories(data: MiniGuideData, modifier: Modifier) {
    val a = stageAccent
    val first = (data.selected - 3).coerceIn(0, (data.categories.size - CATEGORY_ROWS).coerceAtLeast(0))
    Column(modifier) {
        data.categories.drop(first).take(CATEGORY_ROWS).forEachIndexed { i, label ->
            val selected = first + i == data.selected
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.mpx)
                    .heightIn(min = 52.mpx)
                    .then(if (selected) Modifier.stageSelectedBar(a.accent, 14.mpx) else Modifier)
                    .padding(horizontal = 14.mpx, vertical = 7.mpx),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    label, style = stageText(18.5f, 600), color = if (selected) a.accent else StageColors.ItemText,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private const val CATEGORY_ROWS = 16

/** The guide: the top box and programme, the ruler, the rows and the now line. */
@Composable
private fun MiniGrid(data: MiniGuideData, modifier: Modifier) {
    val a = stageAccent
    val now = System.currentTimeMillis()
    val lead = data.rows.firstOrNull()
    val leadNow = lead?.programmes?.firstOrNull { now in it.startMs until it.stopMs }
    Column(modifier) {
        Row(Modifier.padding(top = 44.mpx).fillMaxWidth().height(360.mpx)) {
            // The video box: a Stage surface with the first channel's logo, never a black hole.
            Box(
                Modifier
                    .width(640.mpx)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(28.mpx))
                    .background(StageColors.ControlFill),
                contentAlignment = Alignment.Center,
            ) {
                if (lead != null) {
                    LivePlate(lead.channel.displayLogoUrl, 200.mpx, 140.mpx, radius = 20.mpx)
                    Box(Modifier.align(Alignment.TopStart).padding(start = 20.mpx, top = 18.mpx)) { StageLiveBadge(stringResource(R.string.player_live)) }
                }
            }
            if (lead != null) {
                GuideProgrammeBlock(
                    eyebrow = leadNow?.let { programmeEyebrow(it, lead.name, now) },
                    title = leadNow?.title ?: lead.name,
                    details = null,
                    synopsis = leadNow?.description,
                    modifier = Modifier.padding(start = 46.mpx, top = 76.mpx).widthIn(max = 1080.mpx),
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 18.mpx)) {
            val labelW = LABEL_WIDTH.mpx
            // The guide's number column: wide enough for the longest number, so the name gets the rest
            // and wraps to a second line inside GuideChannelLabel the same way.
            val numberW = maxOf(38f, (data.rows.maxOfOrNull { it.channel.number?.toString()?.length ?: 0 } ?: 0) * 11f).mpx
            GuideRuler(
                windowStart = data.windowStart, windowEnd = data.windowEnd, now = now, scrollPx = 0,
                modifier = Modifier.padding(start = labelW).fillMaxWidth().height(40.mpx),
            )
            Column(Modifier.padding(top = 48.mpx).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GuideGridDefaults.RowGap)) {
                data.rows.forEachIndexed { i, row ->
                    Row(Modifier.fillMaxWidth().height(GuideGridDefaults.RowHeight), verticalAlignment = Alignment.CenterVertically) {
                        GuideChannelLabel(row.channel, row.name, dot = null, selected = i == 0, numberWidth = numberW, modifier = Modifier.width(labelW))
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            ProgrammeStripCanvas(
                                programmes = row.programmes,
                                windowStart = data.windowStart,
                                windowEnd = data.windowEnd,
                                now = now,
                                // The first row's programme on now is drawn focused, so the focus colour shows.
                                focusTime = if (i == 0) now else null,
                                catchupIds = emptySet(),
                                recordingStarts = emptySet(),
                                reminderStarts = emptySet(),
                                scrollPx = 0,
                            )
                        }
                    }
                }
            }
            if (now in data.windowStart..data.windowEnd && data.rows.isNotEmpty()) {
                val nowX = GuideGridDefaults.PxPerMin * ((now - data.windowStart) / 60_000f)
                val rowsHeight = (GuideGridDefaults.RowHeight + GuideGridDefaults.RowGap) * data.rows.size
                Box(
                    Modifier
                        .padding(start = labelW + nowX - 1.5.mpx, top = 38.mpx)
                        .width(3.mpx)
                        .height(rowsHeight + 10.mpx)
                        .guideNowLine(a.accent),
                )
            }
        }
    }
}

/** The channel label column, of 1920: the guide's own share (424), so a name shows as it does there. */
private const val LABEL_WIDTH = 1920f * GuideGridDefaults.ChannelColShare
