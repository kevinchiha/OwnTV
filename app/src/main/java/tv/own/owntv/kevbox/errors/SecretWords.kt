// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.errors

import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.kevbox.session.StoredSession

/**
 * The member's own secrets, held in memory for the scrubber: the playing source's login, the
 * KevBox email and the Supabase tokens. Sentry reads [current] on a crashing thread, where no disk
 * or database read may happen, so the reporter keeps this set filled ahead of time. Never stored,
 * never logged.
 */
object SecretWords {
    @Volatile private var words: Set<String> = emptySet()

    fun current(): Set<String> = words

    fun set(words: Set<String>) {
        this.words = words.filter { it.isNotBlank() }.toSet()
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
