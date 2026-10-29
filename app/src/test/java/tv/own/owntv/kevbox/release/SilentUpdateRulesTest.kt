// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SilentUpdateRulesTest {
    private val base = "https://github.com/kevinchiha/OwnTV/releases/download/v5.2.1.3"
    private val names = listOf(
        "KevBoxLive.apk",
        "KevBoxLive.dm",
        "KevBoxLive-api28.dm",
        "KevBoxLive-x86_64.apk",
        "KevBoxLive-x86_64.dm",
        "KevBoxLive-x86_64-api28.dm",
        "kevbox-silent.json",
    )

    /** 2026-10-28T12:27:32Z */
    private val published = 1_793_190_452_000L
    private val hour = 60 * 60 * 1000L

    private fun body(
        tag: String? = "v5.2.1.3",
        publishedAt: String? = "2026-10-28T12:27:32Z",
        assets: List<String>? = names,
    ): String {
        val fields = mutableListOf<String>()
        if (tag != null) fields += "\"tag_name\": \"$tag\""
        if (publishedAt != null) fields += "\"published_at\": \"$publishedAt\""
        if (assets != null) {
            fields += "\"assets\": [" + assets.joinToString(",") {
                "{\"name\": \"$it\", \"browser_download_url\": \"$base/$it\"}"
            } + "]"
        }
        return "{" + fields.joinToString(",") + "}"
    }

    /** Kevin's Supabase user id, in the shape release.sh checks. */
    private val kevin = "0f8fad5b-d9cb-469f-a165-70867728950e"
    private val after = published + 24 * hour
    private val block = SilentBlock(after, listOf(kevin, "7c9e6679-7425-40de-944b-e07fc1f90ae7"))

    @Test
    fun `an arm box takes the plain APK and the Android 12 profile`() {
        val r = SilentUpdateRules.parse(body(), wantX86 = false, sdk = 33)!!
        assertEquals("5.2.1.3", r.version)
        assertEquals(published, r.publishedAtMs)
        assertEquals("$base/KevBoxLive.apk", r.apkUrl)
        assertEquals("$base/KevBoxLive.dm", r.dmUrl)
        assertEquals("$base/kevbox-silent.json", r.silentUrl)
    }

    @Test
    fun `a release published with --no-silent has no silent block URL`() {
        val r = SilentUpdateRules.parse(body(assets = names - "kevbox-silent.json"), false, 33)!!
        assertEquals("$base/KevBoxLive.apk", r.apkUrl)
        assertNull(r.silentUrl)
    }

    @Test
    fun `only the exact asset name counts as the silent block`() {
        val r = SilentUpdateRules.parse(body(assets = listOf("KevBoxLive.apk", "old-kevbox-silent.json")), false, 33)!!
        assertNull(r.silentUrl)
    }

    @Test
    fun `an x86_64 box takes the x86_64 APK and its profile`() {
        val r = SilentUpdateRules.parse(body(), wantX86 = true, sdk = 31)!!
        assertEquals("$base/KevBoxLive-x86_64.apk", r.apkUrl)
        assertEquals("$base/KevBoxLive-x86_64.dm", r.dmUrl)
    }

    @Test
    fun `Android 9 to 11 take the api28 profile, older ones none`() {
        assertEquals("$base/KevBoxLive-api28.dm", SilentUpdateRules.parse(body(), false, 30)!!.dmUrl)
        assertEquals("$base/KevBoxLive-x86_64-api28.dm", SilentUpdateRules.parse(body(), true, 28)!!.dmUrl)
        assertNull(SilentUpdateRules.parse(body(), false, 27)!!.dmUrl)
    }

    @Test
    fun `a release without the profile still offers the APK`() {
        val r = SilentUpdateRules.parse(body(assets = listOf("KevBoxLive.apk")), false, 33)!!
        assertEquals("$base/KevBoxLive.apk", r.apkUrl)
        assertNull(r.dmUrl)
    }

    @Test
    fun `missing pieces or bad JSON give nothing`() {
        assertNull(SilentUpdateRules.parse(body(assets = null), false, 33))
        assertNull(SilentUpdateRules.parse(body(assets = emptyList()), false, 33))
        assertNull(SilentUpdateRules.parse(body(assets = listOf("KevBoxLive-x86_64.apk")), false, 33))
        assertNull(SilentUpdateRules.parse(body(assets = listOf("KevBoxLive.apk")), true, 33))
        assertNull(SilentUpdateRules.parse(body(tag = null), false, 33))
        assertNull(SilentUpdateRules.parse(body(tag = ""), false, 33))
        assertNull(SilentUpdateRules.parse(body(publishedAt = null), false, 33))
        assertNull(SilentUpdateRules.parse(body(publishedAt = "yesterday"), false, 33))
        assertNull(SilentUpdateRules.parse("not json", false, 33))
        assertNull(SilentUpdateRules.parse("", false, 33))
    }

    @Test
    fun `newer compares numbers segment by segment`() {
        assertTrue(SilentUpdateRules.isNewer("5.2.1.4", "5.2.1.3"))
        assertTrue(SilentUpdateRules.isNewer("v5.2.10.0", "5.2.9.9"))
        assertTrue(SilentUpdateRules.isNewer("5.3", "5.2.1.3"))
        assertFalse(SilentUpdateRules.isNewer("5.2.1.3", "5.2.1.3"))
        assertFalse(SilentUpdateRules.isNewer("v5.2.1.3", "5.2.1.3"))
        assertFalse(SilentUpdateRules.isNewer("5.2.1.2", "5.2.1.3"))
    }

    @Test
    fun `a local build is never older`() {
        assertFalse(SilentUpdateRules.isNewer("5.2.1.3", "99.99.99.99"))
        assertFalse(SilentUpdateRules.isNewer("v98.0.0.0", "99.99.99.99"))
    }

    @Test
    fun `an early user id gets the release at once`() {
        assertTrue(SilentUpdateRules.rolloutDue(block, kevin, published))
        assertTrue(SilentUpdateRules.rolloutDue(block, "7C9E6679-7425-40DE-944B-E07FC1F90AE7", published))
    }

    @Test
    fun `early users match trimmed on both sides, case ignored`() {
        assertTrue(SilentUpdateRules.rolloutDue(block, "  ${kevin.uppercase()} ", published))
        val spaced = SilentBlock(after, listOf("  ${kevin.uppercase()}\t"))
        assertTrue(SilentUpdateRules.rolloutDue(spaced, kevin, published))
    }

    @Test
    fun `anyone else is not early, signed out and blank included`() {
        assertFalse(SilentUpdateRules.rolloutDue(block, "11111111-2222-3333-4444-555555555555", after - 1))
        assertFalse(SilentUpdateRules.rolloutDue(block, null, after - 1))
        assertFalse(SilentUpdateRules.rolloutDue(block, "", after - 1))
        assertFalse(SilentUpdateRules.rolloutDue(block, "  ", after - 1))
        // A blank entry never matches a blank id.
        assertFalse(SilentUpdateRules.rolloutDue(SilentBlock(after, listOf(" ")), " ", after - 1))
        // Emails no longer count.
        assertFalse(SilentUpdateRules.rolloutDue(block, "kevin.chiha@gmail.com", after - 1))
    }

    @Test
    fun `everyone is due from after, on the server's clock`() {
        assertFalse(SilentUpdateRules.rolloutDue(block, null, after - 1))
        assertTrue(SilentUpdateRules.rolloutDue(block, null, after))
        assertTrue(SilentUpdateRules.rolloutDue(block, "11111111-2222-3333-4444-555555555555", after + 1))
        assertTrue(SilentUpdateRules.rolloutDue(SilentBlock(after, emptyList()), null, after))
    }

    @Test
    fun `a valid silent block parses`() {
        assertEquals(
            SilentBlock(1_793_276_852_000L, listOf(kevin, " ABC ")),
            SilentBlock.parse("""{"after": 1793276852000, "earlyUsers": ["$kevin", " ABC "]}"""),
        )
        assertEquals(SilentBlock(5L, emptyList()), SilentBlock.parse("""{"after": 5, "earlyUsers": []}"""))
        assertEquals(SilentBlock(5L, emptyList()), SilentBlock.parse("""{"after": 5}"""))
        assertEquals(SilentBlock(5L, emptyList()), SilentBlock.parse("""{"after": 5, "earlyUsers": null}"""))
        assertEquals(SilentBlock(5L, emptyList()), SilentBlock.parse("""{"after": 5, "earlyUsers": [], "later": true}"""))
    }

    @Test
    fun `a bad after means no silent block`() {
        assertNull(SilentBlock.parse("""{"after": "x"}"""))
        assertNull(SilentBlock.parse("""{"after": "1793276852000"}"""))
        assertNull(SilentBlock.parse("""{"after": 0}"""))
        assertNull(SilentBlock.parse("""{"after": -5}"""))
        assertNull(SilentBlock.parse("""{"after": 1.5}"""))
        assertNull(SilentBlock.parse("""{"after": null}"""))
        assertNull(SilentBlock.parse("""{"after": {"ms": 5}}"""))
        assertNull(SilentBlock.parse("""{"after": 99999999999999999999}"""))
        assertNull(SilentBlock.parse("""{"earlyUsers": []}"""))
    }

    @Test
    fun `bad early users mean no silent block`() {
        assertNull(SilentBlock.parse("""{"after": 5, "earlyUsers": "$kevin"}"""))
        assertNull(SilentBlock.parse("""{"after": 5, "earlyUsers": 7}"""))
        assertNull(SilentBlock.parse("""{"after": 5, "earlyUsers": {"a": "b"}}"""))
        assertNull(SilentBlock.parse("""{"after": 5, "earlyUsers": ["$kevin", 7]}"""))
        assertNull(SilentBlock.parse("""{"after": 5, "earlyUsers": ["$kevin", null]}"""))
    }

    @Test
    fun `a file that is not a JSON object means no silent block`() {
        assertNull(SilentBlock.parse(""))
        assertNull(SilentBlock.parse("not json"))
        assertNull(SilentBlock.parse("[5]"))
        assertNull(SilentBlock.parse("<html>Not Found</html>"))
    }

    @Test
    fun `server time comes from the Date header`() {
        assertEquals(
            published,
            SilentUpdateRules.serverNow("Wed, 28 Oct 2026 12:27:32 GMT", boxNowMs = 5L),
        )
    }

    @Test
    fun `box time stands in when the Date header is missing or unreadable`() {
        assertEquals(5L, SilentUpdateRules.serverNow(null, boxNowMs = 5L))
        assertEquals(5L, SilentUpdateRules.serverNow("", boxNowMs = 5L))
        assertEquals(5L, SilentUpdateRules.serverNow("tomorrow", boxNowMs = 5L))
    }

    @Test
    fun `a box with no screen open is always idle`() {
        assertTrue(SilentUpdateRules.idle(activityStarted = false, playing = true, lastPressMs = 100, nowMs = 100))
    }

    @Test
    fun `an open screen is idle only with nothing playing and 3 hours without a press`() {
        val now = 10 * hour
        val threeHoursAgo = now - SilentUpdateRules.IDLE_AFTER_MS
        assertEquals(3 * hour, SilentUpdateRules.IDLE_AFTER_MS)
        assertTrue(SilentUpdateRules.idle(true, playing = false, lastPressMs = threeHoursAgo, nowMs = now))
        assertFalse(SilentUpdateRules.idle(true, playing = false, lastPressMs = threeHoursAgo + 1, nowMs = now))
        assertFalse(SilentUpdateRules.idle(true, playing = true, lastPressMs = threeHoursAgo, nowMs = now))
        assertFalse(SilentUpdateRules.idle(true, playing = true, lastPressMs = now, nowMs = now))
    }

    private fun fallback(
        gaveUp: String? = null,
        seen: String? = null,
        hasSilent: Boolean = true,
        afterBoxMs: Long? = after,
        installed: String = "5.2.1.2",
        boxNowMs: Long = published,
    ) = SilentUpdateRules.fallbackNeeded(gaveUp, seen, hasSilent, afterBoxMs, installed, boxNowMs)

    @Test
    fun `fallback when the box gave up on a newer version, until that version is installed`() {
        assertTrue(fallback(gaveUp = "5.2.1.3", installed = "5.2.1.2"))
        assertFalse(fallback(gaveUp = "5.2.1.3", installed = "5.2.1.3"))
        assertFalse(fallback(gaveUp = "5.2.1.3", installed = "99.99.99.99"))
    }

    @Test
    fun `fallback at once when the newest release has no silent block`() {
        assertTrue(fallback(seen = "5.2.1.3", hasSilent = false, afterBoxMs = null))
        // A block flag with no stored time counts as no block.
        assertTrue(fallback(seen = "5.2.1.3", hasSilent = true, afterBoxMs = null))
        assertFalse(fallback(seen = "5.2.1.3", hasSilent = false, afterBoxMs = null, installed = "5.2.1.3"))
    }

    @Test
    fun `fallback 72 hours after the block's after, on a box clock a year behind`() {
        assertEquals(72 * hour, SilentUpdateRules.SAFETY_NET_MS)
        // release.sh wrote `after` on the server clock; the updater stored it moved by the box's offset.
        val boxBehind = 365 * 24 * hour
        val afterBox = after - boxBehind
        assertFalse(fallback(seen = "5.2.1.3", afterBoxMs = afterBox, boxNowMs = afterBox))
        assertFalse(fallback(seen = "5.2.1.3", afterBoxMs = afterBox, boxNowMs = afterBox + 72 * hour - 1))
        assertTrue(fallback(seen = "5.2.1.3", afterBoxMs = afterBox, boxNowMs = afterBox + 72 * hour))
    }

    @Test
    fun `no card once the installed version catches up, or with nothing seen`() {
        assertFalse(fallback(seen = "5.2.1.3", installed = "5.2.1.3", boxNowMs = after + 30 * 24 * hour))
        assertFalse(fallback(seen = "5.2.1.3", installed = "5.2.1.4", hasSilent = false, afterBoxMs = null))
        assertFalse(fallback(seen = null, boxNowMs = Long.MAX_VALUE))
    }

    @Test
    fun `an older given-up version brings no card back`() {
        assertFalse(fallback(gaveUp = "5.2.1.1", seen = "5.2.1.2", installed = "5.2.1.2"))
    }

    @Test
    fun `signed alike when the APK carries every installed certificate`() {
        assertTrue(SilentUpdateRules.signedAlike(setOf("a"), setOf("a")))
        // A rotated key: the APK proves it came from the installed one.
        assertTrue(SilentUpdateRules.signedAlike(setOf("new", "a"), setOf("a")))
        assertFalse(SilentUpdateRules.signedAlike(setOf("b"), setOf("a")))
        assertFalse(SilentUpdateRules.signedAlike(setOf("a"), setOf("a", "b")))
        assertFalse(SilentUpdateRules.signedAlike(emptySet(), setOf("a")))
        assertFalse(SilentUpdateRules.signedAlike(setOf("a"), emptySet()))
    }
}
