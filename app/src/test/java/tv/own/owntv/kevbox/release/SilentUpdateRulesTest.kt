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

    private fun release(published: Long = this.published) =
        SilentRelease("5.2.1.3", published, "$base/KevBoxLive.apk", "$base/KevBoxLive.dm")

    @Test
    fun `an arm box takes the plain APK and the Android 12 profile`() {
        val r = SilentUpdateRules.parse(body(), wantX86 = false, sdk = 33)!!
        assertEquals("5.2.1.3", r.version)
        assertEquals(published, r.publishedAtMs)
        assertEquals("$base/KevBoxLive.apk", r.apkUrl)
        assertEquals("$base/KevBoxLive.dm", r.dmUrl)
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
    fun `an early email gets the release at once`() {
        assertTrue(SilentUpdateRules.rolloutDue(release(), "kevin.chiha@gmail.com", published))
        assertTrue(SilentUpdateRules.rolloutDue(release(), "  Kevin.Chiha@Gmail.com ", published + 1))
    }

    @Test
    fun `everyone else waits 24 hours after publishing`() {
        val dayLater = published + SilentUpdateRules.ROLLOUT_DELAY_MS
        assertEquals(24 * hour, SilentUpdateRules.ROLLOUT_DELAY_MS)
        assertFalse(SilentUpdateRules.rolloutDue(release(), "someone@example.com", dayLater - 1))
        assertTrue(SilentUpdateRules.rolloutDue(release(), "someone@example.com", dayLater))
        assertFalse(SilentUpdateRules.rolloutDue(release(), null, dayLater - 1))
        assertTrue(SilentUpdateRules.rolloutDue(release(), null, dayLater))
        assertFalse(SilentUpdateRules.rolloutDue(release(), "", published))
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
}
