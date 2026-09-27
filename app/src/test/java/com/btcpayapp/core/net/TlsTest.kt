package com.btcpayapp.core.net

import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateFactory
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TLS policy: the pin check, the reason a handshake failed, and the
 * probe checks the trust dialog relies on.
 *
 * The fixtures are self-signed P-256 certificates made once with
 * `openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1`, and
 * the expected pin and certificate hash were computed by openssl, not by the
 * code under test.
 */
class TlsTest {

    // --- Pinned trust ------------------------------------------------------

    @Test
    fun `a pinned leaf is accepted`() {
        Tls.PinnedTrustManager(setOf(PINNED_PIN)).checkServerTrusted(arrayOf(pinned), "ECDHE_ECDSA")
    }

    @Test
    fun `a real pinned certificate appended after another leaf is refused`() {
        // [theirLeaf, realServerCert]: the appended certificate is public, and
        // matching any element of the chain would let it vouch for the attacker.
        assertThrows(PinMismatchException::class.java) {
            Tls.PinnedTrustManager(setOf(PINNED_PIN)).checkServerTrusted(arrayOf(other, pinned), "ECDHE_ECDSA")
        }
    }

    @Test
    fun `an expired leaf is refused even when pinned`() {
        val expiredPin = Tls.spkiPin(expired)
        assertThrows(CertificateExpiredException::class.java) {
            Tls.PinnedTrustManager(setOf(expiredPin)).checkServerTrusted(arrayOf(expired), "ECDHE_ECDSA")
        }
    }

    @Test
    fun `an empty chain is refused`() {
        assertThrows(CertificateException::class.java) {
            Tls.PinnedTrustManager(setOf(PINNED_PIN)).checkServerTrusted(emptyArray(), "ECDHE_ECDSA")
        }
    }

    @Test
    fun `the pinned hostname verifier accepts only the configured host`() {
        val verifier = Tls.PinnedHostnameVerifier("btcpay.lan")
        assertTrue(verifier.verify("BTCPay.lan", null))
        assertFalse(verifier.verify("other.lan", null))
        assertFalse(verifier.verify(null, null))
    }

    // --- Probe -------------------------------------------------------------

    @Test
    fun `the pin and the certificate hash match openssl`() {
        val probe = Tls.probeOf(pinned)
        assertEquals(PINNED_PIN, probe.pin)
        assertEquals(PINNED_CERT_SHA256, probe.certificateSha256Hex)
        assertEquals(listOf("pinned.test"), probe.subjectAlternativeNames)
        assertTrue(Tls.fingerprintForDisplay(probe.pin).startsWith("18:88:6D:F0:69:83:"))
    }

    @Test
    fun `a stored pin that is not base64 is displayed as it is`() {
        assertEquals("not base64!", Tls.fingerprintForDisplay("not base64!"))
    }

    @Test
    fun `validity covers exactly notBefore to notAfter`() {
        val probe = probe(notBefore = 1_000, notAfter = 2_000)
        assertTrue(probe.isCurrentlyValid(1_000))
        assertTrue(probe.isCurrentlyValid(1_500))
        assertTrue(probe.isCurrentlyValid(2_000))
        assertFalse(probe.isCurrentlyValid(999))
        assertFalse(probe.isCurrentlyValid(2_001))
        assertTrue(Tls.probeOf(pinned).isCurrentlyValid())
        assertFalse(Tls.probeOf(expired).isCurrentlyValid())
    }

    @Test
    fun `names match case-insensitively and a wildcard covers one label`() {
        val probe = probe(names = listOf("*.example.com", "btcpay.lan", "192.168.1.5"))
        assertTrue(probe.namesHost("a.example.com"))
        assertTrue(probe.namesHost("A.Example.COM"))
        assertTrue(probe.namesHost("btcpay.lan"))
        assertTrue(probe.namesHost("192.168.1.5"))
        assertFalse(probe.namesHost("example.com"))
        assertFalse(probe.namesHost("a.b.example.com"))
        assertFalse(probe.namesHost("other.lan"))
        assertFalse(probe.namesHost(""))
    }

    @Test
    fun `a wildcard never matches an IP address`() {
        assertFalse(probe(names = listOf("*.0.0.1")).namesHost("10.0.0.1"))
    }

    @Test
    fun `the common name counts only when there are no alternative names`() {
        assertTrue(probe(subject = "CN=box.local,O=Home", names = emptyList()).namesHost("box.local"))
        assertFalse(probe(subject = "CN=box.local,O=Home", names = listOf("nas.local")).namesHost("box.local"))
    }

