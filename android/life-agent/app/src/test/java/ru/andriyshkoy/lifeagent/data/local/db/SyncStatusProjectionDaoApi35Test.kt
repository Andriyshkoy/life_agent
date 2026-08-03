package ru.andriyshkoy.lifeagent.data.local.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.andriyshkoy.lifeagent.core.id.MutationIds
import ru.andriyshkoy.lifeagent.core.time.PointTimeResolver
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncAuthAttemptEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncHttpRequestEntity
import ru.andriyshkoy.lifeagent.notes.data.RoomNotesRepository
import ru.andriyshkoy.lifeagent.notes.domain.CreateNoteCommand

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncStatusProjectionDaoApi35Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database: LifeAgentDatabase = LifeAgentDatabaseFactory.createInMemory(context)
    private val notes = RoomNotesRepository(database, collectorVersion = "test")

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun `projection counts pending rows and orders only confirmed exchanges by time`() = runTest {
        val dao = database.syncStatusProjectionDao()
        assertEquals(0, dao.observeProjection().first().pendingCount)

        notes.create(
            CreateNoteCommand(
                ids = MutationIds(uuid(1), uuid(2), uuid(3), uuid(4)),
                text = "локальная заметка",
                effectiveTime = PointTimeResolver.resolveInstant(
                    Instant.parse("2026-08-03T04:00:00Z"),
                    ZoneId.of("Asia/Novosibirsk"),
                ),
                recordedAt = OffsetDateTime.parse("2026-08-03T11:00:00+07:00"),
            ),
        )
        val identity = checkNotNull(database.identityDao().findIdentity())
        database.syncAuthDao().insertAttempt(
            authAttempt(
                requestId = uuid(10).toString(),
                endpointId = "auth_enroll",
                installationId = identity.installationId,
                localOwnerId = identity.localOwnerId,
                updatedAtUtc = "2026-08-03T04:05:06.999Z",
            ),
        )
        database.syncTransportDao().insertRequest(
            terminalRequest(
                requestId = uuid(11).toString(),
                status = 200,
                terminalAtUtc = "2026-08-03T04:05:07Z",
                errorCode = null,
            ),
        )
        database.syncAuthDao().insertAttempt(
            authAttempt(
                requestId = uuid(12).toString(),
                endpointId = "auth_refresh",
                installationId = identity.installationId,
                localOwnerId = identity.localOwnerId,
                updatedAtUtc = "2026-08-03T04:05:07.500Z",
            ),
        )
        database.syncTransportDao().insertRequest(
            terminalRequest(
                requestId = uuid(13).toString(),
                status = 503,
                terminalAtUtc = "2026-08-03T04:05:08Z",
                errorCode = "temporarily_unavailable",
            ),
        )

        val projection = dao.observeProjection().first()

        assertEquals(1, projection.pendingCount)
        assertEquals("completed", projection.latestEnrollmentAttemptState)
        assertEquals(
            "2026-08-03T04:05:07.500Z",
            projection.lastServerConfirmationAtUtc,
        )
    }

    private fun authAttempt(
        requestId: String,
        endpointId: String,
        installationId: String,
        localOwnerId: String,
        updatedAtUtc: String,
    ) = SyncAuthAttemptEntity(
        requestId = requestId,
        endpointId = endpointId,
        installationId = installationId,
        localOwnerId = localOwnerId,
        credentialEpochId = if (endpointId == "auth_refresh") uuid(20).toString() else null,
        expectedDeviceId = if (endpointId == "auth_refresh") uuid(21).toString() else null,
        expectedGeneration = if (endpointId == "auth_refresh") 1 else null,
        state = "completed",
        createdAtUtc = "2026-08-03T04:05:00Z",
        updatedAtUtc = updatedAtUtc,
        lastErrorCode = null,
    )

    private fun terminalRequest(
        requestId: String,
        status: Int,
        terminalAtUtc: String,
        errorCode: String?,
    ): SyncHttpRequestEntity {
        val body = byteArrayOf(1)
        return SyncHttpRequestEntity(
            endpointId = "sync_pull",
            requestIdentity = requestId,
            protocolVersion = "1.0.0",
            credentialEpochId = uuid(20).toString(),
            deviceId = uuid(21).toString(),
            idempotencyKey = null,
            rawRequestBody = body,
            rawBodyHmac = ByteArray(32) { 7 },
            hmacKeyGeneration = 1,
            state = "terminal",
            attemptCount = 1,
            attemptBudget = 8,
            deadlineAtEpochMs = Instant.parse("2026-08-04T00:00:00Z").toEpochMilli(),
            nextAttemptAtEpochMs = null,
            lastAttemptAtEpochMs = Instant.parse("2026-08-03T04:05:00Z").toEpochMilli(),
            leaseExpiresAtEpochMs = null,
            activeAttemptId = null,
            accessGenerationUsed = 1,
            terminalHttpStatus = status,
            exactResponseBody = byteArrayOf(2),
            responseSha256 = "0".repeat(64),
            terminalAtUtc = terminalAtUtc,
            terminalErrorCode = errorCode,
            createdAtUtc = "2026-08-03T04:05:00Z",
            updatedAtUtc = terminalAtUtc,
        )
    }

    private fun uuid(value: Int): UUID = UUID.fromString(
        "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}",
    )
}
