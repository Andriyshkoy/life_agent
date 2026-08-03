package ru.andriyshkoy.lifeagent.data.sync.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class WireDurableRevokeResponseCodecTest {
    @Test
    fun decodesCanonicalEvidenceAndStrictSuccessWithoutRetainingToken() {
        val evidence = WireRequestCodec.decodeDurableRevokeEvidence(
            WireTestFixtures.canonical("auth-revoke-request.json"),
        )
        assertEquals("81000000-0000-4000-8000-000000000003", evidence.requestId)
        assertEquals("91000000-0000-4000-8000-000000000003", evidence.deviceId)
        assertEquals(2L, evidence.generation)
        assertFalse(evidence.toString().contains("lar_"))
        assertFalse(
            evidence.javaClass.declaredFields.any {
                it.name.contains("token", ignoreCase = true)
            },
        )

        val decoded = WireResponseCodec.decodeDurableRevokeResponse(
            httpStatus = 200,
            body = WireTestFixtures.bytes("auth-revoke-response.json"),
            evidence = evidence,
        ) as RevokeSuccess
        assertEquals(evidence.requestId, decoded.requestId)
        assertEquals(evidence.deviceId, decoded.deviceId)
        assertEquals(evidence.generation, decoded.generation)
        assertEquals("2030-01-01T00:20:00Z", decoded.revokedAt)
    }

    @Test
    fun decodesStrictCredentialUnavailableApiError() {
        val evidence = revokeEvidence()
        val errorRoot = WireTestFixtures.withProperty(
            WireTestFixtures.objectFrom("api-error-credential-unavailable.json"),
            "request_id",
            evidence.requestId.asJson(),
        )

        val decoded = WireResponseCodec.decodeDurableRevokeResponse(
            httpStatus = 401,
            body = StrictJson.canonicalBytes(errorRoot),
            evidence = evidence,
        ) as DecodedApiError
        assertEquals(evidence.requestId, decoded.value.requestId)
        assertEquals(ApiErrorCode.CREDENTIAL_UNAVAILABLE, decoded.value.errorCode)
        assertEquals(401, decoded.value.httpStatus)
        assertFalse(decoded.value.retryable)
    }

    @Test
    fun rejectsSuccessCorrelationAndGenerationMismatch() {
        val evidence = revokeEvidence()
        val success = WireTestFixtures.objectFrom("auth-revoke-response.json")
        val wrongCorrelation = WireTestFixtures.withProperty(
            success,
            "request_id",
            "11111111-1111-4111-8111-111111111111".asJson(),
        )
        assertCorrelationFailure {
            WireResponseCodec.decodeDurableRevokeResponse(
                httpStatus = 200,
                body = StrictJson.canonicalBytes(wrongCorrelation),
                evidence = evidence,
            )
        }

        val wrongGeneration = WireTestFixtures.withProperty(
            success,
            "generation",
            (evidence.generation + 1L).asJson(),
        )
        assertCorrelationFailure {
            WireResponseCodec.decodeDurableRevokeResponse(
                httpStatus = 200,
                body = StrictJson.canonicalBytes(wrongGeneration),
                evidence = evidence,
            )
        }
    }

    @Test
    fun rejectsNoncanonicalDurableRequestBytes() {
        val failure = assertThrows(WireProtocolException::class.java) {
            WireRequestCodec.decodeDurableRevokeEvidence(
                WireTestFixtures.bytes("auth-revoke-request.json"),
            )
        }
        assertEquals(WireProtocolFailure.SCHEMA_MISMATCH, failure.failure)
    }

    private fun revokeEvidence(): DurableRevokeEvidence =
        WireRequestCodec.decodeDurableRevokeEvidence(
            WireTestFixtures.canonical("auth-revoke-request.json"),
        )

    private fun assertCorrelationFailure(block: () -> Unit) {
        val failure = assertThrows(WireProtocolException::class.java, block)
        assertEquals(WireProtocolFailure.CORRELATION_MISMATCH, failure.failure)
    }
}