    // --- Local-network hosts ----------------------------------------------

    @Test
    fun `private, loopback, link-local and onion hosts are local`() {
        for (host in listOf(
            "10.0.0.2", "192.168.1.5", "172.20.1.1", "127.0.0.1", "169.254.10.1", "100.64.0.1",
            "fd00::1", "[fe80::1]", "::1", "[::1]",
            "box.local", "nas", "localhost", "xyz.onion", "node.lan", "router.home.arpa", "BOX.LOCAL.",
        )) {
            assertTrue(host, isLocalNetworkHost(host))
        }
    }

    @Test
    fun `public addresses and names are not local`() {
        for (host in listOf(
            "172.32.0.1", "172.15.0.1", "8.8.8.8", "100.128.0.1", "2001:db8::1", "[2606:4700::1]",
            "btcpay.example.com", "10.0.0.2.nip.io", "999.1.1.1", "",
        )) {
            assertFalse(host, isLocalNetworkHost(host))
        }
    }

    // --- Why a handshake failed -------------------------------------------

    @Test
    fun `a wrong name is a hostname mismatch`() {
        assertEquals(TlsProblem.HostnameMismatch, tlsProblemOf(SSLPeerUnverifiedException("Hostname a not verified")))
    }

    @Test
    fun `an expired or not yet valid certificate is expired`() {
        assertEquals(TlsProblem.Expired, tlsProblemOf(SSLException("handshake", CertificateExpiredException("expired"))))
        assertEquals(
            TlsProblem.Expired,
            tlsProblemOf(handshake(CertPathValidatorException("validity check failed", CertificateNotYetValidException()))),
        )
    }

    @Test
    fun `a pin mismatch deep in the chain is a changed key`() {
        assertEquals(TlsProblem.KeyChanged, tlsProblemOf(handshake(CertificateException(PinMismatchException("pin")))))
    }

    @Test
    fun `an unknown authority is an untrusted issuer`() {
        assertEquals(
            TlsProblem.UntrustedIssuer,
            tlsProblemOf(handshake(CertificateException(CertPathValidatorException("Trust anchor for certification path not found.")))),
        )
        assertEquals(
            TlsProblem.UntrustedIssuer,
            tlsProblemOf(SSLHandshakeException("java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.")),
        )
    }

    @Test
    fun `anything else is other`() {
        assertEquals(TlsProblem.Other, tlsProblemOf(SSLHandshakeException("Handshake failed")))
    }

    @Test
    fun `a handshake failure says why in fixed words`() {
        val failure = failureOf(handshake(CertificateException(PinMismatchException("pin mismatch: raw detail"))), requestSent = false)
        assertTrue(failure is HttpFailure.Tls)
        assertEquals(TlsProblem.KeyChanged, (failure as HttpFailure.Tls).problem)
        assertEquals(TlsProblem.KeyChanged.userMessage, failure.message)
        assertFalse(failure.requestSent)
    }

    @Test
    fun `an SSL error after the connection opened is a dropped connection, not a certificate problem`() {
        val failure = failureOf(SSLException("Read error: ssl=0x7b2c: I/O error during system call, Connection reset by peer"), requestSent = true)
        assertTrue(failure is HttpFailure.Transport)
        assertTrue(failure.requestSent)
        assertFalse(failure.message.orEmpty().contains("ssl="))
    }

    // --- Helpers -----------------------------------------------------------

    private fun handshake(cause: Throwable): SSLHandshakeException =
        SSLHandshakeException(cause.toString()).apply { initCause(cause) }

    private fun probe(
        subject: String = "CN=probe.test",
        names: List<String> = emptyList(),
        notBefore: Long = 0,
        notAfter: Long = Long.MAX_VALUE,
    ) = CertificateProbe(
        pin = PINNED_PIN,
        subject = subject,
        notBefore = notBefore,
        notAfter = notAfter,
        subjectAlternativeNames = names,
    )

