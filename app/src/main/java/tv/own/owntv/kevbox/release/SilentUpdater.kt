// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.release

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 * Each [run] reads the latest release and its [SilentBlock] (the `kevbox-silent.json` asset; a release
 * without one is never installed here, see [SilentBlockFetch]), applies [SilentUpdateRules] (newer,
 * install permission, rollout, idle), downloads
 * the APK and its profile into `filesDir/silent-update/`, checks the APK, and commits one
 * [PackageInstaller] session that asks for no user action, after a last idle check. [SilentUpdateReceiver]
 * gets the result. Core's own updater is left alone: its session cannot skip the prompt.
 *
 * The APK download survives across runs: `<version>.apk.part` grows by HTTP range requests until it
 * is whole, then becomes `<version>.apk`, which stays until the session is committed, the version is
 * given up, or another version appears. A cancelled run (WorkManager's 10-minute limit) stops between
 * two buffers and never installs.
 *
 * Idle compares [lastPressElapsedMs] and the last MainActivity stop ([SilentUpdatePrefs.lastStopElapsedMs])
 * with [elapsedNow], all monotonic. [now] is the wall clock, used
 * only when the server sends no `Date`. A blank [feedUrl] means GitHub's latest release of
 * [CoreBuildInfo.releaseRepo].
 */
class SilentUpdater(
    private val context: Context,
    client: OkHttpClient,
    private val prefs: SilentUpdatePrefs,
    /** The signed-in Supabase user id, matched against the block's early users. Never logged. */
    private val signedInUserId: () -> String?,
    private val activityStarted: () -> Boolean,
    private val playing: () -> Boolean,
    private val lastPressElapsedMs: () -> Long,
    private val elapsedNow: () -> Long,
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
        var version: String? = null
        try {
            val word = withContext(Dispatchers.IO) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        attempt { version = it }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "error-${e.javaClass.simpleName}"
                    }
                } else {
                    "old-android"
                }
            }
            return logged(word, version)
        } catch (e: CancellationException) {
            logged("cancelled", version)
            throw e
        } finally {
            running.unlock()
        }
    }

    private fun logged(word: String, version: String?): String {
        Log.i(TAG, if (version == null) "silent update: $word" else "silent update: $word $version")
        return word
    }

    private fun idle() =
        SilentUpdateRules.idle(activityStarted(), playing(), lastPressElapsedMs(), prefs.lastStopElapsedMs, elapsedNow())

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun attempt(found: (String) -> Unit): String {
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
        } catch (e: IOException) {
            return "check-failed"
        }
        val release = SilentUpdateRules.parse(body, wantX86, Build.VERSION.SDK_INT) ?: return "no-release"
        found(release.version)
        val files = VersionFiles(File(context.filesDir, DIR), release.version)
        if (!SilentUpdateRules.isNewer(release.version, BuildConfig.VERSION_NAME)) {
            files.clearAll()
            return "up-to-date"
        }
        if (prefs.skips(release.version)) {
            files.clearAll()
            return "skipped"
        }
        // A missing asset and a file that does not parse mean "no block": Core's card takes over. A
        // failed fetch says nothing about the release, so the last check's record stays.
        val block = when (val url = release.silentUrl) {
            null -> null
            else -> when (val fetched = SilentBlockFetch.fetch(http, url, USER_AGENT)) {
                SilentBlockFetch.Failed -> return "silent-fetch-failed"
                is SilentBlockFetch.Read -> fetched.block
            }
        }
        // `after` stored on the box's own clock (moved by the box's offset from the server), so the
        // 72-hour net, which reads the box clock, works on a box whose date is wrong.
        prefs.seen(release.version, hasSilent = block != null, afterBoxMs = block?.let { it.afterMs + (now() - serverNow) })
        if (block == null) return "no-silent"
        // Without "install unknown apps" Android shows a prompt whatever the session asks; Core's
        // card and its own install path ask the person to allow it. Checked before the rollout.
        val canInstall = context.packageManager.canRequestPackageInstalls()
        when (SilentUpdateRules.hold(block, canInstall, signedInUserId(), serverNow)) {
            SilentUpdateRules.Hold.NO_INSTALL_PERMISSION -> {
                prefs.giveUp(release.version)
                files.clearAll()
                return "no-install-permission"
            }
            SilentUpdateRules.Hold.NOT_YET -> return "waiting"
            null -> Unit
        }
        if (!idle()) return "in-use"

        files.dir.mkdirs()
        files.clearOthers()
        val refusal = try {
            downloadApk(release.apkUrl, files) ?: verify(files.whole)
        } catch (e: IOException) {
            "download-failed"
        }
        if (refusal != null) {
            when (refusal) {
                // A bad release file counts against the version; network and space trouble do not.
                in RELEASE_FAULTS -> {
                    prefs.failed(release.version, withProfile = false)
                    files.clearAll()
                }
                // Android would refuse it as an update; Core's install fails the same way but says so.
                APK_OTHER_KEY -> {
                    prefs.giveUp(release.version)
                    files.clearAll()
                }
            }
            return refusal
        }
        // The session takes its own copy of the APK.
        if (files.dir.usableSpace < files.whole.length()) return "no-space"
        val dm = release.dmUrl?.takeIf { !prefs.skipsProfile(release.version) }?.let { url ->
            val ok = try {
                downloadProfile(url, files.dm)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            files.dm.takeIf { ok }
        }
        if (dm == null) files.dm.delete()
        if (!idle()) return "in-use"
        return install(files, dm, release.version)
    }

    /**
     * Null when [VersionFiles.whole] holds the whole APK (its length checked when the server gave one),
     * else the word for why not. A partial file is resumed with a range request.
     */
    private suspend fun downloadApk(url: String, files: VersionFiles): String? {
        if (files.whole.exists()) return null
        when (SilentUpdateDownload.startAt(files.part.length(), files.expectedTotal())) {
            null -> return files.finish()
            0L -> files.dropPartial()
            else -> Unit
        }
        val first = fetch(url, files)
        if (first != RETRY) return first
        // 416, or a range or size that does not match what is on disk: start over, once.
        files.dropPartial()
        val second = fetch(url, files)
        return if (second == RETRY) "download-failed" else second
    }

    /** One request from the end of the partial file. Null when the file is whole, [RETRY] to start over. */
    private suspend fun fetch(url: String, files: VersionFiles): String? {
        val have = files.part.length()
        val expected = files.expectedTotal()
        val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        if (have > 0) builder.header("Range", "bytes=$have-")
        val total = http.newCall(builder.build()).execute().use { resp ->
            val plan = SilentUpdateDownload.plan(
                have = have,
                expectedTotal = expected,
                code = resp.code,
                contentRange = resp.header("Content-Range"),
                contentLength = resp.body.contentLength(),
            )
            val (append, total) = when (plan) {
                is SilentUpdateDownload.Plan.Append -> true to plan.total
                is SilentUpdateDownload.Plan.Restart -> false to plan.total
                SilentUpdateDownload.Plan.Retry -> return RETRY
                SilentUpdateDownload.Plan.Fail -> return "download-failed"
            }
            val start = if (append) have else 0L
            if (total != null) {
                // Written here and copied once more into the session: room for both.
                if (files.dir.usableSpace < (total - start) + total) return "no-space"
                files.size.writeText(total.toString())
            } else {
                files.size.delete()
            }
            FileOutputStream(files.part, append).use { out ->
                resp.body.byteStream().use { SilentUpdateDownload.copyCancellable(it, out) }
            }
            total
        }
        val length = files.part.length()
        return when {
            total == null || length == total -> files.finish()
            // Cut short: the next run asks for the rest.
            length < total -> "download-incomplete"
            else -> {
                files.dropPartial()
                "download-size-mismatch"
            }
        }
    }

    /** Null when [apk] is this app, newer than the installed one and signed with its key; else why not. */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun verify(apk: File): String? {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return "apk-unreadable"
        if (info.packageName != context.packageName) return "apk-wrong-package"
        if (info.longVersionCode <= BuildConfig.VERSION_CODE.toLong()) return "apk-not-newer"
        @Suppress("DEPRECATION")
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val alike = SilentUpdateRules.signedAlike(
            apkCerts = certificates(info.signingInfo, withHistory = true),
            installedCerts = certificates(installed.signingInfo, withHistory = false),
        )
        return if (alike) null else APK_OTHER_KEY
    }

    /** The current signers, plus for a single signer the older keys it rotated from when [withHistory]. */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun certificates(signing: SigningInfo?, withHistory: Boolean): Set<String> {
        if (signing == null) return emptySet()
        val current = signing.apkContentsSigners.orEmpty().map { it.toCharsString() }
        val history = if (withHistory && !signing.hasMultipleSigners()) {
            signing.signingCertificateHistory.orEmpty().map { it.toCharsString() }
        } else {
            emptyList()
        }
        return (current + history).toSet()
    }

    /**
     * True when [out] holds a profile Android can read: a small zip with `primary.prof` inside.
     * The size is checked from the header first and the read stops past [MAX_PROFILE_BYTES].
     */
    private fun downloadProfile(url: String, out: File): Boolean {
        out.delete()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return false
            if (resp.body.contentLength() > MAX_PROFILE_BYTES) return false
            val bytes = resp.body.byteStream().use { SilentUpdateDownload.readAtMost(it, MAX_PROFILE_BYTES) }
            if (bytes == null || bytes.isEmpty()) return false
            out.writeBytes(bytes)
        }
        return ZipFile(out).use { it.getEntry(PROFILE_ENTRY) != null }
    }

    /**
     * Commits one session; [SilentUpdateReceiver] gets the result. Idle is checked once more after the
     * copy, right before the commit: someone back on the remote keeps the app, and the downloaded file
     * stays for next time. A session that does not reach the commit is abandoned; a cancel installs nothing.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun install(files: VersionFiles, dm: File?, version: String): String {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setAppLabel(SILENT_SESSION_LABEL)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        var sessionId = -1
        try {
            currentCoroutineContext().ensureActive()
            sessionId = installer.createSession(params)
            val committed = installer.openSession(sessionId).use { session ->
                write(session, SESSION_APK_ENTRY, files.whole)
                if (dm != null) write(session, SESSION_DM_ENTRY, dm)
                if (!idle()) return@use false
                currentCoroutineContext().ensureActive()
                val intent = Intent(context, SilentUpdateReceiver::class.java)
                    .putExtra(EXTRA_VERSION, version)
                    .putExtra(EXTRA_WITH_PROFILE, dm != null)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
                true
            }
            if (!committed) {
                abandon(installer, sessionId)
                return "in-use"
            }
        } catch (e: CancellationException) {
            if (sessionId != -1) abandon(installer, sessionId)
            throw e
        } catch (e: Exception) {
            if (sessionId != -1) abandon(installer, sessionId)
            prefs.failed(version, withProfile = dm != null)
            return "install-failed"
        }
        // Android holds its own copy now.
        files.clearAll()
        return "committed"
    }

    private fun abandon(installer: PackageInstaller, sessionId: Int) {
        runCatching { installer.abandonSession(sessionId) }
    }

    private suspend fun write(session: PackageInstaller.Session, name: String, file: File) {
        session.openWrite(name, 0, file.length()).use { out ->
            file.inputStream().use { SilentUpdateDownload.copyCancellable(it, out) }
            session.fsync(out)
        }
    }

    /** One version's files in `filesDir/silent-update/`. */
    private class VersionFiles(val dir: File, version: String) {
        private val stem = version.replace(UNSAFE_NAME, "_")
        val part = File(dir, "$stem.apk.part")
        val size = File(dir, "$stem.apk.size")
        val whole = File(dir, "$stem.apk")
        val dm = File(dir, "$stem.dm")
        private val names = setOf(part.name, size.name, whole.name, dm.name)

        /** The full APK size an earlier response gave, or null. */
        fun expectedTotal(): Long? = size.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()

        /** The partial file is whole: it becomes [whole]. Null on success, like a download. */
        fun finish(): String? {
            size.delete()
            return if (part.renameTo(whole)) null else "download-failed"
        }

        fun dropPartial() {
            part.delete()
            size.delete()
        }

        /** Deletes another version's files (and the old fixed names). */
        fun clearOthers() {
            dir.listFiles()?.filter { it.name !in names }?.forEach { it.delete() }
        }

        fun clearAll() {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    companion object {
        const val SILENT_SESSION_LABEL = "KevBox silent update"
        const val EXTRA_VERSION = "version"
        const val EXTRA_WITH_PROFILE = "with_profile"
        private const val TAG = "KevBoxSilentUpdate"
        private const val USER_AGENT = "KevBoxLive"
        private const val DIR = "silent-update"
        private const val SESSION_APK_ENTRY = "kevbox-update"
        private const val SESSION_DM_ENTRY = "kevbox-update.dm"
        private const val PROFILE_ENTRY = "primary.prof"
        private const val MAX_PROFILE_BYTES = 4 * 1024 * 1024
        private const val APK_OTHER_KEY = "apk-other-key"
        private const val RETRY = "retry"
        private val UNSAFE_NAME = Regex("[^0-9A-Za-z._-]")
        private val RELEASE_FAULTS = setOf("apk-unreadable", "apk-wrong-package", "apk-not-newer")
    }
}
