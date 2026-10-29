// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.release

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.own.owntv.BuildConfig
import tv.own.owntv.core.CoreBuildInfo

/**
 * Installs a newer release with no prompt, on Android 12 and later, while nobody watches.
 *
 * Each [run] reads the latest release, applies [SilentUpdateRules] (newer, rollout, idle), downloads
 * the APK and its profile into `filesDir/silent-update/`, checks the APK, checks idle again, and
 * commits one [PackageInstaller] session that asks for no user action. [SilentUpdateReceiver] gets the
 * result. Core's own updater is left alone: its session cannot skip the prompt.
 *
 * A blank [feedUrl] means GitHub's latest release of [CoreBuildInfo.releaseRepo].
 */
class SilentUpdater(
    private val context: Context,
    client: OkHttpClient,
    private val prefs: SilentUpdatePrefs,
    private val signedInEmail: () -> String?,
    private val activityStarted: () -> Boolean,
    private val playing: () -> Boolean,
    private val lastPressMs: () -> Long,
    private val now: () -> Long,
    private val feedUrl: String,
) {
    /** No idle connections kept, for the same reason as UpdateProfileAttacher's client. */
    private val http = client.newBuilder()
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .build()

    private val running = Mutex()

    /** One check and, when everything allows it, one install. Returns the outcome word it logged. */
    suspend fun run(): String {
        if (!running.tryLock()) return logged("busy", null)
        try {
            var version: String? = null
            val word = withContext(Dispatchers.IO) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        attempt { version = it }
                    } else {
                        "old-android"
                    }
                }.getOrElse { "error-${it.javaClass.simpleName}" }
            }
            return logged(word, version)
        } finally {
            running.unlock()
        }
    }

    private fun logged(word: String, version: String?): String {
        Log.i(TAG, if (version == null) "silent update: $word" else "silent update: $word $version")
        return word
    }

    private fun idle() = SilentUpdateRules.idle(activityStarted(), playing(), lastPressMs(), now())

    @RequiresApi(Build.VERSION_CODES.S)
    private fun attempt(found: (String) -> Unit): String {
        val wantX86 = Build.SUPPORTED_ABIS.firstOrNull() == "x86_64"
        val request = Request.Builder()
            .url(feedUrl.ifBlank { "https://api.github.com/repos/${CoreBuildInfo.releaseRepo}/releases/latest" })
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        val (body, serverNow) = try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return "check-http-${resp.code}"
                resp.body.string() to SilentUpdateRules.serverNow(resp.header("Date"), now())
            }
        } catch (e: java.io.IOException) {
            return "check-failed"
        }
        val release = SilentUpdateRules.parse(body, wantX86, Build.VERSION.SDK_INT) ?: return "no-release"
        found(release.version)
        if (!SilentUpdateRules.isNewer(release.version, BuildConfig.VERSION_NAME)) return "up-to-date"
        if (prefs.skips(release.version)) return "skipped"
        if (!SilentUpdateRules.rolloutDue(release, signedInEmail(), serverNow)) return "waiting"
        if (!idle()) return "in-use"

        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val apk = File(dir, APK_FILE)
        val dm = File(dir, DM_FILE)
        try {
            apk.delete()
            dm.delete()
            val refusal = runCatching { downloadApk(release.apkUrl, apk, dir) }.getOrElse { "download-failed" }
            if (refusal != null) {
                // A bad release file counts against the version; network and space trouble do not.
                if (refusal in RELEASE_FAULTS) prefs.failed(release.version)
                return refusal
            }
            val profile = release.dmUrl?.let { url ->
                runCatching { downloadProfile(url, dm) }.getOrDefault(false)
            } == true
            if (!idle()) return "in-use"
            return install(apk, dm.takeIf { profile }, release.version)
        } finally {
            apk.delete()
            dm.delete()
        }
    }

    /** Null when [out] holds a checked APK, else the word for why not. */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun downloadApk(url: String, out: File, dir: File): String? {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return "download-failed"
            val total = resp.body.contentLength()
            if (total <= 0) return "apk-no-length"
            // Written once here and copied once more into the session: room for two.
            if (dir.usableSpace < total * 2) return "no-space"
            resp.body.byteStream().use { input -> out.outputStream().use { input.copyTo(it, 64 * 1024) } }
            if (out.length() != total) return "apk-truncated"
        }
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(out.absolutePath, 0) ?: return "apk-unreadable"
        if (info.packageName != context.packageName) return "apk-wrong-package"
        if (info.longVersionCode <= BuildConfig.VERSION_CODE.toLong()) return "apk-not-newer"
        return null
    }

    /** True when [out] holds a profile Android can read: a small zip with `primary.prof` inside. */
    private fun downloadProfile(url: String, out: File): Boolean {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return false
            val bytes = resp.body.bytes()
            if (bytes.size !in 1..MAX_PROFILE_BYTES) return false
            out.writeBytes(bytes)
        }
        return ZipFile(out).use { it.getEntry(PROFILE_ENTRY) != null }
    }

    /** Commits one session; [SilentUpdateReceiver] gets the result. A session that fails here is abandoned. */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun install(apk: File, dm: File?, version: String): String {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setAppLabel(SILENT_SESSION_LABEL)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        var sessionId = -1
        return try {
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                write(session, SESSION_APK_ENTRY, apk)
                if (dm != null) write(session, SESSION_DM_ENTRY, dm)
                val intent = Intent(context, SilentUpdateReceiver::class.java).putExtra(EXTRA_VERSION, version)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
            }
            "committed"
        } catch (e: Exception) {
            if (sessionId != -1) runCatching { installer.abandonSession(sessionId) }
            prefs.failed(version)
            "install-failed"
        }
    }

    private fun write(session: PackageInstaller.Session, name: String, file: File) {
        session.openWrite(name, 0, file.length()).use { out ->
            file.inputStream().use { it.copyTo(out) }
            session.fsync(out)
        }
    }

    companion object {
        const val SILENT_SESSION_LABEL = "KevBox silent update"
        const val EXTRA_VERSION = "version"
        private const val TAG = "KevBoxSilentUpdate"
        private const val USER_AGENT = "KevBoxLive"
        private const val DIR = "silent-update"
        private const val APK_FILE = "update.apk"
        private const val DM_FILE = "update.dm"
        private const val SESSION_APK_ENTRY = "kevbox-update"
        private const val SESSION_DM_ENTRY = "kevbox-update.dm"
        private const val PROFILE_ENTRY = "primary.prof"
        private const val MAX_PROFILE_BYTES = 4 * 1024 * 1024
        private val RELEASE_FAULTS = setOf("apk-no-length", "apk-unreadable", "apk-wrong-package", "apk-not-newer")
    }
}
