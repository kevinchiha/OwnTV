package tv.own.owntv.features.epg

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf // KevBox: isSelectedRow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent // KevBox: ◀ on the grid and in the column leaves for the column and the menu
// KevBox: Live TV's guide and its category column slide on a layer (kevbox/live/GuideSidebar.kt).
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import tv.own.owntv.kevbox.live.GuideSidebar
import tv.own.owntv.ui.theme.ownTvTween
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.core.model.RecordingStatus
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.features.live.LiveCategories
import tv.own.owntv.features.live.LiveViewModel
import tv.own.owntv.features.live.ProviderTags
import tv.own.owntv.features.live.edgeScrollSpec
import tv.own.owntv.features.live.liveCategoryEntries
import tv.own.owntv.features.live.liveEpgShiftLabel
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVSpinner
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.stage.shownColor
import tv.own.owntv.ui.stage.stageFocusLook
import tv.own.owntv.ui.stage.StageSearchField
import tv.own.owntv.ui.stage.StageTool
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

@Composable
private fun epgMessageText(message: EpgMessage): String = when (message) {
    EpgMessage.CreateProfile -> stringResource(R.string.content_epg_create_profile)
    EpgMessage.AddPlaylist -> stringResource(R.string.content_epg_add_playlist)
    is EpgMessage.NoChannelsForQuery -> stringResource(R.string.content_epg_no_channels_query, message.query)
    EpgMessage.MismatchedIds -> stringResource(R.string.content_epg_mismatched_ids)
}

@Composable
private fun epgMatchSummaryText(summary: EpgMatchSummary): String = when (summary) {
    EpgMatchSummary.CatchupUnavailable -> stringResource(R.string.content_epg_catchup_unavailable)
    EpgMatchSummary.MatchedNoProgrammes -> stringResource(R.string.content_epg_matched_no_programmes)
    EpgMatchSummary.AddPlaylist -> stringResource(R.string.content_epg_add_playlist_first)
    EpgMatchSummary.NoData -> stringResource(R.string.content_epg_no_match_data)
    EpgMatchSummary.AllMatched -> stringResource(R.string.content_epg_all_matched)
    is EpgMatchSummary.NoMatch -> stringResource(R.string.content_epg_no_match, summary.channelName)
    is EpgMatchSummary.AutoMatched -> if (summary.review > 0) {
        stringResource(
            R.string.content_epg_auto_matched,
            pluralStringResource(R.plurals.content_epg_auto_matched_applied, summary.applied, summary.applied),
            pluralStringResource(R.plurals.content_epg_auto_matched_review, summary.review, summary.review),
        )
    } else {
        pluralStringResource(R.plurals.content_epg_auto_matched_no_review, summary.applied, summary.applied)
    }
}

/** The programme on at [time] in a row, or null in a stretch with nothing on. */
private fun programmeAt(progs: List<EpgProgrammeEntity>?, time: Long): EpgProgrammeEntity? =
    progs?.firstOrNull { time in it.startMs until it.stopMs }

/** Across a gap the cursor moves by half an hour, rather than leaping hours to the next programme. */
private const val GAP_STEP_MS = 30L * 60 * 1000

/** Where ◀ ([dir] -1) or ▶ (+1) takes the cursor: programme by programme, half an hour at a time across gaps. */
private fun cursorStep(progs: List<EpgProgrammeEntity>, cursor: Long, dir: Int, windowStart: Long, windowEnd: Long): Long {
    val cur = programmeAt(progs, cursor)
    val target = if (dir > 0) {
        val from = cur?.stopMs ?: cursor
        val next = progs.firstOrNull { it.startMs >= from && it.startMs > cursor }
        when {
            cur != null && (next == null || next.startMs - cur.stopMs > GAP_STEP_MS) -> cur.stopMs
            cur != null -> next!!.startMs
            next != null && next.startMs - cursor <= GAP_STEP_MS -> next.startMs
            else -> cursor + GAP_STEP_MS
        }
    } else {
        val until = cur?.startMs ?: cursor
        val prev = progs.lastOrNull { it.stopMs <= until }
        when {
            cur != null && (prev == null || cur.startMs - prev.stopMs > GAP_STEP_MS) -> cur.startMs - GAP_STEP_MS
            cur != null -> prev!!.startMs
            prev != null && cursor - prev.stopMs <= GAP_STEP_MS -> prev.startMs
            else -> cursor - GAP_STEP_MS
        }
    }
    return target.coerceIn(windowStart, windowEnd - 1)
}

