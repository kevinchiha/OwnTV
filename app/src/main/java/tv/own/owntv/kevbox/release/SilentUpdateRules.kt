// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.release

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

/**
 * The latest release as the silent updater needs it. [dmUrl] is null when the release has no profile
 * for this box, [silentUrl] when it has no `kevbox-silent.json` (published with `--no-silent`).
 */
data class SilentRelease(
    val version: String,
    val publishedAtMs: Long,
    val apkUrl: String,
    val dmUrl: String?,
    val silentUrl: String?,
)

/**
 * The release's `kevbox-silent.json`, written by release.sh: from [afterMs] (epoch ms, server time)
 * every box may install the release with no prompt, and the Supabase users in [earlyUsers] may at
 * once. Entries are kept as written; [SilentUpdateRules.rolloutDue] trims them. KevBox TV reads the
 * same block from its version.json with the same rules.
 */
data class SilentBlock(val afterMs: Long, val earlyUsers: List<String>) {
    companion object {
        /**
         * Null for anything but a valid block, and never throws: `after` must be a positive bare JSON
         * integer (not `"5"`, `1.5` or null), `earlyUsers` missing, null or an array of strings.
         */
        fun parse(json: String): SilentBlock? = runCatching { parseOrNull(json) }.getOrNull()

        private fun parseOrNull(json: String): SilentBlock? {
            val o = JSONObject(json)
            val afterMs = when (val after = o.opt("after")) {
                is Int -> after.toLong()
                is Long -> after
                else -> return null
            }
            if (afterMs <= 0) return null
            val earlyUsers = when (val users = o.opt("earlyUsers")) {
                null, JSONObject.NULL -> emptyList()
                is JSONArray -> (0 until users.length()).map { i -> users.opt(i) as? String ?: return null }
                else -> return null
            }
            return SilentBlock(afterMs, earlyUsers)
        }
    }
}

/**
 * The decisions behind the silent update, kept free of Android so they run as plain JVM tests.
 * The ABI pick and the version compare copy Core's UpdateManager, so both updaters agree.
 */
object SilentUpdateRules {
    /** The release asset that carries the [SilentBlock]. */
    const val SILENT_ASSET = "kevbox-silent.json"

    /** With the app open, this long without a key press (and nothing playing) counts as nobody watching. */
    const val IDLE_AFTER_MS: Long = 3 * 60 * 60 * 1000L

    /**
     * Reads a GitHub "latest release" body. The APK is the first `.apk` asset whose name holds
     * `x86_64` exactly when [wantX86] (Core's rule); the profile is the asset at
     * [UpdateProfileAttacher.dexMetadataUrl] when the release carries it, and the silent block the
     * asset named [SILENT_ASSET]. Null when the tag, the publish time or a matching APK is missing, or
     * the body is not JSON.
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
        val silentUrl = urls.firstOrNull { (name, _) -> name == SILENT_ASSET }?.second
        return SilentRelease(version, publishedAtMs, apkUrl, dmUrl, silentUrl)
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

    /**
     * At once for a box signed in as one of the block's early users (Supabase user ids, both sides
     * trimmed, case ignored); for everyone else, signed out included, from the block's `after`.
     */
    fun rolloutDue(block: SilentBlock, signedInUserId: String?, serverNowMs: Long): Boolean {
        val id = signedInUserId?.trim().orEmpty()
        if (id.isNotEmpty() && block.earlyUsers.any { it.trim().equals(id, ignoreCase = true) }) return true
        return serverNowMs >= block.afterMs
    }

    /**
     * No screen open, or nothing playing and no key pressed for [IDLE_AFTER_MS]. [lastPressMs] and
     * [nowMs] come from the same monotonic clock, so a wall-clock jump never fakes 3 quiet hours.
     */
    fun idle(activityStarted: Boolean, playing: Boolean, lastPressMs: Long, nowMs: Long): Boolean =
        !activityStarted || (!playing && nowMs - lastPressMs >= IDLE_AFTER_MS)

    /** A release still not installed this long after its block's `after` brings Core's card back (72 h, as on KevBox TV). */
    const val SAFETY_NET_MS: Long = 259_200_000L

    /**
     * Whether Core's startup "update available" card should run on Android 12+. It does when the
     * box gave up installing [gaveUpVersion] silently and it is newer than [installedVersion], or
     * when the newest release a check read ([seenVersion]) is newer than [installedVersion] and
     * either has no silent block ([seenHasSilent] false) or is still not installed [SAFETY_NET_MS]
     * after its `after` ([seenAfterBoxMs], on the box's own clock like [boxNowMs]). Every case turns
     * off by itself once the installed version catches up.
     */
    fun fallbackNeeded(
        gaveUpVersion: String?,
        seenVersion: String?,
        seenHasSilent: Boolean,
        seenAfterBoxMs: Long?,
        installedVersion: String,
        boxNowMs: Long,
    ): Boolean {
        if (gaveUpVersion != null && isNewer(gaveUpVersion, installedVersion)) return true
        if (seenVersion == null || !isNewer(seenVersion, installedVersion)) return false
        val afterMs = seenAfterBoxMs?.takeIf { seenHasSilent } ?: return true
        // Overflow-safe form of `boxNowMs >= afterMs + SAFETY_NET_MS`.
        return boxNowMs - afterMs >= SAFETY_NET_MS
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
