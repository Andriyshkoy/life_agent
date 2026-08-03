package ru.andriyshkoy.lifeagent.data.security

import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialTokenFingerprintHmacContractTest {
    private val key = SecretKeySpec(
        ByteArray(32) { index -> (0xff - index).toByte() },
        "HmacSHA256",
    )

    @Test
    fun frozenSharedDomainFingerprintsDecodedPayloadAndExcludesKindPrefix() {
        val payload = ByteArray(32) { it.toByte() }
        val accessToken = token("laa_", payload)
        val refreshToken = token("lar_", payload)
        val retainedAccess = accessToken.copyOf()
        val retainedRefresh = refreshToken.copyOf()
        val access = CredentialTokenFingerprintHmac.compute(
            key,
            CredentialTokenKind.ACCESS,
            accessToken,
        )
        val refresh = CredentialTokenFingerprintHmac.compute(
            key,
            CredentialTokenKind.REFRESH,
            refreshToken,
        )

        try {
            assertEquals(32, access.size)
            assertArrayEquals(
                "ea8993c417092e762ca0034da87f9df9cda73ff9dc53d83e9ae13faafcf35632"
                    .hexBytes(),
                access,
            )
            assertArrayEquals(access, refresh)
            assertArrayEquals(retainedAccess, accessToken)
            assertArrayEquals(retainedRefresh, refreshToken)
        } finally {
            access.fill(0)
            refresh.fill(0)
            retainedAccess.fill(0)
            retainedRefresh.fill(0)
            accessToken.fill(0)
            refreshToken.fill(0)
            payload.fill(0)
        }
    }

    @Test
    fun differentRandomPayloadProducesDifferentFingerprint() {
        val firstPayload = ByteArray(32) { it.toByte() }
        val secondPayload = firstPayload.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        val firstToken = token("laa_", firstPayload)
        val secondToken = token("laa_", secondPayload)
        val first = CredentialTokenFingerprintHmac.compute(
            key,
            CredentialTokenKind.ACCESS,
            firstToken,
        )
        val second = CredentialTokenFingerprintHmac.compute(
            key,
            CredentialTokenKind.ACCESS,
            secondToken,
        )

        try {
            assertFalse(first.contentEquals(second))
        } finally {
            first.fill(0)
            second.fill(0)
            firstToken.fill(0)
            secondToken.fill(0)
            firstPayload.fill(0)
            secondPayload.fill(0)
        }
    }

    @Test
    fun verificationAcceptsOnlyExactFixedLengthFingerprint() {
        val payload = ByteArray(32) { (it * 3).toByte() }
        val token = token("lar_", payload)
        val expected = CredentialTokenFingerprintHmac.compute(
            key,
            CredentialTokenKind.REFRESH,
            token,
        )
        val changed = expected.copyOf().also {
            it[0] = (it[0].toInt() xor 1).toByte()
        }

        try {
            assertTrue(
                CredentialTokenFingerprintHmac.verify(
                    key,
                    CredentialTokenKind.REFRESH,
                    token,
                    expected,
                ),
            )
            assertFalse(
                CredentialTokenFingerprintHmac.verify(
                    key,
                    CredentialTokenKind.REFRESH,
                    token,
                    changed,
                ),
            )
            assertFalse(
                CredentialTokenFingerprintHmac.verify(
                    key,
                    CredentialTokenKind.REFRESH,
                    token,
                    expected.copyOf(31),
                ),
            )
            assertFalse(
                CredentialTokenFingerprintHmac.verify(
                    key,
                    CredentialTokenKind.REFRESH,
                    token,
                    expected.copyOf(33),
                ),
            )
        } finally {
            expected.fill(0)
            changed.fill(0)
            token.fill(0)
            payload.fill(0)
        }
    }

    @Test
    fun strictByteGrammarRejectsWrongKindAlphabetLengthAndFinalBits() {
        val payload = ByteArray(32) { (it + 17).toByte() }
        val validAccess = token("laa_", payload)
        val validRefresh = token("lar_", payload)
        val nonCanonicalFinalBits = validAccess.copyOf().also { bytes ->
            val canonicalValue = base64UrlValue(bytes.last())
            bytes[bytes.lastIndex] = BASE64URL_ALPHABET[canonicalValue + 1].code.toByte()
        }
        val cases = listOf(
            CredentialTokenKind.ACCESS to validRefresh.copyOf(),
            CredentialTokenKind.REFRESH to validAccess.copyOf(),
            CredentialTokenKind.ACCESS to validAccess.copyOf(46),
            CredentialTokenKind.ACCESS to validAccess.copyOf(48),
            CredentialTokenKind.ACCESS to validAccess.copyOf().also { it[0] = 'L'.code.toByte() },
            CredentialTokenKind.ACCESS to validAccess.copyOf().also { it[9] = '+'.code.toByte() },
            CredentialTokenKind.ACCESS to validAccess.copyOf().also { it[9] = 0x80.toByte() },
            CredentialTokenKind.ACCESS to validAccess.copyOf().also { it[it.lastIndex] = '='.code.toByte() },
            CredentialTokenKind.ACCESS to nonCanonicalFinalBits,
        )

        try {
            cases.forEach { (kind, malformed) ->
                val retained = malformed.copyOf()
                val rendered = malformed.toString(StandardCharsets.ISO_8859_1)
                val failure = assertThrows(CredentialTokenFormatException::class.java) {
                    CredentialTokenFingerprintHmac.compute(key, kind, malformed)
                }
                assertArrayEquals(retained, malformed)
                assertFalse(failure.toString().contains(rendered))
                retained.fill(0)
            }
        } finally {
            cases.forEach { it.second.fill(0) }
            validAccess.fill(0)
            validRefresh.fill(0)
            payload.fill(0)
        }
    }

    private fun token(prefix: String, payload: ByteArray): ByteArray =
        (prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(payload))
            .toByteArray(StandardCharsets.US_ASCII)

    private fun base64UrlValue(byte: Byte): Int =
        BASE64URL_ALPHABET.indexOf(byte.toInt().toChar())

    private fun String.hexBytes(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private companion object {
        const val BASE64URL_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    }
}