/**
 * The TV Guide in the Stage design (P4-01): the focused channel's preview and the focused programme
 * with its actions on top; one control line (Today · Now · Category · Order · Search · Auto-match EPG);
 * the ruler with the now-line; 8 slim rows of channel labels and programme cells.
 *
 * Each row's programmes are one focus target with a time cursor (◀ ▶ move it programme by programme,
 * ▲ ▼ step rows at the same time), so a week of guide stays one node per row. OK watches — the channel,
 * or a programme that has been on from the archive; holding OK or ☰ opens the programme menu.
 *
 * With [liveMode] this is Live TV's Guide view (G14): the category is Live TV's own, the title says
 * Live TV, and List view goes back to the channel list.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun EpgScreen(
    modifier: Modifier = Modifier,
    onFullscreen: () -> Unit = {},
    onAddEpg: () -> Unit = {},
    restoreFocus: Boolean = false,
    onRestored: () -> Unit = {},
    onContentScrolled: (Boolean) -> Unit = {},
    /** Required: every live tune in the app goes through the one shared path in LiveViewModel, so a
     *  channel gets the same Prefer HLS handling, ExoPlayer→mpv ladder, per-channel engine pin and
     *  external-player routing however the user reached it. */
    onPlayChannel: (channel: ChannelEntity, channels: List<ChannelEntity>) -> Unit,
    /** "Watch from start" on a catch-up programme. Required for the same reason, and additionally so the
     *  archive is tracked as catch-up playback (which decides what engine toggle the player HUD offers). */
    onPlayCatchup: (channel: ChannelEntity, programme: EpgProgrammeEntity) -> Unit,
    /** False while the full-screen or mini player owns the video surface. */
    previewEnabled: Boolean = true,
    liveMode: Boolean = false,
    onListView: () -> Unit = {},
    /** How ▶ from the rail enters the guide: at the grid row focus was on (the rail's entry hook, as Home's). */
    onEntryHook: (((() -> Boolean)?) -> Unit)? = null,
    // KevBox: moves focus to the app's main menu (the rail). Live TV's category column hands Back and ◀
    // to it, since the column is the stop between the grid and the menu.
    onOpenMenu: () -> Unit = {},
    // KevBox: Live TV's category column; all three are ignored outside liveMode. menuInUse: the user is on
    // the main menu (the shell's railFocused, not the passing visits focus makes there by itself); the guide
    // then makes room for the menu and the column. menuRoom: the width the menu takes from the screen's
    // left edge, where the column starts. onExpandedChange: true once the guide starts expanding over the
    // menu and the column, false once it starts making room for them again.
    menuInUse: Boolean = false,
    menuRoom: Dp = 0.dp,
    onExpandedChange: (Boolean) -> Unit = {},
    // KevBox: set while the guide was opened from Search (Hold OK); the first Back returns there.
    onBackToSearch: (() -> Unit)? = null,
) {
    val vm: EpgViewModel = koinViewModel()
    // Live TV's view model: its preview player (no second decoder), its category list and playlist marks.
    val liveVm: LiveViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val liveNow by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(30_000L)
            value = System.currentTimeMillis()
        }
    }
    val query by vm.query.collectAsStateWithLifecycle()
    val matching by vm.matching.collectAsStateWithLifecycle()
    val review by vm.review.collectAsStateWithLifecycle()
    val matchSummary by vm.matchSummary.collectAsStateWithLifecycle()
    val sortGuide by vm.sortGuide.collectAsStateWithLifecycle()
    val categoryFilter by vm.categoryFilter.collectAsStateWithLifecycle()
    val guideWidthShares by vm.guideWidthShares.collectAsStateWithLifecycle()
    val catchupPlayer by vm.catchupPlayer.collectAsStateWithLifecycle()
    val recordingRows by vm.recordingRows.collectAsStateWithLifecycle()
    val reminderRows by vm.reminderRows.collectAsStateWithLifecycle()
    val leadMinutes by vm.reminderLeadMinutes.collectAsStateWithLifecycle()
    val railItems by liveVm.railItems.collectAsStateWithLifecycle()
    val liveSelected by liveVm.selectedKey.collectAsStateWithLifecycle()
    val playlistMarks by liveVm.playlistMarks.collectAsStateWithLifecycle()
    val livePreviewSetting by liveVm.livePreviewEnabled.collectAsStateWithLifecycle()
    // KevBox: read below only where the focus preview is on. Read here, it re-ran the whole screen on
    // every ▲ ▼, since each focus move sets the preview channel even with the preview off.
    val previewChannelState = liveVm.previewChannel.collectAsStateWithLifecycle()
    val previewArmed by liveVm.previewArmed.collectAsStateWithLifecycle()
    val guidePreview by vm.guidePreview.collectAsStateWithLifecycle()
    // KevBox: true while the playlist's first sync runs (see the grid's empty states).
    val firstSync = tv.own.owntv.kevbox.live.rememberPlaylistFirstSync()

    // Live TV's Guide view shows Live TV's own category; the TV Guide keeps its own choice.
    val categoryKey: LiveKey? = if (liveMode) liveSelected.takeUnless { it == LiveKey.All } else categoryFilter
    // KevBox: in Live TV it also keeps the guide state from before another category was asked for, until
    // that category's channels are in, so ▶ in the category column never enters a channel list that is
    // about to be replaced.
    var categoryLoadFrom by remember { mutableStateOf<EpgUiState?>(null) }
    LaunchedEffect(liveMode, liveSelected) {
        if (!liveMode) return@LaunchedEffect
        val from = vm.state.value.takeIf { vm.categoryFilter.value != liveSelected.takeUnless { it == LiveKey.All } }
        vm.setCategoryFilter(liveSelected)
        if (from == null) { categoryLoadFrom = null; return@LaunchedEffect }
        categoryLoadFrom = from
        try { vm.state.first { it !== from && !it.loading } } finally { categoryLoadFrom = null }
    }
    LaunchedEffect(Unit) { vm.load() } // reload from DB each time the guide is opened

    // The focused channel plays in the video after the focus settles, as in Live TV.
    val effectivePreview = previewEnabled && livePreviewSetting && guidePreview
    // Switching the Guide's own preview off stops what is already playing, not only the next channel.
    LaunchedEffect(guidePreview) { if (!guidePreview && previewEnabled) liveVm.stopPreview() }
    LaunchedEffect(if (effectivePreview) previewChannelState.value?.id else null, effectivePreview, previewArmed) { // KevBox: see previewChannelState
        if (!effectivePreview || !previewArmed) return@LaunchedEffect
        val ch = previewChannelState.value ?: return@LaunchedEffect
        delay(700)
        liveVm.playPreview(ch)
    }
    // KevBox: with the focus preview off (KevBox Live's default), OK puts a channel in the top box and only
    // OK on the channel already there opens it full screen (Kevin, 2026-10-14). This is the channel OK put
    // there; it keeps playing while focus moves on. KevBox: it lives on the shell's video stage
    // (kevbox/live/LiveVideoStage.kt), so it outlasts full screen and Back brings it back to the box.
    val videoStage = tv.own.owntv.kevbox.live.LocalLiveVideoStage.current
    val localBoxChannel = remember { mutableStateOf<ChannelEntity?>(null) }
    fun boxChannel(): ChannelEntity? = if (videoStage != null) videoStage.boxChannel else localBoxChannel.value
    fun putInBox(ch: ChannelEntity) { if (videoStage != null) videoStage.putInBox(ch, tv.own.owntv.core.nav.MainSection.LIVE_TV) else localBoxChannel.value = ch }
    val okPreviewsFirst = liveMode && !effectivePreview
    // With the focus preview on, the focused channel is already in the box, so one OK goes full screen.
    fun inBox(ch: ChannelEntity): Boolean =
        if (okPreviewsFirst) boxChannel()?.id == ch.id else previewArmed && previewChannelState.value?.id == ch.id // KevBox: see previewChannelState

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val rowListState = rememberLazyListState()
    LaunchedEffect(rowListState.firstVisibleItemIndex > 0) { onContentScrolled(rowListState.firstVisibleItemIndex > 0) }

    // --- The time axis: one scroll position shared by the ruler, the now-line and every row. ----------
    val ppm = with(density) { GuideGridDefaults.PxPerMin.toPx() }
    var timelinePx by remember { mutableIntStateOf(0) }
    var scrollPx by remember { mutableIntStateOf(0) }
    fun pxAt(t: Long): Float = (t - state.windowStart) / 60_000f * ppm
    fun maxScroll(): Int = (pxAt(state.windowEnd) - timelinePx).toInt().coerceAtLeast(0)
    fun timeAt(px: Float): Long = state.windowStart + (px / ppm * 60_000f).toLong()
    // A time is shown 3/8 of the way across, as the guide always had it: the programme on now keeps room
    // on both sides and its title is never cut (owner, 2026-09-30).
    fun showTime(t: Long) {
        scrollPx = (pxAt(t) - timelinePx * 3f / 8f).toInt().coerceIn(0, maxScroll())
    }
    LaunchedEffect(state.windowStart, timelinePx, state.channels.isNotEmpty()) {
        if (timelinePx > 0 && state.channels.isNotEmpty()) showTime(System.currentTimeMillis())
    }

    // --- Rows, focus and the cursor ------------------------------------------------------------------
    val rowRequesters = remember { HashMap<Int, FocusRequester>() }
    fun rowFocus(i: Int): FocusRequester = rowRequesters.getOrPut(i) { FocusRequester() }
    var focusedRow by remember { mutableIntStateOf(0) }
    var gridHasFocus by remember { mutableStateOf(false) }
    var cursorTime by remember { mutableStateOf(0L) }
    // Row stage (false): ▲ ▼ move between channels, ◀ leaves for the menu. Cell stage (true): ◀ ▶ go
    // through the programmes. Without it, reaching the channel list meant ◀ past every programme.
    var inCellMode by remember { mutableStateOf(false) }
    BackHandler(enabled = inCellMode && gridHasFocus) { inCellMode = false }
    // The programme the block on top shows and the buttons act on: the one under the cursor.
    var selected by remember { mutableStateOf<Pair<ChannelEntity, EpgProgrammeEntity?>?>(null) }

    // ▲ ▼ by row number (playbook 2): scroll just enough, wait a frame, focus. A held key keeps counting
    // from the target, and the newest press cancels the step still in flight.
    var stepTarget by remember { mutableStateOf<Int?>(null) }
    var stepJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun stepTo(i: Int) {
        stepTarget = i
        stepJob?.cancel()
        stepJob = scope.launch {
            val info = rowListState.layoutInfo
            val row = info.visibleItemsInfo.firstOrNull { it.index == i }
            val step = ((info.visibleItemsInfo.firstOrNull()?.size ?: 0) + info.mainAxisItemSpacing).toFloat()
            val delta = when {
                row == null -> if (i > (info.visibleItemsInfo.lastOrNull()?.index ?: 0)) step else -step
                row.offset - step < info.viewportStartOffset -> row.offset - step - info.viewportStartOffset
                row.offset + row.size + step > info.viewportEndOffset -> row.offset + row.size + step - info.viewportEndOffset
                else -> 0f
            }
            if (delta != 0f) runCatching { rowListState.scrollBy(delta) }
            if (rowListState.layoutInfo.visibleItemsInfo.none { it.index == i }) runCatching { rowListState.scrollToItem(i) }
            withFrameNanos { }
            runCatching { rowFocus(i).requestFocus() }
            stepTarget = null
        }
    }
    // Back to the grid at the row focus was on (after a menu, the sheet, a jump).
    fun focusGrid(row: Int = focusedRow) {
        val i = row.coerceIn(0, (state.channels.size - 1).coerceAtLeast(0))
        scope.launch {
            val visible = rowListState.layoutInfo.visibleItemsInfo.any { it.index == i }
            if (!visible) runCatching { rowListState.scrollToItem(i) }
            withFrameNanos { }
            if (!runCatching { rowFocus(i).requestFocus() }.getOrDefault(false)) {
                withFrameNanos { }
                runCatching { rowFocus(i).requestFocus() }
            }
        }
    }
    // KevBox: Live TV's category column (kevbox/live/GuideSidebar.kt). It sits beside the main menu and the
    // guide is either moved right to make room for both (collapsed) or slid back over them to fill the screen
    // (expanded). The layout follows focus (GuideSidebar.expanded, applied below); it stays false outside
    // Live TV.
    var expanded by remember { mutableStateOf(false) }
    // The cursor's row in the column (an index into the category list); null on its search field.
    var columnCursor by remember { mutableStateOf<Int?>(null) }
    var columnHasFocus by remember { mutableStateOf(false) }
    val columnFocus = remember { FocusRequester() }
    // ▶ from the main menu and ◀ / Back on a row bump this: the column then asks for focus over a few
    // frames (see below), and columnEntering holds the layout collapsed until it has it.
    var columnEntry by remember { mutableIntStateOf(0) }
    var columnEntering by remember { mutableStateOf(false) }
    // KevBox: a pending move from the category column to the grid, carried out by the effect further down.
    var sidebarExit by remember { mutableStateOf<SidebarExit?>(null) }
    fun enterColumn() {
        sidebarExit = null
        columnEntering = true
        columnEntry++
    }
    // ◀ or Back on a channel row: back to the column, on the open category.
    fun leaveRow() {
        enterColumn()
        runCatching { columnFocus.requestFocus() }
    }
    // ◀ or Back in the column: on to the main menu, dropping a move to the grid still waiting for its channels.
    fun openMenu() {
        sidebarExit = null
        onOpenMenu()
    }
    // The cursor came to rest on category [idx] in the column: the guide behind shows it, from its first row.
    fun showCategory(idx: Int) {
        val key = railItems.getOrNull(idx)?.key ?: return
        if (key == liveSelected) return
        // Pending from now, not from when the effect above next runs: ▶ in between must not enter.
        categoryLoadFrom = state
        selected = null
        liveVm.select(key)
        focusedRow = 0
        scope.launch { runCatching { rowListState.scrollToItem(0) } }
    }
    // The cursor stays in view: scroll only when it reaches an edge, by one slot of room.
    LaunchedEffect(cursorTime, timelinePx) {
        if (!gridHasFocus || timelinePx <= 0 || cursorTime <= 0L) return@LaunchedEffect
        val x = pxAt(cursorTime)
        val margin = GuideGridDefaults.SlotMin * ppm
        when {
            x < scrollPx + margin -> scrollPx = (x - margin).toInt().coerceIn(0, maxScroll())
            x > scrollPx + timelinePx - margin -> scrollPx = (x - timelinePx + margin).toInt().coerceIn(0, maxScroll())
        }
    }

    // Back from a channel tuned in the guide: its row again, once the reload has the grid on screen.
    LaunchedEffect(restoreFocus, state.loading, state.channels.size) {
        if (!restoreFocus || state.loading || state.channels.isEmpty()) return@LaunchedEffect
        val idx = vm.lastTunedChannelId?.let { id -> state.channels.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: 0
        focusedRow = idx
        // KevBox: in Live TV, through the same move into the grid as ▶ from the column: the guide stays
        // expanded while the shell parks focus on the main menu, and the one-shot is spent only once the
        // move has landed or given up, so the shell keeps its menu hidden until then.
        if (liveMode) {
            sidebarExit = SidebarExit(row = idx, oldState = null)
            androidx.compose.runtime.snapshotFlow { sidebarExit }.first { it == null }
            onRestored()
            return@LaunchedEffect
        }
        delay(80)
        focusGrid(idx)
        onRestored()
    }

    // KevBox: Search's "Go to channel" lands here, Live TV having no list view. LiveViewModel.revealChannel
    // has already picked the channel's category; once the grid shows that category with the channel in
    // it, focus its row.
    val reveal by liveVm.reveal.collectAsStateWithLifecycle()
    LaunchedEffect(reveal, liveSelected, state.loading, state.channels) {
        val r = reveal ?: return@LaunchedEffect
        if (!liveMode || state.loading || liveSelected != r.key) return@LaunchedEffect
        val idx = state.channels.indexOfFirst { it.id == r.id }.takeIf { it >= 0 } ?: return@LaunchedEffect
        focusedRow = idx
        // Through the same move into the grid as ▶ from the column, which expands the guide and retries.
        sidebarExit = SidebarExit(row = idx, oldState = null)
        liveVm.revealDone()
    }

    // --- Overlays --------------------------------------------------------------------------------------
    var menuFor by remember { mutableStateOf<Pair<ChannelEntity, EpgProgrammeEntity>?>(null) }
    var channelMenuFor by remember { mutableStateOf<ChannelEntity?>(null) }
    var recordMenuFor by remember { mutableStateOf<Pair<ChannelEntity, EpgProgrammeEntity>?>(null) }
    var askPlayerFor by remember { mutableStateOf<Pair<ChannelEntity, EpgProgrammeEntity>?>(null) }
    var matchingChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    var offsetChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    var orderOpen by remember { mutableStateOf(false) }
    var dayOpen by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }
    var sheetHadFocus by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    // The field reports "not focused" when it first attaches; it only closes after it has had focus (playbook 10).
    var searchHadFocus by remember { mutableStateOf(false) }
    // Where focus goes back to when an overlay closes: the control or button that opened it.
    var returnTo by remember { mutableStateOf<(() -> Unit)?>(null) }
    val anyOverlay = menuFor != null || channelMenuFor != null || recordMenuFor != null || askPlayerFor != null || matchingChannel != null ||
        offsetChannel != null || orderOpen || dayOpen || review.isNotEmpty()
    var hadOverlay by remember { mutableStateOf(false) }
    LaunchedEffect(anyOverlay) {
        if (anyOverlay) { hadOverlay = true; return@LaunchedEffect }
        if (!hadOverlay) return@LaunchedEffect
        hadOverlay = false
        delay(60)
        (returnTo ?: { focusGrid() }).invoke()
    }
    // Leaving the grid (not for one of its own menus) goes back to the row stage.
    LaunchedEffect(gridHasFocus, anyOverlay) { if (!gridHasFocus && !anyOverlay) inCellMode = false }
    // The one-line outcome beside Auto-match EPG goes after a while (the review, when there is one, says it instead).
    LaunchedEffect(matchSummary, review.isEmpty()) {
        if (matchSummary != null && review.isEmpty()) { delay(8_000); vm.clearSummary() }
    }

    val channelsNow = state.channels
    val watch: (ChannelEntity) -> Unit = { ch -> vm.noteChannelTuned(ch); onPlayChannel(ch, channelsNow) }
    val playFromStart: (ChannelEntity, EpgProgrammeEntity) -> Unit = { ch, p ->
        when (catchupPlayer) {
            SettingsRepository.CatchupPlayer.ASK -> askPlayerFor = ch to p
            SettingsRepository.CatchupPlayer.INTERNAL -> { vm.noteChannelTuned(ch); onPlayCatchup(ch, p) }
            SettingsRepository.CatchupPlayer.EXTERNAL -> { vm.noteChannelTuned(ch); vm.playCatchupExternal(ch, p) }
        }
    }
    // OK on a programme: watch — the archive for one that has been on and can be replayed, else the channel.
    val onOk: (ChannelEntity, EpgProgrammeEntity?) -> Unit = { ch, p ->
        val now = System.currentTimeMillis()
        // KevBox: in Live TV a channel must be in the top box before OK opens it full screen; the first OK
        // only previews it there.
        if (liveMode && previewEnabled && !inBox(ch)) {
            putInBox(ch) // KevBox: on the video stage
            liveVm.onChannelFocused(ch)
            liveVm.playPreview(ch)
        } else if (p != null && p.stopMs <= now && vm.canCatchup(ch, p, now)) playFromStart(ch, p) else watch(ch)
    }

    /** What [p] on [ch] can do right now — the programme menu, Record ▾ and the buttons on top share it. */
    @Composable
    fun actionsFor(ch: ChannelEntity, p: EpgProgrammeEntity): GuideProgrammeActions = guideProgrammeActions(
        vm, ch, p, liveNow,
        onWatch = { watch(ch) },
        onPlayFromStart = { playFromStart(ch, p) },
        onPickEpg = { matchingChannel = ch },
        onEpgOffset = { offsetChannel = ch },
    )

    // Channel names as the rows show them: provider tags parsed off, and the category's own name left out
    // when every channel in it starts with it ("Sky Cinema Highlights" under Sky Cinema → "Highlights").
    val (entries, groupsHeading) = liveCategoryEntries(railItems, emptyMap())
    val categoryEntry = entries.firstOrNull { it.item.key == (categoryKey ?: LiveKey.All) }
    val categoryLabel = categoryEntry?.label ?: stringResource(R.string.content_category_all_channels)
    val prefix = categoryLabel.takeIf { categoryKey is LiveKey.Folder || categoryKey is LiveKey.Custom }
    fun rowName(ch: ChannelEntity): String {
        val name = ProviderTags.parse(ch.name).name
        val rest = prefix?.let { p -> name.takeIf { it.startsWith("$p ", ignoreCase = true) }?.substring(p.length)?.trim() }
        return rest?.takeIf { it.isNotEmpty() } ?: name
    }
    val remindersByChannel = remember(reminderRows) { reminderRows.groupBy({ it.channelId }, { it.startMs }).mapValues { it.value.toSet() } }
    val recordingsByChannel = remember(recordingRows) {
        recordingRows.filter { it.status != RecordingStatus.CANCELLED }
            .groupBy({ it.channelId }, { it.programmeStartMs }).mapValues { it.value.toSet() }
    }

    val dayTool = remember { FocusRequester() }
    val categoryTool = remember { FocusRequester() }
    val orderTool = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val sheetFocus = remember { FocusRequester() }
    val catListState = rememberLazyListState()

    DisposableEffect(onEntryHook) {
        onEntryHook?.invoke {
            // KevBox: in Live TV ▶ from the main menu moves to the category column, never the grid, and nothing
            // on screen moves: the column is the one way between the menu and the channels, both ways (Kevin,
            // 2026-10-13). It also keeps an empty category reachable, where the grid has no row to land on.
            if (liveMode) { enterColumn(); return@invoke true }
            state.channels.isNotEmpty() &&
                runCatching { rowFocus(focusedRow.coerceIn(0, state.channels.size - 1)).requestFocus() }.getOrDefault(false)
        }
        onDispose { onEntryHook?.invoke(null) }
    }

    BackHandler(enabled = sheetOpen) { sheetOpen = false; runCatching { categoryTool.requestFocus() } }
    // KevBox: Back on a Live TV channel row (not inside its programmes) goes back to the category column;
    // Back in the column goes on to the main menu, and Back there to the exit prompt. A search typed in
    // the column is cleared first: LiveCategories' own Back handler comes later and wins while it is on.
    BackHandler(enabled = liveMode && gridHasFocus && !inCellMode) { leaveRow() }
    BackHandler(enabled = liveMode && columnHasFocus) { openMenu() }
    // KevBox: opened from Search by holding OK, the first Back from the grid or the column goes back to
    // Search instead (Kevin, 2026-10-14). Composed after the two above, so it wins over them.
    BackHandler(enabled = liveMode && onBackToSearch != null && ((gridHasFocus && !inCellMode) || columnHasFocus)) {
        onBackToSearch?.invoke()
    }

    // KevBox: where a pending move into the grid stands. A category change waits for a load after it: a
    // new state, as two empty lists can be one object.
    fun exitStatus(exit: SidebarExit): GuideSidebar.Exit =
        GuideSidebar.exit(state.loading, exit.oldState != null && state === exit.oldState, state.channels.size)
    // KevBox: moving into the grid (▶ or OK on a category, back from full screen, a reveal). A plain
    // focusGrid() lost focus to the main menu: on a new category it focused the old category's row, which
    // went away when the new channels arrived. So after a category change this waits until the channel
    // list is a new one, then focuses the row and checks over a few frames that the grid kept it. From the
    // moment the channels are in until it ends, the guide is expanded (see the layout rule below).
    LaunchedEffect(sidebarExit, state) {
        val exit = sidebarExit ?: return@LaunchedEffect
        when (exitStatus(exit)) {
            GuideSidebar.Exit.WAIT -> return@LaunchedEffect
            // An empty category has no row to land on: the cursor stays on it in the column.
            GuideSidebar.Exit.EMPTY -> { sidebarExit = null; return@LaunchedEffect }
            GuideSidebar.Exit.READY -> Unit
        }
        val i = exit.row.coerceIn(0, state.channels.size - 1)
        focusedRow = i
        repeat(10) {
            withFrameNanos { }
            if (rowListState.layoutInfo.visibleItemsInfo.none { it.index == i }) runCatching { rowListState.scrollToItem(i) }
            withFrameNanos { }
            if (!gridHasFocus) runCatching { rowFocus(i).requestFocus() }
            withFrameNanos { }
            if (gridHasFocus) { sidebarExit = null; return@LaunchedEffect }
        }
        sidebarExit = null
    }
    // KevBox: ▶ or OK on category [idx] in the column (GuideSidebar.rightOrOk): select it first when the
    // guide does not show it yet, enter the grid when it does, or stay on an empty one.
    fun rightOrOk(idx: Int) {
        val key = railItems.getOrNull(idx)?.key ?: return
        when (GuideSidebar.rightOrOk(key, liveSelected, state.channels.size, categoryLoadFrom != null || state.loading)) {
            GuideSidebar.Move.SELECT_THEN_ENTER -> {
                // The open category still loading waits for the state after the one it was asked from.
                val exit = if (key == liveSelected) SidebarExit(row = focusedRow, oldState = categoryLoadFrom)
                else SidebarExit(row = 0, oldState = state)
                showCategory(idx)
                sidebarExit = exit
            }
            GuideSidebar.Move.ENTER -> sidebarExit = SidebarExit(row = focusedRow, oldState = null)
            GuideSidebar.Move.STAY -> Unit
        }
    }
    // KevBox: the user on the main menu drops a move to the grid still waiting for its channels. One that
    // has them goes on: back from full screen the shell parks focus on the menu until the row takes it.
    LaunchedEffect(menuInUse) {
        val exit = sidebarExit
        if (menuInUse && exit != null && exitStatus(exit) == GuideSidebar.Exit.WAIT) sidebarExit = null
    }
    LaunchedEffect(expanded) { if (liveMode) onExpandedChange(expanded) } // KevBox: the shell hides its menu
    // KevBox: the cursor resting on a category for a moment shows it in the guide behind. Each step restarts
    // the wait, so a held key loads none of the categories it passes.
    LaunchedEffect(columnCursor, columnHasFocus) {
        val idx = columnCursor ?: return@LaunchedEffect
        if (!columnHasFocus) return@LaunchedEffect
        delay(GuideSidebar.REST_SELECT_MS)
        showCategory(idx)
    }
    // KevBox: asks again until the column has focus. Sent from the main menu, the first request comes in
    // from outside the shell's content, whose focus restorer sends it to the grid row last focused; from
    // there the next request is a move inside the content and lands. columnEntering stays on until then,
    // so that row's focus leaves the layout collapsed (GuideSidebar.Entry.INTO_COLUMN).
    LaunchedEffect(columnEntry) {
        if (columnEntry == 0) return@LaunchedEffect
        repeat(6) {
            withFrameNanos { }
            if (columnHasFocus) { columnEntering = false; return@LaunchedEffect }
            runCatching { columnFocus.requestFocus() }
        }
        withFrameNanos { }
        columnEntering = false
    }
    // KevBox: the layout follows focus (GuideSidebar.expanded), and a move this screen has started outranks
    // where focus sits on its way. A move into the grid is under way while a pending one has its channels,
    // while the way back from full screen waits for the restore above to start its move (restoreFocus with
    // the channels in: the frame before that effect runs), and while a reveal waits for its category to
    // load. Through this rule alone, focus never stays in the column with the guide expanded (Up from the
    // first row, a move that never landed) nor on a row with it collapsed (▶ out of a typed column search).
    val gridEntryUnderWay = liveMode && (
        sidebarExit?.let { exitStatus(it) == GuideSidebar.Exit.READY } == true ||
            (restoreFocus && !state.loading && state.channels.isNotEmpty()) ||
            reveal?.let { it.key == liveSelected && (state.loading || categoryLoadFrom != null) } == true
        )
    val focusAt = when {
        gridHasFocus -> GuideSidebar.Focus.GRID
        columnHasFocus -> GuideSidebar.Focus.COLUMN
        menuInUse -> GuideSidebar.Focus.MENU
        else -> GuideSidebar.Focus.ELSEWHERE
    }
    val entryUnderWay = when {
        gridEntryUnderWay -> GuideSidebar.Entry.INTO_GRID
        columnEntering -> GuideSidebar.Entry.INTO_COLUMN
        else -> GuideSidebar.Entry.NONE
    }
    LaunchedEffect(focusAt, entryUnderWay) {
        if (liveMode) expanded = GuideSidebar.expanded(expanded, focusAt, entryUnderWay)
    }
    // KevBox: the slide between the two layouts: 0 collapsed, 1 expanded. The guide and the column read it
    // only inside their graphicsLayer blocks, so a slide neither recomposes nor re-lays out the guide. The
    // video window under the top box reads the same value (LiveVideoStage.boxSlide), so it moves in step.
    val slide = remember { Animatable(0f) }
    val slideSpec = ownTvTween<Float>(GuideSidebar.SLIDE_MS)
    LaunchedEffect(expanded) { slide.animateTo(if (expanded) 1f else 0f, slideSpec) }
    val slideTravelPx = with(density) { (menuRoom + GuideSidebar.COLUMN_WIDTH_MPX.mpx).toPx() }
    DisposableEffect(videoStage, liveMode, slideTravelPx) {
        if (liveMode) videoStage?.boxSlide = { (1f - slide.value) * slideTravelPx }
        onDispose { if (liveMode) videoStage?.boxSlide = null }
    }

    LaunchedEffect(sheetOpen) {
        if (sheetOpen) { withFrameNanos { }; runCatching { sheetFocus.requestFocus() } } else sheetHadFocus = false
    }
    LaunchedEffect(searchOpen) { if (searchOpen) { withFrameNanos { }; runCatching { searchFocus.requestFocus() } } }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            // Entering from the rail lands in the grid, on the row focus was on.
            // KevBox: in Live TV with the guide making room for the column, on the column's open category.
            .focusProperties {
                onEnter = {
                    if (liveMode && !expanded) runCatching { columnFocus.requestFocus() }
                    else if (state.channels.isNotEmpty()) runCatching { rowFocus(focusedRow.coerceIn(0, state.channels.size - 1)).requestFocus() }
                }
            }
            .focusGroup(),
    ) {
        val screenW = maxWidth
        fun fx(px: Int) = screenW * (px / 1920f)
        val labelW = guideWidthShares?.let { (screenW - fx(112)) * (it.channels / 100f) } ?: (screenW * GuideGridDefaults.ChannelColShare)
        val timelineW = screenW - fx(48) - labelW - fx(64)
        LaunchedEffect(timelineW) { timelinePx = with(density) { timelineW.roundToPx() } }

        val top = selected ?: state.channels.getOrNull(focusedRow)?.let { ch -> ch to programmeAt(vm.cachedProgrammes(ch), liveNow) }
        val topChannel = top?.first
        val topProgramme = top?.second
        val full by produceState<EpgProgrammeEntity?>(null, topProgramme?.id) {
            value = topProgramme?.id?.takeIf { it > 0 }?.let { vm.programmeFull(it) }
        }
        val shown = full ?: topProgramme

        // KevBox: in Live TV the guide keeps its full-screen layout and slides on a layer: right by the
        // menu's room plus the column while they show (its right part off the screen edge), back to 0 expanded.
        Column(Modifier.fillMaxSize().then(if (liveMode) Modifier.graphicsLayer { translationX = (1f - slide.value) * slideTravelPx } else Modifier)) {
        // Top (44 → 404): the video, and beside it the programme under the cursor.
        Row(Modifier.padding(start = fx(64), end = fx(64), top = 44.mpx).fillMaxWidth().height(360.mpx)) {
        // KevBox: the channel OK put in the box stays there (with its video) while focus moves on.
        val box = boxChannel().takeIf { okPreviewsFirst && previewEnabled }
        val videoChannel = box ?: if (effectivePreview) previewChannelState.value ?: topChannel else topChannel // KevBox: see previewChannelState
        GuideVideo(
            channel = videoChannel,
            channelLine = videoChannel?.let { listOfNotNull(it.number?.toString(), ProviderTags.parse(it.name).name).joinToString(" · ") },
            nowTitle = videoChannel?.let { ch -> programmeAt(vm.cachedProgrammes(ch), liveNow)?.takeIf { liveNow < it.stopMs }?.title },
            previewEngine = liveVm.previewEngine,
            showVideo = (effectivePreview && previewArmed) || box != null, // KevBox: `|| box != null`
            modifier = Modifier.width(640.mpx),
        )
        // … and the programme under the cursor, with what it can do.
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (topChannel != null) {
                val chName = ProviderTags.parse(topChannel.name).name
                GuideProgrammeBlock(
                    eyebrow = shown?.let { programmeEyebrow(it, chName, liveNow) },
                    title = shown?.title ?: chName,
                    details = if (shown == null) stringResource(R.string.content_epg_no_programme) else programmeDetailsLine(shown),
                    synopsis = shown?.description,
                    modifier = Modifier.padding(start = 46.mpx, top = 76.mpx).widthIn(max = 1080.mpx),
                )
            }
            // The key hints at the bottom right of the top area, level with the video's bottom edge (owner, 2026-10-01).
            // KevBox: liveMode, so ◀ reads "Categories" in Live TV, and OK reads "Preview" on a channel not yet in the box.
            val okPreviews = liveMode && previewEnabled && topChannel != null && !inBox(topChannel)
            Box(Modifier.align(Alignment.BottomEnd)) { GuideKeyHints(cellMode = inCellMode && gridHasFocus, liveMode = liveMode, okPreviews = okPreviews) }
        }

        }

        // The control line (452): one row, every header button of the old guide kept.
        val viewTime = timeAt(scrollPx + timelinePx * 3f / 8f)
        val viewDay = localMidnight(viewTime)
        // KevBox: Live TV has no control line (title, day, Now, category, order, search, List view,
        // Auto-match EPG); the category list is the column beside the main menu. The TV Guide page keeps it.
        if (!liveMode) Row(
            Modifier.padding(start = fx(68), end = fx(64), top = 48.mpx).fillMaxWidth().height(48.mpx),
            horizontalArrangement = Arrangement.spacedBy(8.mpx),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(if (liveMode) R.string.common_nav_live_tv else R.string.content_epg_title),
                style = stageText(30, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.mpx, end = 14.mpx),
            )
            StageTool(
                text = null, icon = OwnTVIcon.CALENDAR, value = guideDayLabel(viewDay), trailingIcon = OwnTVIcon.CHEVRON_DOWN,
                onClick = { returnTo = { runCatching { dayTool.requestFocus() } }; dayOpen = true },
                modifier = Modifier.focusRequester(dayTool),
            )
            StageTool(text = stringResource(R.string.content_epg_now_short), icon = OwnTVIcon.NOW, onClick = { showTime(System.currentTimeMillis()); cursorTime = System.currentTimeMillis() })
            StageTool(
                text = null, icon = OwnTVIcon.LIST, value = categoryLabel, valueAccent = true, trailingIcon = OwnTVIcon.CHEVRON_DOWN,
                onClick = { sheetOpen = true },
                modifier = Modifier.focusRequester(categoryTool).widthIn(max = 380.mpx),
            )
            StageTool(
                text = null, icon = OwnTVIcon.SORT, value = guideSortLabel(sortGuide), trailingIcon = OwnTVIcon.CHEVRON_DOWN,
                onClick = { returnTo = { runCatching { orderTool.requestFocus() } }; orderOpen = true },
                modifier = Modifier.focusRequester(orderTool),
            )
            if (searchOpen || query.isNotEmpty()) {
                StageSearchField(
                    query = query, onQueryChange = vm::setQuery,
                    placeholder = stringResource(R.string.content_epg_search_hint).trimEnd('…'),
                    modifier = Modifier.width(360.mpx).focusRequester(searchFocus)
                        .onFocusChanged {
                            if (it.hasFocus) searchHadFocus = true
                            else if (searchHadFocus && query.isEmpty()) { searchHadFocus = false; searchOpen = false }
                        },
                )
            } else {
                StageTool(text = null, icon = OwnTVIcon.SEARCH, onClick = { searchOpen = true })
            }
            if (liveMode) StageTool(text = stringResource(R.string.content_live_list_view), icon = OwnTVIcon.LIST, onClick = onListView)
            Spacer(Modifier.weight(1f))
            matchSummary?.takeIf { review.isEmpty() }?.let {
                Text(
                    epgMatchSummaryText(it), style = stageText(16, 600), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    // A capped width, not a weight: a second weight split the line and moved Auto-match off the edge.
                    modifier = Modifier.widthIn(max = 620.mpx), textAlign = TextAlign.End,
                )
            }
            if (matching) {
                Row(horizontalArrangement = Arrangement.spacedBy(9.mpx), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.mpx)) {
                    OwnTVSpinner(sizeDp = 18)
                    Text(stringResource(R.string.content_epg_matching), style = stageText(18, 700), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                StageTool(text = stringResource(R.string.content_epg_match_button), icon = OwnTVIcon.SPARKLE, onClick = vm::autoMatchEpg)
            }
        }

        // The grid: ruler at 518, rows from 566.
        Box(Modifier.fillMaxWidth().weight(1f).padding(top = 18.mpx)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { OwnTVSpinner(sizeDp = 44) }
                // KevBox: while the playlist is added and its channels download for the first time, a
                // spinner, never "No EPG added" or "Add a playlist": the app adds both by itself.
                liveMode && firstSync && state.channels.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { OwnTVSpinner(sizeDp = 44) }
                // No EPG feed added yet → a guide it can't fill. Point the user to EPG Sources.
                // KevBox: never in Live TV, where the playlist's own guide is added without asking.
                !liveMode && !state.hasEpgSources && state.channels.isEmpty() -> GuideEmpty(
                    title = stringResource(R.string.content_epg_empty),
                    body = stringResource(R.string.content_epg_add_description),
                    action = stringResource(R.string.content_epg_add), onAction = onAddEpg,
                )
                state.channels.isEmpty() -> GuideEmpty(title = state.message?.let { epgMessageText(it) } ?: stringResource(R.string.content_epg_no_guide))
                else -> {
                    val gx = fx(48) + labelW
                    // The number column fits the longest channel number (38 = the mockup's three digits).
                    val numberWidth = maxOf(38f, (state.channels.maxOfOrNull { it.number?.toString()?.length ?: 0 } ?: 0) * 11f).mpx
                    GuideRuler(
                        windowStart = state.windowStart, windowEnd = state.windowEnd, now = liveNow, scrollPx = scrollPx,
                        modifier = Modifier.padding(start = gx).width(timelineW).height(40.mpx),
                    )
                    CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides edgeScrollSpec) {
                        LazyColumn(
                            state = rowListState,
                            modifier = Modifier
                                .padding(start = fx(48), end = fx(64), top = 48.mpx)
                                .fillMaxSize()
                                .onFocusChanged { gridHasFocus = it.hasFocus }
                                // KevBox: ◀ on a channel row (a row passes it on outside its programmes)
                                // goes back to the category column, as Back does, instead of jumping to the menu.
                                .onKeyEvent { e ->
                                    if (liveMode && !inCellMode && e.key == Key.DirectionLeft) {
                                        if (e.type == KeyEventType.KeyDown) leaveRow()
                                        true
                                    } else false
                                }
                                .focusProperties { onEnter = { runCatching { rowFocus(focusedRow.coerceIn(0, state.channels.size - 1)).requestFocus() } } }
                                .focusGroup(),
                            verticalArrangement = Arrangement.spacedBy(GuideGridDefaults.RowGap),
                            contentPadding = PaddingValues(bottom = 24.mpx),
                        ) {
                            itemsIndexed(state.channels, key = { _, ch -> ch.id }) { index, channel ->
                                // KevBox: each row reads only whether it is the selected one, so a ▲ ▼ re-runs the
                                // two rows that changed instead of every row on screen.
                                val isSelectedRow by remember(index) { derivedStateOf { gridHasFocus && index == focusedRow } }
                                GuideRow(
                                    vm = vm,
                                    channel = channel,
                                    name = rowName(channel),
                                    dot = playlistMarks[channel.sourceId]?.shownColor,
                                    labelWidth = labelW,
                                    numberWidth = numberWidth,
                                    windowStart = state.windowStart,
                                    windowEnd = state.windowEnd,
                                    now = liveNow,
                                    scrollPx = scrollPx,
                                    focusRequester = rowFocus(index),
                                    selectedRow = isSelectedRow, // KevBox: see isSelectedRow
                                    cursorTime = cursorTime,
                                    cellMode = inCellMode,
                                    onEnterCell = { cursorTime = System.currentTimeMillis(); inCellMode = true },
                                    recordingStarts = recordingsByChannel[channel.id].orEmpty(),
                                    reminderStarts = remindersByChannel[channel.id].orEmpty(),
                                    onFocused = {
                                        focusedRow = index
                                        if (cursorTime !in state.windowStart until state.windowEnd) cursorTime = System.currentTimeMillis()
                                        liveVm.onChannelFocused(channel)
                                    },
                                    onCursor = { cursorTime = it },
                                    onShow = { p -> selected = channel to p },
                                    onStep = { delta ->
                                        val from = stepTarget ?: index
                                        val to = from + delta
                                        when {
                                            // ▲ from the first row: up to the control line. KevBox: Live TV has
                                            // none, so ▲ stays on the first row (the focus search could pick the
                                            // category column hidden off screen).
                                            to < 0 -> liveMode
                                            to >= state.channels.size -> true
                                            else -> { stepTo(to); true }
                                        }
                                    },
                                    onOk = { p -> onOk(channel, p) },
                                    okOnRow = liveMode, // KevBox: OK on a Live TV row previews, then watches

                                    onMenu = { p -> returnTo = null; if (p != null) menuFor = channel to p else channelMenuFor = channel },
                                )
                            }
                        }
                    }
                    // The now-line, 3 px with its glow, over every row.
                    val nowX = with(density) { (pxAt(liveNow) - scrollPx).toDp() }
                    if (liveNow in state.windowStart..state.windowEnd && nowX >= 0.mpx && nowX <= timelineW) {
                        Box(
                            Modifier
                                .padding(start = gx + nowX - 1.5.mpx, top = 38.mpx, bottom = 24.mpx)
                                .width(3.mpx)
                                .fillMaxHeight()
                                .guideNowLine(stageAccent.accent),
                        )
                    }
                }
            }
        }

        }

        // KevBox: Live TV's category column, always composed, right of the main menu (GuideSidebar). It uses
        // LiveCategories' flat column with its search. Focus stays where the cursor is when a category loads,
        // and ◀ that nothing in it used (the search field keeps ◀ for its cursor) goes on to the main menu.
        // It slides off to the left with the guide's slide and is not drawn once the guide fills the screen.
        if (liveMode) {
            val railCounts by liveVm.railCounts.collectAsStateWithLifecycle()
            val (columnEntries, _) = liveCategoryEntries(railItems, railCounts)
            val openIndex = railItems.indexOfFirst { it.key == liveSelected }.coerceAtLeast(0)
            LiveCategories(
                searchQuery = vm.categoryQuery.collectAsStateWithLifecycle().value,
                onSearchQueryChange = vm::setCategoryQuery,
                entries = columnEntries,
                selectedIndex = openIndex,
                groupsHeading = groupsHeading,
                sheet = false,
                listState = catListState,
                onSelect = { idx -> rightOrOk(idx) },
                onLongSelect = {},
                onNavigateRight = { rightOrOk(columnCursor ?: openIndex) },
                focusRequester = columnFocus,
                // A move to another row or the search drops a move to the grid still waiting for its channels.
                onRowFocus = { columnCursor = it; sidebarExit = null },
                onSearchFocus = { columnCursor = null; sidebarExit = null },
                focusFollowsSelection = false,
                modifier = Modifier
                    .padding(start = menuRoom)
                    .width(GuideSidebar.COLUMN_WIDTH_MPX.mpx)
                    .fillMaxHeight()
                    .graphicsLayer {
                        val f = slide.value
                        translationX = -f * slideTravelPx
                        alpha = if (f >= 1f) 0f else 1f
                    }
                    .padding(top = 44.mpx, bottom = 24.mpx)
                    .onFocusChanged { columnHasFocus = it.hasFocus }
                    .onKeyEvent { e ->
                        if (e.key == Key.DirectionLeft) {
                            if (e.type == KeyEventType.KeyDown) openMenu()
                            true
                        } else false
                    },
            )
        }

        // ◀ … Category ▾ (P4-03): Live TV's own list as the sheet, titled for the guide.
        if (sheetOpen) {
            val railCounts by liveVm.railCounts.collectAsStateWithLifecycle()
            val (sheetEntries, _) = liveCategoryEntries(railItems, railCounts)
            LiveCategories(
                searchQuery = vm.categoryQuery.collectAsStateWithLifecycle().value,
                onSearchQueryChange = vm::setCategoryQuery,
                entries = sheetEntries,
                selectedIndex = railItems.indexOfFirst { it.key == (categoryKey ?: LiveKey.All) }.coerceAtLeast(0),
                groupsHeading = groupsHeading,
                sheet = true,
                listState = catListState,
                onSelect = { idx ->
                    railItems.getOrNull(idx)?.key?.let { key -> if (liveMode) liveVm.select(key) else vm.setCategoryFilter(key) }
                    sheetOpen = false
                    focusedRow = 0
                    scope.launch { runCatching { rowListState.scrollToItem(0) } }
                    focusGrid(0)
                },
                onLongSelect = {},
                onNavigateRight = { sheetOpen = false; focusGrid() },
                focusRequester = sheetFocus,
                sheetTitle = stringResource(R.string.content_epg_guide_category),
                sheetHint = stringResource(R.string.content_epg_category_hint),
                modifier = Modifier
                    .padding(start = fx(24), top = 24.mpx, bottom = 24.mpx)
                    .width(fx(450))
                    .fillMaxHeight()
                    .onFocusChanged {
                        // Closes when focus leaves it — not on the "unfocused" report every node gets when it attaches.
                        if (it.hasFocus) sheetHadFocus = true else if (sheetHadFocus && sheetOpen) sheetOpen = false
                    },
            )
        }
    }

    menuFor?.let { (ch, p) ->
        val formatTime = tv.own.owntv.ui.format.rememberSystemTimeFormatter()
        GuideProgrammeMenu(
            title = p.title,
            subtitle = listOf(
                stringResource(R.string.content_live_time_range_plain, formatTime(p.startMs), formatTime(p.stopMs)),
                listOfNotNull(ch.number?.toString(), ProviderTags.parse(ch.name).name).joinToString(" "),
            ).joinToString(" · "),
            actions = actionsFor(ch, p),
            leadMinutes = leadMinutes,
            onDismiss = { menuFor = null },
        )
    }
    recordMenuFor?.let { (ch, p) -> GuideRecordMenu(title = p.title, actions = actionsFor(ch, p), onDismiss = { recordMenuFor = null }) }
    askPlayerFor?.let { (ch, p) ->
        GuideCatchupChooser(
            onInternal = { vm.noteChannelTuned(ch); onPlayCatchup(ch, p) },
            onExternal = { vm.noteChannelTuned(ch); vm.playCatchupExternal(ch, p) },
            onDismiss = { askPlayerFor = null },
        )
    }
    if (orderOpen) {
        val showEmpty by vm.showEmpty.collectAsStateWithLifecycle()
        GuideOrderMenu(
            current = sortGuide, onPick = vm::setGuideSort,
            showEmpty = showEmpty, onShowEmpty = vm::setShowEmpty,
            showPreview = guidePreview, onShowPreview = vm::setGuidePreview,
            onDismiss = { orderOpen = false },
        )
    }
    channelMenuFor?.let { ch ->
        GuideChannelMenu(
            channel = ch,
            isFavorite = ch.id in vm.favoriteChannelIds.collectAsStateWithLifecycle().value,
            epgOffsetValue = vm.currentEpgShift(ch)?.let { liveEpgShiftLabel(it) } ?: stringResource(R.string.content_epg_offset_global_short),
            onWatch = { watch(ch) },
            onFavorite = { vm.toggleFavoriteChannel(ch) },
            onAutoMatch = { vm.autoMatchOne(ch) },
            onPickEpg = { matchingChannel = ch },
            onEpgOffset = { offsetChannel = ch },
            onDismiss = { channelMenuFor = null },
        )
    }
    if (dayOpen) {
        val days = remember(state.windowStart, state.windowEnd) {
            generateSequence(localMidnight(state.windowStart)) { localMidnight(it + 36L * 60 * 60 * 1000) }.takeWhile { it < state.windowEnd }.toList()
        }
        GuideDayMenu(
            days = days,
            currentDay = localMidnight(timeAt(scrollPx + timelinePx * 3f / 8f)),
            onPick = { day ->
                val now = System.currentTimeMillis()
                showTime(day + (now - localMidnight(now)))
            },
            onDismiss = { dayOpen = false },
        )
    }
    offsetChannel?.let { channel ->
        tv.own.owntv.features.live.EpgOffsetDialog(
            channelName = channel.name,
            currentMinutes = vm.currentEpgShift(channel),
            globalMinutes = vm.globalEpgShift(),
            onSet = { vm.setEpgShift(channel, it) },
            onDismiss = { offsetChannel = null },
        )
    }
    matchingChannel?.let { channel ->
        tv.own.owntv.features.live.EpgMatchDialog(
            channelName = channel.name,
            currentMatch = vm.currentEpgMatch(channel),
            loadChannels = { vm.availableEpgChannels(channel.name, it) },
            onPick = { vm.setEpgMatch(channel, it); matchingChannel = null },
            onClear = { vm.setEpgMatch(channel, null); matchingChannel = null },
            onDismiss = { matchingChannel = null },
        )
    }
    if (review.isNotEmpty()) {
        val includeLogos by vm.includeGuideLogos.collectAsStateWithLifecycle()
        val settingsVm: tv.own.owntv.features.settings.SettingsViewModel = koinViewModel()
        val chNavEnabled by settingsVm.chNavEnabled.collectAsStateWithLifecycle()
        val chNavUpSkip by settingsVm.chNavUpSkip.collectAsStateWithLifecycle()
        val chNavDownSkip by settingsVm.chNavDownSkip.collectAsStateWithLifecycle()
        GuideReviewPopup(
            suggestions = review,
            matchedCount = (matchSummary as? EpgMatchSummary.AutoMatched)?.applied,
            includeLogos = includeLogos,
            onIncludeLogos = vm::setIncludeGuideLogos,
            onAccept = vm::acceptSuggestion,
            onSkip = vm::dismissSuggestion,
            onAcceptAll = vm::acceptAllSuggestions,
            onSkipAll = vm::clearReview,
            onDone = vm::clearReview,
            chNavEnabled = chNavEnabled,
            chNavUpSkip = chNavUpSkip,
            chNavDownSkip = chNavDownSkip,
        )
    }
}

