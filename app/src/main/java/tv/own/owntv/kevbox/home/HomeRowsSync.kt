// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.home

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.FavoriteDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.launcher.LauncherIntegrationRepository
import tv.own.owntv.core.live.LiveEpgReader
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.settings.SettingsRepository.GuideSort
import tv.own.owntv.kevbox.session.KevBoxAuth

/** What both rows are built from, for the active profile of a signed-in TV. */
data class HomeInputs(
    /** Live watch history, newest first. */
    val history: List<ChannelEntity>,
    /** Every favourite live channel, in no particular order. */
    val favorites: List<ChannelEntity>,
    val sort: GuideSort,
    val liveSourceIds: Set<Long>,
    val customizations: SectionCustomizations,
    /** The profile's hidden categories, plus the adult ones on a Kids profile. */
    val hiddenCategoryIds: Set<Long>,
)

/** The app data [HomeRowsSync] reads. [CoreHomeReads] is the real one. */
interface HomeReads {
    /** Emits whenever a row's content may have changed. */
    fun changes(): Flow<Unit>

    /** Null when signed out or no profile is active: both rows are then emptied. */
    suspend fun inputs(): HomeInputs?

    /** The programme on now, by channel id, from the stored guide. */
    suspend fun nowTitles(channels: List<ChannelEntity>, customizations: SectionCustomizations): Map<Long, String>

    /** Whether Core's own TV home publishing (Settings › App › Android TV home) is on. */
    suspend fun coreHomeOn(): Boolean

    /** Turns Core's publishing off and clears the rows it published, for every profile. */
    suspend fun turnCoreHomeOff()
}

/**
 * Keeps the TV home screen's Recent channels and Favorites rows in step with the app.
 *
 * [start] syncs 1 s after the last change to the watch history, the favourites, the session, the
 * active profile, its customizations or the guide's Order setting, and at once when MainActivity
 * stops, and it schedules the 30-minute background job. [syncNow] is also what HomeRowsWorker calls.
 */
class HomeRowsSync(
    private val provider: HomeProvider,
    private val reads: HomeReads,
    private val art: HomeArt,
    private val link: (ChannelEntity) -> String,
    /** Registers an action for each MainActivity stop. */
    private val onAppLeft: (() -> Unit) -> Unit,
    /** Schedules the periodic background sync; HomeRowsWorker.schedule in the app. */
    private val schedule: () -> Unit,
    private val scope: CoroutineScope,
    /** Where the provider and database are read; Dispatchers.IO in the app. */
    private val io: CoroutineContext,
) {
    private val begun = AtomicBoolean(false)
    private val mutex = Mutex()

    /** Once per process. */
    @OptIn(FlowPreview::class)
    fun start() {
        if (!begun.compareAndSet(false, true)) return
        if (!provider.supported()) return
        schedule()
        scope.launch {
            reads.changes()
                // A database or settings error must not crash the app (the app scope has no handler):
                // log it and watch again after a pause.
                .retryWhen { cause, _ ->
                    if (cause is CancellationException) throw cause
                    Log.w(TAG, "home rows: change watch failed ${cause.javaClass.simpleName}")
                    delay(RETRY_MS)
                    true
                }
                .debounce(DEBOUNCE_MS)
                .collect { syncNow() }
        }
        onAppLeft { scope.launch { syncNow() } }
    }

    /** One full sync of both rows. Never throws: trouble is logged and the next sync tries again. */
    suspend fun syncNow() {
        if (!provider.supported()) return
        try {
            mutex.withLock { withContext(io) { sync() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "home rows: sync failed ${e.javaClass.simpleName}")
        }
    }

    private suspend fun sync() {
        turnCoreHomeOff()
        val channels = provider.ensureChannels()
        val missingArt = LinkedHashSet<String>()
        val fitted: (String) -> String? = { raw -> art.fitted(raw) ?: null.also { missingArt += raw } }
        val wanted = wanted(fitted)
        val existing = provider.programs(channels)
        for ((row, channelId) in channels) {
            val diff = HomeRows.diff(wanted[row].orEmpty(), existing[row].orEmpty())
            if (diff.insert.isNotEmpty() || diff.update.isNotEmpty() || diff.delete.isNotEmpty()) {
                provider.apply(channelId, diff)
            }
        }
        // The fitted logos replace the raw ones on the sync after they are written.
        if (missingArt.isNotEmpty()) art.prepare(missingArt.toList()) { syncNow() }
    }

    /** Turns Core's own publishing off when it is on. A failure is logged and the rows sync anyway. */
    private suspend fun turnCoreHomeOff() {
        try {
            if (reads.coreHomeOn()) reads.turnCoreHomeOff()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "home rows: Core switch-off failed ${e.javaClass.simpleName}")
        }
    }

    private suspend fun wanted(fitted: (String) -> String?): Map<HomeRow, List<HomeCard>> {
        val inputs = reads.inputs() ?: return emptyMap()
        val all = (inputs.history + inputs.favorites).distinctBy { it.id }
        val now = if (all.isEmpty()) emptyMap() else reads.nowTitles(all, inputs.customizations)
        return mapOf(
            HomeRow.RECENT to HomeRows.recent(
                inputs.history, inputs.liveSourceIds, inputs.customizations, inputs.hiddenCategoryIds, now, fitted, link,
            ),
            HomeRow.FAVORITES to HomeRows.favorites(
                inputs.favorites, inputs.sort, inputs.liveSourceIds, inputs.customizations, now, fitted, link,
            ),
        )
    }

    private companion object {
        const val TAG = "KevBoxHomeRows"
        const val DEBOUNCE_MS = 1_000L
        const val RETRY_MS = 30_000L
    }
}

