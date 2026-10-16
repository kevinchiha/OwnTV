// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.errors

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.player.PlayerFailureReason

/**
 * What was on screen when playback failed. [streamUrl] only ever leaves the TV as its host.
 * [channelEntity] is the channel itself, which the watching diary looks up in the stored guide; it
 * stays in memory and is never queued or sent.
 */
data class SceneSnapshot(
    val channel: String?,
    val category: String?,
    val streamUrl: String?,
    val inBox: Boolean,
    val catchup: Boolean,
    val channelEntity: ChannelEntity? = null,
)

/**
 * One failure a watcher saw, before it is classified. [Playback.lost] is true when Core gave up on a
 * channel that had played (its `LostConnection` failure), whatever text it kept.
 */
sealed interface Failure {
    data class Playback(
        val engine: Engine,
        val raw: String?,
        val reason: PlayerFailureReason?,
        val scene: SceneSnapshot,
        val lost: Boolean = false,
    ) : Failure
    data class Playlist(val sourceId: Long, val message: String?) : Failure
    data class Guide(val feedUrl: String?, val message: String?) : Failure
}

private val ACCOUNT_STATUSES = setOf(401, 403, 404)

/**
 * Whether the status hides the reason, so the Xtream account check should run (AC-13): a playback
 * 401, 403 or 404; a playing channel lost (a panel kicking it looks like this); any mpv failure
 * with no status (flood blocks show as timeouts, a panel's 884 as a format failure); every playlist
 * failure. Never a guide.
 */
fun needsAccountCheck(failure: Failure, c: Classified): Boolean = when (failure) {
    is Failure.Playback -> c.httpStatus in ACCOUNT_STATUSES ||
        c.code == LiveErrorCodes.STREAM_LOST ||
        (failure.engine == Engine.MPV && c.httpStatus == null)
    is Failure.Playlist -> true
    is Failure.Guide -> false
}

/**
 * The one entry point every watcher calls. [record] only hands the failure to a channel; one worker
 * on [scope] classifies it, runs the account check when needed, fills the TV fields, scrubs every
 * text field, queues the entry and tells the listeners. Only a dropped failure logs, and then only
 * the exception's class name.
 *
 * The collaborators come in as functions so the pipeline runs in plain JUnit; Koin wires them to
 * the sender, the reconciler, Core's source table, settings and connectivity.
 */
class ErrorReporter(
    private val queue: ErrorQueue,
    private val kick: () -> Unit,
    private val startSender: () -> Unit,
    private val accountCheck: AccountCheck,
    private val currentSource: suspend () -> SourceEntity?,
    private val sourceById: suspend (Long) -> SourceEntity?,
    private val proxyUsable: suspend () -> Boolean,
    private val isOnline: () -> Boolean,
    private val secretChanges: Flow<Any?>,
    private val readSecrets: suspend () -> Set<String>,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val model: () -> String? = { TvInfo.model },
    private val android: () -> String? = { TvInfo.android },
) {
    private val inbox = Channel<Failure>(Channel.UNLIMITED)
    private val listeners = CopyOnWriteArrayList<(ErrorEntry) -> Unit>()
    private val started = AtomicBoolean(false)

    /** Safe on the main thread: one enqueue, then it returns. */
    fun record(failure: Failure) {
        inbox.trySend(failure)
    }

    /** [listener] runs on the worker after each queued entry. One that throws is skipped. */
    fun addListener(listener: (ErrorEntry) -> Unit) {
        listeners += listener
    }

    /** Starts the worker, the sender and the secret-word upkeep. A second call does nothing. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            secretChanges.collect { refreshSecrets() }
        }
        scope.launch(Dispatchers.IO) {
            for (failure in inbox) {
                guarded { handle(failure) }
            }
        }
        startSender()
    }

    private suspend fun handle(failure: Failure) {
        // A refreshed Supabase session changes the tokens without any signal, so read them again here.
        refreshSecrets()
        val online = isOnline()
        val base = when (failure) {
            is Failure.Playback -> ErrorClassifier.playback(failure.engine, failure.raw, failure.reason, online, failure.lost)
            is Failure.Playlist -> ErrorClassifier.playlist(failure.message, online)
            is Failure.Guide -> ErrorClassifier.guide(failure.message, online)
        }
        // The failing playlist names its own source; playback uses the one the TV is on.
        val source = when (failure) {
            is Failure.Playlist -> guardedOrNull { sourceById(failure.sourceId) }
            is Failure.Playback -> if (needsAccountCheck(failure, base)) guardedOrNull { currentSource() } else null
            is Failure.Guide -> null
        }
        val account = if (source != null && needsAccountCheck(failure, base)) accountCheck.result(source) else null
        val classified = ErrorClassifier.withAccount(base, account, playlist = failure is Failure.Playlist)
        val secrets = SecretWords.current() + listOfNotNull(source?.username, source?.password)
        val entry = entryOf(failure, classified, account, source, secrets)
        queue.add(entry)
        listeners.forEach { listener -> guarded { listener(entry) } }
        kick()
    }

    private suspend fun entryOf(
        failure: Failure,
        c: Classified,
        account: AccountResult?,
        source: SourceEntity?,
        secrets: Set<String>,
    ): ErrorEntry {
        val at = now()
        val playback = failure as? Failure.Playback
        val scene = playback?.scene
        val raw = when (failure) {
            is Failure.Playback -> failure.raw
            is Failure.Playlist -> failure.message
            is Failure.Guide -> failure.message
        }
        val host = when (failure) {
            is Failure.Playback -> Scrubber.host(failure.scene.streamUrl)
            is Failure.Playlist -> Scrubber.host(source?.url)
            is Failure.Guide -> Scrubber.host(failure.feedUrl)
        }
        return ErrorEntry(
            firstAtMs = at,
            lastAtMs = at,
            repeatCount = 1,
            code = c.code,
            httpStatus = c.httpStatus,
            errorName = Scrubber.text(c.errorName, secrets, FIELD_MAX),
            accountStatus = account?.status,
            activeCons = account?.activeCons,
            maxConnections = account?.maxConnections,
            engine = playback?.engine?.wire,
            surface = playback?.let { if (it.engine == Engine.EXO && it.scene.inBox) SURFACE_PREVIEW else SURFACE_FULL },
            live = playback?.let { if (it.engine == Engine.EXO) true else !it.scene.catchup },
            channel = Scrubber.text(scene?.channel, secrets, FIELD_MAX),
            category = Scrubber.text(scene?.category, secrets, FIELD_MAX),
            providerHost = host,
            proxy = guardedOrNull { proxyUsable() },
            model = model()?.takeIf { it.isNotEmpty() },
            android = android()?.takeIf { it.isNotEmpty() },
            messageShort = Scrubber.text(raw, secrets, Scrubber.MESSAGE_MAX),
        )
    }

    private suspend fun refreshSecrets() {
        guardedOrNull { readSecrets() }?.let(SecretWords::set)
    }

    /** Runs [block]; anything but cancellation is dropped so the worker keeps going. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Reporting must never take the app down; this one failure goes unreported. The class
            // name only: the message could hold a stream address or a login.
            Log.w(LOG_TAG, "failure dropped: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun <T> guardedOrNull(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val LOG_TAG = "KevBoxErrors"
        const val FIELD_MAX = 120
        const val SURFACE_PREVIEW = "preview"
        const val SURFACE_FULL = "full"
    }
}
