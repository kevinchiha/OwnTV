// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.coroutines.cancellation.CancellationException
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

    /** Moves when the visible line switches, the first fetch of a cold start included: the gate stops the old stream. */
    val visibleSourceSwitches: StateFlow<Int> = reconciler.visibleSourceSwitches

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
    // switch or a half-done wipe would leave the TV in neither state. A Room or Core call that
    // throws is caught: the app stays up, and the next playlist check finishes a half-done choose.
    fun choose(clientNumber: String) {
        viewModelScope.launch { survive { coordinator.choose(clientNumber) } }
    }

    fun dismissPopup() = expiry.dismissPopup()

    fun onChannelFailed() = coordinator.onChannelFailed()

    /** [KevBoxCoordinator.signOut] runs every step past a failing one, so the gate still reaches SignedOut. */
    fun signOut() {
        viewModelScope.launch { survive { coordinator.signOut() } }
    }

    private suspend fun survive(block: suspend () -> Unit) {
        withContext(NonCancellable) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Nothing to show: the gate keeps reflecting whatever state the TV reached.
            }
        }
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