/** [HomeReads] over Core's database and settings and the KevBox session. */
class CoreHomeReads(
    private val auth: KevBoxAuth,
    private val settings: SettingsRepository,
    private val sourceDao: SourceDao,
    private val channelDao: ChannelDao,
    private val categoryDao: CategoryDao,
    private val favoriteDao: FavoriteDao,
    private val customize: CustomizationStore,
    private val epg: LiveEpgReader,
    private val profileDao: ProfileDao,
    private val launcher: LauncherIntegrationRepository,
) : HomeReads {

    private val signedIn = auth.email.map { it != null }.distinctUntilChanged()
    private val active = activeProfileSources(settings, sourceDao)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun changes(): Flow<Unit> =
        combine(signedIn, active, settings.sortGuide) { signed, sources, _ -> if (signed) sources.profileId else NO_PROFILE }
            .flatMapLatest { pid ->
                if (pid < 0) {
                    flowOf(Unit)
                } else {
                    combine(
                        channelDao.recentlyWatched(pid, HISTORY_LIMIT),
                        favoriteDao.observeFavoriteIds(pid, MediaType.LIVE),
                        customize.observe(pid, MediaType.LIVE),
                    ) { _, _, _ -> }
                }
            }

    override suspend fun inputs(): HomeInputs? {
        if (auth.email.value == null) return null
        val sources = active.first()
        val pid = sources.profileId
        if (pid < 0) return null
        // The history query has a limit; the favourites are every id, each channel loaded on its own.
        val favoriteIds = favoriteDao.observeFavoriteIds(pid, MediaType.LIVE).first()
        val customizations = customize.observe(pid, MediaType.LIVE).first()
        return HomeInputs(
            history = channelDao.recentlyWatched(pid, HISTORY_LIMIT).first(),
            favorites = favoriteIds.distinct().mapNotNull { channelDao.getById(it) },
            sort = settings.sortGuide.first(),
            liveSourceIds = sources.liveSourceIds.toSet(),
            customizations = customizations,
            hiddenCategoryIds = hiddenCategoryIds(pid, sources.liveSourceIds, customizations),
        )
    }

    /** As EpgViewModel and ListedChannels read them: hidden categories, and adult ones on a Kids profile. */
    private suspend fun hiddenCategoryIds(pid: Long, liveSourceIds: List<Long>, cust: SectionCustomizations): Set<Long> {
        val isKids = profileDao.getById(pid)?.isKids == true
        if (cust.hiddenCategories.isEmpty() && !isKids) return emptySet()
        return AdultCategoryClassifier.hiddenCategoryIds(
            categoryDao.observe(liveSourceIds, MediaType.LIVE).first(),
            cust.hiddenCategories,
            isKids,
        )
    }

    /** As GuideNow reads it: one batched nowProgrammesFor, never nowNext (no provider calls). */
    override suspend fun nowTitles(
        channels: List<ChannelEntity>,
        customizations: SectionCustomizations,
    ): Map<Long, String> =
        epg.nowProgrammesFor(channels, customizations, settings.epgOffsetMinutes.first()).mapValues { it.value.title }

    override suspend fun coreHomeOn(): Boolean = settings.androidTvHomeEnabled.first()

    override suspend fun turnCoreHomeOff() {
        settings.setAndroidTvHomeEnabled(false)
        for (profile in profileDao.getAllOnce()) launcher.clearProfile(profile.id)
    }

    private companion object {
        const val NO_PROFILE = -1L
        /** More than the row's 15, so hidden and other-source channels still leave a full row. */
        const val HISTORY_LIMIT = 50
    }
}