/**
 * One guide row: the channel label, then the programmes as one focus target with a cursor. ◀ ▶ move the
 * cursor programme by programme (◀ from the first leaves to the rail), ▲ ▼ step rows ([onStep]), OK
 * watches, holding OK or ☰ opens the programme menu. Programmes are read when the row comes into view.
 */
@Composable
private fun GuideRow(
    vm: EpgViewModel,
    channel: ChannelEntity,
    name: String,
    dot: Color?,
    labelWidth: androidx.compose.ui.unit.Dp,
    numberWidth: androidx.compose.ui.unit.Dp,
    windowStart: Long,
    windowEnd: Long,
    now: Long,
    scrollPx: Int,
    focusRequester: FocusRequester,
    selectedRow: Boolean,
    cursorTime: Long,
    cellMode: Boolean,
    onEnterCell: () -> Unit,
    recordingStarts: Set<Long>,
    reminderStarts: Set<Long>,
    onFocused: () -> Unit,
    onCursor: (Long) -> Unit,
    onShow: (EpgProgrammeEntity?) -> Unit,
    onStep: (Int) -> Boolean,
    onOk: (EpgProgrammeEntity?) -> Unit,
    onMenu: (EpgProgrammeEntity?) -> Unit,
    // KevBox: OK on the row (outside its programmes) goes to onOk(null) instead of into the programmes.
    okOnRow: Boolean = false,
) {
    // Cache peek first, so a row scrolled back into view draws at once; a miss reads this one channel.
    val cacheRevision by vm.cacheRevision.collectAsStateWithLifecycle()
    val programmes by produceState(initialValue = vm.cachedProgrammes(channel), channel.id, windowStart, cacheRevision) {
        value = vm.cachedProgrammes(channel) ?: vm.programmesFor(channel)
        // Still empty: ask again a little later, twice, before the row settles on "No program".
        repeat(2) {
            if (value?.isNotEmpty() == true) return@produceState
            delay(3_000)
            value = vm.programmesFor(channel)
        }
    }
    var focused by remember { mutableStateOf(false) }
    var okDown by remember { mutableStateOf(false) }
    LaunchedEffect(focused, cursorTime, programmes) { if (focused) onShow(programmeAt(programmes, cursorTime)) }
    val rowSelected = focused && !cellMode
    Row(Modifier.fillMaxWidth().height(GuideGridDefaults.RowHeight), verticalAlignment = Alignment.CenterVertically) {
        GuideChannelLabel(channel, name, dot, selected = selectedRow, numberWidth = numberWidth, modifier = Modifier.width(labelWidth))
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
                .onPreviewKeyEvent { e ->
                    val progs = programmes.orEmpty()
                    val down = e.type == KeyEventType.KeyDown
                    when (e.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            val repeat = e.nativeKeyEvent.repeatCount
                            if (down && repeat == 0) okDown = true
                            else if (down && okDown) { okDown = false; onMenu(programmeAt(progs, cursorTime)) }
                            // KevBox: with okOnRow (Live TV), OK on the row itself previews or watches the channel
                            // live (onOk(null)); ▶ still steps into the programmes.
                            else if (!down && okDown) { okDown = false; if (cellMode) onOk(programmeAt(progs, cursorTime)) else if (okOnRow) onOk(null) else onEnterCell() }
                            true
                        }
                        Key.Menu -> { if (down) onMenu(programmeAt(progs, cursorTime)); true }
                        // Physical by design: the timeline runs left to right in every language.
                        // Row stage: ◀ leaves for the menu, ▶ steps into the programmes.
                        Key.DirectionLeft -> if (!cellMode) false else {
                            if (down) onCursor(cursorStep(progs, cursorTime, -1, windowStart, windowEnd))
                            true
                        }
                        Key.DirectionRight -> {
                            if (down && !cellMode) onEnterCell()
                            else if (down) onCursor(cursorStep(progs, cursorTime, +1, windowStart, windowEnd))
                            true
                        }
                        Key.DirectionUp -> if (down) onStep(-1) else true
                        Key.DirectionDown -> if (down) onStep(+1) else true
                        else -> false
                    }
                }
                .focusable()
                .then(if (rowSelected) Modifier.stageFocusLook(tv.own.owntv.ui.stage.StageFocus.FX, 14.mpx) else Modifier),
        ) {
            programmes?.let { progs ->
                val catchupIds = remember(progs, channel, now) { progs.filter { vm.canCatchup(channel, it, now) }.mapTo(HashSet()) { it.id } }
                ProgrammeStripCanvas(
                    programmes = progs,
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    now = now,
                    focusTime = if (focused && cellMode) cursorTime else null,
                    catchupIds = catchupIds,
                    recordingStarts = recordingStarts,
                    reminderStarts = reminderStarts,
                    scrollPx = scrollPx,
                )
            }
        }
    }
}

