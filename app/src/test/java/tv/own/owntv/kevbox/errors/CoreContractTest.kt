// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.errors

import kotlin.reflect.KProperty1
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.epg.EpgSource
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.sync.SyncResult
import tv.own.owntv.core.sync.SyncRunStats
import tv.own.owntv.core.sync.work.CatalogSyncScheduler
import tv.own.owntv.core.sync.work.CatalogSyncState
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

        listOf(playerErrors, exoActive, previewErrors, reason, raw, result, finishedAt, failedMessage, lastError, playerFailure, previewFailure)
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
