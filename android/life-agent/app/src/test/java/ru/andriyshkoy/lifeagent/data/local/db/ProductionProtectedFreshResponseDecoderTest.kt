package ru.andriyshkoy.lifeagent.data.local.db

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.andriyshkoy.lifeagent.data.sync.wire.ApiErrorCode
import ru.andriyshkoy.lifeagent.data.sync.wire.M2Endpoint
import ru.andriyshkoy.lifeagent.data.sync.wire.StrictJson
import ru.andriyshkoy.lifeagent.data.sync.wire.WireJsonObject
import ru.andriyshkoy.lifeagent.data.sync.wire.WireTestFixtures
import ru.andriyshkoy.lifeagent.data.sync.wire.asJson
import ru.andriyshkoy.lifeagent.data.sync.wire.requireArray
import ru.andriyshkoy.lifeagent.data.sync.wire.requireString

class ProductionProtectedFreshResponseDecoderTest {
    @Test
    fun mapsFreshPushAcksAndOperationErrorToClosedPersistenceValues() {
        val push = decodeSuccess(
            endpoint = M2Endpoint.SYNC_PUSH,
            requestFixture = "sync-push-batch-request.json",
            responseFixture = "sync-push-batch-response.json",
        ) as ProtectedResponseCommand.PushSuccess

        assertEquals(3, push.results.size)
        val ack = push.results.first() as PushAckPersistence
        assertEquals(0, ack.ordinal)
        assertNull(ack.detailsJcs)
        with(ack.change) {
            assertEquals(1L, serverSequence)
            assertEquals("95000000-0000-4000-8000-000000000001", operationId)
            assertEquals(
                "81411dbf4319ab8d7b4bbfd1dfcac76ab3b212e4fcd9988907248b4a3b55e87b",
                operationContentSha256,
            )
            assertEquals("applied", resultCode)
            assertEquals("94000000-0000-4000-8000-000000000001", captureId)
            assertEquals("92000000-0000-4000-8000-000000000001", eventId)
            assertEquals("93000000-0000-4000-8000-000000000001", revisionId)
            assertEquals(revisionId, currentRevisionId)
            assertEquals("2030-01-01T00:00:01Z", committedAtUtc)
            assertEquals(M2Endpoint.SYNC_PUSH.endpointId, firstEndpointId)
            assertEquals("96000000-0000-4000-8000-000000000001", firstRequestIdentity)
            assertEquals(TERMINAL_AT_UTC, verifiedAtUtc)
        }

        val collision = decodeSuccess(
            endpoint = M2Endpoint.SYNC_PUSH,
            requestFixture = "sync-push-operation-id-collision-request.json",
            responseFixture = "sync-push-operation-id-collision-response.json",
        ) as ProtectedResponseCommand.PushSuccess
        val error = collision.results.single() as PushErrorPersistence
        assertEquals(0, error.ordinal)
        assertEquals("95000000-0000-4000-8000-000000000003", error.operationId)
        assertEquals(
            "0bbdad12596b1978ce90408369cc181a287173e877f33e35629cebdd6b419270",
            error.operationContentSha256,
        )
        assertEquals("operation_id_collision", error.errorCode)
        assertFalse(error.retryable)
        assertArrayEquals("[]".encodeToByteArray(), error.detailsJcs)
    }

    @Test
    fun mapsFreshBootstrapPageToStagedReceiptAndCanonicalChanges() {
        val response = WireTestFixtures.objectFrom("sync-bootstrap-response.json")
        val command = decodeSuccess(
            endpoint = M2Endpoint.SYNC_BOOTSTRAP,
            requestFixture = "sync-bootstrap-request.json",
            responseFixture = "sync-bootstrap-response.json",
            replicaPageIndex = 4,
        ) as ProtectedResponseCommand.BootstrapPage

        with(command.receipt) {
            assertEquals(response.requireString("page_id"), pageId)
            assertEquals(M2Endpoint.SYNC_BOOTSTRAP.endpointId, endpointId)
            assertEquals(response.requireString("request_id"), requestIdentity)
            assertEquals(response.requireString("bootstrap_id"), bootstrapId)
            assertEquals(4, pageIndex)
            assertEquals(response.requireString("snapshot_id"), snapshotId)
            assertNull(fromCursor)
            assertEquals(response.requireString("next_page_cursor"), nextCursor)
            assertEquals(response.requireString("incremental_cursor"), incrementalCursor)
            assertEquals(response.requireString("page_sha256"), pageSha256)
            assertEquals(2, changeCount)
            assertFalse(completeOrHasMore)
            assertEquals("staged", state)
            assertEquals(1L, firstServerSequence)
            assertEquals(2L, lastServerSequence)
            assertEquals(TERMINAL_AT_UTC, receivedAtUtc)
            assertNull(appliedAtUtc)
        }
        assertEquals(2, command.changes.size)
        assertArrayEquals(
            StrictJson.canonicalBytes(
                response.requireArray("changes").elements.first() as WireJsonObject,
            ),
            command.changes.first().changeJcs,
        )
        assertEquals("2030-01-01T00:00:01Z", command.changes.first().committedAtUtc)
    }

