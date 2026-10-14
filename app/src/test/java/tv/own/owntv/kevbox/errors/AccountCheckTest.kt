// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.errors

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.parser.XtreamClient.XtAccountDetails

class AccountCheckTest {

    private var clock = NOW
    private var fetches = 0
    private var answer: () -> XtAccountDetails = { XtAccountDetails(status = "Active", maxConnections = 1, activeConnections = 0) }

    private fun check() = AccountCheck(fetch = { fetches++; answer() }, now = { clock })

    @Test
    fun `account check runs at most once per 10 minutes`() = runBlocking {
        val check = check()
        check.result(XTREAM)
        clock += 9 * MINUTE
        check.result(XTREAM)
        assertEquals(1, fetches)

        clock += MINUTE
        check.result(XTREAM)
        assertEquals(2, fetches)
    }

    @Test
    fun `an unreachable answer is kept for 1 minute only`() = runBlocking {
        answer = { throw IOException("connection reset") }
        val check = check()
        assertEquals(AccountStatus.UNREACHABLE, check.result(XTREAM)!!.status)
        clock += AccountCheck.UNREACHABLE_GAP_MS - 1
        check.result(XTREAM)
        assertEquals(1, fetches)

        answer = { XtAccountDetails(status = "Expired") }
        clock += 1
        assertEquals(AccountStatus.EXPIRED, check.result(XTREAM)!!.status)
        assertEquals(2, fetches)
        // A real answer keeps the 10 minute gap.
        clock += 5 * MINUTE
        check.result(XTREAM)
        assertEquals(2, fetches)
    }

    @Test(timeout = 10_000)
    fun `a call that blocks past the timeout reads as unreachable and frees the caller`() = runBlocking {
        val release = CountDownLatch(1)
        // Core's HTTP call blocks the thread and ignores cancellation, as a dead panel would.
        val check = AccountCheck(fetch = { release.await(); XtAccountDetails(status = "Active") }, now = { clock }, timeoutMs = 200)
        val startedAt = System.nanoTime()
        val result = check.result(XTREAM)
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(AccountResult(AccountStatus.UNREACHABLE, null, null), result)
        assertTrue("took $tookMs ms", tookMs < 2_000)
        release.countDown()
        assertTrue(release.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun `a source that is not Xtream gets no check`() = runBlocking {
        assertNull(check().result(XTREAM.copy(type = SourceType.M3U)))
        assertEquals(0, fetches)
    }

    @Test
    fun `account results map from Core's details`() = runBlocking {
        assertEquals(
            AccountResult(AccountStatus.WRONG_LOGIN, null, 0),
            accountResultOf(XtAccountDetails(authOk = false)),
        )
        assertEquals(
            AccountResult(AccountStatus.EXPIRED, 0, 2),
            accountResultOf(XtAccountDetails(status = "Expired", activeConnections = 0, maxConnections = 2)),
        )
        assertEquals(AccountStatus.BANNED, accountResultOf(XtAccountDetails(status = "BANNED")).status)
        assertEquals(AccountStatus.DISABLED, accountResultOf(XtAccountDetails(status = "disabled")).status)
        // XC_VM sends auth: 0 with the status; the status wins.
        assertEquals(AccountStatus.EXPIRED, accountResultOf(XtAccountDetails(status = "Expired", authOk = false)).status)
        // No user_info at all: Core returns its defaults.
        assertEquals(AccountResult(AccountStatus.UNREACHABLE, null, null), accountResultOf(XtAccountDetails()))
        // A word the panel invented, with a good login, counts as active; max 0 means unlimited.
        assertEquals(
            AccountResult(AccountStatus.ACTIVE, 3, 0),
            accountResultOf(XtAccountDetails(status = "Trialing", activeConnections = 3)),
        )
        assertEquals(AccountStatus.ACTIVE, accountResultOf(XtAccountDetails(status = "Active")).status)

        answer = { throw IOException("connection reset") }
        assertEquals(AccountResult(AccountStatus.UNREACHABLE, null, null), check().result(XTREAM))
    }

    companion object {
        const val NOW = 1_800_000_000_000L
        const val MINUTE = 60_000L

        val XTREAM = SourceEntity(
            id = 3,
            name = "KevBox",
            type = SourceType.XTREAM,
            url = "http://panel.example.test:8080",
            username = "member",
            password = "s3cretpass",
        )
    }
}
