// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.session

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * The stored session, read once per process on a thread of its own.
 *
 * Opening the session asks Android's key store, a system service. On some TVs (Xiaomi MiTV-AFMU0,
 * Sentry KEVBOX-LIVE-5, -6, -7) it can take more than five seconds to answer, and a main thread
 * waiting on it at start-up is a freeze Android kills. So the read starts here, the main thread
 * goes on, and the two start-up readers, the Sentry scrubber and [KevBoxAuth], share its answer.
 *
 * A read that throws counts as no session, the same as a blob that no longer opens.
 */
class SessionPreload(
    load: () -> StoredSession?,
    /** Where the read runs: its own thread in the app; tests pass one they control. */
    run: (Runnable) -> Unit = ::onOwnThread,
) {
    private val result = CompletableDeferred<StoredSession?>()

    init {
        run {
            try {
                result.complete(load())
            } catch (_: Exception) {
                result.complete(null)
            } finally {
                // An Error still ends the wait; it then goes on as before.
                result.complete(null)
            }
        }
    }

    val isDone: Boolean get() = result.isCompleted

    suspend fun await(): StoredSession? = result.await()

    /**
     * Runs [block] with the session once the read ends: at once when it already has, else on the
     * reading thread. A [block] that throws is dropped, so one reader can't stop the next.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun whenDone(block: (StoredSession?) -> Unit) {
        result.invokeOnCompletion {
            try {
                block(result.getCompleted())
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val THREAD_NAME = "kevbox-session-read"

        @Volatile private var app: SessionPreload? = null

        /** Reads [store] now, on the caller: tests, and code that already runs off the main thread. */
        fun now(store: SessionStore): SessionPreload = SessionPreload(store::load) { it.run() }

        /** This process's read of the stored session; the first call starts it. */
        fun forApp(context: Context): SessionPreload = app ?: synchronized(this) {
            app ?: SessionPreload({
                val prefs = context.getSharedPreferences(PrefsSessionStore.PREFS_NAME, Context.MODE_PRIVATE)
                PrefsSessionStore(prefs, KeystoreTokenCipher()).load()
            }).also { app = it }
        }

        private fun onOwnThread(read: Runnable) {
            Thread(read, THREAD_NAME).apply { isDaemon = true }.start()
        }
    }
}
