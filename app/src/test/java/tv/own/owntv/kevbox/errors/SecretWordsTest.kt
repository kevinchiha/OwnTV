// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.errors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SecretWordsTest {

    @Test
    fun `the chosen playlist's VPN login is scrubbed like the other secrets`() {
        // Premiumize logins are a customer id and an API key: no pattern catches either.
        val words = secretWordsOf(source = null, email = "kev@example.com", session = null, vpnUsername = "1234567", vpnPassword = "k3yZ9q")
        assertEquals(setOf("kev@example.com", "1234567", "k3yZ9q"), words)

        val out = Scrubber.text("engine said: login 1234567 with k3yZ9q refused", words)!!
        assertFalse(out, out.contains("1234567"))
        assertFalse(out, out.contains("k3yZ9q"))

        // No VPN on the chosen row: nothing extra, and blanks never count.
        assertEquals(setOf("kev@example.com"), secretWordsOf(null, "kev@example.com", null, null, " "))
    }
}
