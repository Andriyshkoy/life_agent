package ru.andriyshkoy.lifeagent.persistence

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class SyncRunnableRequestCandidateDaoApi35InstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtures = mutableListOf<SyncM2PersistenceFixture>()

    @After
    fun tearDown() {
        fixtures.asReversed().forEach { fixture ->
            runCatching(fixture::close)
        }
        fixtures.clear()
    }

    @Test
    fun authoritativeRoutePrioritiesAreStable() = runBlocking {
        val revokeFixture = newFixture("runnable-candidate-revoke")
        revokeFixture.seedIdentity(
            deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            personId = SyncM2PersistenceFixture.PERSON_ID,
        )
        revokeFixture.database.syncAuthDao().insertStateRow(
            revokeFixture.authState(state = "revoke_pending"),
        )
        revokeFixture.database.syncTransportDao().insertRequest(
            revokeFixture.sealedRevokeRequest(),
        )

        val revoke = revokeFixture.database.syncTransportDao()
            .findRunnableRequestCandidates(SyncM2PersistenceFixture.NOW_MS, 1)
            .single()
        assertEquals("auth_revoke", revoke.endpointId)
        assertEquals(0, revoke.routePriority)

        val bootstrapFixture = newFixture("runnable-candidate-bootstrap")
        bootstrapFixture.seedIdentity(
            deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            personId = SyncM2PersistenceFixture.PERSON_ID,
        )
        bootstrapFixture.installActiveAuth(bootstrapRequired = true)
        bootstrapFixture.database.syncReplicaDao().insertStreamState(
            bootstrapFixture.streamState(bootstrapRequired = true),
        )
        val bootstrapIntent = bootstrapFixture.bootstrapIntent()
        bootstrapFixture.database.syncReplicaDao().insertBootstrapSession(
            bootstrapIntent.session,
        )
        bootstrapFixture.database.syncTransportDao().insertRequest(
            bootstrapIntent.firstRequest,
        )

        val bootstrap = bootstrapFixture.database.syncTransportDao()
            .findRunnableRequestCandidates(SyncM2PersistenceFixture.NOW_MS, 1)
            .single()
        assertEquals("sync_bootstrap", bootstrap.endpointId)
        assertEquals(1, bootstrap.routePriority)
    }

    @Test
    fun bodyPoisonCannotHideQueueHeadOrStarveNextDueRoute() = runBlocking {
        val fixture = newFixture("runnable-candidate-body-blind")
        fixture.seedIdentity(
            deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            personId = SyncM2PersistenceFixture.PERSON_ID,
        )
        fixture.installActiveAuth()
        fixture.seedIncrementalStream()
        val dao = fixture.database.syncTransportDao()
        val duePushId = "f1000000-0000-4000-8000-000000000001"
        val futurePushId = "01000000-0000-4000-8000-000000000001"
        val duePullId = "01000000-0000-4000-8000-000000000002"
        val sendingPullId = "01000000-0000-4000-8000-000000000003"
        val futureRetryAt = SyncM2PersistenceFixture.NOW_MS + 5_000
        val sendingLeaseAt = SyncM2PersistenceFixture.NOW_MS + 9_000

        dao.insertRequest(
            fixture.request(
                endpointId = "sync_push",
                requestIdentity = duePushId,
            ),
        )
        dao.insertRequest(
            fixture.request(
                endpointId = "sync_push",
                requestIdentity = futurePushId,
                state = "retry_wait",
                attemptCount = 1,
                nextAttemptAtEpochMs = futureRetryAt,
            ),
        )
        dao.insertRequest(
            fixture.request(
                endpointId = "sync_pull",
                requestIdentity = duePullId,
            ),
        )
        dao.insertRequest(
            fixture.request(
                endpointId = "sync_pull",
                requestIdentity = sendingPullId,
                state = "sending",
                attemptCount = 1,
                activeAttemptId = UUID.randomUUID().toString(),
                leaseExpiresAtEpochMs = sendingLeaseAt,
            ),
        )

        fixture.database.openHelper.writableDatabase.apply {
            execSQL("DROP TRIGGER IF EXISTS guard_sync_http_request_body_update")
            execSQL(
                """
                UPDATE sync_http_request
                SET body_storage_kind = 'keystore_aead',
                    raw_request_body = NULL,
                    sealed_body_ciphertext = 'poison-ciphertext',
                    sealed_body_nonce = 'poison-nonce',
                    raw_body_hmac = 'poison-hmac',
                    exact_response_body = 'poison-response'
                WHERE endpoint_id = 'sync_push'
                  AND request_identity = ?
                """.trimIndent(),
                arrayOf(duePushId),
            )
        }

        val firstPage = dao.findRunnableRequestCandidates(
            SyncM2PersistenceFixture.NOW_MS,
            10,
        )
        assertEquals(
            listOf(duePushId, duePullId),
            firstPage.map { it.requestIdentity },
        )
        assertEquals(listOf(2, 3), firstPage.map { it.routePriority })
        assertFalse(firstPage.any { it.requestIdentity == futurePushId })
        assertFalse(firstPage.any { it.requestIdentity == sendingPullId })
        assertEquals(SyncM2PersistenceFixture.NOW_MS, firstPage.first().scheduledAtEpochMs)
        assertFalse(firstPage.first().toString().contains(duePushId))
        assertFalse(
            firstPage.first().toString().contains(SyncM2PersistenceFixture.EPOCH_ID),
        )

        val storageClasses = fixture.database.openHelper.writableDatabase.query(
            """
            SELECT typeof(raw_request_body),
                   typeof(sealed_body_ciphertext),
                   typeof(raw_body_hmac),
                   typeof(exact_response_body)
            FROM sync_http_request
            WHERE endpoint_id = 'sync_push' AND request_identity = ?
            """.trimIndent(),
            arrayOf(duePushId),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            List(4) { index -> cursor.getString(index) }
        }
        assertEquals(listOf("null", "text", "text", "text"), storageClasses)

        fixture.database.openHelper.writableDatabase.execSQL(
            """
            UPDATE sync_http_request
            SET state = 'integrity_failure'
            WHERE endpoint_id = 'sync_push' AND request_identity = ?
            """.trimIndent(),
            arrayOf(duePushId),
        )

        val nextDue = dao.findRunnableRequestCandidates(
            SyncM2PersistenceFixture.NOW_MS,
            1,
        ).single()
        assertEquals(duePullId, nextDue.requestIdentity)
        assertEquals(3, nextDue.routePriority)
        assertEquals(
            SyncM2PersistenceFixture.NOW_MS,
            dao.findEarliestDispatchCandidateAtEpochMs(SyncM2PersistenceFixture.NOW_MS),
        )
        assertEquals(
            sendingLeaseAt,
            dao.findEarliestSendingRecoveryAtEpochMs(SyncM2PersistenceFixture.NOW_MS),
        )
    }

    @Test
    fun expiredSendingLeaseIsRunnableWhileLiveLeaseRemainsOpenAndHidden() = runBlocking {
        val fixture = newFixture("runnable-candidate-expired-lease")
        fixture.seedIdentity(
            deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            personId = SyncM2PersistenceFixture.PERSON_ID,
        )
        fixture.installActiveAuth()
        fixture.seedIncrementalStream()
        val dao = fixture.database.syncTransportDao()
        val expiredPushId = "01000000-0000-4000-8000-000000000004"
        val livePullId = "01000000-0000-4000-8000-000000000005"
        val expiredLeaseAt = SyncM2PersistenceFixture.NOW_MS
        val liveLeaseAt = SyncM2PersistenceFixture.NOW_MS + 5_000

        dao.insertRequest(
            fixture.request(
                endpointId = "sync_push",
                requestIdentity = expiredPushId,
                state = "sending",
                attemptCount = 1,
                activeAttemptId = UUID.randomUUID().toString(),
                leaseExpiresAtEpochMs = expiredLeaseAt,
            ),
        )
        dao.insertRequest(
            fixture.request(
                endpointId = "sync_pull",
                requestIdentity = livePullId,
                state = "sending",
                attemptCount = 1,
                activeAttemptId = UUID.randomUUID().toString(),
                leaseExpiresAtEpochMs = liveLeaseAt,
            ),
        )

        val nowCandidates = dao.findRunnableRequestCandidates(
            SyncM2PersistenceFixture.NOW_MS,
            10,
        )
        assertEquals(listOf(expiredPushId), nowCandidates.map { it.requestIdentity })
        assertEquals("sending", nowCandidates.single().state)
        assertEquals(expiredLeaseAt, nowCandidates.single().scheduledAtEpochMs)
        assertEquals(2L, dao.countOpenRequestRows())

        val afterLiveLeaseExpires = dao.findRunnableRequestCandidates(liveLeaseAt, 10)
        assertEquals(
            listOf(expiredPushId, livePullId),
            afterLiveLeaseExpires.map { it.requestIdentity },
        )
        assertEquals(
            listOf(expiredLeaseAt, liveLeaseAt),
            afterLiveLeaseExpires.map { it.scheduledAtEpochMs },
        )
    }

    private fun newFixture(label: String): SyncM2PersistenceFixture =
        SyncM2PersistenceFixture(context, label).also(fixtures::add)
}
