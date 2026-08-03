package ru.andriyshkoy.lifeagent.persistence

import android.content.Context
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabase
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabaseFactory
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncHttpRequestEntity

@RunWith(AndroidJUnit4::class)
class SyncResponseRouteSnapshotDaoInstrumentedTest {
    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var databaseName: String
    private lateinit var database: LifeAgentDatabase

    @Before
    fun setUp() {
        databaseName = "sync-response-route-snapshot-${UUID.randomUUID()}.db"
        database = LifeAgentDatabaseFactory.create(
            context = context,
            openHelperFactory = FrameworkSQLiteOpenHelperFactory(),
            databaseName = databaseName,
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun canonicalRawRequestIsSafeToHydrateOnlyAfterBodyBlindSnapshot() = runBlocking {
        val request = request()
        val dao = database.syncTransportDao()
        dao.insertRequest(request)

        val snapshot = requireNotNull(
            dao.findResponseRouteSnapshot(
                request.endpointId,
                request.requestIdentity,
                requireNotNull(request.activeAttemptId),
            ),
        )

        assertEquals(request.endpointId, snapshot.endpointId)
        assertEquals(request.requestIdentity, snapshot.requestIdentity)
        assertEquals(request.protocolVersion, snapshot.protocolVersion)
        assertEquals(request.credentialEpochId, snapshot.credentialEpochId)
        assertEquals(request.deviceId, snapshot.deviceId)
        assertNull(snapshot.idempotencyKey)
        assertEquals(SyncHttpRequestEntity.BODY_STORAGE_RAW, snapshot.bodyStorageKind)
        assertEquals("sending", snapshot.state)
        assertEquals(request.activeAttemptId, snapshot.activeAttemptId)
        assertEquals(request.accessGenerationUsed, snapshot.accessGenerationUsed)
        assertEquals(request.attemptCount.toLong(), snapshot.attemptCount)
        assertTrue(snapshot.hasRoomSafeRequiredTextStorage)
        assertTrue(snapshot.hasRoomSafeNullableTextStorage)
        assertTrue(snapshot.hasRoomSafeRequiredIntegerStorage)
        assertTrue(snapshot.hasRoomSafeNullableIntegerStorage)
        assertTrue(snapshot.hasRoomSafeRequiredBlobStorage)
        assertTrue(snapshot.hasRoomSafeNullableBlobStorage)
        assertTrue(snapshot.hasRoomSafeStorageClasses)
        assertTrue(snapshot.hasRoomSafeEntityShape)
        assertTrue(snapshot.hasFreshResponseMetadataShape)
        assertTrue(snapshot.canHydrateRequestEntity)
        val hydrated = requireNotNull(
            dao.findRequest(request.endpointId, request.requestIdentity),
        )
        assertEquals(request.endpointId, hydrated.endpointId)
        assertEquals(request.requestIdentity, hydrated.requestIdentity)
    }

    @Test
    fun malformedDynamicStorageNeverEntersProjectedKotlinFields() = runBlocking {
        val request = request()
        val shapeOnlyRequest = request(
            requestIdentity = UUID.randomUUID().toString(),
            attemptId = UUID.randomUUID().toString(),
        )
        val freshShapeOnlyRequest = request(
            requestIdentity = UUID.randomUUID().toString(),
            attemptId = UUID.randomUUID().toString(),
        )
        val primaryKeyDriftRequest = request(
            requestIdentity = UUID.randomUUID().toString(),
            attemptId = UUID.randomUUID().toString(),
        )
        val wideIntegerRequest = request(
            requestIdentity = UUID.randomUUID().toString(),
            attemptId = UUID.randomUUID().toString(),
        )
        val dao = database.syncTransportDao()
        dao.insertRequest(request)
        dao.insertRequest(shapeOnlyRequest)
        dao.insertRequest(freshShapeOnlyRequest)
        dao.insertRequest(primaryKeyDriftRequest)
        dao.insertRequest(wideIntegerRequest)
        dropRequestUpdateGuard()

        database.openHelper.writableDatabase.execSQL(
            """
            UPDATE sync_http_request
            SET credential_epoch_id = X'0102',
                access_generation_used = 'not-an-integer',
                terminal_http_status = 'not-an-integer',
                exact_response_body = 'not-a-blob',
                response_sha256 = X'0304'
            WHERE endpoint_id = 'sync_pull'
              AND request_identity = ?
            """.trimIndent(),
            arrayOf(request.requestIdentity),
        )

        val malformed = requireNotNull(
            dao.findResponseRouteSnapshot(
                request.endpointId,
                request.requestIdentity,
                requireNotNull(request.activeAttemptId),
            ),
        )
        assertNull(malformed.credentialEpochId)
        assertNull(malformed.accessGenerationUsed)
        assertFalse(malformed.hasRoomSafeRequiredTextStorage)
        assertFalse(malformed.hasRoomSafeNullableTextStorage)
        assertFalse(malformed.hasRoomSafeNullableIntegerStorage)
        assertFalse(malformed.hasRoomSafeNullableBlobStorage)
        assertFalse(malformed.hasRoomSafeStorageClasses)
        assertFalse(malformed.canHydrateRequestEntity)

        database.openHelper.writableDatabase.execSQL(
            """
            UPDATE sync_http_request
            SET raw_request_body = X'01'
            WHERE endpoint_id = 'sync_pull'
              AND request_identity = ?
            """.trimIndent(),
            arrayOf(shapeOnlyRequest.requestIdentity),
        )
        val invalidShape = requireNotNull(
            dao.findResponseRouteSnapshot(
                shapeOnlyRequest.endpointId,
                shapeOnlyRequest.requestIdentity,
                requireNotNull(shapeOnlyRequest.activeAttemptId),
            ),
        )
        assertTrue(invalidShape.hasRoomSafeStorageClasses)
        assertFalse(invalidShape.hasRoomSafeEntityShape)
        assertFalse(invalidShape.canHydrateRequestEntity)

        database.openHelper.writableDatabase.execSQL(
            """
            UPDATE sync_http_request
            SET refresh_attempted = 2
            WHERE endpoint_id = 'sync_pull'
              AND request_identity = ?
            """.trimIndent(),
            arrayOf(freshShapeOnlyRequest.requestIdentity),
        )
        val invalidFreshShape = requireNotNull(
            dao.findResponseRouteSnapshot(
                freshShapeOnlyRequest.endpointId,
                freshShapeOnlyRequest.requestIdentity,
                requireNotNull(freshShapeOnlyRequest.activeAttemptId),
            ),
        )
        assertTrue(invalidFreshShape.hasRoomSafeStorageClasses)
        assertTrue(invalidFreshShape.hasRoomSafeEntityShape)
        assertFalse(invalidFreshShape.hasFreshResponseMetadataShape)
        assertTrue(invalidFreshShape.canHydrateRequestEntity)

        database.openHelper.writableDatabase.execSQL(
            """
            UPDATE sync_http_request
            SET attempt_count = ?,
                attempt_budget = ?
            WHERE endpoint_id = 'sync_pull'
              AND request_identity = ?
            """.trimIndent(),
            arrayOf<Any>(
                Int.MAX_VALUE.toLong() + 1,
                Int.MAX_VALUE.toLong() + 2,
                wideIntegerRequest.requestIdentity,
            ),
        )
        val wideIntegerShape = requireNotNull(
            dao.findResponseRouteSnapshot(
                wideIntegerRequest.endpointId,
                wideIntegerRequest.requestIdentity,
                requireNotNull(wideIntegerRequest.activeAttemptId),
            ),
        )
        assertTrue(wideIntegerShape.hasRoomSafeStorageClasses)
        assertTrue(wideIntegerShape.hasRoomSafeEntityShape)
        assertFalse(wideIntegerShape.hasFreshResponseMetadataShape)

        database.openHelper.writableDatabase.execSQL(
            """
            UPDATE sync_http_request
            SET endpoint_id = ?,
                request_identity = ?
            WHERE endpoint_id = 'sync_pull'
              AND request_identity = ?
            """.trimIndent(),
            arrayOf(
                primaryKeyDriftRequest.endpointId.toByteArray(StandardCharsets.UTF_8),
                primaryKeyDriftRequest.requestIdentity.toByteArray(StandardCharsets.UTF_8),
                primaryKeyDriftRequest.requestIdentity,
            ),
        )
        val primaryKeyDrift = requireNotNull(
            dao.findResponseRouteSnapshot(
                primaryKeyDriftRequest.endpointId,
                primaryKeyDriftRequest.requestIdentity,
                requireNotNull(primaryKeyDriftRequest.activeAttemptId),
            ),
        )
        assertNull(primaryKeyDrift.endpointId)
        assertNull(primaryKeyDrift.requestIdentity)
        assertEquals("sending", primaryKeyDrift.state)
        assertEquals(primaryKeyDriftRequest.activeAttemptId, primaryKeyDrift.activeAttemptId)
        assertFalse(primaryKeyDrift.hasRoomSafeRequiredTextStorage)
        assertFalse(primaryKeyDrift.canHydrateRequestEntity)
    }

    @Test
    fun exactTerminalReplayNeedsNoRequestHmacVerifierOrCallbackMetadataMatch() =
        runBlocking {
            val request = request()
            val responseBody = """{"data":{"accepted":true}}"""
                .toByteArray(StandardCharsets.UTF_8)
            val responseSha256 = sha256(responseBody)
            val dao = database.syncTransportDao()
            dao.insertRequest(request)

            assertEquals(
                1,
                dao.storeTerminalResponse(
                    endpointId = request.endpointId,
                    requestIdentity = request.requestIdentity,
                    expectedAttemptId = requireNotNull(request.activeAttemptId),
                    httpStatus = 200,
                    exactResponseBody = responseBody,
                    responseSha256 = responseSha256,
                    terminalAtUtc = TERMINAL_UTC,
                    terminalErrorCode = null,
                ),
            )

            // This test never creates the request-HMAC Keystore key. Exact
            // terminal replay is compared entirely inside SQLite.
            assertTrue(
                dao.matchesExactTerminalResponse(
                    request.endpointId,
                    request.requestIdentity,
                    200,
                    responseBody,
                    responseSha256,
                ),
            )
            assertFalse(
                dao.matchesExactTerminalResponse(
                    request.endpointId,
                    request.requestIdentity,
                    409,
                    responseBody,
                    responseSha256,
                ),
            )
            assertFalse(
                dao.matchesExactTerminalResponse(
                    request.endpointId,
                    request.requestIdentity,
                    200,
                    "{}".toByteArray(StandardCharsets.UTF_8),
                    responseSha256,
                ),
            )
            assertFalse(
                dao.matchesExactTerminalResponse(
                    request.endpointId,
                    request.requestIdentity,
                    200,
                    responseBody,
                    "0".repeat(64),
                ),
            )

            database.openHelper.writableDatabase.execSQL(
                """
                UPDATE sync_http_request
                SET terminal_at_utc = ?,
                    terminal_error_code = 'later_observation'
                WHERE endpoint_id = 'sync_pull'
                  AND request_identity = ?
                """.trimIndent(),
                arrayOf(LATER_UTC, request.requestIdentity),
            )
            assertTrue(
                dao.matchesExactTerminalResponse(
                    request.endpointId,
                    request.requestIdentity,
                    200,
                    responseBody,
                    responseSha256,
                ),
            )

            database.openHelper.writableDatabase.execSQL(
                """
                UPDATE sync_http_request
                SET terminal_at_utc = X'01'
                WHERE endpoint_id = 'sync_pull'
                  AND request_identity = ?
                """.trimIndent(),
                arrayOf(request.requestIdentity),
            )
            assertFalse(
                dao.matchesExactTerminalResponse(
                    request.endpointId,
                    request.requestIdentity,
                    200,
                    responseBody,
                    responseSha256,
                ),
            )
            responseBody.fill(0)
        }

    @Test
    fun quarantineAcceptsMalformedProvenanceButRejectsCanonicalStaleClaims() =
        runBlocking {
            val corrupted = request()
            val otherEpoch = request(
                requestIdentity = UUID.randomUUID().toString(),
                credentialEpochId = OTHER_EPOCH_ID,
                attemptId = UUID.randomUUID().toString(),
            )
            val otherGeneration = request(
                requestIdentity = UUID.randomUUID().toString(),
                accessGenerationUsed = ACCESS_GENERATION + 1,
                attemptId = UUID.randomUUID().toString(),
            )
            val otherAttempt = request(
                requestIdentity = UUID.randomUUID().toString(),
                attemptId = UUID.randomUUID().toString(),
            )
            val dao = database.syncTransportDao()
            listOf(corrupted, otherEpoch, otherGeneration, otherAttempt)
                .forEach { dao.insertRequest(it) }
            dropRequestUpdateGuard()

            database.openHelper.writableDatabase.execSQL(
                """
                UPDATE sync_http_request
                SET credential_epoch_id = X'0102',
                    access_generation_used = 'tampered',
                    terminal_http_status = 'tampered',
                    exact_response_body = 'tampered',
                    response_sha256 = X'0304'
                WHERE endpoint_id = 'sync_pull'
                  AND request_identity = ?
                """.trimIndent(),
                arrayOf(corrupted.requestIdentity),
            )

            assertEquals(
                1,
                dao.quarantineFreshResponseMetadata(
                    endpointId = corrupted.endpointId,
                    requestIdentity = corrupted.requestIdentity,
                    credentialEpochId = EPOCH_ID,
                    accessGenerationUsed = ACCESS_GENERATION,
                    expectedAttemptId = requireNotNull(corrupted.activeAttemptId),
                    failedAtUtc = FAILURE_UTC,
                    failureCode = RESPONSE_METADATA_INVALID,
                ),
            )
            assertEquals(
                RawQuarantineState(
                    state = "integrity_failure",
                    terminalHttpStatusStorage = "null",
                    exactResponseBodyStorage = "null",
                    responseSha256Storage = "null",
                    terminalAtUtc = FAILURE_UTC,
                    terminalErrorCode = RESPONSE_METADATA_INVALID,
                    activeAttemptId = null,
                    leaseExpiresAtEpochMs = null,
                ),
                readRawQuarantineState(corrupted.requestIdentity),
            )
            assertEquals(
                0,
                dao.quarantineFreshResponseMetadata(
                    endpointId = corrupted.endpointId,
                    requestIdentity = corrupted.requestIdentity,
                    credentialEpochId = EPOCH_ID,
                    accessGenerationUsed = ACCESS_GENERATION,
                    expectedAttemptId = requireNotNull(corrupted.activeAttemptId),
                    failedAtUtc = FAILURE_UTC,
                    failureCode = RESPONSE_METADATA_INVALID,
                ),
            )

            assertEquals(
                0,
                quarantineWithOriginalClaim(otherEpoch),
            )
            assertEquals(
                0,
                quarantineWithOriginalClaim(otherGeneration),
            )
            assertEquals(
                0,
                dao.quarantineFreshResponseMetadata(
                    endpointId = otherAttempt.endpointId,
                    requestIdentity = otherAttempt.requestIdentity,
                    credentialEpochId = EPOCH_ID,
                    accessGenerationUsed = ACCESS_GENERATION,
                    expectedAttemptId = UUID.randomUUID().toString(),
                    failedAtUtc = FAILURE_UTC,
                    failureCode = RESPONSE_METADATA_INVALID,
                ),
            )
            assertEquals(
                "sending",
                dao.findRequest(
                    otherEpoch.endpointId,
                    otherEpoch.requestIdentity,
                )?.state,
            )
            assertEquals(
                "sending",
                dao.findRequest(
                    otherGeneration.endpointId,
                    otherGeneration.requestIdentity,
                )?.state,
            )
            assertEquals(
                "sending",
                dao.findRequest(
                    otherAttempt.endpointId,
                    otherAttempt.requestIdentity,
                )?.state,
            )
        }

    @Test
    fun completedRetryFailureTerminalizesOnlyExhaustedExactAttempt() = runBlocking {
        val budgetExhausted = request(
            requestIdentity = UUID.randomUUID().toString(),
            attemptId = UUID.randomUUID().toString(),
            attemptCount = 8,
            attemptBudget = 8,
        )
        val deadlineExhausted = request(
            requestIdentity = UUID.randomUUID().toString(),
            attemptId = UUID.randomUUID().toString(),
            deadlineAtEpochMs = 1_500_000L,
        )
        val stillRetryable = request(
            requestIdentity = UUID.randomUUID().toString(),
            attemptId = UUID.randomUUID().toString(),
        )
        val dao = database.syncTransportDao()
        listOf(budgetExhausted, deadlineExhausted, stillRetryable)
            .forEach { dao.insertRequest(it) }

        assertEquals(
            1,
            dao.terminalizeCompletedRetryFailure(
                endpointId = budgetExhausted.endpointId,
                requestIdentity = budgetExhausted.requestIdentity,
                expectedAttemptId = requireNotNull(budgetExhausted.activeAttemptId),
                proposedNextAttemptAtEpochMs = 1_400_000L,
                terminalAtUtc = FAILURE_UTC,
                failureCode = "transport_timeout",
            ),
        )
        assertEquals(
            1,
            dao.terminalizeCompletedRetryFailure(
                endpointId = deadlineExhausted.endpointId,
                requestIdentity = deadlineExhausted.requestIdentity,
                expectedAttemptId = requireNotNull(deadlineExhausted.activeAttemptId),
                proposedNextAttemptAtEpochMs = deadlineExhausted.deadlineAtEpochMs,
                terminalAtUtc = FAILURE_UTC,
                failureCode = "transport_timeout",
            ),
        )
        assertEquals(
            0,
            dao.terminalizeCompletedRetryFailure(
                endpointId = stillRetryable.endpointId,
                requestIdentity = stillRetryable.requestIdentity,
                expectedAttemptId = requireNotNull(stillRetryable.activeAttemptId),
                proposedNextAttemptAtEpochMs = 1_500_000L,
                terminalAtUtc = FAILURE_UTC,
                failureCode = "transport_timeout",
            ),
        )
        assertEquals(
            0,
            dao.terminalizeCompletedRetryFailure(
                endpointId = stillRetryable.endpointId,
                requestIdentity = stillRetryable.requestIdentity,
                expectedAttemptId = UUID.randomUUID().toString(),
                proposedNextAttemptAtEpochMs = stillRetryable.deadlineAtEpochMs,
                terminalAtUtc = FAILURE_UTC,
                failureCode = "transport_timeout",
            ),
        )

        listOf(budgetExhausted, deadlineExhausted).forEach { request ->
            val terminal = requireNotNull(
                dao.findRequest(request.endpointId, request.requestIdentity),
            )
            assertEquals("terminal_local", terminal.state)
            assertEquals(FAILURE_UTC, terminal.terminalAtUtc)
            assertEquals("transport_timeout", terminal.terminalErrorCode)
            assertNull(terminal.nextAttemptAtEpochMs)
            assertNull(terminal.leaseExpiresAtEpochMs)
            assertNull(terminal.activeAttemptId)
        }
        assertEquals(
            "sending",
            dao.findRequest(
                stillRetryable.endpointId,
                stillRetryable.requestIdentity,
            )?.state,
        )
    }

    private suspend fun quarantineWithOriginalClaim(request: SyncHttpRequestEntity): Int =
        database.syncTransportDao().quarantineFreshResponseMetadata(
            endpointId = request.endpointId,
            requestIdentity = request.requestIdentity,
            credentialEpochId = EPOCH_ID,
            accessGenerationUsed = ACCESS_GENERATION,
            expectedAttemptId = requireNotNull(request.activeAttemptId),
            failedAtUtc = FAILURE_UTC,
            failureCode = RESPONSE_METADATA_INVALID,
        )

    private fun dropRequestUpdateGuard() {
        database.openHelper.writableDatabase.execSQL(
            "DROP TRIGGER IF EXISTS guard_sync_http_request_body_update",
        )
    }

    private fun readRawQuarantineState(requestIdentity: String): RawQuarantineState =
        database.openHelper.readableDatabase.query(
            """
            SELECT state,
                   typeof(terminal_http_status),
                   typeof(exact_response_body),
                   typeof(response_sha256),
                   terminal_at_utc,
                   terminal_error_code,
                   active_attempt_id,
                   lease_expires_at_epoch_ms
            FROM sync_http_request
            WHERE endpoint_id = 'sync_pull'
              AND request_identity = ?
            """.trimIndent(),
            arrayOf(requestIdentity),
        ).use { cursor ->
            check(cursor.moveToFirst())
            RawQuarantineState(
                state = cursor.getString(0),
                terminalHttpStatusStorage = cursor.getString(1),
                exactResponseBodyStorage = cursor.getString(2),
                responseSha256Storage = cursor.getString(3),
                terminalAtUtc = cursor.getString(4),
                terminalErrorCode = cursor.getString(5),
                activeAttemptId = if (cursor.isNull(6)) null else cursor.getString(6),
                leaseExpiresAtEpochMs = if (cursor.isNull(7)) null else cursor.getLong(7),
            )
        }

    private fun request(
        requestIdentity: String = UUID.randomUUID().toString(),
        credentialEpochId: String = EPOCH_ID,
        accessGenerationUsed: Long = ACCESS_GENERATION,
        attemptId: String = UUID.randomUUID().toString(),
        attemptCount: Int = 1,
        attemptBudget: Int = 8,
        deadlineAtEpochMs: Long = 2_000_000L,
    ): SyncHttpRequestEntity {
        val body = """{"request_id":"$requestIdentity"}"""
            .toByteArray(StandardCharsets.UTF_8)
        return SyncHttpRequestEntity(
            endpointId = "sync_pull",
            requestIdentity = requestIdentity,
            protocolVersion = "1.0.0",
            credentialEpochId = credentialEpochId,
            deviceId = DEVICE_ID,
            idempotencyKey = null,
            rawRequestBody = body,
            rawBodyHmac = ByteArray(32) { 7 },
            hmacKeyGeneration = 1,
            state = "sending",
            attemptCount = attemptCount,
            attemptBudget = attemptBudget,
            deadlineAtEpochMs = deadlineAtEpochMs,
            nextAttemptAtEpochMs = null,
            lastAttemptAtEpochMs = 1_000_000L,
            leaseExpiresAtEpochMs = 1_100_000L,
            activeAttemptId = attemptId,
            accessGenerationUsed = accessGenerationUsed,
            terminalHttpStatus = null,
            exactResponseBody = null,
            responseSha256 = null,
            terminalAtUtc = null,
            terminalErrorCode = null,
            createdAtUtc = CREATED_UTC,
            updatedAtUtc = CREATED_UTC,
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class RawQuarantineState(
        val state: String,
        val terminalHttpStatusStorage: String,
        val exactResponseBodyStorage: String,
        val responseSha256Storage: String,
        val terminalAtUtc: String,
        val terminalErrorCode: String,
        val activeAttemptId: String?,
        val leaseExpiresAtEpochMs: Long?,
    )

    private companion object {
        const val EPOCH_ID = "10000000-0000-4000-8000-000000000001"
        const val OTHER_EPOCH_ID = "10000000-0000-4000-8000-000000000002"
        const val DEVICE_ID = "20000000-0000-4000-8000-000000000001"
        const val ACCESS_GENERATION = 3L
        const val CREATED_UTC = "2030-01-01T00:00:00Z"
        const val TERMINAL_UTC = "2030-01-01T00:00:01Z"
        const val LATER_UTC = "2030-01-01T00:00:02Z"
        const val FAILURE_UTC = "2030-01-01T00:00:03Z"
        const val RESPONSE_METADATA_INVALID = "response_metadata_invalid"
    }
}