    @Test
    fun mapsFreshPullPageToAppliedReceiptAndCanonicalChange() {
        val response = WireTestFixtures.objectFrom("sync-pull-response.json")
        val command = decodeSuccess(
            endpoint = M2Endpoint.SYNC_PULL,
            requestFixture = "sync-pull-request.json",
            responseFixture = "sync-pull-response.json",
            replicaPageIndex = 7,
        ) as ProtectedResponseCommand.PullPage

        with(command.receipt) {
            assertEquals(response.requireString("page_id"), pageId)
            assertEquals(M2Endpoint.SYNC_PULL.endpointId, endpointId)
            assertEquals(response.requireString("request_id"), requestIdentity)
            assertNull(bootstrapId)
            assertEquals(7, pageIndex)
            assertNull(snapshotId)
            assertEquals(response.requireString("from_cursor"), fromCursor)
            assertEquals(response.requireString("next_cursor"), nextCursor)
            assertNull(incrementalCursor)
            assertEquals(response.requireString("page_sha256"), pageSha256)
            assertEquals(1, changeCount)
            assertTrue(completeOrHasMore)
            assertEquals("applied", state)
            assertEquals(4L, firstServerSequence)
            assertEquals(4L, lastServerSequence)
            assertEquals(TERMINAL_AT_UTC, receivedAtUtc)
            assertEquals(TERMINAL_AT_UTC, appliedAtUtc)
        }
        val change = command.changes.single()
        assertEquals(4L, change.serverSequence)
        assertEquals("2030-01-01T01:00:01Z", change.committedAtUtc)
        assertArrayEquals(
            StrictJson.canonicalBytes(
                response.requireArray("changes").elements.single() as WireJsonObject,
            ),
            change.changeJcs,
        )
    }

    @Test
    fun mapsFreshRevokeSuccess() {
        assertSame(
            ProtectedResponseCommand.RevokeSuccess,
            decodeSuccess(
                endpoint = M2Endpoint.AUTH_REVOKE,
                requestFixture = "auth-revoke-request.json",
                responseFixture = "auth-revoke-response.json",
            ),
        )
    }

    @Test
    fun mapsSyncAndRevokeCredentialUnavailableSeparately() {
        assertSame(
            ProtectedResponseCommand.TrustedUnauthorized,
            decodeError(
                endpoint = M2Endpoint.SYNC_PULL,
                requestFixture = "sync-pull-request.json",
                errorCode = ApiErrorCode.CREDENTIAL_UNAVAILABLE,
                httpStatus = 401,
                retryable = false,
                replicaPageIndex = 0,
            ),
        )
        assertSame(
            ProtectedResponseCommand.RevokeCredentialUnavailable,
            decodeError(
                endpoint = M2Endpoint.AUTH_REVOKE,
                requestFixture = "auth-revoke-request.json",
                errorCode = ApiErrorCode.CREDENTIAL_UNAVAILABLE,
                httpStatus = 401,
                retryable = false,
            ),
        )
    }

    @Test
    fun mapsRateLimitAndTemporaryUnavailabilityToRetryableErrors() {
        val rateLimited = decodeError(
            endpoint = M2Endpoint.SYNC_PULL,
            requestFixture = "sync-pull-request.json",
            errorCode = ApiErrorCode.RATE_LIMITED,
            httpStatus = 429,
            retryable = true,
            replicaPageIndex = 0,
        ) as ProtectedResponseCommand.RetryableApiError
        assertEquals(ApiErrorCode.RATE_LIMITED, rateLimited.errorCode)

        val unavailable = decodeError(
            endpoint = M2Endpoint.SYNC_PUSH,
            requestFixture = "sync-push-batch-request.json",
            errorCode = ApiErrorCode.TEMPORARILY_UNAVAILABLE,
            httpStatus = 503,
            retryable = true,
        ) as ProtectedResponseCommand.RetryableApiError
        assertEquals(ApiErrorCode.TEMPORARILY_UNAVAILABLE, unavailable.errorCode)
    }

