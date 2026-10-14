package tv.own.owntv.kevbox.errors

import android.content.ContextWrapper
import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.android.core.SentryAndroidOptions
import io.sentry.protocol.Message
import io.sentry.protocol.SentryException
import io.sentry.protocol.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KevBoxSentryTest {

    @Test
    fun `no key never initialises Sentry`() {
        var calls = 0
        listOf("", " ", "\t\n ").forEach { dsn ->
            assertFalse(KevBoxSentry.start(ContextWrapper(null), dsn) { _, _ -> calls++ })
        }
        assertEquals(0, calls)
    }

    @Test
    fun `a key initialises Sentry once with the configured options`() {
        val options = SentryAndroidOptions()
        var calls = 0
        val started = KevBoxSentry.start(ContextWrapper(null), DSN) { _, configuration ->
            calls++
            configuration.configure(options)
        }
        assertTrue(started)
        assertEquals(1, calls)
        assertEquals(DSN, options.dsn)
        assertEquals("production", options.environment)
    }

    @Test
    fun `scrubber leaves nothing private in an event`() {
        val username = "kevuser42"
        val password = "S3cretPass"
        val refresh = "v1rfr3shOpaque"
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJl"
        val email = "kev@example.com"
        val address = "http://panel.example:8080/live/$username/$password/1.ts"
        val secrets = setOf(username, password, refresh, email, jwt)

        val event = SentryEvent().apply {
            message = Message().apply {
                formatted = "player failed on $address"
                message = "player failed on $address"
            }
            exceptions = listOf(SentryException().apply { value = "login $username:$password rejected" })
            breadcrumbs = mutableListOf(
                Breadcrumb("signed in as $email").apply { setData("who", email) },
            )
            setTag("session", jwt)
            setExtra("refresh", "token $refresh")
            user = User().apply {
                id = "user-1"
                this.email = email
                this.username = username
                ipAddress = "203.0.113.9"
            }
        }

        val out = KevBoxSentry.scrub(event, secrets)

        val texts = listOfNotNull(
            out.message?.formatted,
            out.message?.message,
            *out.exceptions.orEmpty().map { it.value }.toTypedArray(),
            *out.breadcrumbs.orEmpty().map { it.message }.toTypedArray(),
            *out.breadcrumbs.orEmpty().flatMap { it.data.values }.map { it.toString() }.toTypedArray(),
            *out.tags.orEmpty().values.toTypedArray(),
            *out.extras.orEmpty().values.map { it.toString() }.toTypedArray(),
        )
        assertTrue(texts.size >= 7)
        listOf("panel.example", username, password, refresh, email, "eyJ").forEach { secret ->
            texts.forEach { assertFalse("$secret in $it", it.contains(secret, ignoreCase = true)) }
        }
        assertNull(out.user?.email)
        assertNull(out.user?.username)
        assertNull(out.user?.ipAddress)
        assertEquals("user-1", out.user?.id)
    }

    @Test
    fun `configure turns off tracing, profiling, sessions and replay and keeps ANR reporting on`() {
        val options = SentryAndroidOptions()
        KevBoxSentry.configure(options, DSN)

        assertEquals(DSN, options.dsn)
        assertEquals("production", options.environment)
        assertEquals(0.0, options.tracesSampleRate!!, 0.0)
        assertEquals(0.0, options.profilesSampleRate!!, 0.0)
        assertEquals(0.0, options.profileSessionSampleRate!!, 0.0)
        assertFalse(options.isEnableAutoSessionTracking)
        assertEquals(0.0, options.sessionReplay.sessionSampleRate ?: 0.0, 0.0)
        assertEquals(0.0, options.sessionReplay.onErrorSampleRate ?: 0.0, 0.0)
        assertFalse(options.isAttachScreenshot)
        assertFalse(options.isAttachViewHierarchy)
        assertFalse(options.isSendDefaultPii)

        assertTrue(options.isAnrEnabled)
        assertTrue(options.isEnableNdk)

        assertFalse(options.isEnableActivityLifecycleBreadcrumbs)
        assertFalse(options.isEnableAppLifecycleBreadcrumbs)
        assertFalse(options.isEnableSystemEventBreadcrumbs)
        assertFalse(options.isEnableAppComponentBreadcrumbs)
        assertFalse(options.isEnableNetworkEventBreadcrumbs)
        assertFalse(options.isEnableUserInteractionBreadcrumbs)
        assertEquals(20, options.maxBreadcrumbs)
    }

    @Test
    fun `configure scrubs events and breadcrumbs before they leave`() {
        // Patterns only: SecretWords is process-wide and ErrorReporterTest's workers may still be writing it.
        val options = SentryAndroidOptions()
        KevBoxSentry.configure(options, DSN)

        val event = SentryEvent().apply {
            message = Message().apply { formatted = "open http://panel.example/live/u/p/1.ts failed" }
            user = User().apply { id = "user-1"; email = "kev@example.com" }
        }
        val sent = options.beforeSend!!.execute(event, io.sentry.Hint())!!
        assertFalse(sent.message!!.formatted!!.contains("panel.example"))
        assertNull(sent.user!!.email)
        assertEquals("user-1", sent.user!!.id)

        val crumb = Breadcrumb("open http://panel.example/x").apply { setData("who", "kev@example.com") }
        val kept = options.beforeBreadcrumb!!.execute(crumb, io.sentry.Hint())!!
        assertFalse(kept.message!!.contains("panel.example"))
        assertFalse(kept.data.values.joinToString().contains("kev@example.com"))
    }

    @Test
    fun `reporter breadcrumbs carry code, status, channel and engine, never an address`() {
        val entry = ErrorEntry(
            firstAtMs = 1_000L, lastAtMs = 9_000L, repeatCount = 2,
            code = "http_404", httpStatus = 404, errorName = "InvalidResponseCodeException",
            accountStatus = "active", activeCons = 1, maxConnections = 2,
            engine = "exo", surface = "full", live = true,
            channel = "BBC One", category = "UK", providerHost = "panel.example", proxy = false,
            model = "AFTMM", android = "9", messageShort = "Response code: 404 from panel.example",
        )

        val crumb = KevBoxSentry.breadcrumbOf(entry)

        assertEquals("kevbox.error", crumb.category)
        assertEquals("http_404", crumb.message)
        assertEquals(9_000L, crumb.timestamp.time)
        assertEquals(
            mapOf("code" to "http_404", "http_status" to 404, "channel" to "BBC One", "engine" to "exo"),
            crumb.data.toMap(),
        )
        val everything = listOf(crumb.message, crumb.category) + crumb.data.values.map { it.toString() }
        everything.forEach { assertFalse(it, it!!.contains("panel.example")) }
    }

    private companion object {
        const val DSN = "https://public@sentry.example/1"
    }
}
