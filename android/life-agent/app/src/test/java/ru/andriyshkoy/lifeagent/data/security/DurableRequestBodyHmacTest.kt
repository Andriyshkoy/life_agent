package ru.andriyshkoy.lifeagent.data.security

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableRequestBodyHmacTest {
    private val keyBytes = ByteArray(32) { it.toByte() }
    private val key = SecretKeySpec(keyBytes, "HmacSHA256")
    private val binding = DurableRequestBodyHmacBinding(
        endpointId = "sync_pull",
        protocolVersion = "1.0.0",
        localCredentialEpochId = "97000000-0000-4000-8000-000000000001",
        deviceId = "93000000-0000-4000-8000-000000000001",
        keyEpoch = 1u,
    )

    @Test
    fun frozenKnownAnswerUsesExactSevenComponentLp64Frame() {
        val actual = DurableRequestBodyHmac.compute(key, binding, "{}".encodeToByteArray())
        assertArrayEquals(
            "0c3f4772038de3b2d0e8a2a2e09c3416552aaaa8cf577b61c3d23165affec0ad"
                .hexBytes(),
            actual,
        )
        actual.fill(0)
    }

    @Test
    fun trackedCrossLanguageHighEpochOracleMatchesFrameAndHmac() {
        val oracleBinding = DurableRequestBodyHmacBinding(
            endpointId = "sync_push",
            protocolVersion = "1.0.0",
            localCredentialEpochId = "97000000-0000-4000-8000-000000000001",
            deviceId = "91000000-0000-4000-8000-000000000003",
            keyEpoch = 0x0102030405060708u,
        )
        val oracleBody = byteArrayOf(0, 0x7b, 0x7d, 0xff.toByte())
        val oracleKey = SecretKeySpec(
            "7773f9f4b309e7ebd87db0a5cf00df495ade68c0081c4ff08da06f91878eb0b3"
                .hexBytes(),
            "HmacSHA256",
        )
        val frame = manualFrame(
            endpointId = oracleBinding.endpointId,
            protocolVersion = oracleBinding.protocolVersion,
            localEpoch = oracleBinding.localCredentialEpochId,
            deviceId = oracleBinding.deviceId,
            keyEpoch = oracleBinding.keyEpoch,
            body = oracleBody,
        )
        assertArrayEquals(
            "88c9baa12c842c66592710a129b3dadfb71df2cbca06a25ca49b7c0e7177a19c"
                .hexBytes(),
            MessageDigest.getInstance("SHA-256").digest(frame),
        )
        val actual = DurableRequestBodyHmac.compute(oracleKey, oracleBinding, oracleBody)
        assertArrayEquals(
            "be3036edf681071c7d8f217c0188d165586adf58a53ae1fe0e1b5ff8bbe55f94"
                .hexBytes(),
            actual,
        )
        frame.fill(0)
        actual.fill(0)
        oracleBody.fill(0)
    }

    @Test
    fun everyFrozenFrameComponentMutationChangesMac() {
        val body = "{}".encodeToByteArray()
        val expected = DurableRequestBodyHmac.compute(key, binding, body)
        val mutatedFrames = listOf(
            manualMac(domain = "$REQUEST_BODY_HMAC_DOMAIN-x"),
            DurableRequestBodyHmac.compute(key, binding.copy(endpointId = "sync_push"), body),
            manualMac(protocolVersion = "1.0.1"),
            DurableRequestBodyHmac.compute(
                key,
                binding.copy(
                    localCredentialEpochId = "97000000-0000-4000-8000-000000000002",
                ),
                body,
            ),
            DurableRequestBodyHmac.compute(
                key,
                binding.copy(deviceId = "93000000-0000-4000-8000-000000000002"),
                body,
            ),
            DurableRequestBodyHmac.compute(key, binding.copy(keyEpoch = ULong.MAX_VALUE), body),
            DurableRequestBodyHmac.compute(key, binding, "{ }".encodeToByteArray()),
        )
        mutatedFrames.forEach { mutated ->
            assertFalse(MessageDigest.isEqual(expected, mutated))
            mutated.fill(0)
        }
        expected.fill(0)
        body.fill(0)
    }

    @Test
    fun lengthPrefixesRemoveConcatenationAmbiguity() {
        val first = manualMac(endpointId = "sync_push", protocolVersion = "1.0.0")
        val second = manualMac(endpointId = "sync_push1", protocolVersion = ".0.0")
        assertFalse(MessageDigest.isEqual(first, second))
        first.fill(0)
        second.fill(0)
    }

    @Test
    fun maxUnsignedEpochEmitsEightFfOctets() {
        val body = "{}".encodeToByteArray()
        val maxBinding = binding.copy(keyEpoch = ULong.MAX_VALUE)
        val expected = manualMac(keyEpoch = ULong.MAX_VALUE)
        val actual = DurableRequestBodyHmac.compute(key, maxBinding, body)
        assertArrayEquals(expected, actual)
        expected.fill(0)
        actual.fill(0)
        body.fill(0)
    }

    @Test
    fun verifyRejectsWrongLengthMutationAndPlainSha256() {
        val body = "{}".encodeToByteArray()
        val expected = DurableRequestBodyHmac.compute(key, binding, body)
        assertTrue(DurableRequestBodyHmac.verify(key, binding, body, expected))
        assertFalse(DurableRequestBodyHmac.verify(key, binding, body, expected.copyOf(31)))
        assertFalse(DurableRequestBodyHmac.verify(key, binding, body, expected.copyOf(33)))
        val flipped = expected.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(DurableRequestBodyHmac.verify(key, binding, body, flipped))
        val plainSha = MessageDigest.getInstance("SHA-256").digest(body)
        assertFalse(DurableRequestBodyHmac.verify(key, binding, body, plainSha))
        expected.fill(0)
        flipped.fill(0)
        plainSha.fill(0)
        body.fill(0)
    }

    @Test
    fun bindingIsClosedCanonicalAndRedacted() {
        assertThrows(IllegalArgumentException::class.java) {
            binding.copy(endpointId = "auth_refresh")
        }
        assertThrows(IllegalArgumentException::class.java) {
            binding.copy(protocolVersion = "01.0.0")
        }
        assertThrows(IllegalArgumentException::class.java) {
            binding.copy(localCredentialEpochId = "00000000-0000-0000-0000-000000000000")
        }
        assertThrows(IllegalArgumentException::class.java) {
            binding.copy(deviceId = "A3000000-0000-4000-8000-000000000001")
        }
        assertThrows(IllegalArgumentException::class.java) {
            binding.copy(keyEpoch = 0u)
        }
        val rendered = binding.toString()
        assertFalse(rendered.contains(binding.localCredentialEpochId))
        assertFalse(rendered.contains(binding.deviceId))
    }

    private fun manualMac(
        domain: String = REQUEST_BODY_HMAC_DOMAIN,
        endpointId: String = binding.endpointId,
        protocolVersion: String = binding.protocolVersion,
        localEpoch: String = binding.localCredentialEpochId,
        deviceId: String = binding.deviceId,
        keyEpoch: ULong = binding.keyEpoch,
        body: ByteArray = "{}".encodeToByteArray(),
    ): ByteArray {
        val frame = manualFrame(
            domain = domain,
            endpointId = endpointId,
            protocolVersion = protocolVersion,
            localEpoch = localEpoch,
            deviceId = deviceId,
            keyEpoch = keyEpoch,
            body = body,
        )
        return try {
            Mac.getInstance("HmacSHA256").run {
                init(key)
                doFinal(frame)
            }
        } finally {
            frame.fill(0)
        }
    }

    private fun manualFrame(
        domain: String = REQUEST_BODY_HMAC_DOMAIN,
        endpointId: String = binding.endpointId,
        protocolVersion: String = binding.protocolVersion,
        localEpoch: String = binding.localCredentialEpochId,
        deviceId: String = binding.deviceId,
        keyEpoch: ULong = binding.keyEpoch,
        body: ByteArray = "{}".encodeToByteArray(),
    ): ByteArray = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                listOf(domain, endpointId, protocolVersion, localEpoch, deviceId).forEach {
                    data.writeFramed(it.toByteArray(StandardCharsets.US_ASCII))
                }
                data.writeFramed(ByteArray(Long.SIZE_BYTES).also { epoch ->
                    for (index in epoch.indices) {
                        epoch[index] = (keyEpoch shr ((7 - index) * 8)).toByte()
                    }
                })
                data.writeFramed(body)
            }
            output.toByteArray()
        }

    private fun DataOutputStream.writeFramed(value: ByteArray) {
        writeLong(value.size.toLong())
        write(value)
    }

    private fun String.hexBytes(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
