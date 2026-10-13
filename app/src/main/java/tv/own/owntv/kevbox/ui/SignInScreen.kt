// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.features.settings.SettingHelp
import tv.own.owntv.features.settings.StageFieldRow
import tv.own.owntv.features.setup.WizardAction
import tv.own.owntv.features.setup.WizardFrame
import tv.own.owntv.features.setup.WizardStep
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StagePill
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/** Why the last sign-in did not work, as the sign-in screen words it. */
enum class SignInError { WRONG_CREDENTIALS, NO_NETWORK, FAILED }

/** What the sign-in screen draws: a request in flight, and the last error if there was one. */
data class SignInUiState(val busy: Boolean = false, val error: SignInError? = null)

/**
 * The first screen of KevBox Live: email, password (masked) and Sign in, in the setup wizard's frame.
 * Focus starts on the email row. While [SignInUiState.busy] the buttons are disabled. An error shows
 * under the fields; a network or server failure adds Retry, which sends the text again. The typed text
 * stays after any error. Both values live in `remember`, never in saved instance state, so the
 * password is not written into the activity's saved bundle.
 */
@Composable
fun SignInScreen(state: SignInUiState, onSubmit: (email: String, password: String) -> Unit, modifier: Modifier = Modifier) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val emailFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { emailFocus.requestFocus() } }
    val canSubmit = !state.busy && email.isNotBlank() && password.isNotEmpty()
    fun submit() { if (canSubmit) onSubmit(email, password) }

    val emailLabel = stringResource(R.string.kevbox_email)
    val passwordLabel = stringResource(R.string.kevbox_password)
    val fieldHelp = stringResource(R.string.settings_form_field_help)
    Box(modifier) {
        WizardFrame(
            step = WizardStep.PROFILE,
            title = stringResource(R.string.kevbox_sign_in_title),
            sub = stringResource(R.string.kevbox_sign_in_hint),
            back = null,
            next = WizardAction(stringResource(R.string.kevbox_sign_in), { submit() }, enabled = canSubmit),
            // The first screen: Back leaves the app as it always has.
            onBack = null,
        ) {
            StageFieldRow(
                OwnTVIcon.PERSON, emailLabel, email, { email = it }, SettingHelp(emailLabel, fieldHelp),
                keyboardType = KeyboardType.Email,
                modifier = Modifier.focusRequester(emailFocus),
            )
            StageFieldRow(
                OwnTVIcon.PENCIL, passwordLabel, password, { password = it }, SettingHelp(passwordLabel, fieldHelp),
                password = true,
            )
            val error = state.error
            if (error != null) {
                val message = stringResource(
                    when (error) {
                        SignInError.WRONG_CREDENTIALS -> R.string.kevbox_wrong_credentials
                        SignInError.NO_NETWORK -> R.string.kevbox_no_internet
                        SignInError.FAILED -> R.string.kevbox_sign_in_failed
                    },
                )
                Row(
                    Modifier.padding(top = 14.mpx),
                    horizontalArrangement = Arrangement.spacedBy(18.mpx),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(message, style = stageText(19, 700), color = StageColors.Danger, modifier = Modifier.weight(1f))
                    if (error != SignInError.WRONG_CREDENTIALS) {
                        StagePill(stringResource(R.string.kevbox_retry), onClick = { submit() }, enabled = canSubmit)
                    }
                }
            }
        }
    }
}
