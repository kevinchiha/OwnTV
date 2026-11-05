// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.errors

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.kevbox.session.StoredSession

/**
 * The member's own secrets, held in memory for the scrubber: the playing source's login, the
 * KevBox email and the Supabase tokens. Sentry reads [current] on a crashing thread, where no disk
 * or database read may happen, so the set is filled ahead of time: from the stored session once
 * the start-up read ends ([KevBoxSentry.start]), then by the reporter whenever the member, the
 * session or the sources change. Never stored, never logged.
 */
object SecretWords {
    @Volatile private var words: Set<String> = emptySet()

    // Open until KevBoxSentry.start arms it; then shut until the start-up read ends.
    @Volatile private var startup: CountDownLatch? = null
    @Volatile private var gaveUp = false

    fun current(): Set<String> = words

    @Synchronized
    fun set(words: Set<String>) {
        this.words = words.filter { it.isNotBlank() }.toSet()
    }

    /** Adds to the set without dropping what the reporter put there. */
    @Synchronized
    fun add(more: Set<String>) {
        words = words + more.filter { it.isNotBlank() }
    }

    /** Called before Sentry starts; the returned function marks the start-up words as in. */
    fun expectStartup(): () -> Unit {
        val latch = CountDownLatch(1)
        gaveUp = false
        startup = latch
        return latch::countDown
    }

    /**
     * Waits up to [timeoutMs] for the start-up words, so a report sent in the first moments (last
     * run's freeze or native crash) still has the refresh token, which no pattern catches, to scrub.
     * Once a wait runs out, later calls stop waiting: a key store that never answers costs one wait.
     */
    fun awaitStartup(timeoutMs: Long) {
        val latch = startup ?: return
        if (gaveUp) return
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) gaveUp = true
    }
}

/**
 * The words to scrub for one member: the source's username and password, the email, and the
 * access and refresh tokens (a refresh token is a short opaque string no pattern catches).
 */
fun secretWordsOf(source: SourceEntity?, email: String?, session: StoredSession?): Set<String> =
    listOfNotNull(
        source?.username,
        source?.password,
        email,
        session?.email,
        session?.accessToken,
        session?.refreshToken,
    ).filter { it.isNotBlank() }.toSet()
