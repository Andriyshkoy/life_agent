package ru.andriyshkoy.lifeagent.persistence

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.andriyshkoy.lifeagent.data.local.db.SyncAuthPersistenceStore

@RunWith(AndroidJUnit4::class)
class SyncDispatchGuardsInstrumentedTest {
    private lateinit var fixture: SyncM2PersistenceFixture

    @Before
    fun setUp() {
        fixture = SyncM2PersistenceFixture(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            label = "m2-dispatch-guards",
        )
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun exactCurrentFamilyIsRunnableAndClaimUsesGenerationCas() = runBlocking {
        seedIncrementalFamily()
        val requestId = UUID.randomUUID().toString()
        fixture.database.syncTransportDao().insertRequest(
            fixture.request(
                endpointId = "sync_pull",
                requestIdentity = requestId,
            ),
        )

        assertEquals(
            listOf(requestId),
            fixture.database.syncTransportDao()
                .findRunnableRequests(SyncM2PersistenceFixture.NOW_MS, 10)
                .map { it.requestIdentity },
        )
        assertEquals(
            SyncM2PersistenceFixture.NOW_MS,
            fixture.database.syncTransportDao()
                .findEarliestRunnableAtEpochMs(SyncM2PersistenceFixture.NOW_MS),
        )
        assertEquals(
            0,
            claim(
                requestId = requestId,
                generation = 2,
                attemptId = UUID.randomUUID().toString(),
            ),
        )
        assertEquals(
            1,
            claim(
                requestId = requestId,
                generation = 1,
                attemptId = UUID.randomUUID().toString(),
            ),
        )
        assertEquals(
            "sending",
            fixture.database.syncTransportDao()
                .findRequest("sync_pull", requestId)
                ?.state,
        )
    }

    @Test
    fun exactAccessExpiryBoundaryIsNeitherRunnableNorClaimable() = runBlocking {
        fixture.seedIdentity(
            deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            personId = SyncM2PersistenceFixture.PERSON_ID,
        )
        fixture.installActiveAuth(
            accessExpiresAtEpochMs = SyncM2PersistenceFixture.NOW_MS,
        )
        fixture.seedIncrementalStream()
        val requestId = UUID.randomUUID().toString()
        fixture.database.syncTransportDao().insertRequest(
            fixture.request(
                endpointId = "sync_pull",
                requestIdentity = requestId,
            ),
        )

        assertEquals(
            emptyList<Any>(),
            fixture.database.syncTransportDao()
                .findRunnableRequests(SyncM2PersistenceFixture.NOW_MS, 10),
        )
        assertEquals(
            SyncM2PersistenceFixture.DEADLINE_MS,
            fixture.database.syncTransportDao()
                .findEarliestRunnableAtEpochMs(SyncM2PersistenceFixture.NOW_MS),
        )
        assertEquals(
            0,
            claim(
                requestId = requestId,
                generation = 1,
                attemptId = UUID.randomUUID().toString(),
            ),
        )
    }

    @Test
    fun serverPersonMismatchIsNeitherRunnableNorClaimable() = runBlocking {
        fixture.seedIdentity(
            deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            personId = "d5000000-0000-4000-8000-000000000001",
        )
        fixture.installActiveAuth()
        fixture.seedIncrementalStream()
        val requestId = UUID.randomUUID().toString()
        fixture.database.syncTransportDao().insertRequest(
            fixture.request(
                endpointId = "sync_pull",
                requestIdentity = requestId,
            ),
        )

        assertEquals(
            emptyList<Any>(),
            fixture.database.syncTransportDao()
                .findRunnableRequests(SyncM2PersistenceFixture.NOW_MS, 10),
        )
        assertEquals(
            SyncM2PersistenceFixture.DEADLINE_MS,
            fixture.database.syncTransportDao()
                .findEarliestRunnableAtEpochMs(SyncM2PersistenceFixture.NOW_MS),
        )
        assertEquals(
            0,
            claim(
                requestId = requestId,
                generation = 1,
                attemptId = UUID.randomUUID().toString(),
            ),
        )
    }

    @Test
    fun quarantinedFamilyIsNeitherRunnableNorClaimable() = runBlocking {
        fixture.seedIdentity(
            deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            personId = SyncM2PersistenceFixture.PERSON_ID,
        )
        fixture.database.syncAuthDao().insertStateRow(
            fixture.authState(state = "quarantined"),
        )
        fixture.seedIncrementalStream()
        val requestId = UUID.randomUUID().toString()
        fixture.database.syncTransportDao().insertRequest(
            fixture.request(
                endpointId = "sync_pull",
                requestIdentity = requestId,
            ),
        )

        assertEquals(
            emptyList<Any>(),
            fixture.database.syncTransportDao()
                .findRunnableRequests(SyncM2PersistenceFixture.NOW_MS, 10),
        )
        assertEquals(
            SyncM2PersistenceFixture.DEADLINE_MS,
            fixture.database.syncTransportDao()
                .findEarliestRunnableAtEpochMs(SyncM2PersistenceFixture.NOW_MS),
        )
        assertEquals(
            0,
            claim(
                requestId = requestId,
                generation = 1,
                attemptId = UUID.randomUUID().toString(),
            ),
        )
    }

    @Test
    fun integrityHaltedStreamIsNeitherRunnableNorClaimable() = runBlocking {
        fixture.seedIdentity(
            deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            personId = SyncM2PersistenceFixture.PERSON_ID,
        )
        fixture.installActiveAuth()
        fixture.database.syncReplicaDao().insertStreamState(
            fixture.streamState().copy(
                phase = "integrity_halted",
                integrityErrorCode = "fixture_integrity_failure",
            ),
        )
        val requestId = UUID.randomUUID().toString()
        fixture.database.syncTransportDao().insertRequest(
            fixture.request(
                endpointId = "sync_pull",
                requestIdentity = requestId,
            ),
        )

        assertEquals(
            emptyList<Any>(),
            fixture.database.syncTransportDao()
                .findRunnableRequests(SyncM2PersistenceFixture.NOW_MS, 10),
        )
        assertEquals(
            SyncM2PersistenceFixture.DEADLINE_MS,
            fixture.database.syncTransportDao()
                .findEarliestRunnableAtEpochMs(SyncM2PersistenceFixture.NOW_MS),
        )
        assertEquals(
            0,
            claim(
                requestId = requestId,
                generation = 1,
                attemptId = UUID.randomUUID().toString(),
            ),
        )
    }

    @Test
    fun dispatchingReplacementEnrollmentFreezesRunnableSync() = runBlocking {
        seedIncrementalFamily()
        val requestId = UUID.randomUUID().toString()
        fixture.database.syncTransportDao().insertRequest(
            fixture.request(
                endpointId = "sync_pull",
                requestIdentity = requestId,
            ),
        )
        SyncAuthPersistenceStore(fixture.database).beginEnrollment(
            fixture.enrollmentAttempt(
                credentialEpochId = SyncM2PersistenceFixture.EPOCH_ID,
                deviceId = SyncM2PersistenceFixture.DEVICE_ID,
                generation = 1,
            ),
        )

        assertEquals(
            emptyList<Any>(),
            fixture.database.syncTransportDao()
                .findRunnableRequests(SyncM2PersistenceFixture.NOW_MS, 10),
        )
        assertEquals(
            0,
            claim(
                requestId = requestId,
                generation = 1,
                attemptId = UUID.randomUUID().toString(),
            ),
        )
    }

    @Test
    fun bootstrapRequiredPhaseRunsOnlyItsCoherentBootstrapIntent() = runBlocking {
        fixture.seedIdentity(
            deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            personId = SyncM2PersistenceFixture.PERSON_ID,
        )
        fixture.installActiveAuth(bootstrapRequired = true)
        fixture.database.syncReplicaDao().insertStreamState(
            fixture.streamState(bootstrapRequired = true),
        )
        val intent = fixture.bootstrapIntent()
        fixture.database.syncReplicaDao().insertBootstrapSession(intent.session)
        fixture.database.syncTransportDao().insertRequest(intent.firstRequest)
        val staleBootstrap = fixture.request(
            endpointId = "sync_bootstrap",
            requestIdentity = UUID.randomUUID().toString(),
            bootstrapId = UUID.randomUUID().toString(),
        )
        fixture.database.syncTransportDao().insertRequest(staleBootstrap)
        val pullId = UUID.randomUUID().toString()
        fixture.database.syncTransportDao().insertRequest(
            fixture.request(
                endpointId = "sync_pull",
                requestIdentity = pullId,
            ),
        )

        assertEquals(
            listOf(intent.firstRequest.requestIdentity),
            fixture.database.syncTransportDao()
                .findRunnableRequests(SyncM2PersistenceFixture.NOW_MS, 10)
                .map { it.requestIdentity },
        )
        assertEquals(
            0,
            claim(
                endpointId = "sync_bootstrap",
                requestId = staleBootstrap.requestIdentity,
                generation = 1,
                attemptId = UUID.randomUUID().toString(),
            ),
        )
        assertEquals(
            0,
            claim(
                endpointId = "sync_pull",
                requestId = pullId,
                generation = 1,
                attemptId = UUID.randomUUID().toString(),
            ),
        )
        assertEquals(
            1,
            claim(
                endpointId = "sync_bootstrap",
                requestId = intent.firstRequest.requestIdentity,
                generation = 1,
                attemptId = UUID.randomUUID().toString(),
            ),
        )
    }

    @Test
    fun futureMisboundBootstrapDoesNotScheduleAFalseWake() = runBlocking {
        fixture.seedIdentity(
            deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            personId = SyncM2PersistenceFixture.PERSON_ID,
        )
        fixture.installActiveAuth(bootstrapRequired = true)
        fixture.database.syncReplicaDao().insertStreamState(
            fixture.streamState(bootstrapRequired = true),
        )
        val intent = fixture.bootstrapIntent()
        fixture.database.syncReplicaDao().insertBootstrapSession(intent.session)
        val stale = fixture.request(
            endpointId = "sync_bootstrap",
            requestIdentity = UUID.randomUUID().toString(),
            bootstrapId = UUID.randomUUID().toString(),
            state = "retry_wait",
            attemptCount = 1,
            nextAttemptAtEpochMs = SyncM2PersistenceFixture.NOW_MS + 1_000,
        )
        fixture.database.syncTransportDao().insertRequest(stale)

        assertEquals(
            emptyList<Any>(),
            fixture.database.syncTransportDao()
                .findRunnableRequests(
                    SyncM2PersistenceFixture.NOW_MS + 1_000,
                    10,
                ),
        )
        assertEquals(
            SyncM2PersistenceFixture.DEADLINE_MS,
            fixture.database.syncTransportDao()
                .findEarliestRunnableAtEpochMs(SyncM2PersistenceFixture.NOW_MS),
        )
    }

    @Test
    fun bootstrapBindingRejectsStringPageSize() = runBlocking {
        fixture.seedIdentity(
            deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            personId = SyncM2PersistenceFixture.PERSON_ID,
        )
        fixture.installActiveAuth(bootstrapRequired = true)
        fixture.database.syncReplicaDao().insertStreamState(
            fixture.streamState(bootstrapRequired = true),
        )
        val intent = fixture.bootstrapIntent()
        val progressedSession = intent.session.copy(
            nextPageCursor = "7",
            nextPageIndex = 1,
            stagedPageCount = 1,
        )
        fixture.database.syncReplicaDao().insertBootstrapSession(progressedSession)
        val requestId = UUID.randomUUID().toString()
        val malformedBody =
            """
            {"protocol_version":"1.0.0","message_type":"bootstrap_request","request_id":"$requestId","bootstrap_id":"${progressedSession.bootstrapId}","device_id":"${progressedSession.deviceId}","page_size":"100","page_cursor":"7"}
            """.trimIndent().encodeToByteArray()
        val malformed = fixture.request(
            endpointId = "sync_bootstrap",
            requestIdentity = requestId,
            bootstrapId = progressedSession.bootstrapId,
        ).copy(
            rawRequestBody = malformedBody,
            requestBodyOctetCount = malformedBody.size.toLong(),
            rawBodyHmac = ByteArray(32) { 3 },
        )
        fixture.database.syncTransportDao().insertRequest(malformed)

        assertEquals(
            emptyList<Any>(),
            fixture.database.syncTransportDao()
                .findRunnableRequests(SyncM2PersistenceFixture.NOW_MS, 10),
        )
        assertEquals(
            SyncM2PersistenceFixture.DEADLINE_MS,
            fixture.database.syncTransportDao()
                .findEarliestRunnableAtEpochMs(SyncM2PersistenceFixture.NOW_MS),
        )
        assertEquals(
            0,
            claim(
                endpointId = "sync_bootstrap",
                requestId = requestId,
                generation = 1,
                attemptId = UUID.randomUUID().toString(),
            ),
        )
    }

    @Test
    fun bootstrapBindingRejectsNumericPageCursorWhenSessionExpectsStringCursor() = runBlocking {
        fixture.seedIdentity(
            deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            personId = SyncM2PersistenceFixture.PERSON_ID,
        )
        fixture.installActiveAuth(bootstrapRequired = true)
        fixture.database.syncReplicaDao().insertStreamState(
            fixture.streamState(bootstrapRequired = true),
        )
        val intent = fixture.bootstrapIntent()
        val progressedSession = intent.session.copy(
            nextPageCursor = "7",
            nextPageIndex = 1,
            stagedPageCount = 1,
        )
        fixture.database.syncReplicaDao().insertBootstrapSession(progressedSession)
        val requestId = UUID.randomUUID().toString()
        val malformedBody =
            """
            {"protocol_version":"1.0.0","message_type":"bootstrap_request","request_id":"$requestId","bootstrap_id":"${progressedSession.bootstrapId}","device_id":"${progressedSession.deviceId}","page_size":100,"page_cursor":7}
            """.trimIndent().encodeToByteArray()
        val malformed = fixture.request(
            endpointId = "sync_bootstrap",
            requestIdentity = requestId,
            bootstrapId = progressedSession.bootstrapId,
        ).copy(
            rawRequestBody = malformedBody,
            requestBodyOctetCount = malformedBody.size.toLong(),
            rawBodyHmac = ByteArray(32) { 4 },
        )
        fixture.database.syncTransportDao().insertRequest(malformed)

        assertEquals(
            emptyList<Any>(),
            fixture.database.syncTransportDao()
                .findRunnableRequests(SyncM2PersistenceFixture.NOW_MS, 10),
        )
        assertEquals(
            SyncM2PersistenceFixture.DEADLINE_MS,
            fixture.database.syncTransportDao()
                .findEarliestRunnableAtEpochMs(SyncM2PersistenceFixture.NOW_MS),
        )
        assertEquals(
            0,
            claim(
                endpointId = "sync_bootstrap",
                requestId = requestId,
                generation = 1,
                attemptId = UUID.randomUUID().toString(),
            ),
        )
    }

    @Test
    fun expiredSendingLeaseIsDiscoveredAndTakenOverAfterReopen() = runBlocking {
        seedIncrementalFamily()
        val requestId = UUID.randomUUID().toString()
        val oldAttemptId = UUID.randomUUID().toString()
        fixture.database.syncTransportDao().insertRequest(
            fixture.request(
                endpointId = "sync_pull",
                requestIdentity = requestId,
                state = "sending",
                attemptCount = 1,
                activeAttemptId = oldAttemptId,
                leaseExpiresAtEpochMs = SyncM2PersistenceFixture.NOW_MS,
            ),
        )
        fixture.reopen()

        assertEquals(
            listOf(requestId),
            fixture.database.syncTransportDao()
                .findRunnableRequests(SyncM2PersistenceFixture.NOW_MS, 10)
                .map { it.requestIdentity },
        )
        val successorAttemptId = UUID.randomUUID().toString()
        assertEquals(
            1,
            claim(
                requestId = requestId,
                generation = 1,
                attemptId = successorAttemptId,
            ),
        )
        val retained = fixture.database.syncTransportDao()
            .findRequest("sync_pull", requestId)
        assertEquals(2, retained?.attemptCount)
        assertEquals(successorAttemptId, retained?.activeAttemptId)
    }

    private suspend fun seedIncrementalFamily() {
        fixture.seedIdentity(
            deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            personId = SyncM2PersistenceFixture.PERSON_ID,
        )
        fixture.installActiveAuth()
        fixture.seedIncrementalStream()
    }

    private suspend fun claim(
        endpointId: String = "sync_pull",
        requestId: String,
        generation: Long,
        attemptId: String,
    ): Int = fixture.database.syncTransportDao().claimAttempt(
        endpointId = endpointId,
        requestIdentity = requestId,
        credentialEpochId = SyncM2PersistenceFixture.EPOCH_ID,
        accessGenerationUsed = generation,
        attemptId = attemptId,
        attemptedAtEpochMs = SyncM2PersistenceFixture.NOW_MS,
        leaseExpiresAtEpochMs = SyncM2PersistenceFixture.NOW_MS + 60_000,
        updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
    )
}