    @Test
    fun mapsCursorAndBootstrapRecoveryErrors() {
        assertSame(
            ProtectedResponseCommand.CursorInvalid,
            decodeError(
                endpoint = M2Endpoint.SYNC_PULL,
                requestFixture = "sync-pull-request.json",
                errorCode = ApiErrorCode.CURSOR_INVALID,
                httpStatus = 400,
                retryable = false,
                replicaPageIndex = 0,
            ),
        )

        val expired = decodeError(
            endpoint = M2Endpoint.SYNC_BOOTSTRAP,
            requestFixture = "sync-bootstrap-request.json",
            errorCode = ApiErrorCode.CURSOR_EXPIRED,
            httpStatus = 410,
            retryable = false,
            replicaPageIndex = 2,
        ) as ProtectedResponseCommand.BootstrapCursorExpired
        assertEquals("70000000-0000-4000-8000-000000000001", expired.expiredBootstrapId)

        assertSame(
            ProtectedResponseCommand.BootstrapRequired,
            decodeError(
                endpoint = M2Endpoint.SYNC_PULL,
                requestFixture = "sync-pull-request.json",
                errorCode = ApiErrorCode.BOOTSTRAP_REQUIRED,
                httpStatus = 409,
                retryable = false,
                replicaPageIndex = 0,
            ),
        )
    }

    @Test
    fun mapsRepresentativeNonretryableApiErrorToPermanentError() {
        val command = decodeError(
            endpoint = M2Endpoint.SYNC_PULL,
            requestFixture = "sync-pull-request.json",
            errorCode = ApiErrorCode.DEVICE_MISMATCH,
            httpStatus = 403,
            retryable = false,
            replicaPageIndex = 0,
        ) as ProtectedResponseCommand.PermanentApiError

        assertEquals(ApiErrorCode.DEVICE_MISMATCH, command.errorCode)
        assertEquals(ApiErrorCode.DEVICE_MISMATCH.wireName, command.terminalErrorCode)
    }

    private fun decodeSuccess(
        endpoint: M2Endpoint,
        requestFixture: String,
        responseFixture: String,
        replicaPageIndex: Int? = null,
    ): ProtectedResponseCommand = ProductionProtectedFreshResponseDecoder.decode(
        input(
            endpoint = endpoint,
            requestFixture = requestFixture,
            httpStatus = 200,
            responseBody = WireTestFixtures.bytes(responseFixture),
            replicaPageIndex = replicaPageIndex,
        ),
    )

    private fun decodeError(
        endpoint: M2Endpoint,
        requestFixture: String,
        errorCode: ApiErrorCode,
        httpStatus: Int,
        retryable: Boolean,
        replicaPageIndex: Int? = null,
    ): ProtectedResponseCommand {
        val requestIdentity = requestIdentity(endpoint, requestFixture)
        var response = WireTestFixtures.objectFrom("api-error-credential-unavailable.json")
        response = WireTestFixtures.withProperty(
            response,
            "request_id",
            requestIdentity.asJson(),
        )
        response = WireTestFixtures.withProperty(response, "error_code", errorCode.wireName.asJson())
        response = WireTestFixtures.withProperty(response, "http_status", httpStatus.asJson())
        response = WireTestFixtures.withProperty(response, "retryable", retryable.asJson())
        return ProductionProtectedFreshResponseDecoder.decode(
            input(
                endpoint = endpoint,
                requestFixture = requestFixture,
                httpStatus = httpStatus,
                responseBody = StrictJson.canonicalBytes(response),
                replicaPageIndex = replicaPageIndex,
            ),
        )
    }

    private fun input(
        endpoint: M2Endpoint,
        requestFixture: String,
        httpStatus: Int,
        responseBody: ByteArray,
        replicaPageIndex: Int?,
    ) = ProtectedFreshResponseInput(
        endpoint = endpoint,
        requestIdentity = requestIdentity(endpoint, requestFixture),
        httpStatus = httpStatus,
        retryAfterSeconds = if (httpStatus == 429) 17 else null,
        terminalAtUtc = TERMINAL_AT_UTC,
        replicaPageIndex = replicaPageIndex,
        exactRequestBody = WireTestFixtures.canonical(requestFixture),
        exactResponseBody = responseBody,
    )

    private fun requestIdentity(endpoint: M2Endpoint, requestFixture: String): String {
        val root = WireTestFixtures.objectFrom(requestFixture)
        return root.requireString(
            if (endpoint == M2Endpoint.SYNC_PUSH) "batch_id" else "request_id",
        )
    }

    private companion object {
        const val TERMINAL_AT_UTC = "2031-02-03T04:05:06Z"
    }
}
