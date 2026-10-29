// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.session

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import tv.own.owntv.kevbox.supabase.SignInResult

/**
 * Signs in from the login file KevBox Setup leaves in the app's external files folder
 * (kevbox-setup's docs/login-file.md).
 *
 * The file is deleted before the sign-in starts, and so is an old result file. A stale, unreadable
 * or incomplete file is deleted and nothing else happens. Otherwise one result file is written: as
 * soon as the server answers, or with the timeout text after [deadlineMs]. Nothing here logs, and a
 * parse error is dropped without its message, since org.json quotes the input in it.
 *
 * [toJson] is there for tests: the JVM's org.json stops deep nesting with an exception, while the
 * device's recurses until the stack overflows.
 */
class LoginFileSignIn(
    private val dir: () -> File?,
    private val now: () -> Long,
    private val signedInEmail: () -> String?,
    private val signOut: suspend () -> Unit,
    private val signIn: suspend (email: String, password: String, onAnswer: (SignInResult) -> Unit) -> SignInResult,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val deadlineMs: Long = 20_000,
    private val toJson: (String) -> JSONObject = ::JSONObject,
) {
    // Held from the file read until the sign-in call returns, which may be after the result.
    private val busy = AtomicBoolean(false)

    /** From MainActivity.onResume and onNewIntent. Returns at once; does nothing while a file sign-in runs. */
    fun check() {
        if (!busy.compareAndSet(false, true)) return
        scope.launch(io) { guarded() }
    }

    /** [check]'s work, returning once the result is written or none is due. For tests. */
    suspend fun checkNow() {
        if (!busy.compareAndSet(false, true)) return
        withContext(io) { guarded() }
    }

    private suspend fun guarded() {
        var signing: Job? = null
        try {
            signing = signInFromFile()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A file or folder error: Setup reports its own timeout. The message is not kept.
        } finally {
            val job = signing
            if (job == null) {
                busy.set(false)
            } else {
                // A file Setup pushed while this sign-in ran (after a timeout) gets no check of its
                // own, so look once more. Only after a sign-in that ran to its end: a file that will
                // not delete starts no sign-in, so it cannot bring this back round.
                job.invokeOnCompletion { cause ->
                    busy.set(false)
                    if (cause == null && loginFileWaiting()) check()
                }
            }
        }
    }

    private fun loginFileWaiting(): Boolean = try {
        dir()?.let { File(it, LOGIN_FILE).exists() } == true
    } catch (_: Exception) {
        false
    }

    /** Returns the sign-in job when one started, so the busy flag can wait for it. */
    private suspend fun signInFromFile(): Job? {
        val folder = dir() ?: return null
        val file = File(folder, LOGIN_FILE)
        if (!file.exists()) return null
        val login = read(file)
        if (login == null || now() - login.requestedAt > MAX_AGE_MS) {
            file.delete()
            return null
        }
        if (!file.delete() || file.exists()) {
            writeResult(folder, FAILED)
            return null
        }
        // Setup deletes it too before pushing; a result left from an earlier file must not pass for this one's.
        File(folder, RESULT_FILE).delete()

        val answer = CompletableDeferred<SignInResult>()
        val job = scope.launch(io) {
            try {
                val current = signedInEmail()
                if (current != null && normal(current) != normal(login.email)) signOut()
                val result = signIn(login.email, login.password) { answer.complete(it) }
                answer.complete(result)
            } catch (e: CancellationException) {
                answer.complete(SignInResult.Failed)
                throw e
            } catch (_: Exception) {
                answer.complete(SignInResult.Failed)
            }
        }
        val result = withTimeoutOrNull(deadlineMs) { answer.await() }
        writeResult(folder, result?.let(::jsonOf) ?: TOO_SLOW)
        return job
    }

    private class Login(val email: String, val password: String, val requestedAt: Long)

    /** The file's three keys, or null for anything else. Never throws, never keeps a message. */
    private fun read(file: File): Login? = try {
        if (file.length() > MAX_BYTES) {
            null
        } else {
            val bytes = file.readBytes()
            if (bytes.size > MAX_BYTES) null else parse(String(bytes, Charsets.UTF_8))
        }
    } catch (_: Exception) {
        null
    } catch (_: StackOverflowError) {
        // Deep nesting such as 60 KB of "[": the device's org.json recurses once per level.
        null
    }

    private fun parse(text: String): Login? {
        val json = toJson(text)
        val email = (json.opt(EMAIL) as? String)?.takeIf { it.isNotBlank() } ?: return null
        val password = json.opt(PASSWORD) as? String ?: return null
        val requestedAt = (json.opt(REQUESTED_AT) as? Number)?.toLong() ?: return null
        return Login(email, password, requestedAt)
    }

    /** Written to a temporary file in the same folder, then renamed, so Setup never reads half of it. */
    private fun writeResult(folder: File, json: String) {
        val temp = File(folder, RESULT_FILE + TEMP_SUFFIX)
        try {
            temp.writeText(json)
            if (!temp.renameTo(File(folder, RESULT_FILE))) temp.delete()
        } catch (_: Exception) {
            temp.delete()
        }
    }

    private fun normal(email: String) = email.trim().lowercase()

    companion object {
        const val LOGIN_FILE = "kevbox-login.json"
        const val RESULT_FILE = "kevbox-login.result.json"
        private const val TEMP_SUFFIX = ".tmp"
        private const val MAX_AGE_MS = 600_000L
        private const val MAX_BYTES = 64 * 1024

        private const val EMAIL = "email"
        private const val PASSWORD = "password"
        private const val REQUESTED_AT = "requestedAt"

        // The sign-in screen's English strings (kevbox_strings.xml). Setup and Kevin's report are English.
        private const val WRONG_CREDENTIALS = "Wrong email or password"
        private const val NO_NETWORK = "No internet connection"
        private const val SIGN_IN_FAILED = "Sign-in failed. Try again."
        private const val TIMEOUT = "Sign-in took too long. Check the box's internet."

        private const val OK = """{"ok":true}"""
        private val FAILED = failure(SIGN_IN_FAILED)
        private val TOO_SLOW = failure(TIMEOUT)

        private fun failure(error: String) = """{"ok":false,"error":${JSONObject.quote(error)}}"""

        /** The result file's text for [result]. */
        fun jsonOf(result: SignInResult): String = when (result) {
            is SignInResult.Success -> OK
            SignInResult.WrongCredentials -> failure(WRONG_CREDENTIALS)
            SignInResult.NoNetwork -> failure(NO_NETWORK)
            SignInResult.Failed -> FAILED
        }
    }
}
