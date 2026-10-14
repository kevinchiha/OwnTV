package tv.own.owntv.kevbox.errors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrubberTest {

    @Test
    fun `scrubber removes a real Xtream address and every secret kind`() {
        val address = Scrubber.text("Failed to open http://host/live/user/pass/1.ts", listOf("user", "pass"))!!
        assertEquals("Failed to open [url]", address)

        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJl"
        val secrets = listOf("kevuser42", "S3cretPass")
        val input = "open http://panel.tv:8080/get.php?username=kevuser42&password=S3cretPass failed; " +
            "login KEVUSER42 / s3cretpass, mail kev@example.com, jwt $jwt, retry with refresh_token=abc123def " +
            "and {\"access_token\": \"zzz\"} sent Bearer qwerty123"
        val out = Scrubber.text(input, secrets)!!
        listOf("panel.tv", "kevuser42", "s3cretpass", "kev@example.com", "eyJ", "abc123def", "zzz", "qwerty123").forEach {
            assertFalse("$it in $out", out.contains(it, ignoreCase = true))
        }
        assertTrue(out, out.contains("[url]"))
        assertTrue(out, out.contains("[email]"))
        assertTrue(out, out.contains("[token]"))
        assertTrue(out, out.contains("refresh_token=[removed]"))
        assertTrue(out, out.contains("\"access_token\": \"[removed]\""))
        assertTrue(out, out.contains("Bearer [token]"))

        listOf(0, 5, 40, Scrubber.MESSAGE_MAX).forEach { max ->
            assertTrue(Scrubber.text(input, secrets, max)!!.length <= max)
        }
        assertEquals(out, Scrubber.text(input, secrets, null))
    }

    @Test
    fun `query values outside an address are removed`() {
        assertEquals(
            "bad username=[removed] password=[removed] token=[removed]&x=1",
            Scrubber.text("bad username=bob password=hunter2 token=t0k&x=1", emptyList()),
        )
    }

    @Test
    fun `short secrets are skipped and null stays null`() {
        assertEquals("pin 123 code 7", Scrubber.text("pin 123 code 7", listOf("123", "7")))
        assertNull(Scrubber.text(null, listOf("secret")))
    }

    @Test
    fun `host keeps only the lowercased host`() {
        assertEquals("panel.example.com", Scrubber.host("http://u:p@Panel.Example.com:8080/live/u/p/1.ts"))
        assertEquals("panel.example.com", Scrubber.host("https://panel.example.com"))
        assertEquals("10.0.0.2", Scrubber.host("http://10.0.0.2/get.php?username=a&password=b"))
        assertNull(Scrubber.host(null))
        assertNull(Scrubber.host(""))
        assertNull(Scrubber.host("not an address"))
        assertNull(Scrubber.host("panel.example.com/live"))
    }
}
