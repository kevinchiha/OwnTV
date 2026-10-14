package tv.own.owntv.kevbox.errors

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
