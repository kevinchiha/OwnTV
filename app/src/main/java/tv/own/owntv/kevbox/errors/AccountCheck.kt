// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.errors

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.parser.XtreamClient

/**
 * What Core's account details say about the account, in the order the panels need (AC-13):
 * a dead-account status wins over `auth` (XC_VM sends `auth: 0` together with "Expired"), then a
 * refused login, then an answer with no `user_info` at all, which Core parses to its defaults.
 */
fun accountResultOf(details: XtreamClient.XtAccountDetails): AccountResult {
    val word = details.status?.trim()?.lowercase()
    val status = when {
        word in DEAD_STATUSES -> word!!
        !details.authOk -> AccountStatus.WRONG_LOGIN
        details == NO_USER_INFO -> AccountStatus.UNREACHABLE
        else -> AccountStatus.ACTIVE
    }
    if (status == AccountStatus.UNREACHABLE) return AccountResult(status, null, null)
    return AccountResult(
        status = status,
        activeCons = details.activeConnections.takeIf { it >= 0 },
        // 0 means unlimited and is kept as 0.
        maxConnections = details.maxConnections,
    )
}

private val DEAD_STATUSES = setOf(AccountStatus.EXPIRED, AccountStatus.BANNED, AccountStatus.DISABLED)

/** What Core returns when the body held no `user_info`: every field at its default. */
private val NO_USER_INFO = XtreamClient.XtAccountDetails()

/**
 * Asks the panel's `player_api.php` about the account through Core's [XtreamClient] (Core's HTTP
 * client, the global proxy and the playlist's user agent). It never opens a stream, and the login
 * stays in the source entity. At most one call per [MIN_GAP_MS] for the same source, or per
 * [UNREACHABLE_GAP_MS] after an answer that never came; the last result lives in memory only, so a
 * restart checks afresh.
 *
 * Core's HTTP call blocks and ignores coroutine cancellation, so it runs on its own scope and only
 * the wait for it is bounded by [timeoutMs]: a dead or trickling panel costs one stuck IO thread,
 * never the reporter's single worker.
 */
class AccountCheck(
    private val fetch: suspend (SourceEntity) -> XtreamClient.XtAccountDetails,
    private val now: () -> Long,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    private val calls = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var lastSourceId: Long? = null
    private var lastAtMs: Long? = null
    private var last: AccountResult? = null

    /** Null for a source that is not Xtream. A failure or a 15 s timeout reads as unreachable. */
    suspend fun result(source: SourceEntity): AccountResult? {
        if (source.type != SourceType.XTREAM) return null
        return lock.withLock {
            val at = lastAtMs
            val previous = last
            // A network blip must not label every failure for 10 minutes, so unreachable keeps 1.
            val gap = if (previous?.status == AccountStatus.UNREACHABLE) UNREACHABLE_GAP_MS else MIN_GAP_MS
            if (previous != null && at != null && lastSourceId == source.id && now() - at < gap) {
                return@withLock previous
            }
            val fresh = check(source)
            lastSourceId = source.id
            lastAtMs = now()
            last = fresh
            fresh
        }
    }

    private suspend fun check(source: SourceEntity): AccountResult {
        val call = calls.async { fetch(source) }
        val details = try {
            withTimeoutOrNull(timeoutMs) { call.await() }
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        } catch (_: Exception) {
            null
        }
        // Does nothing once the call is done; after a timeout it frees the call as soon as Core lets go.
        call.cancel()
        return details?.let(::accountResultOf) ?: AccountResult(AccountStatus.UNREACHABLE, null, null)
    }

    companion object {
        const val MIN_GAP_MS = 600_000L
        const val UNREACHABLE_GAP_MS = 60_000L
        private const val TIMEOUT_MS = 15_000L
    }
}
