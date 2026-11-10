// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.network

import java.net.InetAddress
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RelayTrustTest {
    private val base = RelayFixtures.baseTrust()
    private val otherPem = RelayFixtures.pem(RelayFixtures.OTHER_CA_PEM)
    private val relayChain = RelayFixtures.chain(RelayFixtures.RELAY_TEST_P12)
    private val otherChain = RelayFixtures.chain(RelayFixtures.OTHER_CA_P12)

    private fun accepts(trust: X509TrustManager, chain: Array<X509Certificate>): Boolean = try {
        trust.checkServerTrusted(chain, AUTH)
        true
    } catch (_: CertificateException) {
        false
    }

    @Test
    fun `extra roots count only in a debug build`() {
        val release = relayTrust(base, debug = false, extraPem = otherPem)
        assertSame("a release build ignores the extra roots", base, release)
        assertTrue(accepts(release, relayChain))
        assertEquals(false, accepts(release, otherChain))

        val debug = relayTrust(base, debug = true, extraPem = otherPem)
        assertTrue("base-trusted chain", accepts(debug, relayChain))
        assertTrue("extra root", accepts(debug, otherChain))
        val issuers = debug.acceptedIssuers.map { it.subjectX500Principal }.toSet()
        val expected = (base.acceptedIssuers.toList() + RelayFixtures.certificates(otherPem)).map { it.subjectX500Principal }.toSet()
        assertEquals(expected, issuers)
    }

    @Test
    fun `no certificate in the extra PEM leaves the base trust alone`() {
        for (pem in listOf(null, "", "  \n", "not a certificate")) {
            assertSame("'$pem'", base, relayTrust(base, debug = true, extraPem = pem))
        }
    }

    @Test(timeout = 60_000)
    fun `the relay carries a chain the debug extra roots trust and refuses it without them`() {
        RelayFixtures.TlsEcho(RelayFixtures.OTHER_CA_P12).use { server ->
            val target = RelayTarget(RelayFixtures.HOST, server.port, "127.0.0.1")
            val noLookup: (String) -> List<InetAddress> = { fail("looked up $it"); emptyList() }

            val refused = LocalRelay(relayTrust(base, false, otherPem), noLookup, RelayFixtures.freePort())
            try {
                RelayFixtures.player(refused.start(target)).use { assertTrue(RelayFixtures.readsEnd(it)) }
            } finally {
                refused.stop()
            }

            val accepted = LocalRelay(relayTrust(base, true, otherPem), noLookup, RelayFixtures.freePort())
            try {
                RelayFixtures.player(accepted.start(target)).use { RelayFixtures.assertRoundTrip(it, RelayFixtures.ALL_BYTES) }
            } finally {
                accepted.stop()
            }
        }
    }

    private companion object {
        const val AUTH = "ECDHE_ECDSA"
    }
}
