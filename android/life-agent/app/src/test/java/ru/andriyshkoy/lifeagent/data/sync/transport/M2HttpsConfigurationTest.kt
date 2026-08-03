package ru.andriyshkoy.lifeagent.data.sync.transport

import java.util.Base64
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.andriyshkoy.lifeagent.data.sync.wire.M2Endpoint

class M2HttpsConfigurationTest {
    @Test
    fun deploymentPresenceDistinguishesAbsentPartialAndUnvalidatedValues() {
        assertEquals(
            M2HttpsDeploymentPresence.ABSENT,
            m2HttpsDeploymentPresence(rawOrigin = "", rawSpkiPins = ""),
        )
        listOf(
            "" to FIRST_PIN,
            SYNTHETIC_ORIGIN to "",
        ).forEach { (origin, pins) ->
            assertEquals(
                M2HttpsDeploymentPresence.PARTIAL,
                m2HttpsDeploymentPresence(origin, pins),
            )
        }
        listOf(
            " " to " ",
            "not-a-url" to "not-a-pin",
            SYNTHETIC_ORIGIN to "$FIRST_PIN,$SECOND_PIN",
        ).forEach { (origin, pins) ->
            assertEquals(
                M2HttpsDeploymentPresence.PRESENT_UNVALIDATED,
                m2HttpsDeploymentPresence(origin, pins),
            )
        }
    }

    @Test
    fun blankAndPartialConfigurationFailsClosed() {
        val validPins = "$FIRST_PIN,$SECOND_PIN"
        val incompleteConfigurations = listOf(
            "" to "",
            "" to validPins,
            " " to validPins,
            SYNTHETIC_ORIGIN to "",
            SYNTHETIC_ORIGIN to " ",
            "https://" to validPins,
            SYNTHETIC_HOST to validPins,
            SYNTHETIC_ORIGIN to FIRST_PIN,
            SYNTHETIC_ORIGIN to "$FIRST_PIN,",
            SYNTHETIC_ORIGIN to ",$SECOND_PIN",
            SYNTHETIC_ORIGIN to "$FIRST_PIN,,$SECOND_PIN",
        )

        incompleteConfigurations.forEach { (origin, pins) ->
            assertRejected(origin, pins)
        }
    }

    @Test
    fun originMustBeAnExactRootHttpsOriginOnTheDefaultPort() {
        val validPins = "$FIRST_PIN,$SECOND_PIN"
        val invalidOrigins = listOf(
            "http://$SYNTHETIC_HOST",
            "https://user@$SYNTHETIC_HOST",
            "https://user:password@$SYNTHETIC_HOST",
            "$SYNTHETIC_ORIGIN?query=value",
            "$SYNTHETIC_ORIGIN#fragment",
            "$SYNTHETIC_ORIGIN/api/v1",
            "$SYNTHETIC_ORIGIN//api/v1",
            "https://$SYNTHETIC_HOST:444",
            " $SYNTHETIC_ORIGIN",
            "$SYNTHETIC_ORIGIN ",
            "not-a-url",
        )

        invalidOrigins.forEach { origin ->
            assertRejected(origin, validPins)
        }
    }

    @Test
    fun malformedPinsAreRejected() {
        val zeroDigestPin = canonicalPin(ByteArray(SHA256_DIGEST_BYTES))
        val malformedPins = listOf(
            "sha1/${zeroDigestPin.substringAfter('/')}",
            "SHA256/${zeroDigestPin.substringAfter('/')}",
            zeroDigestPin.replaceFirst('A', '*'),
            canonicalPin(ByteArray(SHA256_DIGEST_BYTES - 1)),
            canonicalPin(ByteArray(SHA256_DIGEST_BYTES + 1)),
            zeroDigestPin.dropLast(1),
            "$zeroDigestPin=",
            "$FIRST_PIN,malformed",
            " $FIRST_PIN,$SECOND_PIN",
            "$FIRST_PIN,$SECOND_PIN ",
        )

        malformedPins.forEach { pins ->
            assertRejected(SYNTHETIC_ORIGIN, pins)
        }
    }

    @Test
    fun duplicateCanonicalPinsDoNotMeetTheTwoUniquePinMinimum() {
        assertRejected(SYNTHETIC_ORIGIN, "$FIRST_PIN,$FIRST_PIN")
    }

    @Test
    fun duplicateEquivalentNonCanonicalBase64IsRejected() {
        val digest = ByteArray(SHA256_DIGEST_BYTES)
        val canonical = canonicalPin(digest)
        val nonCanonicalEquivalent = canonical.replaceRange(
            canonical.length - 2,
            canonical.length - 1,
            "B",
        )
        assertArrayEquals(
            digest,
            Base64.getDecoder().decode(nonCanonicalEquivalent.substringAfter('/')),
        )

        assertRejected(
            rawOrigin = SYNTHETIC_ORIGIN,
            rawSpkiPins = "$canonical,$nonCanonicalEquivalent",
        )
    }

