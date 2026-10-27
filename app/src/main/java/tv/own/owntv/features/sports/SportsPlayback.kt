package tv.own.owntv.features.sports

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sports.AlertKind
import tv.own.owntv.core.sports.AlertSnapshot
import tv.own.owntv.core.sports.Demand
import tv.own.owntv.core.sports.MatchAlert
import tv.own.owntv.core.sports.MatchAlertDiff
import tv.own.owntv.core.sports.SportsMemory
import tv.own.owntv.core.sports.SportsRepository
import tv.own.owntv.core.sports.WatchFilter
import tv.own.owntv.core.sports.WatchList
import tv.own.owntv.core.sports.WatchOption
import tv.own.owntv.core.sports.WatchReader
import tv.own.owntv.core.sports.model.Match
import java.util.Locale

/** A channel started from a match: the match, and every channel showing it, best first (after the filter). */
data class SportsSession(val match: Match, val options: List<WatchOption>)

/**
 * What the player knows about Sports: which match the playing channel was started from, its
 * score while the info bar is open, and goals in *other* followed matches for the toast. Nothing here
 * touches playback; with no session, or ESPN unreachable, the player is exactly the plain Live player.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsPlayback(
    private val settings: SettingsRepository,
    private val sourceDao: SourceDao,
    private val repository: SportsRepository,
    private val watchReader: WatchReader,
    // KevBox: LiveOnSat's listed channels, first in what channelsFor gives the goal toast's switch.
    private val listedChannels: tv.own.owntv.kevbox.sports.ListedChannels,
) {
    private val _session = MutableStateFlow<SportsSession?>(null)
    val session: StateFlow<SportsSession?> = _session.asStateFlow()

    /** The match page the player's *Match* button asked for, and the channel Back from it returns to. */
    val pendingMatch = MutableStateFlow<Pair<Match, ChannelEntity?>?>(null)

    /** The hub's chosen sport and league chips: kept while the app runs, back to All on a fresh start. */
    var hubChip: Any? = null
    var hubLeague: Any? = null

    /** The match page a channel was played full screen from: Back from the player lands on it again. */
    var returnToMatch: Match? = null

    /** The channel last played from a match page, so its row takes focus again on return. */
    var lastPlayedChannelId: Long? = null

    fun start(match: Match, options: List<WatchOption>) { _session.value = SportsSession(match, options) }

    /** [match] as it stands now, refreshed while collected (`Demand.PlayerScore`: that one league, 30 s live). */
    fun score(match: Match): Flow<Match> =
        repository.observe(Demand.PlayerScore(match)).map { it.match(match.key) ?: match }.distinctUntilChanged()

    /**
     * Goals in other followed matches, while collected and only with Goal alerts on. The followed leagues
     * are watched as the Home row watches them (live ones every 60 s). A match's first sighting only
     * records it, so opening the player never replays goals scored before.
     */
    fun goals(): Flow<MatchAlert> = settings.activeSportsConfig.map { it.alerts.enabled }.distinctUntilChanged().flatMapLatest { on ->
        if (!on) emptyFlow() else flow {
            val seen = HashMap<String, AlertSnapshot>()
            repository.observe(Demand.HomeRow).collect { board ->
                val config = settings.activeSportsConfig.first()
                val followed = config.leagues.toSet()
                for (m in board.today.values.flatten()) {
                    if (!MatchAlertDiff.wants(config, followed, m)) continue
                    val before = seen.put(m.key, AlertSnapshot.of(m)) ?: continue
                    if (m.key == _session.value?.match?.key) continue
                    MatchAlertDiff.diff(before, m, setOf(AlertKind.GOALS))
                        .filter { it.kind == MatchAlert.Kind.GOAL }
                        .forEach { emit(it) }
                }
            }
        }
    }

    /**
     * [match]'s channels as "Watch live" lists them by default (the profile's filter) — only if Find
     * channels already found them; empty otherwise, and nothing is searched here.
     */
    suspend fun channelsFor(match: Match): List<WatchOption> {
        val config = settings.activeSportsConfig.first()
        val core = watchReader.found(match) { SportsMemory.rememberedFor(config, it) } ?: return emptyList()
        // KevBox: the goal toast's "switch to that match" plays the first of these, so LiveOnSat's
        // listed channels go first, and stay first after Core's sort among the channels the filter
        // passes (a listed one it rejects stays below those); with none, the list is Core's alone.
        // A listings call made here has 4 s for its HTTP call (SupabaseApi's listings timeout); a
        // token refresh before it can add to that.
        val options = tv.own.owntv.kevbox.sports.ListedChannels.merge(listedChannels.options(match), core)
        val liveSources = activeProfileSources(settings, sourceDao).first().liveSourceIds
        val filter = WatchFilter.from(config, Locale.getDefault().language, liveSources)
        return tv.own.owntv.kevbox.sports.ListedChannels.listedFirst(WatchList.of(options, filter).shown, filter::accepts)
    }
}