    private fun certificate(pem: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(pem.trimIndent().byteInputStream()) as X509Certificate

    private val pinned = certificate(
        """
        -----BEGIN CERTIFICATE-----
        MIIBmjCCAUGgAwIBAgIUdwmYdtNjvesb4+CKnTIRAo3R5dMwCgYIKoZIzj0EAwIw
        FjEUMBIGA1UEAwwLcGlubmVkLnRlc3QwIBcNMjAwMTAxMDAwMDAwWhgPMjEwMDAx
        MDEwMDAwMDBaMBYxFDASBgNVBAMMC3Bpbm5lZC50ZXN0MFkwEwYHKoZIzj0CAQYI
        KoZIzj0DAQcDQgAEmSO3UIDta/qiLxTQX/mA5e4tqbf4niXTZk0OXOmZ092s1y+w
        KGsyj8AK81PY2urzgPF7Ph2oQoySeuy6OtcTO6NrMGkwHQYDVR0OBBYEFCsilseA
        3+oL82QczVh7IWHnAw8FMB8GA1UdIwQYMBaAFCsilseA3+oL82QczVh7IWHnAw8F
        MA8GA1UdEwEB/wQFMAMBAf8wFgYDVR0RBA8wDYILcGlubmVkLnRlc3QwCgYIKoZI
        zj0EAwIDRwAwRAIgNokZrLwltTrW303QRvOGIauu1EOnZpMbyDzxYT3iJPsCIB+h
        qdF852l4kBL/I8XcRoEJrxqUYuHZsl1hdxLFYUvC
        -----END CERTIFICATE-----
        """,
    )

    private val other = certificate(
        """
        -----BEGIN CERTIFICATE-----
        MIIBmDCCAT6gAwIBAgIUVy4NX8DU55jop81YUkb8mMBjthUwCgYIKoZIzj0EAwIw
        FTETMBEGA1UEAwwKb3RoZXIudGVzdDAgFw0yMDAxMDEwMDAwMDBaGA8yMTAwMDEw
        MTAwMDAwMFowFTETMBEGA1UEAwwKb3RoZXIudGVzdDBZMBMGByqGSM49AgEGCCqG
        SM49AwEHA0IABEsruYL18oRdxmYt9PwT4qbnUd+V7ghG9R5NKrHTHylgk8V5OAFx
        rogBKm2O0Kb/xHK6JVRzJHfby1iD7EOWP5ejajBoMB0GA1UdDgQWBBSHMIzlrJu9
        QKJLsvvuod/rVSoTBTAfBgNVHSMEGDAWgBSHMIzlrJu9QKJLsvvuod/rVSoTBTAP
        BgNVHRMBAf8EBTADAQH/MBUGA1UdEQQOMAyCCm90aGVyLnRlc3QwCgYIKoZIzj0E
        AwIDSAAwRQIgRJaz5UBuRIoU/yOys5fM+zSN9kG80S9wbyGvzCuEy/MCIQDlmwYh
        oV74zdIMbzdIqWHPcD9fpXYlQuVXMS5A1IU0ZA==
        -----END CERTIFICATE-----
        """,
    )

    /** Valid from 2020-01-01 to 2021-01-01. */
    private val expired = certificate(
        """
        -----BEGIN CERTIFICATE-----
        MIIBnDCCAUKgAwIBAgIUclro5H/nYkkyG47zBfEl6znYXEYwCgYIKoZIzj0EAwIw
        FzEVMBMGA1UEAwwMZXhwaXJlZC50ZXN0MB4XDTIwMDEwMTAwMDAwMFoXDTIxMDEw
        MTAwMDAwMFowFzEVMBMGA1UEAwwMZXhwaXJlZC50ZXN0MFkwEwYHKoZIzj0CAQYI
        KoZIzj0DAQcDQgAE+GY1Sj3Y59fpSHCXlfK0K6fR+PnQc+Hbm8tg82YzQIvQcfN9
        47ETMjbwKlAe92WSdspxnfV0Z7lPo+0KEoXXjKNsMGowHQYDVR0OBBYEFAZ+MJnl
        6/xOPwWPyofUjxTjBhzaMB8GA1UdIwQYMBaAFAZ+MJnl6/xOPwWPyofUjxTjBhza
        MA8GA1UdEwEB/wQFMAMBAf8wFwYDVR0RBBAwDoIMZXhwaXJlZC50ZXN0MAoGCCqG
        SM49BAMCA0gAMEUCIQC9gkUaoGKTWtLKDdYIbPz0JJQJZdBrIRt5aswgC5KcPwIg
        QgmJMPF3HTlcUSWkJ6LmCvP3Mr0RpBHXrkH2KSXAfQM=
        -----END CERTIFICATE-----
        """,
    )

    private companion object {
        /** `openssl x509 -pubkey -noout | openssl pkey -pubin -outform DER | openssl dgst -sha256 -binary | base64` */
        const val PINNED_PIN = "GIht8GmDQ5yBzYNpDqObGb59Ezjk0fkjDzSn0Nt+eBI="

        /** `openssl x509 -outform DER | openssl dgst -sha256` */
        const val PINNED_CERT_SHA256 = "f8d967ee1bc1153632b852aca36407b391f8f42a8c0dda8c55969901cb4d0d10"
    }
}
