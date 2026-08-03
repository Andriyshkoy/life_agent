package ru.andriyshkoy.lifeagent.data.sync.wire

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import ru.andriyshkoy.lifeagent.data.local.db.ReplicaChangeCodec

class WireReplicaPersistenceMapperTest {
    @Test
    fun mapsStrictFixtureIntoReplicaCodecConsumableCanonicalRoots() {
        val request = WireTestFixtures.pullRequest()
        val fresh = WireResponseCodec.decodeFreshReplicaPage(
            httpStatus = 200,
            body = WireTestFixtures.bytes("sync-pull-response.json"),
            expectation = FreshPullPageExpectation(request),
        )
        val mapped = WireReplicaPersistenceMapper.map(
            response = fresh,
            pageIndex = 0,
            terminalAtUtc = LOCAL_TERMINAL_AT,
        )

        val page = (fresh as FreshPullPage).page
        assertEquals(LOCAL_TERMINAL_AT, mapped.receipt.receivedAtUtc)
        mapped.changes.zip(page.changes).forEach { (persistence, wire) ->
            assertEquals(wire.event.receivedAt, persistence.committedAtUtc)
            ReplicaChangeCodec().decode(persistence)
        }
    }

    @Test
    fun mapsBootstrapReceiptAndCompleteServerChangeRootWithoutUsingServerTime() {
        val decoded = FreshBootstrapPage(
            page = BootstrapPageSuccess(
                requestId = REQUEST_ID,
                bootstrapId = BOOTSTRAP_ID,
                deviceId = DEVICE_ID,
                fromPageCursor = null,
                snapshotId = SNAPSHOT_ID,
                pageId = PAGE_ID,
                pageSha256 = PAGE_SHA256,
                changes = listOf(serverChange()),
                nextPageCursor = NEXT_CURSOR,
                incrementalCursor = INCREMENTAL_CURSOR,
                complete = false,
                serverTime = RESPONSE_SERVER_TIME,
            ),
            requestBodySha256 = REQUEST_SHA256,
            responseBodySha256 = RESPONSE_SHA256,
        )

        val mapped = WireReplicaPersistenceMapper.map(
            response = decoded,
            pageIndex = 3,
            terminalAtUtc = LOCAL_TERMINAL_AT,
        )

        assertEquals(PAGE_ID, mapped.receipt.pageId)
        assertEquals(M2Endpoint.SYNC_BOOTSTRAP.endpointId, mapped.receipt.endpointId)
        assertEquals(REQUEST_ID, mapped.receipt.requestIdentity)
        assertEquals(BOOTSTRAP_ID, mapped.receipt.bootstrapId)
        assertEquals(3, mapped.receipt.pageIndex)
        assertEquals(SNAPSHOT_ID, mapped.receipt.snapshotId)
        assertNull(mapped.receipt.fromCursor)
        assertEquals(NEXT_CURSOR, mapped.receipt.nextCursor)
        assertEquals(INCREMENTAL_CURSOR, mapped.receipt.incrementalCursor)
        assertEquals(PAGE_SHA256, mapped.receipt.pageSha256)
        assertEquals(1, mapped.receipt.changeCount)
        assertEquals(false, mapped.receipt.completeOrHasMore)
        assertEquals("staged", mapped.receipt.state)
        assertEquals(SERVER_SEQUENCE, mapped.receipt.firstServerSequence)
        assertEquals(SERVER_SEQUENCE, mapped.receipt.lastServerSequence)
        assertEquals(LOCAL_TERMINAL_AT, mapped.receipt.receivedAtUtc)
        assertNull(mapped.receipt.appliedAtUtc)

        val change = mapped.changes.single()
        assertEquals(EVENT_RECEIVED_AT, change.committedAtUtc)
        assertEquals(EXPECTED_SERVER_CHANGE_JCS, change.changeJcs.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun mapsPullReceiptTimestampsFromLocalTerminalTime() {
        val decoded = FreshPullPage(
            page = PullPageSuccess(
                requestId = REQUEST_ID,
                deviceId = DEVICE_ID,
                fromCursor = FROM_CURSOR,
                pageId = PAGE_ID,
                pageSha256 = PAGE_SHA256,
                changes = listOf(serverChange()),
                nextCursor = NEXT_CURSOR,
                hasMore = true,
                serverTime = RESPONSE_SERVER_TIME,
            ),
            requestBodySha256 = REQUEST_SHA256,
            responseBodySha256 = RESPONSE_SHA256,
        )

        val mapped = WireReplicaPersistenceMapper.map(
            response = decoded,
            pageIndex = 9,
            terminalAtUtc = LOCAL_TERMINAL_AT,
        )

        assertEquals(M2Endpoint.SYNC_PULL.endpointId, mapped.receipt.endpointId)
        assertEquals(REQUEST_ID, mapped.receipt.requestIdentity)
        assertNull(mapped.receipt.bootstrapId)
        assertEquals(9, mapped.receipt.pageIndex)
        assertNull(mapped.receipt.snapshotId)
        assertEquals(FROM_CURSOR, mapped.receipt.fromCursor)
        assertEquals(NEXT_CURSOR, mapped.receipt.nextCursor)
        assertNull(mapped.receipt.incrementalCursor)
        assertEquals(true, mapped.receipt.completeOrHasMore)
        assertEquals("applied", mapped.receipt.state)
        assertEquals(LOCAL_TERMINAL_AT, mapped.receipt.receivedAtUtc)
        assertEquals(LOCAL_TERMINAL_AT, mapped.receipt.appliedAtUtc)
        assertEquals(EVENT_RECEIVED_AT, mapped.changes.single().committedAtUtc)
    }

    @Test
    fun rejectsUntrustedPersistenceContext() {
        val pull = FreshPullPage(
            page = PullPageSuccess(
                requestId = REQUEST_ID,
                deviceId = DEVICE_ID,
                fromCursor = FROM_CURSOR,
                pageId = PAGE_ID,
                pageSha256 = PAGE_SHA256,
                changes = emptyList(),
                nextCursor = FROM_CURSOR,
                hasMore = false,
                serverTime = RESPONSE_SERVER_TIME,
            ),
            requestBodySha256 = REQUEST_SHA256,
            responseBodySha256 = RESPONSE_SHA256,
        )
        assertThrows(IllegalArgumentException::class.java) {
            WireReplicaPersistenceMapper.map(pull, -1, LOCAL_TERMINAL_AT)
        }
        val invalidTime = assertThrows(WireProtocolException::class.java) {
            WireReplicaPersistenceMapper.map(pull, 0, RESPONSE_SERVER_TIME.removeSuffix("Z"))
        }
        assertEquals(WireProtocolFailure.SCHEMA_MISMATCH, invalidTime.failure)
    }

    private fun serverChange(): ServerChangeWire {
        val captureDocument = jsonObjectOf(
            "nested" to jsonObjectOf("z" to true.asJson()),
            "capture_id" to CAPTURE_ID.asJson(),
        )
        val eventDocument = jsonObjectOf(
            "server" to jsonObjectOf(
                "server_sequence" to SERVER_SEQUENCE.asJson(),
                "received_at" to EVENT_RECEIVED_AT.asJson(),
            ),
            "event_id" to EVENT_ID.asJson(),
        )
        return ServerChangeWire(
            serverSequence = SERVER_SEQUENCE,
            resultCode = PushResultCode.APPLIED,
            operationId = OPERATION_ID,
            captureId = CAPTURE_ID,
            eventId = EVENT_ID,
            revisionId = REVISION_ID,
            currentRevisionId = REVISION_ID,
            operationContentSha256 = OPERATION_SHA256,
            capture = M2NoteCaptureWire(
                document = captureDocument,
                captureId = CAPTURE_ID,
                operationId = OPERATION_ID,
                installationId = INSTALLATION_ID,
                localOwnerId = OWNER_ID,
                deviceId = DEVICE_ID,
                persistenceState = "authenticated_ingress",
            ),
            event = M2NoteEventWire(
                document = eventDocument,
                eventId = EVENT_ID,
                revisionId = REVISION_ID,
                revisionNo = 1,
                captureId = CAPTURE_ID,
                operationId = OPERATION_ID,
                installationId = INSTALLATION_ID,
                localOwnerId = OWNER_ID,
                deviceId = DEVICE_ID,
                parentRevisionId = null,
                recordStatus = "active",
                persistenceState = "server_committed",
                serverSequence = SERVER_SEQUENCE,
                receivedAt = EVENT_RECEIVED_AT,
            ),
        )
    }

    private companion object {
        const val REQUEST_ID = "11111111-1111-4111-8111-111111111111"
        const val BOOTSTRAP_ID = "22222222-2222-4222-8222-222222222222"
        const val DEVICE_ID = "33333333-3333-4333-8333-333333333333"
        const val SNAPSHOT_ID = "44444444-4444-4444-8444-444444444444"
        const val PAGE_ID = "55555555-5555-4555-8555-555555555555"
        const val OPERATION_ID = "66666666-6666-4666-8666-666666666666"
        const val CAPTURE_ID = "77777777-7777-4777-8777-777777777777"
        const val EVENT_ID = "88888888-8888-4888-8888-888888888888"
        const val REVISION_ID = "99999999-9999-4999-8999-999999999999"
        const val INSTALLATION_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val OWNER_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val FROM_CURSOR = "from-cursor"
        const val NEXT_CURSOR = "next-cursor"
        const val INCREMENTAL_CURSOR = "incremental-cursor"
        const val SERVER_SEQUENCE = 42L
        const val EVENT_RECEIVED_AT = "2026-07-01T02:03:04.005Z"
        const val LOCAL_TERMINAL_AT = "2026-08-02T03:04:05.006Z"
        const val RESPONSE_SERVER_TIME = "2026-12-31T23:59:59.999Z"
        const val OPERATION_SHA256 =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val PAGE_SHA256 =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val REQUEST_SHA256 =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val RESPONSE_SHA256 =
            "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        const val EXPECTED_SERVER_CHANGE_JCS =
            "{\"capture\":{\"capture_id\":\"$CAPTURE_ID\",\"nested\":{\"z\":true}}," +
                "\"capture_id\":\"$CAPTURE_ID\",\"change_kind\":\"event_revision_committed\"," +
                "\"current_revision_id\":\"$REVISION_ID\",\"event\":{\"event_id\":\"$EVENT_ID\"," +
                "\"server\":{\"received_at\":\"$EVENT_RECEIVED_AT\",\"server_sequence\":42}}," +
                "\"event_id\":\"$EVENT_ID\",\"operation_content_sha256\":\"$OPERATION_SHA256\"," +
                "\"operation_id\":\"$OPERATION_ID\",\"result_code\":\"applied\"," +
                "\"revision_id\":\"$REVISION_ID\",\"server_sequence\":42}"
    }
}
