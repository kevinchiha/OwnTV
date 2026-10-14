// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.refresh

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.settings.SettingsRepository

/**
 * Stores the member's [RefreshChoice] and keeps Core's per-playlist timer on it for every playlist
 * on this TV, a new or returning line included. Core's own AutoRefresh then does the refreshing.
 * Nothing stored reads as [RefreshChoice.DEFAULT], so a TV updating to this version moves to it too.
 */
class PlaylistRefreshSetting(
    private val prefs: SharedPreferences,
    private val settings: SettingsRepository,
    private val sourceDao: SourceDao,
    private val scope: CoroutineScope,
) {
    private val _choice = MutableStateFlow(RefreshChoice.parse(prefs.getString(KEY_CHOICE, null)))
    val choice: StateFlow<RefreshChoice> = _choice.asStateFlow()

    @Volatile private var started = false

    fun set(choice: RefreshChoice) {
        prefs.edit { putString(KEY_CHOICE, choice.name) }
        _choice.value = choice
    }

    /** Once, from OwnTVApp. Re-applies on every change of choice or of the playlists on this TV. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            val sourceIds = sourceDao.observeAll().map { all -> all.map { it.id }.sorted() }.distinctUntilChanged()
            combine(_choice, sourceIds) { choice, ids -> choice to ids }.collect { (choice, ids) ->
                try {
                    planRefreshWrites(choice, ids, settings.playlistAutoRefresh.first())
                        .forEach { (id, refresh) -> settings.setPlaylistAutoRefresh(id, refresh) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The next change tries again.
                }
            }
        }
    }

    companion object {
        const val PREFS_NAME = "kevbox_playlist_refresh"
        private const val KEY_CHOICE = "choice"
    }
}
