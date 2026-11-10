// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OvpnConfigTest {

    private val countries = listOf(
        "at", "au", "be", "ca", "ch", "cz", "de", "es", "fi",
        "fr", "gb", "gr", "it", "jp", "nl", "pl", "sg", "us",
    )

    private fun fixture(name: String): String {
        val stream = javaClass.getResourceAsStream("/vpn/$name")
        assertNotNull("missing fixture $name", stream)
        return stream!!.use { String(it.readBytes(), Charsets.UTF_8) }
    }

    /**
     * A Premiumize file as Premiumize serves it, with CRLF line endings. The repo stores the
     * fixtures with LF (.gitattributes), so they are converted back here.
     */
    private fun premiumize(country: String) =
        fixture("vpn-$country.premiumize.me.ovpn").replace("\r\n", "\n").replace("\n", "\r\n")

    /** Every line of [config] without its line ending. */
    private fun lines(config: String): List<String> = config.split("\n").map { it.removeSuffix("\r") }

    private fun caBlock(config: String): String =
        config.substring(config.indexOf("<ca>"), config.indexOf("</ca>") + "</ca>".length)

    @Test
    fun `prepareOvpn keeps every Premiumize option but the retry`() {
        assertEquals(18, countries.size)
        for (country in countries) {
            val raw = premiumize(country)
            val out = prepareOvpn(raw)
            val outLines = lines(out)
            val host = "vpn-$country.premiumize.me"
            for (kept in listOf("remote $host", "verify-x509-name CN=$host", "proto udp", "cipher AES-256-CBC")) {
                assertTrue("$country lost '$kept'", lines(raw).contains(kept))
                assertTrue("$country lost '$kept'", outLines.contains(kept))
            }
            assertEquals("$country changed its <ca> block", caBlock(raw), caBlock(out))
            assertFalse("$country still retries forever", outLines.contains("resolv-retry infinite"))
            assertTrue("$country has no bounded retry", outLines.contains("resolv-retry 25"))
            // Nothing else changes: removing the added lines and restoring the retry gives the file back.
            val restored = out
                .replace("resolv-retry 25", "resolv-retry infinite")
                .replace("data-ciphers AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305:AES-256-CBC\r\n", "")
                .replace("data-ciphers-fallback AES-256-CBC\r\n", "")
            assertEquals("$country changed another line", raw, restored)
        }
    }

    @Test
    fun `prepareOvpn adds the cipher fallback once`() {
        val raw = premiumize("de")
        val added = lines(prepareOvpn(raw))
        assertEquals(1, added.count { it.startsWith("data-ciphers ") })
        assertEquals(1, added.count { it.startsWith("data-ciphers-fallback ") })
        assertEquals(
            "data-ciphers AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305:AES-256-CBC",
            added[added.indexOf("cipher AES-256-CBC") + 1],
        )

        val withCiphers = raw.replace("cipher AES-256-CBC\r\n", "cipher AES-256-CBC\r\ndata-ciphers AES-256-GCM\r\n")
        val out = lines(prepareOvpn(withCiphers))
        assertEquals(1, out.count { it.startsWith("data-ciphers ") })
        assertTrue(out.contains("data-ciphers AES-256-GCM"))
        assertEquals(0, out.count { it.startsWith("data-ciphers-fallback ") })
    }

    @Test
    fun `prepareOvpn strips an inline auth block`() {
        val raw = premiumize("nl").replace("<ca>\r\n", "<auth-user-pass>\r\nu\r\np\r\n</auth-user-pass>\r\n<ca>\r\n")
        val out = lines(prepareOvpn(raw))
        assertFalse(out.contains("<auth-user-pass>"))
        assertFalse(out.contains("</auth-user-pass>"))
        assertFalse(out.contains("u"))
        assertFalse(out.contains("p"))
        assertTrue(out.contains("auth-user-pass"))
        assertTrue(out.contains("<ca>"))
    }

    @Test
    fun `an inline-only login block leaves a bare auth-user-pass in its place`() {
        // The file's own bare line removed: the block is the directive.
        val bare = premiumize("nl").replace("auth-user-pass\r\n", "")
        assertFalse(lines(bare).contains("auth-user-pass"))

        // Tags on their own lines, and the login on the tag lines.
        val ownLines = bare.replace("<ca>\r\n", "<auth-user-pass>\r\nu\r\np\r\n</auth-user-pass>\r\n<ca>\r\n")
        val onTags = bare.replace("<ca>\r\n", "<auth-user-pass>u\r\np</auth-user-pass>\r\n<ca>\r\n")
        for (raw in listOf(ownLines, onTags)) {
            val out = lines(prepareOvpn(raw))
            assertEquals(1, out.count { it == "auth-user-pass" })
            assertTrue(out.indexOf("auth-user-pass") < out.indexOf("<ca>"))
            assertFalse(out.any { it.contains("<auth-user-pass>") || it.contains("</auth-user-pass>") })
            assertFalse(out.contains("u") || out.contains("p") || out.contains("p</auth-user-pass>"))
        }

        // A file that has the bare line keeps exactly one.
        val both = premiumize("nl").replace("<ca>\r\n", "<auth-user-pass>u\r\np</auth-user-pass>\r\n<ca>\r\n")
        assertEquals(1, lines(prepareOvpn(both)).count { it == "auth-user-pass" })
    }

    @Test
    fun `the engine's log runs below the level that logs management commands`() {
        // OpenVPN keeps the last verb it reads; the engine writes its own "verb 4" before these.
        assertEquals("verb 2\n", quietEngineLog(null))
        assertEquals("verb 2\n", quietEngineLog(""))
        val parsed = "# These options found in the config file do not map to config settings:\nreneg-sec 0"
        val out = quietEngineLog(parsed)
        assertTrue(out.startsWith(parsed + "\n"))
        assertEquals("verb $ENGINE_LOG_VERB", out.trimEnd().lines().last())
        assertTrue(ENGINE_LOG_VERB < 3)
    }

    @Test
    fun `prepareOvpn keeps the file's line endings`() {
        val crlf = prepareOvpn(premiumize("fr"))
        assertFalse("a bare LF crept in", Regex("(?<!\r)\n").containsMatchIn(crlf))

        val lf = prepareOvpn(premiumize("fr").replace("\r\n", "\n"))
        assertFalse("a CR crept in", lf.contains("\r"))
        assertTrue(lines(lf).contains("data-ciphers-fallback AES-256-CBC"))
    }
}
