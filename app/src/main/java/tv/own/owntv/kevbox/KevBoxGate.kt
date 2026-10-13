// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.kevbox.ui.AccessLockedScreen
import tv.own.owntv.kevbox.ui.ChoosePlaylistScreen
import tv.own.owntv.kevbox.ui.ExpiryPopupHost
import tv.own.owntv.kevbox.ui.NoPlaylistScreen
import tv.own.owntv.kevbox.ui.SignInScreen
import tv.own.owntv.ui.components.BrandMark
import tv.own.owntv.ui.components.rememberAppliedIcon
import tv.own.owntv.ui.stage.stageBackground
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent

/**
 * Puts KevBox Live in front of [content] (onboarding, the profile gate and the shell): sign-in
 * first, then the lock, then the playlist screens (AC-10). [content] is composed only while the
 * gate is Open, with the expiry popup over it.
 *
 * [onStopPlayback] runs whenever the gate leaves Open and whenever the visible source switches
 * while it stays Open, the first fetch after a cold start included: a hidden line may belong to another TV now, so nothing keeps streaming it
 * (AC-30). An admin edit of the line keeps its ID and keeps playing (AC-19, AC-36). [onExit] is Back on the first-run playlist choice, which leaves without a pick (AC-16).
 */
@Composable
fun KevBoxGate(onExit: () -> Unit, onStopPlayback: () -> Unit, content: @Composable () -> Unit) {
    val vm: KevBoxViewModel = koinViewModel()
    val gate by vm.gate.collectAsStateWithLifecycle()
    val playlistKnown by vm.playlistKnown.collectAsStateWithLifecycle()
    val switches by vm.visibleSourceSwitches.collectAsStateWithLifecycle()
    val stop by rememberUpdatedState(onStopPlayback)

    val open = gate == GateState.Open
    // Holders, not state: these only remember the last value seen, they never redraw anything.
    val lastOpen = remember { booleanArrayOf(false) }
    LaunchedEffect(open) {
        if (lastOpen[0] && !open) stop()
        lastOpen[0] = open
    }
    // The reconciler counts switches from the stored pick, so the first fetch of a cold start that
    // moves this TV off the line it was playing stops that stream too.
    val lastSwitches = remember { intArrayOf(switches) }
    LaunchedEffect(switches) {
        if (open && switches != lastSwitches[0]) stop()
        lastSwitches[0] = switches
    }

    when (val state = gate) {
        GateState.SignedOut -> {
            val signIn by vm.signIn.collectAsStateWithLifecycle()
            SignInScreen(signIn, onSubmit = vm::submitSignIn, modifier = Modifier.fillMaxSize())
        }
        GateState.Locked -> AccessLockedScreen(Modifier.fillMaxSize())
        is GateState.ChoosePlaylist ->
            ChoosePlaylistScreen(state.rows, onChoose = vm::choose, onBack = onExit, modifier = Modifier.fillMaxSize())
        // Before the first good fetch an empty store shows only the mark: "No playlist assigned yet"
        // must not flash between a good sign-in and the rows arriving.
        GateState.NoPlaylist -> if (playlistKnown) NoPlaylistScreen(Modifier.fillMaxSize()) else MarkOnly()
        GateState.Open -> {
            content()
            val popup by vm.popup.collectAsStateWithLifecycle()
            ExpiryPopupHost(popup, onDismiss = vm::dismissPopup)
        }
    }
}

/** The status screens' background and mark, with no text. */
@Composable
private fun MarkOnly() {
    Box(Modifier.fillMaxSize().stageBackground(stageAccent.accent), contentAlignment = Alignment.Center) {
        BrandMark(rememberAppliedIcon(), 180.mpx, followAccent = false)
    }
}
