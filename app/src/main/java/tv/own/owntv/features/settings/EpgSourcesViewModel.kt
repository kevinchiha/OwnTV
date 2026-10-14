package tv.own.owntv.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
// KevBox: combine, flatMapLatest, flowOf and map build automaticIds.
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.core.epg.EpgSource
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.repository.EpgRepository
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.sync.work.EpgSyncScheduler
import tv.own.owntv.core.sync.work.EpgSyncState
import tv.own.owntv.core.settings.EpgAutoRefresh
import tv.own.owntv.core.settings.EpgRefresh
import tv.own.owntv.core.settings.SettingsRepository
// KevBox: the automatic guide's ids (AC-26).
import tv.own.owntv.kevbox.epg.GuideFeed
import tv.own.owntv.kevbox.epg.automaticGuideIds

/** Manage standalone EPG (XMLTV) sources: list, add (auto-sync), edit, re-sync, delete. */
class EpgSourcesViewModel(
    private val store: EpgSourceStore,
    private val epgRepository: EpgRepository,
    private val sourceRepository: SourceRepository,
    private val settings: SettingsRepository,
    private val epgDao: tv.own.owntv.core.database.dao.EpgDao,
    private val channelDao: tv.own.owntv.core.database.dao.ChannelDao,
    private val epgSyncScheduler: EpgSyncScheduler,
) : ViewModel() {

    /** An existing playlist's EPG feed, offered as a one-tap "fill from playlist" option. */
    data class PlaylistEpg(val name: String, val url: String)

    val sources: StateFlow<List<EpgSource>> =
        store.sources.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Per-source EPG auto-refresh selection (Off / Startup / staleness threshold). */
    val autoRefresh: StateFlow<Map<Long, EpgRefresh>> = settings.epgAutoRefresh
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun setAutoRefresh(source: EpgSource, refresh: EpgRefresh) {
        viewModelScope.launch { settings.setEpgAutoRefresh(source.id, refresh) }
    }

    /** EPG sources whose own `<icon src>` logos replace the playlist's channel logos. */
    val useLogos: StateFlow<Set<Long>> = settings.epgUseLogos
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun setUseLogos(source: EpgSource, enabled: Boolean) {
        viewModelScope.launch { settings.setEpgUseLogos(source.id, enabled) }
    }

    fun add(name: String, url: String, userAgent: String? = null, autoRefresh: EpgRefresh = EpgRefresh.OFF, useLogos: Boolean = false) {
        viewModelScope.launch {
            val source = store.add(name, url, userAgent)
            settings.setEpgAutoRefresh(source.id, autoRefresh)
            if (useLogos) settings.setEpgUseLogos(source.id, true)
            epgSyncScheduler.enqueueSync(source.id, "add")
        }
    }

    fun resync(source: EpgSource) {
        viewModelScope.launch {
            val base = epgDao.countForSources(listOf(source.id))
            epgSyncScheduler.enqueueSync(source.id, "manual_resync", base)
        }
    }

    fun update(source: EpgSource, name: String, url: String, userAgent: String?) {
        viewModelScope.launch {
            val updated = source.copy(name = name.trim().ifBlank { source.name }, url = url.trim(), userAgent = userAgent?.trim()?.takeIf { it.isNotBlank() })
            store.update(updated)
            val base = epgDao.countForSources(listOf(updated.id))
            epgSyncScheduler.enqueueSync(updated.id, "update", base)
        }
    }

    /** Sources whose guide data is being deleted right now — the row shows a "Deleting…" status and
     *  hides its actions until the (potentially large) DELETE finishes. */
    private val _deletingIds = MutableStateFlow<Set<Long>>(emptySet())
    val deletingIds: StateFlow<Set<Long>> = _deletingIds.asStateFlow()

    fun delete(source: EpgSource) {
        if (source.id in _deletingIds.value) return
        viewModelScope.launch {
            epgSyncScheduler.cancelSync(source.id)
            _deletingIds.value = _deletingIds.value + source.id
            try {
                // NonCancellable + DB-clear-BEFORE-store-remove: a big EPG (100k+ programmes) can take a
                // while to delete. If the user leaves the screen mid-delete, finish anyway — otherwise the
                // source would already be gone from the list while its programmes were left orphaned with
                // nothing left to clean them up.
                withContext(NonCancellable) {
                    epgRepository.clear(source.id)
                    store.remove(source.id)
                }
            } finally {
                _deletingIds.value = _deletingIds.value - source.id
            }
        }
    }

    fun observeSync(sourceId: Long): Flow<EpgSyncState> = epgSyncScheduler.observeSync(sourceId)

    fun cancelSync(source: EpgSource) {
        epgSyncScheduler.cancelSync(source.id)
    }

    /** A source's status line: guide channels, stored programmes, and how many of the profile's
     *  channels advertise catch-up (the last is playlist-wide, shown so it's visible alongside the EPG). */
    suspend fun counts(id: Long): Triple<Int, Int, Int> {
        val channels = epgDao.countGuideChannels(listOf(id))
        val programmes = epgDao.countForSources(listOf(id))
        val pid = settings.activeProfileId.first()
        val playlistIds = if (pid < 0) emptyList() else sourceRepository.observeSources(pid).first().map { it.id }
        val catchup = if (playlistIds.isEmpty()) 0 else channelDao.countCatchup(playlistIds)
        return Triple(channels, programmes, catchup)
    }

    /** Playlists and their own EPG URLs (Xtream xmltv.php / M3U url-tvg), which mark the automatic feeds. */
    suspend fun playlistEpgOptions(): List<PlaylistEpg> {
        // KevBox: the same list now also feeds automaticIds, so its source moved to activePlaylistEpgs.
        return activePlaylistEpgs().first()
    }

    // KevBox: the active profile's playlists and their own guide URLs, live, for playlistEpgOptions
    // and automaticIds. Follows a profile switch and a playlist's server change while the page is open.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun activePlaylistEpgs(): Flow<List<PlaylistEpg>> =
        settings.activeProfileId.flatMapLatest { pid ->
            if (pid < 0) flowOf(emptyList()) else sourceRepository.observeSources(pid).map { sources ->
                // A comma-separated url-tvg names several feeds; offer each one on its own.
                sources.flatMap { src -> epgRepository.guideUrls(src).map { PlaylistEpg(src.name, it) } }
            }
        }

    /**
     * KevBox: ids of the automatic guide, the feeds whose URL is one of the active playlists' own
     * guide URLs (added by GuideFeeds). The page offers no Delete and no URL edit for them (AC-26).
     * Null until loaded, so no feed offers Delete before the set is known.
     */
    val automaticIds: StateFlow<Set<Long>?> =
        combine(store.sources, activePlaylistEpgs()) { feeds, playlists ->
            automaticGuideIds(feeds.map { GuideFeed(it.id, it.url, it.lastSyncAt) }, playlists.map { it.url })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