/** An empty guide: what is missing, and — with no EPG added — the way to add one. */
@Composable
private fun GuideEmpty(title: String, body: String? = null, action: String? = null, onAction: () -> Unit = {}) {
    Box(Modifier.fillMaxSize().padding(bottom = 80.mpx), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = stageText(24, 700), color = StageColors.Text, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 900.mpx))
            if (body != null) {
                Text(body, style = stageText(19, 400), color = StageColors.Muted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 900.mpx).padding(top = 8.mpx))
            }
            if (action != null) {
                Spacer(Modifier.height(24.mpx))
                StageButton(action, onClick = onAction, icon = OwnTVIcon.ADD, height = 56.mpx, textSize = 19)
            }
        }
    }
}

/**
 * How to move, under the programme beside the video (owner, 2026-10-01: in place of the old action buttons,
 * which could not be reached without leaving the channel — they are all in Hold OK's menu). At row level:
 * OK Browse · ▲▼ Channels · ◀ Menu · Hold OK Options; inside a row: OK Watch · ◀ ▶ Programs · Back Channels.
 */
@Composable
// KevBox: liveMode added (Live TV's ◀ goes back to the category column), and okPreviews (OK puts the channel in
// the top box first, so it reads "Preview" until the channel is there).
private fun GuideKeyHints(cellMode: Boolean, liveMode: Boolean = false, okPreviews: Boolean = false) {
    // KevBox: OK's word follows the top box in Live TV.
    val ok = stringResource(R.string.common_ok) to stringResource(if (okPreviews) R.string.kevbox_key_preview else R.string.content_key_watch)
    val hints = if (cellMode) {
        listOf(
            ok,
            "◀ ▶" to stringResource(R.string.content_key_programmes),
            stringResource(R.string.common_back) to stringResource(R.string.content_key_channels),
            stringResource(R.string.content_key_hold_ok) to stringResource(R.string.content_key_options),
        )
    } else if (liveMode) {
        // KevBox: Live TV's row stage. OK previews or watches the channel; ▶ steps into its programmes.
        listOf(
            ok,
            "▲ ▼" to stringResource(R.string.content_key_channels),
            "◀" to stringResource(R.string.content_category_browser_title),
            "▶" to stringResource(R.string.content_key_programmes),
            stringResource(R.string.content_key_hold_ok) to stringResource(R.string.content_key_options),
        )
    } else {
        listOf(
            stringResource(R.string.common_ok) to stringResource(R.string.content_key_browse),
            "▲ ▼" to stringResource(R.string.content_key_channels),
            "◀" to stringResource(R.string.content_key_menu),
            stringResource(R.string.content_key_hold_ok) to stringResource(R.string.content_key_options),
        )
    }
    tv.own.owntv.ui.stage.StageKeyHints(hints)
}

/**
 * KevBox: where focus goes when it leaves Live TV's category column for the grid: [row], once the guide
 * state is no longer [oldState] (the state on screen when a category was picked whose channels were not
 * in yet; null when the grid already shows them). An empty result leaves the cursor in the column.
 */
private class SidebarExit(val row: Int, val oldState: EpgUiState?)