    @Test
    fun twoOrMoreCanonicalUniquePinsAreAccepted() {
        val twoPins = M2HttpsConfiguration.parse(
            rawOrigin = SYNTHETIC_ORIGIN,
            rawSpkiPins = "$FIRST_PIN,$SECOND_PIN",
        )
        val threeUniquePinsWithWhitespaceAndADuplicate = M2HttpsConfiguration.parse(
            rawOrigin = "$SYNTHETIC_ORIGIN/",
            rawSpkiPins = "$FIRST_PIN, $SECOND_PIN, $THIRD_PIN, $SECOND_PIN",
        )

        listOf(twoPins, threeUniquePinsWithWhitespaceAndADuplicate).forEach { configuration ->
            assertEquals(SYNTHETIC_HOST, configuration.host)
            assertNotNull(configuration.certificatePinner())
        }
    }

    @Test
    fun certificatePinnerAcceptsEitherConfiguredSpkiAndRejectsAnUnrelatedChain() {
        val currentCertificate = syntheticCertificate("current.m2.invalid")
        val backupCertificate = syntheticCertificate("backup.m2.invalid")
        val unrelatedCertificate = syntheticCertificate("unrelated.m2.invalid")
        val configuration = M2HttpsConfiguration.parse(
            rawOrigin = SYNTHETIC_ORIGIN,
            rawSpkiPins = listOf(currentCertificate, backupCertificate).joinToString(",") {
                CertificatePinner.pin(it.certificate)
            },
        )
        val certificatePinner = configuration.certificatePinner()

        certificatePinner.check(
            SYNTHETIC_HOST,
            listOf(unrelatedCertificate.certificate, currentCertificate.certificate),
        )
        certificatePinner.check(
            SYNTHETIC_HOST,
            listOf(unrelatedCertificate.certificate, backupCertificate.certificate),
        )
        assertThrows(SSLPeerUnverifiedException::class.java) {
            certificatePinner.check(
                SYNTHETIC_HOST,
                listOf(unrelatedCertificate.certificate),
            )
        }
    }

    @Test
    fun everyEndpointUsesItsExactContractPath() {
        val configuration = validConfiguration()

        M2Endpoint.entries.forEach { endpoint ->
            val url = configuration.endpointUrl(endpoint)

            assertEquals("$SYNTHETIC_ORIGIN${endpoint.path}", url.toString())
            assertEquals("https", url.scheme)
            assertEquals(SYNTHETIC_HOST, url.host)
            assertEquals(443, url.port)
            assertTrue(url.username.isEmpty())
            assertTrue(url.password.isEmpty())
            assertEquals(endpoint.path, url.encodedPath)
            assertNull(url.query)
            assertNull(url.fragment)
        }
    }

    @Test
    fun diagnosticTextDoesNotExposeTheOriginOrPins() {
        val configuration = validConfiguration()
        val diagnosticText = configuration.toString()

        assertEquals("M2HttpsConfiguration(redacted=true)", diagnosticText)
        assertFalse(diagnosticText.contains(SYNTHETIC_HOST))
        assertFalse(diagnosticText.contains(FIRST_PIN))
        assertFalse(diagnosticText.contains(SECOND_PIN))
    }

    @Test
    fun sharedTransportBundleIsLazyAndCreatedOnlyOnce() {
        val configuration = validConfiguration()
        val client = OkHttpClient()
        val expected = ProductionM2HttpsTransportBundle(
            exact = ExactHttpsTransport(client, configuration),
            auth = OneShotAuthHttpsTransport(client, configuration),
        )
        var creationCount = 0
        val lazyBundle = LazyProductionM2HttpsTransportBundle {
            creationCount += 1
            expected
        }

        assertEquals(0, creationCount)
        assertEquals(
            "LazyProductionM2HttpsTransportBundle(redacted=true)",
            lazyBundle.toString(),
        )
        assertSame(expected, lazyBundle.open())
        assertSame(expected, lazyBundle.open())
        assertEquals(1, creationCount)
        assertEquals(
            "ProductionM2HttpsTransportBundle(redacted=true)",
            expected.toString(),
        )
    }

    private fun validConfiguration(): M2HttpsConfiguration = M2HttpsConfiguration.parse(
        rawOrigin = SYNTHETIC_ORIGIN,
        rawSpkiPins = "$FIRST_PIN,$SECOND_PIN",
    )

    private fun assertRejected(
        rawOrigin: String,
        rawSpkiPins: String,
    ) {
        assertThrows(IllegalArgumentException::class.java) {
            M2HttpsConfiguration.parse(rawOrigin, rawSpkiPins)
        }
    }

    private companion object {
        const val SYNTHETIC_HOST = "m2.invalid"
        const val SYNTHETIC_ORIGIN = "https://$SYNTHETIC_HOST"
        const val SHA256_DIGEST_BYTES = 32

        val FIRST_PIN = syntheticPin(seed = 1)
        val SECOND_PIN = syntheticPin(seed = 37)
        val THIRD_PIN = syntheticPin(seed = 73)

        fun syntheticPin(seed: Int): String = canonicalPin(
            ByteArray(SHA256_DIGEST_BYTES) { index -> (seed + index).toByte() },
        )

        fun canonicalPin(digest: ByteArray): String =
            "sha256/" + Base64.getEncoder().encodeToString(digest)

        fun syntheticCertificate(commonName: String): HeldCertificate =
            HeldCertificate.Builder()
                .commonName(commonName)
                .build()
    }
}
