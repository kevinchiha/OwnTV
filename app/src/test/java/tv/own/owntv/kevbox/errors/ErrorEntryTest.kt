// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.errors

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorEntryTest {

    @Test
    fun `toJson writes exactly the contract keys`() {
        val json = SPARSE.toJson()

        assertEquals(CONTRACT_KEYS, json.keys().asSequence().toSet())
        listOf("http_status", "error_name", "channel", "live", "proxy", "message_short").forEach {
            assertTrue("$it is JSON null", json.isNull(it))
            assertTrue("$it is present", json.has(it))
        }
        assertEquals(SPARSE, ErrorEntry.fromJson(json))
        assertEquals(FULL, ErrorEntry.fromJson(FULL.toJson()))
        assertEquals(CONTRACT_KEYS, FULL.toJson().keys().asSequence().toSet())
    }

    @Test
    fun `timestamps go out as UTC ISO-8601 with milliseconds and Z`() {
        val json = FULL.toJson()

        assertEquals("2026-10-14T18:02:11.123Z", json.getString("first_at"))
        assertEquals("2026-10-14T18:02:11.000Z", ErrorEntry.isoUtc(1_792_000_931_000L))
    }

    @Test
    fun `fromJson refuses an entry without a code or a time`() {
        val noCode = FULL.toJson().apply { remove("code") }
        val badTime = FULL.toJson().put("first_at", "yesterday")

        assertNull(ErrorEntry.fromJson(noCode))
        assertNull(ErrorEntry.fromJson(badTime))
        assertNull(ErrorEntry.fromJson(JSONObject()))
    }

    private companion object {
        val CONTRACT_KEYS = setOf(
            "first_at", "last_at", "repeat_count", "code", "http_status", "error_name",
            "account_status", "active_cons", "max_connections", "engine", "surface", "live",
            "channel", "category", "provider_host", "proxy", "model", "android", "message_short",
        )

        // 2026-10-14T18:02:11.123Z
        const val AT = 1_792_000_931_123L

        val FULL = ErrorEntry(
            firstAtMs = AT, lastAtMs = AT + 5_000, repeatCount = 3,
            code = "http_404", httpStatus = 404, errorName = "InvalidResponseCodeException",
            accountStatus = "active", activeCons = 1, maxConnections = 2,
            engine = "exo", surface = "full", live = true,
            channel = "BBC One", category = "UK", providerHost = "panel.example", proxy = false,
            model = "AFTMM", android = "9", messageShort = "Response code: 404",
        )

        val SPARSE = ErrorEntry(
            firstAtMs = AT, lastAtMs = AT, repeatCount = 1,
            code = "playlist_unreachable", httpStatus = null, errorName = null,
            accountStatus = null, activeCons = null, maxConnections = null,
            engine = null, surface = null, live = null,
            channel = null, category = null, providerHost = null, proxy = null,
            model = null, android = null, messageShort = null,
        )
    }
}
