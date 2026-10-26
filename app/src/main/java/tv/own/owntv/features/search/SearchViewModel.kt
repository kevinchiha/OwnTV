@file:OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)

package tv.own.owntv.features.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.transformLatest
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.entity.MetadataCacheEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.metadata.MetadataRepository
import tv.own.owntv.ui.stage.PlaylistMark
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.dao.resolveExistingProfileId
import tv.own.owntv.core.content.SearchIntent
import tv.own.owntv.core.content.SearchKind
import tv.own.owntv.core.content.SearchReader
import tv.own.owntv.core.content.SearchResults
import tv.own.owntv.core.repository.ActiveProfileSources
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository

/**
 * Cross-section search over a profile's channels, movies and series.
 *
 * The searching itself is core's [SearchReader]: the FTS expression, the hidden items and
 * categories, the kids filter and the Customize renames are the same rules on a phone as on a
 * television, so they are not written twice. What stays here is the screen's own behaviour —
 * debouncing and the recents list. A result is never played here: OK goes to it.
 */
class SearchViewModel(
    private val profileDao: ProfileDao,
    private val sourceDao: SourceDao,
    private val categoryDao: CategoryDao,
    private val settings: SettingsRepository,
    private val searchReader: SearchReader,
    private val metadata: MetadataRepository,
    // KevBox: the TV guide tab's show finder, and the store its hidden channels and categories come from.
    private val showSearch: tv.own.owntv.kevbox.live.ShowSearch,
    private val customize: tv.own.owntv.core.customize.CustomizationStore,
) : ViewModel() {

    // Observe the active profile's sources reactively so adding/removing a playlist refreshes Search
    // immediately (was read once at startup, so a new playlist showed nothing until app restart).
    // Per-section Off flags split the id sets so an Off section never surfaces in results.
    private val ctx: StateFlow<ActiveProfileSources> = activeProfileSources(settings, sourceDao)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, ActiveProfileSources(-1L, emptyList()))

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    // KevBox: the results paired with the (trimmed) query they were found for, so the screen can tell the
    // query on screen's results from the last one's and wait for them and the shows. Was `results` itself.
    val searched: StateFlow<Pair<String, SearchResults>> = _query
        .map { it.trim() }
        .debounce(300)
        .distinctUntilChanged()
        .flatMapLatest { q ->
            if (q.length < 2) {
                flowOf(q to SearchResults()) // KevBox: with its query
            } else {
                flowOf(q to search(q)) // KevBox: with its query
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "" to SearchResults()) // KevBox
    val results: StateFlow<SearchResults> = searched.map { it.second } // KevBox: from searched above
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    /**
     * KevBox: the shows for the TV guide tab, on now and in the next 24 hours, from the same debounced
     * query, paired with that query like [searched]. Search lists these instead of core's "On TV"
     * ([SearchResults.programmes]), which stops at 12 hours and 20 rows (Kevin, 2026-10-14). Found again
     * when the live playlists change, and an error finds nothing rather than ending the flow.
     */
    val shows: StateFlow<Pair<String, tv.own.owntv.kevbox.live.ShowMatches>> = combine(
        _query.map { it.trim() }.debounce(300).distinctUntilChanged(),
        ctx, // KevBox: found again when the live playlists change
    ) { q, c -> q to c }
        .mapLatest { (q, c) ->
            q to runCatching { findShows(q, c) }.getOrElse { // KevBox: an error finds nothing
                if (it is kotlinx.coroutines.CancellationException) throw it
                tv.own.owntv.kevbox.live.ShowMatches.EMPTY
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "" to tv.own.owntv.kevbox.live.ShowMatches.EMPTY)

    /** KevBox: the tab the user moved to; null lets the results pick (kevbox/live/SearchBox.kt pickShowTab). */
    private val _showTab = MutableStateFlow<tv.own.owntv.kevbox.live.SearchShowTab?>(null)
    val showTab: StateFlow<tv.own.owntv.kevbox.live.SearchShowTab?> = _showTab.asStateFlow()
    fun setShowTab(t: tv.own.owntv.kevbox.live.SearchShowTab) { _showTab.value = t } // KevBox

    /**
     * KevBox: the hidden channels and categories are read the way core's SearchReader reads them: the
     * profile's Live customizations, and on a kids profile the adult categories as well.
     */
    private suspend fun findShows(q: String, sources: ActiveProfileSources): tv.own.owntv.kevbox.live.ShowMatches {
        if (q.length < 2) return tv.own.owntv.kevbox.live.ShowMatches.EMPTY
        val pid = currentProfileId() ?: return tv.own.owntv.kevbox.live.ShowMatches.EMPTY
        val liveIds = sources.liveSourceIds // KevBox: the sources the flow passed in
        if (liveIds.isEmpty()) return tv.own.owntv.kevbox.live.ShowMatches.EMPTY
        val cust = customize.observe(pid, tv.own.owntv.core.model.MediaType.LIVE).first()
        val isKids = profileDao.getById(pid)?.isKids == true
        val hiddenCats = if (cust.hiddenCategories.isEmpty() && !isKids) emptySet() else
            tv.own.owntv.core.content.AdultCategoryClassifier.hiddenCategoryIds(
                categoryDao.observe(liveIds, tv.own.owntv.core.model.MediaType.LIVE).first(),
                cust.hiddenCategories,
                isKids,
            )
        return showSearch.find(q, liveIds, cust, hiddenCats, System.currentTimeMillis())
    }

    fun setQuery(q: String) {
        // KevBox: a new query also forgets the tab the last one was on (_showTab).
        if (q.trim() != _query.value.trim()) { _showTab.value = null; lastFocusKey = null }
        _query.value = q
        if (q.isNotBlank()) _intent.value = null // typing overrides an active launcher intent
    }

    // --- Empty-state launcher (recent search terms + Continue / Unwatched / Channels) ---

    /** Persisted recent search terms (most-recent first). */
    val recentSearches: StateFlow<List<String>> = settings.recentSearches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearRecentSearches() { viewModelScope.launch { settings.clearRecentSearches() } }

    /** Save the current query into recents — called when the user actually opens a result. */
    fun rememberCurrentQuery() { viewModelScope.launch { settings.addRecentSearch(_query.value) } }

    private val _intent = MutableStateFlow<SearchIntent?>(null)
    val intent: StateFlow<SearchIntent?> = _intent.asStateFlow()

    /** Selecting an intent clears any typed query so the curated list shows; null returns to the launcher. */
    fun setIntent(i: SearchIntent?) {
        _intent.value = i
        if (i != null) _query.value = ""
    }

    /** Curated results for the active empty-state intent (bounded; reuses favourites/history queries). */
    val curatedResults: StateFlow<SearchResults> = combine(_intent, ctx) { i, c -> i to c }
        .flatMapLatest { (i, c) ->
            if (i == null) flowOf(SearchResults())
            else flowOf(searchReader.curated(c.profileId, c, i))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    private suspend fun search(q: String): SearchResults {
        val pid = currentProfileId() ?: return SearchResults()
        return searchReader.search(pid, ctx.value, q)
    }

    // --- Stage page ---

    /** The filter buttons switched on; none = everything. Kept across new search text; Back clears it. */
    private val _kinds = MutableStateFlow<Set<SearchKind>>(emptySet())
    val kinds: StateFlow<Set<SearchKind>> = _kinds.asStateFlow()
    fun toggleKind(k: SearchKind) { _kinds.value = if (k in _kinds.value) _kinds.value - k else _kinds.value + k }
    fun showOnly(k: SearchKind) { _kinds.value = setOf(k) }
    fun clearKinds() { _kinds.value = emptySet() }

    /** The row a "Go to" left from ("c12", "m34", "s56"), so Back to Search lands on it again. */
    var lastFocusKey: String? = null

    /** Which playlist a row comes from, as on the browse screens; empty with one playlist. */
    val playlistMarks: StateFlow<Map<Long, PlaylistMark>> = ctx
        .map { aps -> if (aps.sources.size < 2) emptyMap() else aps.sources.withIndex().associate { (i, s) -> s.id to PlaylistMark.of(s.name, i) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Category names for the movie and series rows ("2005 · ★ 6.2 · Disney+ Movies"). */
    val categoryNames: StateFlow<Map<Long, String>> = combine(results, curatedResults) { a, b ->
        (a.movies.mapNotNull { it.categoryId } + a.series.mapNotNull { it.categoryId } +
            b.movies.mapNotNull { it.categoryId } + b.series.mapNotNull { it.categoryId }).toSet()
    }
        .distinctUntilChanged()
        .mapLatest { ids -> ids.mapNotNull { id -> categoryDao.getById(id)?.let { id to it.name } }.toMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val metadataMode: StateFlow<tv.own.owntv.core.metadata.MetadataMode> = settings.metadataMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), tv.own.owntv.core.metadata.MetadataMode.PROVIDER_PLUS_TMDB)

    /** The focused movie or series, for the poster panel's TMDB details (backdrop, genres, cast). */
    private val _focusedVod = MutableStateFlow<Any?>(null)
    fun focusVod(item: Any?) { _focusedVod.value = item }

    /** The focused title's key ("m34" / "s56") and its TMDB details, after the same focus debounce the
     *  browse screens use, so passing over rows looks nothing up. */
    val focusedMeta: StateFlow<Pair<String, MetadataCacheEntity?>?> = _focusedVod
        .transformLatest { item ->
            val key = when (item) { is MovieEntity -> "m${item.id}"; is SeriesEntity -> "s${item.id}"; else -> null }
            if (key == null) { emit(null); return@transformLatest }
            delay(MetadataRepository.FOCUS_DEBOUNCE_MS)
            val cache = runCatching {
                when (item) { is MovieEntity -> metadata.resolveMovie(item); is SeriesEntity -> metadata.resolveSeries(item); else -> null }
            }.getOrNull()
            emit(key to cache)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private suspend fun currentProfileId(): Long? {
        val preferred = settings.activeProfileId.first()
        return if (preferred >= 0) profileDao.resolveExistingProfileId(preferred) else null
    }
}
