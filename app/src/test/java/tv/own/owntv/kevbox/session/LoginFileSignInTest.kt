package tv.own.owntv.kevbox.session

import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.own.owntv.kevbox.supabase.SignInResult

class LoginFileSignInTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val clock = NOW
    @Volatile private var signedIn: String? = null
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The coordinator's sign-in as far as the file sign-in sees it. Answers and returns Success by default. */
    @Volatile private var server: suspend (email: String, password: String, onAnswer: (SignInResult) -> Unit) -> SignInResult =
        { email, _, onAnswer -> SignInResult.Success(email).also(onAnswer) }

    private val loginFile get() = File(folder.root, LoginFileSignIn.LOGIN_FILE)
    private val resultFile get() = File(folder.root, LoginFileSignIn.RESULT_FILE)

    private fun result(): String? = resultFile.takeIf { it.exists() }?.readText()

    private fun login(email: Any? = "member@example.com", password: Any? = "pw", requestedAt: Any? = NOW) =
        JSONObject().apply {
            if (email != null) put("email", email)
            if (password != null) put("password", password)
            if (requestedAt != null) put("requestedAt", requestedAt)
        }.toString()

    private fun leave(text: String) = loginFile.writeText(text)

    private fun files() = folder.root.list()!!.sorted()

    private fun test(deadlineMs: Long = 5_000, block: suspend CoroutineScope.(LoginFileSignIn) -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        try {
            val subject = LoginFileSignIn(
                dir = { folder.root },
                now = { clock },
                signedInEmail = { signedIn },
                signOut = {
                    events += "signOut"
                    signedIn = null
                },
                signIn = { email, password, onAnswer ->
                    events += "signIn $email $password"
                    server(email, password, onAnswer)
                },
                scope = scope,
                io = Dispatchers.IO,
                deadlineMs = deadlineMs,
            )
            scope.block(subject)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(5) }

    // --- the file ------------------------------------------------------------------------------

    @Test
    fun `a fresh file signs in with its email and password, is gone, and leaves ok`() = test { subject ->
        leave(login(email = "Member@Example.com", password = " p w "))

        subject.checkNow()

        assertEquals(listOf("signIn Member@Example.com  p w "), events.toList())
        assertEquals("""{"ok":true}""", result())
        assertEquals(listOf(LoginFileSignIn.RESULT_FILE), files())
    }

    @Test
    fun `a file exactly 10 minutes old still signs in`() = test { subject ->
        leave(login(requestedAt = NOW - 600_000))

        subject.checkNow()

        assertEquals(1, events.size)
        assertEquals("""{"ok":true}""", result())
    }

    @Test
    fun `a file more than 10 minutes old is deleted with no sign-in and no result`() = test { subject ->
        leave(login(requestedAt = NOW - 600_001))

        subject.checkNow()

        assertTrue(events.isEmpty())
        assertEquals(emptyList<String>(), files())
    }

    @Test
    fun `bad JSON, a JSON array and a file over 64 KB are each deleted with no sign-in and no result`() {
        val big = login(password = "x".repeat(64 * 1024))
        for (text in listOf("{not json", "[1, 2]", "", big)) {
            test { subject ->
                leave(text)

                subject.checkNow()

                assertTrue(text.take(20), events.isEmpty())
                assertEquals(text.take(20), emptyList<String>(), files())
            }
        }
    }

    @Test
    fun `a missing or wrongly typed key is deleted with no sign-in and no result`() {
        val texts = listOf(
            login(email = null),
            login(email = "  "),
            login(email = 5),
            login(password = null),
            login(password = 1234),
            login(requestedAt = null),
            login(requestedAt = "$NOW"),
        )
        for (text in texts) {
            test { subject ->
                leave(text)

                subject.checkNow()

                assertTrue(events.isEmpty())
                assertEquals(emptyList<String>(), files())
            }
        }
    }

    @Test
    fun `no file does nothing`() = test { subject ->
        subject.checkNow()

        assertTrue(events.isEmpty())
        assertEquals(emptyList<String>(), files())
    }

    // --- order and results ---------------------------------------------------------------------

    @Test
    fun `the file is already gone when the sign-in call starts`() = test { subject ->
        var fileThere: Boolean? = null
        server = { email, _, onAnswer ->
            fileThere = loginFile.exists()
            SignInResult.Success(email).also(onAnswer)
        }
        leave(login())

        subject.checkNow()

        assertEquals(false, fileThere)
    }

    @Test
    fun `each sign-in answer writes its own result`() {
        val expected = mapOf(
            SignInResult.Success("member@example.com") to """{"ok":true}""",
            SignInResult.WrongCredentials to """{"ok":false,"error":"Wrong email or password"}""",
            SignInResult.NoNetwork to """{"ok":false,"error":"No internet connection"}""",
            SignInResult.Failed to """{"ok":false,"error":"Sign-in failed. Try again."}""",
        )
        for ((answer, json) in expected) {
            resultFile.delete()
            test { subject ->
                server = { _, _, onAnswer -> answer.also(onAnswer) }
                leave(login())

                subject.checkNow()

                assertEquals(json, result())
                assertFalse(result()!!.contains("pw"))
            }
        }
    }

    @Test
    fun `a sign-in call that throws writes the failed result`() = test { subject ->
        server = { _, _, _ -> throw IllegalStateException("boom") }
        leave(login())

        subject.checkNow()

        assertEquals("""{"ok":false,"error":"Sign-in failed. Try again."}""", result())
    }

    @Test
    fun `the result is written at the server's answer, before the sign-in call returns`() = test { subject ->
        val rest = CompletableDeferred<Unit>()
        server = { email, _, onAnswer ->
            val answer = SignInResult.Success(email)
            onAnswer(answer)
            rest.await() // the playlist check
            answer
        }
        leave(login())

        subject.checkNow()

        assertEquals("""{"ok":true}""", result())
        assertFalse(rest.isCompleted)
        rest.complete(Unit)
    }

    @Test
    fun `no answer by the deadline writes the timeout text, and a late answer does not overwrite it`() = test(deadlineMs = 200) { subject ->
        val gate = CompletableDeferred<Unit>()
        val returned = AtomicBoolean(false)
        server = { email, _, onAnswer ->
            gate.await()
            SignInResult.Success(email).also(onAnswer).also { returned.set(true) }
        }
        leave(login())

        subject.checkNow()

        val timeout = """{"ok":false,"error":"Sign-in took too long. Check the box's internet."}"""
        assertEquals(timeout, result())
        gate.complete(Unit)
        waitFor { returned.get() }
        delay(50)
        assertEquals(timeout, result())
        assertEquals(listOf(LoginFileSignIn.RESULT_FILE), files())
    }

    @Test
    fun `a check while a file sign-in runs does nothing, and the next file is read once it ends`() = test(deadlineMs = 100) { subject ->
        val gate = CompletableDeferred<Unit>()
        server = { email, _, onAnswer ->
            gate.await()
            SignInResult.Success(email).also(onAnswer)
        }
        leave(login())
        subject.checkNow() // times out; the call is still out
        leave(login(email = "second@example.com"))

        subject.checkNow()
        subject.check()
        delay(50)

        assertTrue(loginFile.exists())
        assertEquals(1, events.size)

        gate.complete(Unit)
        resultFile.delete()
        waitFor {
            subject.check()
            !loginFile.exists() && resultFile.exists()
        }
        assertEquals("signIn second@example.com pw", events.last())
        assertEquals("""{"ok":true}""", result())
    }

    // --- accounts ------------------------------------------------------------------------------

    @Test
    fun `signed in as a different email, it signs out first`() = test { subject ->
        signedIn = "old@example.com"
        leave(login(email = "new@example.com"))

        subject.checkNow()

        assertEquals(listOf("signOut", "signIn new@example.com pw"), events.toList())
        assertEquals("""{"ok":true}""", result())
    }

    @Test
    fun `signed in as the same email, trimmed and lowercased, it signs in again without signing out`() = test { subject ->
        signedIn = "member@example.com"
        leave(login(email = " Member@EXAMPLE.com "))

        subject.checkNow()

        assertEquals(listOf("signIn  Member@EXAMPLE.com  pw"), events.toList())
    }

    @Test
    fun `signed out, it signs in without signing out`() = test { subject ->
        leave(login())

        subject.checkNow()

        assertEquals(listOf("signIn member@example.com pw"), events.toList())
        assertNull(signedIn)
    }

    private companion object {
        const val NOW = 1_761_746_592_000L
    }
}
