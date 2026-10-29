// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.release

import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.own.owntv.core.update.UpdateManager

/**
 * Makes an in-app update start compiled instead of slow.
 *
 * Android compiles an app ahead of time at install only when the install carries the app's profile
 * (a `.dm` file next to the APK). Core's updater installs the APK alone, so every update used to start
 * in the slowest mode (`status=verify`) until the TV's overnight maintenance compiled it; measured on
 * Kevin's TCL, that was the difference between a smooth guide and a laggy one.
 *
 * Each release now carries the profile beside the APK ([dexMetadataUrl]). When Core offers an update,
 * this fetches that profile. When Core then opens its install session, this writes the profile into the
 * same session under the name Android pairs with Core's APK entry, before Core finishes copying the
 * 60 MB APK and commits. Tested on the TCL with `pm install-write`: `status=speed-profile
 * [reason=install-dm]`.
 *
 * Anything missing (no profile on the release, a download that failed, a session already committed)
 * leaves Core's install exactly as it was.
 */
class UpdateProfileAttacher(
    private val context: Context,
    private val client: OkHttpClient,
    private val updates: UpdateManager,
    private val scope: CoroutineScope,
) {
    /**
     * Its own connection pool that keeps nothing. On the shared pool, the profile download left an idle
     * connection to github.com that GitHub had closed by the time the member pressed Update now; Core's
     * client never retries (retryOnConnectionFailure off), so the APK download failed on it once with
     * "unexpected end of stream" (seen on the emulator, 2026-10-14).
     */
    private val http = client.newBuilder()
        .connectionPool(okhttp3.ConnectionPool(0, 1, java.util.concurrent.TimeUnit.SECONDS))
        .build()

    @Volatile private var ready: File? = null
    @Volatile private var fetchedFor: String? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        val url = { info: UpdateManager.UpdateInfo -> dexMetadataUrl(info.apkUrl, Build.VERSION.SDK_INT) }
        scope.launch {
            updates.state.collect { state ->
                val info = (state as? UpdateManager.State.Available)?.info ?: return@collect
                val dmUrl = url(info) ?: return@collect
                if (fetchedFor != dmUrl) {
                    ready = null
                    fetchedFor = dmUrl
                    ready = runCatching { withContext(Dispatchers.IO) { fetch(dmUrl) } }
                        .onFailure { Log.i(TAG, "no profile for this update: ${it.message}") }
                        .getOrNull()
                }
            }
        }
        val thread = HandlerThread(TAG).apply { start() }
        context.packageManager.packageInstaller.registerSessionCallback(Callback(), Handler(thread.looper))
    }

    private fun fetch(url: String): File {
        val out = File(File(context.cacheDir, DIR).apply { mkdirs() }, FILE)
        out.delete()
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            val bytes = resp.body.bytes()
            check(bytes.size in 1..MAX_BYTES) { "size ${bytes.size}" }
            out.writeBytes(bytes)
        }
        // A broken profile would make the installer refuse the whole update: only a readable one goes in.
        ZipFile(out).use { zip -> check(zip.getEntry(PROFILE_ENTRY) != null) { "no profile inside" } }
        return out
    }

    private inner class Callback : PackageInstaller.SessionCallback() {
        override fun onCreated(sessionId: Int) {
            val dm = ready ?: return
            val installer = context.packageManager.packageInstaller
            val info = installer.getSessionInfo(sessionId) ?: return
            // Only Core's update session. SilentUpdater's session carries its own profile.
            if (info.installerPackageName != context.packageName) return
            if (info.appLabel?.toString() == SilentUpdater.SILENT_SESSION_LABEL) return
            runCatching {
                installer.openSession(sessionId).use { session ->
                    session.openWrite(SESSION_PROFILE_ENTRY, 0, dm.length()).use { out ->
                        dm.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                }
            }.onSuccess { Log.i(TAG, "profile added to update session $sessionId") }
                .onFailure { Log.w(TAG, "profile not added: ${it.message}") }
        }

        override fun onBadgingChanged(sessionId: Int) = Unit
        override fun onActiveChanged(sessionId: Int, active: Boolean) = Unit
        override fun onProgressChanged(sessionId: Int, progress: Float) = Unit
        override fun onFinished(sessionId: Int, success: Boolean) = Unit
    }

    companion object {
        private const val TAG = "KevBoxUpdateProfile"
        private const val DIR = "update-profile"
        private const val FILE = "update.dm"
        private const val PROFILE_ENTRY = "primary.prof"
        private const val MAX_BYTES = 4 * 1024 * 1024

        /**
         * Core writes its APK into the session as `owntv-update` (UpdateManager.SESSION_ENTRY). Android
         * pairs a `.dm` with the APK of the same name plus `.dm`.
         */
        private const val SESSION_PROFILE_ENTRY = "owntv-update.dm"

        /**
         * The profile file published next to [apkUrl]: `KevBoxLive.dm` for Android 12 and later,
         * `KevBoxLive-api28.dm` for Android 9 to 11 (the profile format differs), none before 9.
         */
        fun dexMetadataUrl(apkUrl: String, sdk: Int): String? {
            if (sdk < 28 || !apkUrl.endsWith(".apk")) return null
            val base = apkUrl.removeSuffix(".apk")
            return if (sdk >= 31) "$base.dm" else "$base-api28.dm"
        }
    }
}
