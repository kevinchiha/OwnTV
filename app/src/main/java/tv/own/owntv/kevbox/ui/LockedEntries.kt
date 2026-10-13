// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.features.settings.StageFullPage
import tv.own.owntv.kevbox.KevBoxViewModel
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.mpxSp
import tv.own.owntv.ui.theme.stageText

/** The rules for what the locked app still offers in place of upstream's source management. */
object LockedEntries {
    /** Settings shows Choose playlist only when the member has two or more playlists to pick from. */
    fun showChoosePlaylist(rowCount: Int): Boolean = rowCount >= 2
}

/** What the Settings › Choose playlist row needs: the name of the playlist the TV plays now. */
data class KevBoxPlaylistChoice(val currentName: String?)

/**
 * The Choose playlist row's data, or null while the row is hidden (fewer than two playlists). Kept
 * here so the Settings screen, which sits at the JVM method-size limit, gains one call and no more.
 */
@Composable
fun kevboxPlaylistChoice(): KevBoxPlaylistChoice? {
    val vm: KevBoxViewModel = koinViewModel()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val current by vm.currentClientNumber.collectAsStateWithLifecycle()
    if (!LockedEntries.showChoosePlaylist(rows.size)) return null
    return KevBoxPlaylistChoice(rows.firstOrNull { it.clientNumber == current }?.name)
}

/**
 * Settings › Choose playlist: the Settings full page around [ChoosePlaylistPage]. It draws the title
 * and handles Back; when the member drops below two playlists while it is open, it closes itself.
 */
@Composable
fun KevBoxChoosePlaylistSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: KevBoxViewModel = koinViewModel()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val current by vm.currentClientNumber.collectAsStateWithLifecycle()
    val shown = LockedEntries.showChoosePlaylist(rows.size)
    LaunchedEffect(shown) { if (!shown) onBack() }
    StageFullPage(
        parents = listOf(stringResource(R.string.settings_group_sources)),
        title = stringResource(R.string.kevbox_choose_playlist),
        count = pluralStringResource(R.plurals.more_playlist_count, rows.size, rows.size),
        onBack = onBack,
        modifier = modifier,
    ) {
        ChoosePlaylistPage(rows = rows, currentClientNumber = current, onChoose = vm::choose)
    }
}

/** The signed-in email for the More › Account item's value, or null while signed out. */
@Composable
fun kevboxSignedInEmail(): String? {
    val vm: KevBoxViewModel = koinViewModel()
    val email by vm.email.collectAsStateWithLifecycle()
    return email
}

/**
 * More › Account: the page title, then [AccountPage] (the email and Sign out). [entry] is what OK or ▶
 * on the More sheet focuses: the page's first row.
 */
@Composable
fun KevBoxAccountMorePage(entry: FocusRequester, modifier: Modifier = Modifier) {
    val vm: KevBoxViewModel = koinViewModel()
    val email by vm.email.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
        // The same title style as the other More pages (42/800, −1).
        Text(
            stringResource(R.string.kevbox_account),
            style = stageText(42, 800, (-1).mpxSp),
            color = StageColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.padding(top = 26.mpx).focusRequester(entry).focusGroup()) {
            AccountPage(email = email.orEmpty(), onSignOut = vm::signOut)
        }
    }
}
