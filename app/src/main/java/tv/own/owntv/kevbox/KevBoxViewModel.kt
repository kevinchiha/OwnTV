// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.kevbox.expiry.ExpiryMonitor
import tv.own.owntv.kevbox.expiry.ExpiryPopup
import tv.own.owntv.kevbox.playlist.PlaylistReconciler
import tv.own.owntv.kevbox.session.KevBoxAuth
import tv.own.owntv.kevbox.supabase.PlaylistRow
import tv.own.owntv.kevbox.supabase.SignInResult
import tv.own.owntv.kevbox.ui.SignInError
import tv.own.owntv.kevbox.ui.SignInUiState

/**
 * What the KevBox Live screens read and the buttons they press. Every value but [signIn] comes
 * from process-long singletons, so two instances (the gate's and the player's) always agree.
 * [signIn] holds only "busy" and the last error: never the email or the password.
 */
class KevBoxViewModel(
    private val coordinator: KevBoxCoordinator,
    auth: KevBoxAuth,
    reconciler: PlaylistReconciler,
    private val expiry: ExpiryMonitor,
) : ViewModel() {

    val gate: StateFlow<GateState> = coordinator.gate

    /** False until this run's first good playlist fetch (see [KevBoxCoordinator.playlistKnown]). */
    val playlistKnown: StateFlow<Boolean> = coordinator.playlistKnown

    /** Goes up whenever the visible source changes, so the gate can stop the old line's stream. */
    val visibleSourceChanges: StateFlow<Int> = reconciler.visibleSourceChanges

    private val _signIn = MutableStateFlow(SignInUiState())
    val signIn: StateFlow<SignInUiState> = _signIn.asStateFlow()

    val email: StateFlow<String?> = auth.email
    val rows: StateFlow<List<PlaylistRow>> = reconciler.rows
    val currentClientNumber: StateFlow<String?> = reconciler.currentClientNumber
    val popup: StateFlow<ExpiryPopup?> = expiry.popup
    val channelShowsExpired: StateFlow<Boolean> = expiry.channelShowsExpired

    fun submitSignIn(email: String, password: String) {
        if (_signIn.value.busy) return
        _signIn.value = SignInUiState(busy = true)
        viewModelScope.launch {
            val result = coordinator.signIn(email, password)
            _signIn.value = SignInUiState(busy = false, error = errorOf(result))
        }
    }

    // Choose and sign out finish even when the screen that asked goes away mid-way: a half-done
    // switch or a half-done wipe would leave the TV in neither state.
    fun choose(clientNumber: String) {
        viewModelScope.launch { withContext(NonCancellable) { coordinator.choose(clientNumber) } }
    }

    fun dismissPopup() = expiry.dismissPopup()

    fun onChannelFailed() = coordinator.onChannelFailed()

    fun signOut() {
        viewModelScope.launch { withContext(NonCancellable) { coordinator.signOut() } }
    }

    companion object {
        fun errorOf(result: SignInResult): SignInError? = when (result) {
            is SignInResult.Success -> null
            SignInResult.WrongCredentials -> SignInError.WRONG_CREDENTIALS
            SignInResult.NoNetwork -> SignInError.NO_NETWORK
            SignInResult.Failed -> SignInError.FAILED
        }
    }
}
