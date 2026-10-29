// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.errors

import android.content.Context
import android.net.Uri
import kotlin.reflect.KFunction2
import kotlin.reflect.KProperty1
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import okhttp3.OkHttpClient
import org.junit.Test
import tv.own.owntv.core.brand.AppIcon
import tv.own.owntv.core.brand.AppIconSwitcher
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.FavoriteDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.epg.EpgSource
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.core.launcher.LauncherDeepLink
import tv.own.owntv.core.launcher.LauncherIntegrationRepository
import tv.own.owntv.core.live.LiveEpgReader
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.parser.XtEpgEntry
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.core.repository.ActiveProfileSources
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sync.SyncResult
import tv.own.owntv.core.sync.SyncRunStats
import tv.own.owntv.core.sync.work.CatalogSyncScheduler
import tv.own.owntv.core.sync.work.CatalogSyncState
import tv.own.owntv.core.tv.LiveLogoPosterArt
import tv.own.owntv.player.ErrorInfo
import tv.own.owntv.player.LivePreviewEngine
import tv.own.owntv.player.LiveTuneController
import tv.own.owntv.player.OwnTVPlayer
import tv.own.owntv.player.PlaybackFailure
import tv.own.owntv.player.PlayerErrors
import tv.own.owntv.player.PlayerFailureReason

/**
 * Pins every Core member this phase reads (AC-21). Each typed reference below only compiles while
 * the member keeps its shape, so a Core update that changes one fails the build here, not on a TV.
 */
class CoreContractTest {

