// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.theme.AnimationLevel
import tv.own.owntv.core.theme.ThemeMode
import tv.own.owntv.features.setup.SetupViewModel
import tv.own.owntv.kevbox.live.LiveOnly
import tv.own.owntv.ui.components.BrandMark
import tv.own.owntv.ui.components.rememberAppliedIcon
import tv.own.owntv.ui.stage.stageBackground
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent

/**
 * Stands in for upstream's onboarding (language, screen size, disclaimer, create profile): signing in
 * is the whole setup (Kevin, 2026-10-14). Makes one profile with the default name, no PIN and the
 * letter avatar, makes it active and hands its ID to [onDone]. The screen shows the KevBox mark only.
 *
 * With [firstRun] it also sets the look a new install starts with, before the profile opens: dark
 * mode, the KevBox blue accent ([LiveOnly.ACCENT_HEX]), the live preview off and Animations On, so
 * Live TV's video grows into full screen and back. These are written once, as upstream's onboarding
 * wrote its choices, so a member can change any of them in Settings.
 * "Add profile" from the profile picker still runs upstream's create-profile step.
 */
@Composable
fun KevBoxAutoSetup(firstRun: Boolean, onDone: (Long?) -> Unit, modifier: Modifier = Modifier) {
    val vm: SetupViewModel = koinViewModel()
    val settings: SettingsRepository = koinInject()
    val profileName = stringResource(R.string.setup_default_profile)
    // Saveable, so a configuration change mid-setup never makes a second profile.
    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (started) return@LaunchedEffect
        started = true
        if (firstRun) {
            settings.setThemeMode(ThemeMode.DARK)
            settings.setCustomAccent(LiveOnly.ACCENT_HEX)
            settings.setLivePreviewEnabled(false)
            settings.setAnimationLevel(AnimationLevel.FULL)
        }
        // -1 is upstream's "the letter" avatar, the one its create-profile step starts on.
        vm.createProfile(profileName, avatarId = -1, isKids = false, pin = null, picture = null) { vm.finish(onDone) }
    }
    Box(modifier.stageBackground(stageAccent.accent), contentAlignment = Alignment.Center) {
        BrandMark(rememberAppliedIcon(), 180.mpx, followAccent = false)
    }
}
