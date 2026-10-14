// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.errors

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveErrorCodesTest {

    /** Unit tests run in `app/`, so the repo's tools folder is one level up. */
    private fun codeList(): JSONObject = JSONObject(File("../tools/kevbox/live-error-codes.json").readText())

    @Test
    fun `the Kotlin list equals the JSON file`() {
        val json = codeList()
        val fixed = json.getJSONArray("fixed")
        val fromJson = (0 until fixed.length()).map { fixed.getString(it) }

        assertEquals(fromJson.size, fromJson.toSet().size)
        assertEquals(fromJson.toSet(), LiveErrorCodes.FIXED)

        val httpPattern = Regex(json.getString("httpPattern"))
        assertTrue(httpPattern.matches(LiveErrorCodes.http(458)))
        assertTrue(httpPattern.matches(LiveErrorCodes.http(884)))
        assertEquals("http_403", LiveErrorCodes.http(403))
    }

    @Test
    fun `isKnown accepts fixed and three-digit http codes only`() {
        LiveErrorCodes.FIXED.forEach { assertTrue(it, LiveErrorCodes.isKnown(it)) }
        assertTrue(LiveErrorCodes.isKnown("http_458"))
        assertTrue(LiveErrorCodes.isKnown("http_999"))
        assertFalse(LiveErrorCodes.isKnown("http_099"))
        assertFalse(LiveErrorCodes.isKnown("http_1000"))
        assertFalse(LiveErrorCodes.isKnown("http_"))
        assertFalse(LiveErrorCodes.isKnown("account_unreachable"))
        assertFalse(LiveErrorCodes.isKnown(""))
    }
}
