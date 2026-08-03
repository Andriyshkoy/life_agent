package ru.andriyshkoy.lifeagent.data.sync.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class WireDurableReplicaApiErrorCodecTest {
    @Test
    fun decodesBootstrapAndPullErrorsWithoutReplicaStreamState() {
        val bootstrapRequest = WireTestFixtures.bootstrapRequest(
            "sync-bootstrap-page-2-request.json",
        )
        val bootstrapError = WireResponseCodec.decodeDurableReplicaApiError(
            httpStatus = 410,
            body = WireTestFixtures.bytes("api-error-cursor-expired.json"),
            expectation = BootstrapApiErrorExpectation(bootstrapRequest),
        )
        assertEquals(bootstrapRequest.requestId, bootstrapError.requestId)
        assertEquals(ApiErrorCode.CURSOR_EXPIRED, bootstrapError.errorCode)
        assertEquals(410, bootstrapError.httpStatus)
        assertFalse(bootstrapError.retryable)

        val pullRequest = WireTestFixtures.pullRequest()
        val pullErrorBody = StrictJson.canonicalBytes(
            pullBootstrapRequiredError(pullRequest.requestId),
        )
        val pullError = WireResponseCodec.decodeDurableReplicaApiError(
            httpStatus = 409,
            body = pullErrorBody,
            expectation = PullApiErrorExpectation(pullRequest),
        )
        assertEquals(pullRequest.requestId, pullError.requestId)
        assertEquals(ApiErrorCode.BOOTSTRAP_REQUIRED, pullError.errorCode)
        assertEquals(409, pullError.httpStatus)
        assertFalse(pullError.retryable)
    }

    @Test
    fun rejectsSuccessStatusWrongEndpointAndCorrelation() {
        val bootstrapRequest = WireTestFixtures.bootstrapRequest(
            "sync-bootstrap-page-2-request.json",
        )
        val cursorExpired = WireTestFixtures.bytes("api-error-cursor-expired.json")
        assertStatusFailure {
            WireResponseCodec.decodeDurableReplicaApiError(
                httpStatus = 200,
                body = cursorExpired,
                expectation = BootstrapApiErrorExpectation(bootstrapRequest),
            )
        }

        val pullRequest = WireTestFixtures.pullRequest()
        assertStatusFailure {
            WireResponseCodec.decodeDurableReplicaApiError(
                httpStatus = 410,
                body = cursorExpired,
                expectation = PullApiErrorExpectation(pullRequest),
            )
        }

        val wrongCorrelation = WireTestFixtures.withProperty(
            pullBootstrapRequiredError(pullRequest.requestId),
            "request_id",
            "11111111-1111-4111-8111-111111111111".asJson(),
        )
        val failure = assertThrows(WireProtocolException::class.java) {
            WireResponseCodec.decodeDurableReplicaApiError(
                httpStatus = 409,
                body = StrictJson.canonicalBytes(wrongCorrelation),
                expectation = PullApiErrorExpectation(pullRequest),
            )
        }
        assertEquals(WireProtocolFailure.CORRELATION_MISMATCH, failure.failure)
    }

    @Test
    fun rejectsEnvelopeSchemaAndTransportBodyStatusMismatch() {
        val request = WireTestFixtures.pullRequest()
        val valid = pullBootstrapRequiredError(request.requestId)
        val unexpectedField = WireJsonObject(
            valid.properties + ("unexpected" to true.asJson()),
        )
        val schemaFailure = assertThrows(WireProtocolException::class.java) {
            WireResponseCodec.decodeDurableReplicaApiError(
                httpStatus = 409,
                body = StrictJson.canonicalBytes(unexpectedField),
                expectation = PullApiErrorExpectation(request),
            )
        }
        assertEquals(WireProtocolFailure.JSON_TRUST_BOUNDARY, schemaFailure.failure)

        val mismatchedStatus = WireTestFixtures.withProperty(
            valid,
            "http_status",
            400.asJson(),
        )
        assertStatusFailure {
            WireResponseCodec.decodeDurableReplicaApiError(
                httpStatus = 409,
                body = StrictJson.canonicalBytes(mismatchedStatus),
                expectation = PullApiErrorExpectation(request),
            )
        }
    }

    @Test
    fun errorExpectationsContainNoSyntheticStreamState() {
        listOf(
            BootstrapApiErrorExpectation::class.java,
            PullApiErrorExpectation::class.java,
        ).forEach { type ->
            assertFalse(
                type.declaredFields.any {
                    it.type == ReplicaStreamValidationState::class.java
                },
            )
        }
    }

    private fun pullBootstrapRequiredError(requestId: String): WireJsonObject {
        val base = WireTestFixtures.objectFrom("api-error-request-id-collision.json")
        return WireJsonObject(
            base.properties + mapOf(
                "request_id" to requestId.asJson(),
                "error_code" to ApiErrorCode.BOOTSTRAP_REQUIRED.wireName.asJson(),
                "http_status" to 409.asJson(),
                "retryable" to false.asJson(),
            ),
        )
    }

    private fun assertStatusFailure(block: () -> Unit) {
        val failure = assertThrows(WireProtocolException::class.java, block)
        assertEquals(WireProtocolFailure.STATUS_ERROR_MISMATCH, failure.failure)
    }
}