    @Test
    fun `Core contract`() {
        val playerErrors: KProperty1<OwnTVPlayer, StateFlow<ErrorInfo?>> = OwnTVPlayer::errorInfo
        val exoActive: KProperty1<OwnTVPlayer, Boolean> = OwnTVPlayer::isExoActive
        val previewErrors: KProperty1<LivePreviewEngine, StateFlow<ErrorInfo?>> = LivePreviewEngine::errorInfo
        val reason: KProperty1<ErrorInfo, PlayerFailureReason?> = ErrorInfo::reason
        val raw: KProperty1<ErrorInfo, String?> = ErrorInfo::raw
        val lastStats: (SourceRepository, Long) -> SyncRunStats? = SourceRepository::getLastSyncStats
        val playerFailure: KProperty1<OwnTVPlayer, StateFlow<PlaybackFailure?>> = OwnTVPlayer::error
        val previewFailure: KProperty1<LivePreviewEngine, StateFlow<PlaybackFailure?>> = LivePreviewEngine::error
        val lost: PlaybackFailure = PlaybackFailure.LostConnection
        // LiveViewModel's host overrides this to hear Core's engine ladder (AC-10).
        val ladder: LiveTuneController.Host.(Boolean, PlayerFailureReason, String) -> Unit =
            LiveTuneController.Host::recordLadderEvent
        val ladderSteps = listOf(PlayerFailureReason.LIVE_FALLBACK, PlayerFailureReason.LIVE_NO_FALLBACK)
        val result: KProperty1<SyncRunStats, SyncResult> = SyncRunStats::result
        val finishedAt: KProperty1<SyncRunStats, Long> = SyncRunStats::finishedAt
        val failedMessage: KProperty1<SyncResult.Failed, String?> = SyncResult.Failed::message
        val lastError: KProperty1<EpgSource, String?> = EpgSource::lastError
        val accountStatus: suspend (XtreamClient, SourceEntity) -> XtreamClient.XtAccountDetails = XtreamClient::fetchAccountStatus
        val observeSync: (CatalogSyncScheduler, Long) -> Flow<CatalogSyncState> = CatalogSyncScheduler::observeSync
        // 0.4 play notes: PlayWatcher reads whether each engine plays and whether the player's item is live.
        val previewState: KProperty1<LivePreviewEngine, StateFlow<LivePreviewEngine.State>> = LivePreviewEngine::state
        val previewPlaying: LivePreviewEngine.State = LivePreviewEngine.State.PLAYING
        val playerPlaying: KProperty1<OwnTVPlayer, StateFlow<Boolean>> = OwnTVPlayer::isPlaying
        val playerLive: KProperty1<OwnTVPlayer, Boolean> = OwnTVPlayer::isLiveContent
        // isPlaying stays true through mpv's silent retries; buffering says no frame shows yet.
        val playerBuffering: KProperty1<OwnTVPlayer, StateFlow<Boolean>> = OwnTVPlayer::buffering

        // 0.5 watching diary: GuideNow reads the stored guide's on-now entry as LiveViewModel.nowPlayingFor does,
        // with the profile's Live customizations and the guide offset read as MiniGuideLoader reads them.
        val nowProgrammes: suspend (LiveEpgReader, List<ChannelEntity>, SectionCustomizations, Int) -> Map<Long, XtEpgEntry> =
            LiveEpgReader::nowProgrammesFor
        val entryTitle: KProperty1<XtEpgEntry, String> = XtEpgEntry::title
        val entryStop: KProperty1<XtEpgEntry, Long> = XtEpgEntry::stopMs
        val observeCustom: (CustomizationStore, Long, MediaType) -> Flow<SectionCustomizations> = CustomizationStore::observe
        val epgOffset: KProperty1<SettingsRepository, Flow<Int>> = SettingsRepository::epgOffsetMinutes
        val activeSources: (SettingsRepository, SourceDao) -> Flow<ActiveProfileSources> = ::activeProfileSources
        val activeProfile: KProperty1<ActiveProfileSources, Long> = ActiveProfileSources::profileId
        val channelId: KProperty1<ChannelEntity, Long> = ChannelEntity::id
        listOf(entryTitle, entryStop, epgOffset, activeProfile, channelId).forEach { assertNotNull(it) }
        assertNotNull(nowProgrammes)
        assertNotNull(observeCustom)
        assertNotNull(activeSources)

        // Home screen rows (kevbox/home): what the rows hold, Core's own TV home switch, the card
        // links, the channel logo and the fitted 16:9 poster art.
        val recentlyWatched: (ChannelDao, Long, Int) -> Flow<List<ChannelEntity>> = ChannelDao::recentlyWatched
        val channelById: suspend (ChannelDao, Long) -> ChannelEntity? = ChannelDao::getById
        val favoriteIds: (FavoriteDao, Long, MediaType) -> Flow<List<Long>> = FavoriteDao::observeFavoriteIds
        val allProfiles: suspend (ProfileDao) -> List<ProfileEntity> = ProfileDao::getAllOnce
        val profileId: KProperty1<ProfileEntity, Long> = ProfileEntity::id
        val liveSources: KProperty1<ActiveProfileSources, List<Long>> = ActiveProfileSources::liveSourceIds
        val tvHomeOn: KProperty1<SettingsRepository, Flow<Boolean>> = SettingsRepository::androidTvHomeEnabled
        val setTvHomeOn: suspend (SettingsRepository, Boolean) -> Unit = SettingsRepository::setAndroidTvHomeEnabled
        val sortGuide: KProperty1<SettingsRepository, Flow<SettingsRepository.GuideSort>> = SettingsRepository::sortGuide
        val guideSorts = SettingsRepository.GuideSort.entries.map { it.name }
        val clearProfile: suspend (LauncherIntegrationRepository, Long) -> Unit = LauncherIntegrationRepository::clearProfile
        val liveLink: (Long?, String?, String?) -> LauncherDeepLink = { sourceId, remoteId, name ->
            LauncherDeepLink.Live(sourceId = sourceId, remoteId = remoteId, name = name)
        }
        val linkUri: (LauncherDeepLink) -> Uri = LauncherDeepLink::toUri
        val openLive: LauncherDeepLink = LauncherDeepLink.OpenLiveSection
        val posterArt: KFunction2<Context, OkHttpClient, LiveLogoPosterArt> = ::LiveLogoPosterArt
        val posterCached: (LiveLogoPosterArt, Uri, Int) -> Uri? = LiveLogoPosterArt::cached
        val posterPrepare: (LiveLogoPosterArt, List<Uri>, Int, suspend () -> Unit) -> Unit = LiveLogoPosterArt::prepare
        val appliedIcon: (Context) -> AppIcon = AppIconSwitcher::applied
        val banner: KProperty1<AppIcon, Int> = AppIcon::banner
        val customizeKey: (ChannelEntity) -> String = CustomizeKeys::channel
        val hiddenItems: KProperty1<SectionCustomizations, Map<String, String>> = SectionCustomizations::hiddenItems
        val itemNames: KProperty1<SectionCustomizations, Map<String, String>> = SectionCustomizations::itemNames
        val logoUrl: KProperty1<ChannelEntity, String?> = ChannelEntity::displayLogoUrl
        listOf(profileId, liveSources, tvHomeOn, sortGuide, banner, hiddenItems, itemNames, logoUrl).forEach { assertNotNull(it) }
        listOf(recentlyWatched, channelById, favoriteIds, allProfiles, setTvHomeOn, clearProfile, liveLink, linkUri)
            .forEach { assertNotNull(it) }
        listOf(openLive, posterArt, posterCached, posterPrepare, appliedIcon, customizeKey).forEach { assertNotNull(it) }
        // The Recent row drops hidden categories, and adult ones on a Kids profile, as EpgViewModel reads them.
        val hiddenCategoryIds: (List<CategoryEntity>, Set<String>, Boolean) -> Set<Long> = AdultCategoryClassifier::hiddenCategoryIds
        val observeCategories: (CategoryDao, List<Long>, MediaType) -> Flow<List<CategoryEntity>> = CategoryDao::observe
        val profileById: suspend (ProfileDao, Long) -> ProfileEntity? = ProfileDao::getById
        val isKids: KProperty1<ProfileEntity, Boolean> = ProfileEntity::isKids
        val hiddenCategories: KProperty1<SectionCustomizations, Set<String>> = SectionCustomizations::hiddenCategories
        val channelCategory: KProperty1<ChannelEntity, Long?> = ChannelEntity::categoryId
        listOf(hiddenCategoryIds, observeCategories, profileById, isKids, hiddenCategories, channelCategory).forEach { assertNotNull(it) }
        // HomeRows.favorites copies EpgViewModel's guideOrder with one branch per value.
        assertEquals(listOf("ALPHA", "PROVIDER", "LIVE_TV", "CATCHUP", "FAVORITES"), guideSorts)

        listOf(playerErrors, exoActive, previewErrors, reason, raw, result, finishedAt, failedMessage, lastError, playerFailure, previewFailure)
            .forEach { assertNotNull(it) }
        listOf(previewState, previewPlaying, playerPlaying, playerLive, playerBuffering)
            .forEach { assertNotNull(it) }
        assertNotNull(lastStats)
        assertNotNull(accountStatus)
        assertNotNull(observeSync)
        assertNotNull(lost)
        assertNotNull(ladder)
        assertEquals(2, ladderSteps.toSet().size)

        // accountResultOf reads "no user_info" as Core's all-defaults value; these are those defaults.
        assertEquals(
            XtreamClient.XtAccountDetails(
                expiryMs = null,
                hlsSupported = false,
                maxConnections = 0,
                activeConnections = -1,
                status = null,
                authOk = true,
                trial = false,
            ),
            XtreamClient.XtAccountDetails(),
        )

        assertEquals(458, PlayerErrors.httpStatusIn("Response code: 458"))
        assertEquals(403, PlayerErrors.httpStatusIn("http: HTTP error 403 Forbidden"))
        assertNull(PlayerErrors.httpStatusIn("Server returned 4XX Client Error, but not one of 40{0,1,3,4}"))
    }
}
