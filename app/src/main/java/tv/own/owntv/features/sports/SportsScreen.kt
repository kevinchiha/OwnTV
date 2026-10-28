package tv.own.owntv.features.sports

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.features.live.LiveViewModel

/**
 * The Sports section: the hub, and the match page opened from it (Back returns to the hub) — or from the
 * player's *Match* button, when Back plays that channel again.
 */
@Composable
fun SportsScreen(
    onPlayChannel: (ChannelEntity, List<ChannelEntity>) -> Unit,
    onAddPlaylist: () -> Unit,
    onChildFocused: () -> Unit,
    onEntryHook: (((() -> Boolean)?) -> Unit)?,
    contentStart: Dp,
    modifier: Modifier = Modifier,
    /** False while the player is full screen over the section: its Back is the player's. */
    active: Boolean = true,
    /** KevBox: false while full screen covers the section, which the shell keeps composed behind a Sports box. */
    onScreen: Boolean = true,
    /** KevBox: the way back from full screen, for the match page; it calls [onRestored] once focus is placed. */
    restoreFocus: Boolean = false,
    onRestored: () -> Unit = {},
    vm: SportsViewModel = koinViewModel(),
    liveVm: LiveViewModel = koinViewModel(),
) {
    var match by remember { mutableStateOf<Match?>(null) }
    var filters by remember { mutableStateOf(false) }
    // The league page, over the hub or a match (Back returns to whichever it came from).
    var league by remember { mutableStateOf<String?>(null) }
    val openLeague: (String?) -> Unit = { k -> league = k; vm.openLeague(k) }
    // Set when the page was opened from the player's Match button: Back plays that channel again.
    var returnTo by remember { mutableStateOf<ChannelEntity?>(null) }
    // The card a match was opened from, so Back lands on it.
    var lastKey by remember { mutableStateOf<String?>(null) }
    val open: (Match?) -> Unit = { m -> if (m != null) lastKey = m.key; match = m; vm.open(m) }
    val playback = koinInject<SportsPlayback>()
    // KevBox: collected, not read once: the goal toast asks for a match while this section stays composed
    // behind full screen. returnToMatch is gone; the match page itself stays (OwnTVShell). A Sports box on
    // screen belongs to the page being left, so it stops; behind full screen it is the stream playing, so
    // it stays, and Back boxes the playing channel on the new match's page.
    val videoStage = tv.own.owntv.kevbox.live.LocalLiveVideoStage.current
    val onScreenNow by androidx.compose.runtime.rememberUpdatedState(onScreen)
    LaunchedEffect(playback) {
        playback.pendingMatch.collect { pending ->
            val (m, ch) = pending ?: return@collect
            playback.pendingMatch.value = null
            if (onScreenNow && tv.own.owntv.kevbox.sports.clearSportsBox(videoStage)) liveVm.stopPreview()
            if (league != null) openLeague(null)
            returnTo = ch
            open(m)
        }
    }
    val current = match
    val leagueKey = league
    if (leagueKey != null) {
        SportsLeagueScreen(
            vm = vm,
            onOpenMatch = { m -> openLeague(null); open(m) },
            onBack = { openLeague(null) },
            active = active,
            contentStart = contentStart,
            modifier = modifier,
        )
    } else if (current == null) {
        SportsHubScreen(
            vm = vm,
            onOpenMatch = open,
            onPlayChannel = onPlayChannel,
            onChildFocused = onChildFocused,
            onEntryHook = onEntryHook,
            contentStart = contentStart,
            modifier = modifier,
            restoreKey = lastKey,
        )
    } else {
        SportsMatchScreen(
            vm = vm,
            liveVm = liveVm,
            onPlayChannel = onPlayChannel, // KevBox: no returnToMatch; this page stays composed behind full screen
            onOpenFilters = { filters = true },
            // KevBox: the match page empties its Sports box before it calls this (SportsMatchScreen).
            onOpenLeague = { m -> openLeague(tv.own.owntv.core.sports.model.League.keyOf(m.sport, m.leagueSlug)) },
            onAddPlaylist = onAddPlaylist,
            onBack = {
                val ch = returnTo
                returnTo = null
                open(null)
                if (ch != null) onPlayChannel(ch, playback.session.value?.options?.map { it.channel }.orEmpty())
            },
            active = active,
            onScreen = onScreen, // KevBox: Back from full screen lands on the playing channel's row
            restoreFocus = restoreFocus,
            onRestored = onRestored,
            contentStart = contentStart,
            modifier = modifier,
        )
        val page = vm.matchPage.collectAsStateWithLifecycle().value
        if (filters && page != null && page.hasPlaylist) {
            SportsFilterPanel(page, onFilter = vm::setFilter, onRemember = vm::setRememberFilters, onDismiss = { filters = false })
        }
    }
}
