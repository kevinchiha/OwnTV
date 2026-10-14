// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.errors

import android.content.Context
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.kevbox.session.KeystoreTokenCipher
import tv.own.owntv.kevbox.session.PrefsSessionStore
import tv.own.owntv.kevbox.session.StoredSession

/**
 * The member's own secrets, held in memory for the scrubber: the playing source's login, the
 * KevBox email and the Supabase tokens. Sentry reads [current] on a crashing thread, where no disk
 * or database read may happen, so the set is filled ahead of time: [startupSecretWords] at Sentry's
 * start, then the reporter whenever the member, the session or the sources change. Never stored,
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

/**
 * The words to scrub before anything else has started: the stored session's email and tokens, read
 * from its prefs file. The source logins live in the database, which start-up may not read on the
 * main thread; the reporter adds them once it runs. Empty when nothing is stored or it won't open.
 */
fun startupSecretWords(context: Context): Set<String> {
    val prefs = context.getSharedPreferences(PrefsSessionStore.PREFS_NAME, Context.MODE_PRIVATE) ?: return emptySet()
    val session = PrefsSessionStore(prefs, KeystoreTokenCipher()).load()
    return secretWordsOf(source = null, email = null, session = session)
}
