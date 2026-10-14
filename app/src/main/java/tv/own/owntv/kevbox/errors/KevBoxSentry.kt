// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.errors

import android.content.Context
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
import io.sentry.protocol.User
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tv.own.owntv.kevbox.session.KevBoxAuth

/**
 * Crashes, freezes (ANRs) and native crashes go to the KevBox Live Sentry project, and nothing
 * else: errors the app catches go to KevBox through [ErrorReporter], never to Sentry. A build with
 * no key never starts Sentry, so it never contacts a Sentry host.
 *
 * Every event and breadcrumb passes through [Scrubber] with the member's own secrets
 * ([SecretWords]) before it leaves the TV, and the Sentry user carries the KevBox user id only.
 */
object KevBoxSentry {
    private const val ENVIRONMENT = "production"
    private const val BREADCRUMB_CATEGORY = "kevbox.error"

    /** The reporter's last entries; with every automatic source off, these are all Sentry keeps. */
    private const val MAX_BREADCRUMBS = 20

    /**
     * Starts Sentry with [dsn]. Returns false, without calling [init], when [dsn] is blank (a
     * keyless build), and false when [init] throws, so start-up carries on either way.
     */
    fun start(
        context: Context,
        dsn: String,
        init: (Context, Sentry.OptionsConfiguration<SentryAndroidOptions>) -> Unit = SentryAndroid::init,
    ): Boolean {
        if (dsn.isBlank()) return false
        return try {
            init(context, Sentry.OptionsConfiguration { options -> configure(options, dsn) })
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun configure(options: SentryAndroidOptions, dsn: String) {
        options.dsn = dsn
        options.environment = ENVIRONMENT
        // Release stays the SDK default: tv.kevbox.live@<versionName>+<versionCode>.

        // No tracing, profiling, sessions or replay (AC-7).
        options.tracesSampleRate = 0.0
        options.profilesSampleRate = 0.0
        options.profileSessionSampleRate = 0.0
        options.isEnableAppStartProfiling = false
        options.isEnableAutoActivityLifecycleTracing = false
        options.isEnableUserInteractionTracing = false
        options.isEnableFramesTracking = false
        options.isEnableAutoSessionTracking = false
        options.sessionReplay.sessionSampleRate = 0.0
        options.sessionReplay.onErrorSampleRate = 0.0

        // Nothing of the screen and nothing personal (AC-7).
        options.isAttachScreenshot = false
        options.isAttachViewHierarchy = false
        options.isSendDefaultPii = false

        // Freezes (AC-3) and native crashes (AC-2).
        options.isAnrEnabled = true
        options.isEnableNdk = true

        // Every automatic breadcrumb source off, so the breadcrumbs kept are the reporter's (AC-5).
        options.isEnableActivityLifecycleBreadcrumbs = false
        options.isEnableAppLifecycleBreadcrumbs = false
        options.isEnableSystemEventBreadcrumbs = false
        options.isEnableAppComponentBreadcrumbs = false
        options.isEnableNetworkEventBreadcrumbs = false
        options.isEnableUserInteractionBreadcrumbs = false
        options.maxBreadcrumbs = MAX_BREADCRUMBS

        // Scrub at send time, with the secrets the reporter keeps in memory (no disk read on a crash).
        options.beforeSend = SentryOptions.BeforeSendCallback { event, _ -> scrub(event, SecretWords.current()) }
        options.beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { breadcrumb, _ ->
            scrubBreadcrumb(breadcrumb, SecretWords.current())
        }
    }

    /**
     * Runs [Scrubber.text] (no length cap) over every text an event carries: the message, each
     * exception value, each breadcrumb, the tags and the extras. Drops the user's email, username
     * and IP address and keeps the id (AC-6).
     */
    fun scrub(event: SentryEvent, secrets: Collection<String>): SentryEvent {
        event.message?.let { message ->
            message.formatted = clean(message.formatted, secrets)
            message.message = clean(message.message, secrets)
            message.params = message.params?.map { clean(it, secrets).orEmpty() }
        }
        event.exceptions?.forEach { it.value = clean(it.value, secrets) }
        event.breadcrumbs?.forEach { scrubBreadcrumb(it, secrets) }
        event.tags?.toMap()?.forEach { (key, value) -> event.setTag(key, clean(value, secrets).orEmpty()) }
        event.extras?.toMap()?.forEach { (key, value) -> cleanValue(value, secrets)?.let { event.setExtra(key, it) } }
        // A request block would hold an address; crashes never need one.
        event.request = null
        event.user?.let { user ->
            user.email = null
            user.username = null
            user.ipAddress = null
        }
        return event
    }

    /**
     * The reporter's entry as a breadcrumb: the code, HTTP status, channel and engine. Never the
     * provider host or any address (AC-5).
     */
    fun breadcrumbOf(entry: ErrorEntry): Breadcrumb = Breadcrumb(Date(entry.lastAtMs)).apply {
        category = BREADCRUMB_CATEGORY
        level = SentryLevel.ERROR
        message = entry.code
        setData(ErrorEntry.CODE, entry.code)
        entry.httpStatus?.let { setData(ErrorEntry.HTTP_STATUS, it) }
        entry.channel?.let { setData(ErrorEntry.CHANNEL, it) }
        entry.engine?.let { setData(ErrorEntry.ENGINE, it) }
    }

    /**
     * Keeps the Sentry user on the signed-in member's id (never the email, AC-1), and turns each
     * reporter entry into a breadcrumb. Does nothing when Sentry did not start.
     */
    fun bind(auth: KevBoxAuth, reporter: ErrorReporter, scope: CoroutineScope) {
        if (!Sentry.isEnabled()) return
        scope.launch {
            auth.userId.collect { id -> Sentry.setUser(id?.let { User().apply { this.id = it } }) }
        }
        reporter.addListener { entry -> Sentry.addBreadcrumb(breadcrumbOf(entry)) }
    }

    private fun scrubBreadcrumb(breadcrumb: Breadcrumb, secrets: Collection<String>): Breadcrumb {
        breadcrumb.message = clean(breadcrumb.message, secrets)
        breadcrumb.data.toMap().forEach { (key, value) -> cleanValue(value, secrets)?.let { breadcrumb.setData(key, it) } }
        return breadcrumb
    }

    private fun clean(text: String?, secrets: Collection<String>): String? = Scrubber.text(text, secrets)

    /** Numbers and booleans carry no text; anything else is scrubbed as the text it prints as. */
    private fun cleanValue(value: Any?, secrets: Collection<String>): Any? = when (value) {
        null, is Number, is Boolean -> value
        else -> clean(value.toString(), secrets)
    }
}
