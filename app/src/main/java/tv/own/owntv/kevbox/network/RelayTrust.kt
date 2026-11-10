// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.network

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** The PEM a debug build reads from its external files folder for the emulator check. */
const val RELAY_TEST_CA_FILE = "kevbox-relay-test-ca.pem"

/**
 * The roots the relay checks persovps's certificate against.
 *
 * A release build always gets [base] alone (Android's roots plus Core's bundled Let's Encrypt
 * roots). Only a debug build may add the test roots in [extraPem], so the emulator check (AC-22)
 * can use a local TLS proxy with a test certificate; then [base] is asked first and the extra
 * roots only when [base] refuses.
 */
fun relayTrust(base: X509TrustManager, debug: Boolean, extraPem: String?): X509TrustManager {
    if (!debug || extraPem.isNullOrBlank()) return base
    val extra = pemCertificates(extraPem)
    if (extra.isEmpty()) return base
    return EitherTrust(base, trustManagerFor(extra), extra)
}

private fun pemCertificates(pem: String): List<X509Certificate> = try {
    CertificateFactory.getInstance(X509).generateCertificates(ByteArrayInputStream(pem.toByteArray()))
        .filterIsInstance<X509Certificate>()
} catch (_: CertificateException) {
    emptyList()
}

private fun trustManagerFor(roots: List<X509Certificate>): X509TrustManager {
    val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
    roots.forEachIndexed { i, cert -> store.setCertificateEntry(i.toString(), cert) }
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
    return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
}

/** Accepts what [base] accepts and, when it refuses, a chain ending at one of [extra]. */
private class EitherTrust(
    private val base: X509TrustManager,
    private val extraTrust: X509TrustManager,
    private val extra: List<X509Certificate>,
) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        try {
            base.checkServerTrusted(chain, authType)
        } catch (refused: CertificateException) {
            try {
                extraTrust.checkServerTrusted(chain, authType)
            } catch (_: CertificateException) {
                throw refused
            }
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
        try {
            base.checkClientTrusted(chain, authType)
        } catch (refused: CertificateException) {
            try {
                extraTrust.checkClientTrusted(chain, authType)
            } catch (_: CertificateException) {
                throw refused
            }
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = base.acceptedIssuers + extra
}

private const val X509 = "X.509"
