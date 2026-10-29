// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.release

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.json.JSONObject

/** The latest release as the silent updater needs it. [dmUrl] is null when the release has no profile for this box. */
data class SilentRelease(val version: String, val publishedAtMs: Long, val apkUrl: String, val dmUrl: String?)

/**
 * The decisions behind the silent update, kept free of Android so they run as plain JVM tests.
 * The ABI pick and the version compare copy Core's UpdateManager, so both updaters agree.
 */
object SilentUpdateRules {
    /** Signed in as one of these, a box installs a release at once. Compared trimmed and lowercased. */
    val EARLY_EMAILS: Set<String> = setOf("kevin.chiha@gmail.com")

    /** Every other box waits this long after the release is published. */
    const val ROLLOUT_DELAY_MS: Long = 24 * 60 * 60 * 1000L

    /** With the app open, this long without a key press (and nothing playing) counts as nobody watching. */
    const val IDLE_AFTER_MS: Long = 3 * 60 * 60 * 1000L

    /**
     * Reads a GitHub "latest release" body. The APK is the first `.apk` asset whose name holds
     * `x86_64` exactly when [wantX86] (Core's rule); the profile is the asset at
     * [UpdateProfileAttacher.dexMetadataUrl] when the release carries it. Null when the tag, the
     * publish time or a matching APK is missing, or the body is not JSON.
     */
    fun parse(body: String, wantX86: Boolean, sdk: Int): SilentRelease? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val version = o.optString("tag_name").removePrefix("v").takeIf { it.isNotBlank() } ?: return null
        val publishedAtMs = runCatching { Instant.parse(o.optString("published_at")).toEpochMilli() }
            .getOrNull() ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        val urls = (0 until assets.length()).mapNotNull { i ->
            val asset = assets.optJSONObject(i) ?: return@mapNotNull null
            val url = asset.optString("browser_download_url")
            if (url.isBlank()) null else asset.optString("name") to url
        }
        val apkUrl = urls.firstOrNull { (name, _) ->
            name.endsWith(".apk") && name.contains("x86_64", ignoreCase = true) == wantX86
        }?.second ?: return null
        val wanted = UpdateProfileAttacher.dexMetadataUrl(apkUrl, sdk)
        val dmUrl = wanted?.takeIf { want -> urls.any { it.second == want } }
        return SilentRelease(version, publishedAtMs, apkUrl, dmUrl)
    }

    /** Core's compare: number by number, a leading `v` dropped, junk counts as 0, equal is not newer. */
    fun isNewer(remote: String, local: String): Boolean {
        val r = segments(remote)
        val l = segments(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun segments(version: String): List<Int> =
        version.removePrefix("v").split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    /** Early emails at once; everyone else, signed out included, from [ROLLOUT_DELAY_MS] after publishing. */
    fun rolloutDue(release: SilentRelease, signedInEmail: String?, serverNowMs: Long): Boolean {
        val email = signedInEmail?.trim()?.lowercase()
        if (email != null && email in EARLY_EMAILS) return true
        return serverNowMs - release.publishedAtMs >= ROLLOUT_DELAY_MS
    }

    /**
     * No screen open, or nothing playing and no key pressed for [IDLE_AFTER_MS]. [lastPressMs] and
     * [nowMs] come from the same monotonic clock, so a wall-clock jump never fakes 3 quiet hours.
     */
    fun idle(activityStarted: Boolean, playing: Boolean, lastPressMs: Long, nowMs: Long): Boolean =
        !activityStarted || (!playing && nowMs - lastPressMs >= IDLE_AFTER_MS)

    /** A release the box has seen but not installed this long after publishing brings Core's card back. */
    const val FALLBACK_AFTER_MS: Long = 7 * 24 * 60 * 60 * 1000L

    /**
     * Whether Core's startup "update available" card should run on Android 12+: the box gave up
     * installing [gaveUpVersion] silently and it is newer than [installedVersion], or the newest
     * release the updater saw ([seenVersion]) is still not installed more than [FALLBACK_AFTER_MS]
     * after it was published. Both turn off by themselves once the installed version catches up.
     */
    fun fallbackNeeded(
        gaveUpVersion: String?,
        seenVersion: String?,
        seenPublishedAtMs: Long,
        installedVersion: String,
        boxNowMs: Long,
    ): Boolean {
        if (gaveUpVersion != null && isNewer(gaveUpVersion, installedVersion)) return true
        return seenVersion != null && isNewer(seenVersion, installedVersion) &&
            boxNowMs - seenPublishedAtMs > FALLBACK_AFTER_MS
    }

    /**
     * Whether Android would accept the downloaded APK as an update of the installed app: every
     * certificate the installed app signs with is among the APK's ([apkCerts] holds its signers and,
     * for a single signer, the older keys it proves it rotated from). Nothing on either side is a no.
     */
    fun signedAlike(apkCerts: Set<String>, installedCerts: Set<String>): Boolean =
        apkCerts.isNotEmpty() && installedCerts.isNotEmpty() && apkCerts.containsAll(installedCerts)

    /** The time from an HTTP `Date` header, or [boxNowMs] when the header is missing or unreadable. */
    fun serverNow(dateHeader: String?, boxNowMs: Long): Long {
        if (dateHeader.isNullOrBlank()) return boxNowMs
        return runCatching {
            ZonedDateTime.parse(dateHeader.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrDefault(boxNowMs)
    }
}
