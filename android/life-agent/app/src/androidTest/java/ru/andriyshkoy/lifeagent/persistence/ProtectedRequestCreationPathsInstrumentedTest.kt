package ru.andriyshkoy.lifeagent.persistence

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.andriyshkoy.lifeagent.core.id.MutationIds
import ru.andriyshkoy.lifeagent.core.time.PointTimeResolver
import ru.andriyshkoy.lifeagent.data.local.db.EnrollmentSuccessPersistence
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedRequestClaim
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedSyncRequestStore
import ru.andriyshkoy.lifeagent.data.local.db.ReplicaChangePersistence
import ru.andriyshkoy.lifeagent.data.local.db.SyncAuthPersistenceStore
import ru.andriyshkoy.lifeagent.data.local.db.SyncPersistenceStore
import ru.andriyshkoy.lifeagent.data.local.db.TerminalHttpResponsePersistence
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncBootstrapSessionEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncHttpRequestEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPageReceiptEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPushBatchEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPushBatchItemEntity
import ru.andriyshkoy.lifeagent.data.security.DurableSyncRequestProtector
import ru.andriyshkoy.lifeagent.data.security.DurableSyncRequestVerifier
import ru.andriyshkoy.lifeagent.data.security.KeystoreRequestBodyHmacKeyring
import ru.andriyshkoy.lifeagent.data.security.NewDurableRequestPersistence
import ru.andriyshkoy.lifeagent.data.security.revokeAeadAlias
import ru.andriyshkoy.lifeagent.data.sync.wire.BootstrapRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.M2WireRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.PushBatchRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.RevokeRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.WipeableSecret
import ru.andriyshkoy.lifeagent.data.sync.wire.WireRequestCodec
import ru.andriyshkoy.lifeagent.data.local.serialization.CanonicalNoteCodec
import ru.andriyshkoy.lifeagent.notes.data.RoomNotesRepository
import ru.andriyshkoy.lifeagent.notes.domain.CreateNoteCommand

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class ProtectedRequestCreationPathsInstrumentedTest {
    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtures = mutableListOf<SyncM2PersistenceFixture>()
    private val revokeAliases = mutableSetOf<String>()
    private lateinit var testId: String
    private lateinit var hmacAlias: String
    private lateinit var markerRelativePath: String

    @Before
    fun setUp() {
        testId = UUID.randomUUID().toString()
        hmacAlias = "life_agent_test_creation_hmac_$testId"
        markerRelativePath = "crypto-tests/creation-hmac-$testId.marker"
    }

    @After
    fun tearDown() {
        fixtures.asReversed().forEach { fixture -> runCatching(fixture::close) }
        fixtures.clear()
        deleteMarkerArtifacts()
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
            (revokeAliases + hmacAlias).forEach { alias ->
                if (containsAlias(alias)) deleteEntry(alias)
            }
        }
        revokeAliases.clear()
    }

    @Test
    fun persistPushProtectsCanonicalRowsAndBootstrapRequiredReplayIsSideEffectFree() =
        runBlocking {
            val (fixture, push) = newPushFixture("protected-push-bootstrap-required")
            val store = protectedStore(fixture)

            store.persistPush(
                request = push.request,
                persistence = persistence(),
                batch = push.batch,
                items = push.items,
            )
            assertRawProtectedRequest(
                fixture = fixture,
                endpointId = "sync_push",
                requestIdentity = push.request.batchId,
                exactBody = push.exactBody,
            )
            fixture.reopen()
            assertVerifiedExact(
                fixture.database.syncTransportDao().findRequest(
                    "sync_push",
                    push.request.batchId,
                ),
                push.exactBody,
            )

            val attemptId = claimRequest(
                fixture = fixture,
                endpointId = "sync_push",
                requestIdentity = push.request.batchId,
            )
            val response = terminalResponse(
                endpointId = "sync_push",
                requestIdentity = push.request.batchId,
                attemptId = attemptId,
                status = 409,
                body = "{\"error\":\"bootstrap_required\"}",
                terminalAtUtc = T1,
                errorCode = "bootstrap_required",
            )
            val staleIntent = fixture.bootstrapIntent(
                deviceId = PUSH_DEVICE_ID,
                bootstrapId = uuid(405),
                requestId = uuid(406),
            )
            val staleRequest = BootstrapRequest(
                requestId = staleIntent.firstRequest.requestIdentity,
                bootstrapId = staleIntent.session.bootstrapId,
                deviceId = PUSH_DEVICE_ID,
                pageSize = 100,
                pageCursor = null,
            )
            assertFalse(
                protectedStore(fixture).commitPushBootstrapRequired(
                    response = response.copy(expectedAttemptId = uuid(407)),
                    session = staleIntent.session,
                    bootstrapRequest = staleRequest,
                    persistence = persistence(),
                ),
            )
            assertEquals(
                "sending",
                fixture.database.syncTransportDao()
                    .findRequest("sync_push", push.request.batchId)?.state,
            )
            assertNull(
                fixture.database.syncTransportDao()
                    .findRequest("sync_bootstrap", staleRequest.requestId),
            )
            assertNull(
                fixture.database.syncReplicaDao()
                    .findBootstrapSession(staleIntent.session.bootstrapId),
            )
            val intent = fixture.bootstrapIntent(
                deviceId = PUSH_DEVICE_ID,
                bootstrapId = uuid(401),
                requestId = uuid(402),
            )
            val firstPage = BootstrapRequest(
                requestId = intent.firstRequest.requestIdentity,
                bootstrapId = intent.session.bootstrapId,
                deviceId = PUSH_DEVICE_ID,
                pageSize = 100,
                pageCursor = null,
            )
            val firstPageBody = exactBody(firstPage)

            assertTrue(
                protectedStore(fixture).commitPushBootstrapRequired(
                    response = response,
                    session = intent.session,
                    bootstrapRequest = firstPage,
                    persistence = persistence(),
                ),
            )
            assertEquals(
                "terminal",
                fixture.database.syncTransportDao()
                    .findRequest("sync_push", push.request.batchId)?.state,
            )
            assertRawProtectedRequest(
                fixture = fixture,
                endpointId = "sync_bootstrap",
                requestIdentity = firstPage.requestId,
                exactBody = firstPageBody,
            )
            assertTrue(fixture.database.syncAuthDao().findState()?.bootstrapRequired == true)
            assertEquals(
                "bootstrap_required",
                fixture.database.syncReplicaDao().findStreamState()?.phase,
            )

            // Exact terminal replay must return before both the existing-candidate
            // verifier and the lazy request factory. A bad retained tag would be
            // quarantined if the verifier were reached.
            fixture.database.openHelper.writableDatabase.execSQL(
                """
                UPDATE sync_http_request
                SET raw_body_hmac = zeroblob(32)
                WHERE endpoint_id = 'sync_bootstrap' AND request_identity = ?
                """.trimIndent(),
                arrayOf(firstPage.requestId),
            )
            val unusedIntent = fixture.bootstrapIntent(
                deviceId = PUSH_DEVICE_ID,
                bootstrapId = uuid(403),
                requestId = uuid(404),
            )
            val unusedRequest = BootstrapRequest(
                requestId = unusedIntent.firstRequest.requestIdentity,
                bootstrapId = unusedIntent.session.bootstrapId,
                deviceId = PUSH_DEVICE_ID,
                pageSize = 100,
                pageCursor = null,
            )
            assertFalse(
                protectedStore(fixture).commitPushBootstrapRequired(
                    response = response,
                    session = unusedIntent.session,
                    bootstrapRequest = unusedRequest,
                    persistence = persistence(),
                ),
            )
            assertEquals(
                "ready",
                fixture.database.syncTransportDao()
                    .findRequest("sync_bootstrap", firstPage.requestId)?.state,
            )
            assertNull(
                fixture.database.syncTransportDao()
                    .findRequest("sync_bootstrap", unusedRequest.requestId),
            )
            assertNull(
                fixture.database.syncReplicaDao()
                    .findBootstrapSession(unusedIntent.session.bootstrapId),
            )

            firstPageBody.fill(0)
            push.exactBody.fill(0)
        }

    @Test
    fun beginRevokeSealsExactBodyAndRejectsIneligibleFamilyBeforeAliasCreation() =
        runBlocking {
            val fixture = newActiveFixture("protected-revoke", bootstrapRequired = false)
            val rejectedId = uuid(501)
            val rejectedAlias = revokeAeadAlias(
                SyncM2PersistenceFixture.EPOCH_ID,
                rejectedId,
            )
            revokeAliases += rejectedAlias
            val rejected = RevokeRequest(
                requestId = rejectedId,
                deviceId = SyncM2PersistenceFixture.DEVICE_ID,
                generation = 2,
                refreshToken = refreshToken(),
            )
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    protectedStore(fixture).beginRevoke(
                        request = rejected,
                        persistence = persistence(accessGeneration = 1),
                        nowEpochMs = SyncM2PersistenceFixture.NOW_MS,
                        updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
                    )
                }
            }
            assertFalse(keystoreContains(rejectedAlias))
            assertNull(
                fixture.database.syncTransportDao()
                    .findRequest("auth_revoke", rejectedId),
            )
            assertEquals("active", fixture.database.syncAuthDao().findState()?.state)

            val request = RevokeRequest(
                requestId = uuid(502),
                deviceId = SyncM2PersistenceFixture.DEVICE_ID,
                generation = 1,
                refreshToken = refreshToken(),
            )
            val body = exactBody(request)
            val alias = revokeAeadAlias(
                SyncM2PersistenceFixture.EPOCH_ID,
                request.requestId,
            )
            revokeAliases += alias
            protectedStore(fixture).beginRevoke(
                request = request,
                persistence = persistence(),
                nowEpochMs = SyncM2PersistenceFixture.NOW_MS,
                updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
            )
            val stored = requireNotNull(
                fixture.database.syncTransportDao()
                    .findRequest("auth_revoke", request.requestId),
            )
            assertEquals(SyncHttpRequestEntity.BODY_STORAGE_KEYSTORE_AEAD, stored.bodyStorageKind)
            assertNull(stored.rawRequestBody)
            assertNotNull(stored.sealedBodyCiphertext)
            assertEquals(alias, stored.sealedBodyKeyAlias)
            assertTrue(keystoreContains(alias))
            assertEquals("revoke_pending", fixture.database.syncAuthDao().findState()?.state)

            fixture.reopen()
            assertVerifiedExact(
                fixture.database.syncTransportDao()
                    .findRequest("auth_revoke", request.requestId),
                body,
            )
            body.fill(0)
        }

    @Test
    fun enrollmentCommitsProtectedBootstrapAndRollsBackMismatchedIntent() = runBlocking {
        val fixture = newFixture("protected-enrollment")
        fixture.seedIdentity()
        val authStore = SyncAuthPersistenceStore(fixture.database)
        val attempt = fixture.enrollmentAttempt()
        authStore.beginEnrollment(attempt)
        val intent = fixture.bootstrapIntent(bootstrapId = uuid(601), requestId = uuid(602))
        val bundle = enrollmentBundle(fixture, attempt.requestId, intent.session)

        val wrongRequest = BootstrapRequest(
            requestId = uuid(603),
            bootstrapId = uuid(604),
            deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            pageSize = 100,
            pageCursor = null,
        )
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                protectedStore(fixture).commitEnrollmentSuccess(
                    bundle = bundle,
                    bootstrapRequest = wrongRequest,
                    persistence = persistence(),
                )
            }
        }
        assertNull(fixture.database.syncAuthDao().findState())
        assertNull(fixture.database.syncReplicaDao().findStreamState())
        assertNull(
            fixture.database.syncReplicaDao().findBootstrapSession(intent.session.bootstrapId),
        )
        assertNull(
            fixture.database.syncTransportDao()
                .findRequest("sync_bootstrap", wrongRequest.requestId),
        )
        assertEquals(
            "dispatching",
            fixture.database.syncAuthDao().findAttempt(attempt.requestId)?.state,
        )

        val request = BootstrapRequest(
            requestId = intent.firstRequest.requestIdentity,
            bootstrapId = intent.session.bootstrapId,
            deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            pageSize = 100,
            pageCursor = null,
        )
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                protectedStore(fixture).commitEnrollmentSuccess(
                    bundle = bundle,
                    bootstrapRequest = request,
                    persistence = persistence(accessGeneration = 2),
                )
            }
        }
        assertNull(fixture.database.syncAuthDao().findState())
        assertNull(fixture.database.syncReplicaDao().findStreamState())
        assertNull(
            fixture.database.syncReplicaDao().findBootstrapSession(intent.session.bootstrapId),
        )
        assertNull(
            fixture.database.syncTransportDao()
                .findRequest("sync_bootstrap", request.requestId),
        )
        assertEquals(
            "dispatching",
            fixture.database.syncAuthDao().findAttempt(attempt.requestId)?.state,
        )

        val body = exactBody(request)
        protectedStore(fixture).commitEnrollmentSuccess(
            bundle = bundle,
            bootstrapRequest = request,
            persistence = persistence(),
        )
        assertEquals("completed", fixture.database.syncAuthDao().findAttempt(attempt.requestId)?.state)
        assertRawProtectedRequest(
            fixture = fixture,
            endpointId = "sync_bootstrap",
            requestIdentity = request.requestId,
            exactBody = body,
        )
        fixture.reopen()
        assertVerifiedExact(
            fixture.database.syncTransportDao()
                .findRequest("sync_bootstrap", request.requestId),
            body,
        )
        body.fill(0)
    }

    @Test
    fun nonFinalBootstrapPagePersistsProtectedContinuationAndReplaySkipsFactory() =
        runBlocking {
            val fixture = newBootstrapFixture("protected-bootstrap-continuation")
            val session = requireNotNull(
                fixture.database.syncReplicaDao().findBootstrapSessionWithActiveSlot(),
            )
            val request = BootstrapRequest(
                requestId = uuid(701),
                bootstrapId = session.bootstrapId,
                deviceId = session.deviceId,
                pageSize = 100,
                pageCursor = null,
            )
            persistDirect(fixture, request)
            val attemptId = claimRequest(
                fixture,
                endpointId = "sync_bootstrap",
                requestIdentity = request.requestId,
            )
            val response = terminalResponse(
                endpointId = "sync_bootstrap",
                requestIdentity = request.requestId,
                attemptId = attemptId,
                status = 200,
                body = "{\"page\":1}",
                terminalAtUtc = T1,
                errorCode = null,
            )
            val change = ReplicaCreationFixture().change(sequence = 1)
            val nextCursor = cursor(11)
            val receipt = bootstrapReceipt(
                session = session,
                requestId = request.requestId,
                pageId = uuid(702),
                nextCursor = nextCursor,
                change = change,
            )
            val continuation = BootstrapRequest(
                requestId = uuid(703),
                bootstrapId = session.bootstrapId,
                deviceId = session.deviceId,
                pageSize = 100,
                pageCursor = nextCursor,
            )
            val continuationBody = exactBody(continuation)

            protectedStore(fixture).commitBootstrapPage(
                response = response,
                receipt = receipt,
                changes = listOf(change),
                continuationRequest = continuation,
                continuationPersistence = persistence(),
            )
            assertRawProtectedRequest(
                fixture = fixture,
                endpointId = "sync_bootstrap",
                requestIdentity = continuation.requestId,
                exactBody = continuationBody,
            )
            val advanced = requireNotNull(
                fixture.database.syncReplicaDao().findBootstrapSession(session.bootstrapId),
            )
            assertEquals(1, advanced.nextPageIndex)
            assertEquals(nextCursor, advanced.nextPageCursor)

            var factoryCalls = 0
            SyncPersistenceStore(fixture.database).commitBootstrapPage(
                response = response,
                receipt = receipt,
                changes = listOf(change),
                continuationFactory = {
                    factoryCalls += 1
                    error("Exact replay must not create another continuation")
                },
            )
            assertEquals(0, factoryCalls)

            // The protected wrapper must preserve the same laziness. Removing
            // its continuity marker makes any accidental provision/factory
            // call fail because durable HMAC references already exist.
            deleteMarkerArtifacts()
            val unusedContinuation = BootstrapRequest(
                requestId = uuid(704),
                bootstrapId = session.bootstrapId,
                deviceId = session.deviceId,
                pageSize = 100,
                pageCursor = nextCursor,
            )
            protectedStore(fixture).commitBootstrapPage(
                response = response,
                receipt = receipt,
                changes = listOf(change),
                continuationRequest = unusedContinuation,
                continuationPersistence = persistence(),
            )
            assertNull(
                fixture.database.syncTransportDao()
                    .findRequest("sync_bootstrap", unusedContinuation.requestId),
            )
            val replayedSession = requireNotNull(
                fixture.database.syncReplicaDao().findBootstrapSession(session.bootstrapId),
            )
            assertEquals(advanced, replayedSession)
            assertEquals(
                1,
                fixture.database.syncTransportDao()
                    .findOpenBootstrapRequests(
                        SyncM2PersistenceFixture.EPOCH_ID,
                        SyncM2PersistenceFixture.DEVICE_ID,
                    ).size,
            )
            continuationBody.fill(0)
        }

    @Test
    fun cursorExpiredPersistsProtectedReplacementAndReplaySkipsVerifierAndFactory() =
        runBlocking {
            val fixture = newBootstrapFixture("protected-bootstrap-expired")
            val expiredSession = requireNotNull(
                fixture.database.syncReplicaDao().findBootstrapSessionWithActiveSlot(),
            )
            val expiredRequest = BootstrapRequest(
                requestId = uuid(801),
                bootstrapId = expiredSession.bootstrapId,
                deviceId = expiredSession.deviceId,
                pageSize = 100,
                pageCursor = expiredSession.nextPageCursor,
            )
            persistDirect(fixture, expiredRequest)
            val attemptId = claimRequest(
                fixture,
                endpointId = "sync_bootstrap",
                requestIdentity = expiredRequest.requestId,
            )
            val response = terminalResponse(
                endpointId = "sync_bootstrap",
                requestIdentity = expiredRequest.requestId,
                attemptId = attemptId,
                status = 409,
                body = "{\"error\":\"cursor_expired\"}",
                terminalAtUtc = T2,
                errorCode = "cursor_expired",
            )
            val replacementSession = fixture.bootstrapIntent(
                bootstrapId = uuid(802),
                requestId = uuid(803),
            ).session.copy(createdAtUtc = T2, updatedAtUtc = T2)
            val replacement = BootstrapRequest(
                requestId = uuid(803),
                bootstrapId = replacementSession.bootstrapId,
                deviceId = replacementSession.deviceId,
                pageSize = 100,
                pageCursor = null,
            )
            val replacementBody = exactBody(replacement)

            protectedStore(fixture).commitBootstrapCursorExpired(
                response = response,
                expiredBootstrapId = expiredSession.bootstrapId,
                replacementSession = replacementSession,
                replacementRequest = replacement,
                replacementPersistence = persistence(),
            )
            assertEquals(
                "expired",
                fixture.database.syncReplicaDao()
                    .findBootstrapSession(expiredSession.bootstrapId)?.state,
            )
            assertRawProtectedRequest(
                fixture = fixture,
                endpointId = "sync_bootstrap",
                requestIdentity = replacement.requestId,
                exactBody = replacementBody,
            )
            fixture.reopen()
            assertVerifiedExact(
                fixture.database.syncTransportDao()
                    .findRequest("sync_bootstrap", replacement.requestId),
                replacementBody,
            )

            fixture.database.openHelper.writableDatabase.execSQL(
                """
                UPDATE sync_http_request
                SET raw_body_hmac = zeroblob(32)
                WHERE endpoint_id = 'sync_bootstrap' AND request_identity = ?
                """.trimIndent(),
                arrayOf(replacement.requestId),
            )
            val unusedSession = replacementSession.copy(
                bootstrapId = uuid(804),
                createdAtUtc = T2,
                updatedAtUtc = T2,
            )
            val unusedRequest = BootstrapRequest(
                requestId = uuid(805),
                bootstrapId = unusedSession.bootstrapId,
                deviceId = unusedSession.deviceId,
                pageSize = 100,
                pageCursor = null,
            )
            protectedStore(fixture).commitBootstrapCursorExpired(
                response = response,
                expiredBootstrapId = expiredSession.bootstrapId,
                replacementSession = unusedSession,
                replacementRequest = unusedRequest,
                replacementPersistence = persistence(),
            )
            assertEquals(
                "ready",
                fixture.database.syncTransportDao()
                    .findRequest("sync_bootstrap", replacement.requestId)?.state,
            )
            assertNull(
                fixture.database.syncTransportDao()
                    .findRequest("sync_bootstrap", unusedRequest.requestId),
            )
            assertNull(
                fixture.database.syncReplicaDao()
                    .findBootstrapSession(unusedSession.bootstrapId),
            )
            replacementBody.fill(0)
        }

    @Test
    fun pushBootstrapExistingCandidateInvalidStorageOrFutureGenerationQuarantines() =
        runBlocking {
            listOf(
                ExistingCandidateMode.TEXT_HMAC,
                ExistingCandidateMode.FUTURE_GENERATION,
            ).forEachIndexed { index, mode ->
                val (fixture, push) = newPushFixture("push-existing-${mode.name}")
                val store = protectedStore(fixture)
                store.persistPush(push.request, persistence(), push.batch, push.items)
                val attemptId = claimRequest(
                    fixture,
                    endpointId = "sync_push",
                    requestIdentity = push.request.batchId,
                )
                val session = fixture.bootstrapIntent(
                    deviceId = PUSH_DEVICE_ID,
                    bootstrapId = uuid(900L + index * 10L),
                    requestId = uuid(901L + index * 10L),
                ).session
                fixture.database.syncReplicaDao().insertBootstrapSession(session)
                val candidate = BootstrapRequest(
                    requestId = uuid(902L + index * 10L),
                    bootstrapId = session.bootstrapId,
                    deviceId = session.deviceId,
                    pageSize = 100,
                    pageCursor = null,
                )
                val candidateBody = exactBody(candidate)
                persistDirect(
                    fixture,
                    candidate,
                    accessGeneration = if (
                        mode == ExistingCandidateMode.FUTURE_GENERATION
                    ) {
                        2
                    } else {
                        1
                    },
                )
                if (mode == ExistingCandidateMode.FUTURE_GENERATION) {
                    // Access generation is deliberately outside the HMAC frame;
                    // prove that cryptography is valid before metadata recovery.
                    assertVerifiedExact(
                        fixture.database.syncTransportDao()
                            .findRequest("sync_bootstrap", candidate.requestId),
                        candidateBody,
                    )
                } else {
                    storeHmacAsSqliteText(fixture, candidate.requestId)
                    fixture.reopen()
                }

                val response = terminalResponse(
                    endpointId = "sync_push",
                    requestIdentity = push.request.batchId,
                    attemptId = attemptId,
                    status = 409,
                    body = "{\"error\":\"bootstrap_required\"}",
                    terminalAtUtc = T2,
                    errorCode = "bootstrap_required",
                )
                val proposed = BootstrapRequest(
                    requestId = uuid(903L + index * 10L),
                    bootstrapId = session.bootstrapId,
                    deviceId = session.deviceId,
                    pageSize = 100,
                    pageCursor = null,
                )
                assertFalse(
                    protectedStore(fixture).commitPushBootstrapRequired(
                        response = response,
                        session = session,
                        bootstrapRequest = proposed,
                        persistence = persistence(),
                    ),
                )

                val quarantined = requireNotNull(
                    fixture.database.syncTransportDao()
                        .findRequest("sync_bootstrap", candidate.requestId),
                )
                assertEquals("integrity_failure", quarantined.state)
                assertEquals("request_body_metadata_invalid", quarantined.terminalErrorCode)
                assertEquals(0, quarantined.attemptCount)
                assertNull(quarantined.activeAttemptId)
                if (mode == ExistingCandidateMode.TEXT_HMAC) {
                    assertEquals(32, quarantined.rawBodyHmac.size)
                    assertTrue(quarantined.rawBodyHmac.all { it == 0.toByte() })
                }
                assertEquals(
                    "sending",
                    fixture.database.syncTransportDao()
                        .findRequest("sync_push", push.request.batchId)?.state,
                )
                assertNull(
                    fixture.database.syncTransportDao()
                        .findRequest("sync_bootstrap", proposed.requestId),
                )
                val stream = requireNotNull(
                    fixture.database.syncReplicaDao().findStreamState(),
                )
                assertEquals("integrity_halted", stream.phase)
                assertEquals("request_body_metadata_invalid", stream.integrityErrorCode)
                assertEquals(
                    "staging",
                    fixture.database.syncReplicaDao()
                        .findBootstrapSession(session.bootstrapId)?.state,
                )

                candidateBody.fill(0)
                push.exactBody.fill(0)
            }
        }

    @Test
    fun cursorExpiredInvalidCandidateQuarantinesButInactiveFamilyOnlyDefers() =
        runBlocking {
            ExistingCandidateMode.entries.forEachIndexed { index, mode ->
                val fixture = newBootstrapFixture("cursor-existing-${mode.name}")
                val session = requireNotNull(
                    fixture.database.syncReplicaDao().findBootstrapSessionWithActiveSlot(),
                )
                val request = BootstrapRequest(
                    requestId = uuid(950L + index * 10L),
                    bootstrapId = session.bootstrapId,
                    deviceId = session.deviceId,
                    pageSize = 100,
                    pageCursor = session.nextPageCursor,
                )
                val exact = exactBody(request)
                persistDirect(fixture, request)
                val attemptId = claimRequest(
                    fixture,
                    endpointId = "sync_bootstrap",
                    requestIdentity = request.requestId,
                )
                when (mode) {
                    ExistingCandidateMode.TEXT_HMAC -> {
                        storeHmacAsSqliteText(fixture, request.requestId)
                        fixture.reopen()
                    }

                    ExistingCandidateMode.FUTURE_GENERATION -> {
                        fixture.database.openHelper.writableDatabase.execSQL(
                            """
                            UPDATE sync_http_request
                            SET access_generation_used = 2
                            WHERE endpoint_id = 'sync_bootstrap'
                              AND request_identity = ?
                            """.trimIndent(),
                            arrayOf(request.requestId),
                        )
                        assertVerifiedExact(
                            fixture.database.syncTransportDao()
                                .findRequest("sync_bootstrap", request.requestId),
                            exact,
                        )
                    }

                    ExistingCandidateMode.INACTIVE_FAMILY ->
                        markCurrentFamilyInactive(fixture)
                }
                val response = terminalResponse(
                    endpointId = "sync_bootstrap",
                    requestIdentity = request.requestId,
                    attemptId = attemptId,
                    status = 409,
                    body = "{\"error\":\"cursor_expired\"}",
                    terminalAtUtc = T2,
                    errorCode = "cursor_expired",
                )
                val replacementSession = session.copy(
                    bootstrapId = uuid(951L + index * 10L),
                    createdAtUtc = T2,
                    updatedAtUtc = T2,
                )
                val replacement = BootstrapRequest(
                    requestId = uuid(952L + index * 10L),
                    bootstrapId = replacementSession.bootstrapId,
                    deviceId = replacementSession.deviceId,
                    pageSize = 100,
                    pageCursor = null,
                )

                protectedStore(fixture).commitBootstrapCursorExpired(
                    response = response,
                    expiredBootstrapId = session.bootstrapId,
                    replacementSession = replacementSession,
                    replacementRequest = replacement,
                    replacementPersistence = persistence(),
                )
                val retained = requireNotNull(
                    fixture.database.syncTransportDao()
                        .findRequest("sync_bootstrap", request.requestId),
                )
                val stream = requireNotNull(
                    fixture.database.syncReplicaDao().findStreamState(),
                )
                if (mode == ExistingCandidateMode.INACTIVE_FAMILY) {
                    assertEquals("sending", retained.state)
                    assertNull(retained.terminalErrorCode)
                    assertEquals("bootstrap_required", stream.phase)
                    assertNull(stream.integrityErrorCode)
                } else {
                    assertEquals("integrity_failure", retained.state)
                    assertEquals("request_body_metadata_invalid", retained.terminalErrorCode)
                    assertEquals(1, retained.attemptCount)
                    assertNull(retained.activeAttemptId)
                    assertEquals("integrity_halted", stream.phase)
                    assertEquals("request_body_metadata_invalid", stream.integrityErrorCode)
                    if (mode == ExistingCandidateMode.TEXT_HMAC) {
                        assertEquals(32, retained.rawBodyHmac.size)
                        assertTrue(retained.rawBodyHmac.all { it == 0.toByte() })
                    }
                }
                assertEquals(
                    "staging",
                    fixture.database.syncReplicaDao()
                        .findBootstrapSession(session.bootstrapId)?.state,
                )
                assertNull(
                    fixture.database.syncReplicaDao()
                        .findBootstrapSession(replacementSession.bootstrapId),
                )
                assertNull(
                    fixture.database.syncTransportDao()
                        .findRequest("sync_bootstrap", replacement.requestId),
                )
                exact.fill(0)
            }
        }

    @Test
    fun cursorExpiredForSupersededSessionDefersWithoutFalseIntegrity() = runBlocking {
        val fixture = newBootstrapFixture("cursor-superseded-session")
        val superseded = requireNotNull(
            fixture.database.syncReplicaDao().findBootstrapSessionWithActiveSlot(),
        )
        val request = BootstrapRequest(
            requestId = uuid(990),
            bootstrapId = superseded.bootstrapId,
            deviceId = superseded.deviceId,
            pageSize = 100,
            pageCursor = superseded.nextPageCursor,
        )
        persistDirect(fixture, request)
        val attemptId = claimRequest(
            fixture,
            endpointId = "sync_bootstrap",
            requestIdentity = request.requestId,
        )
        fixture.database.openHelper.writableDatabase.execSQL(
            """
            UPDATE sync_bootstrap_session
            SET state = 'superseded', active_slot = NULL, updated_at_utc = ?
            WHERE bootstrap_id = ?
            """.trimIndent(),
            arrayOf(T1, superseded.bootstrapId),
        )
        val current = superseded.copy(
            bootstrapId = uuid(991),
            state = "staging",
            activeSlot = 1,
            createdAtUtc = T1,
            updatedAtUtc = T1,
        )
        fixture.database.syncReplicaDao().insertBootstrapSession(current)
        val response = terminalResponse(
            endpointId = "sync_bootstrap",
            requestIdentity = request.requestId,
            attemptId = attemptId,
            status = 409,
            body = "{\"error\":\"cursor_expired\"}",
            terminalAtUtc = T2,
            errorCode = "cursor_expired",
        )
        val unusedSession = current.copy(
            bootstrapId = uuid(992),
            createdAtUtc = T2,
            updatedAtUtc = T2,
        )
        val unusedRequest = BootstrapRequest(
            requestId = uuid(993),
            bootstrapId = unusedSession.bootstrapId,
            deviceId = unusedSession.deviceId,
            pageSize = 100,
            pageCursor = null,
        )

        var verifierCalls = 0
        var factoryCalls = 0
        SyncPersistenceStore(fixture.database)
            .commitBootstrapCursorExpiredWithProtectedReplacement(
                response = response,
                bootstrapId = superseded.bootstrapId,
                replacementFactory = {
                    factoryCalls += 1
                    error("Superseded callback must not build a replacement")
                },
                existingCandidateVerifier = {
                    verifierCalls += 1
                    true
                },
            )
        assertEquals(0, verifierCalls)
        assertEquals(0, factoryCalls)

        protectedStore(fixture).commitBootstrapCursorExpired(
            response = response,
            expiredBootstrapId = superseded.bootstrapId,
            replacementSession = unusedSession,
            replacementRequest = unusedRequest,
            replacementPersistence = persistence(),
        )

        val retained = requireNotNull(
            fixture.database.syncTransportDao()
                .findRequest("sync_bootstrap", request.requestId),
        )
        assertEquals("sending", retained.state)
        assertNull(retained.terminalErrorCode)
        assertEquals(
            "superseded",
            fixture.database.syncReplicaDao()
                .findBootstrapSession(superseded.bootstrapId)?.state,
        )
        assertEquals(
            current,
            fixture.database.syncReplicaDao().findBootstrapSession(current.bootstrapId),
        )
        val stream = requireNotNull(fixture.database.syncReplicaDao().findStreamState())
        assertEquals("bootstrap_required", stream.phase)
        assertNull(stream.integrityErrorCode)
        assertNull(
            fixture.database.syncReplicaDao()
                .findBootstrapSession(unusedSession.bootstrapId),
        )
        assertNull(
            fixture.database.syncTransportDao()
                .findRequest("sync_bootstrap", unusedRequest.requestId),
        )
    }

    private fun protectedStore(fixture: SyncM2PersistenceFixture) =
        ProtectedSyncRequestStore(context, fixture.database, keyring())

    private fun keyring() = KeystoreRequestBodyHmacKeyring(
        context = context,
        keyAlias = hmacAlias,
        markerRelativePath = markerRelativePath,
    )

    private fun persistence(accessGeneration: Long = 1) =
        NewDurableRequestPersistence(
            localCredentialEpochId = SyncM2PersistenceFixture.EPOCH_ID,
            accessGenerationUsed = accessGeneration,
            attemptBudget = 8,
            deadlineAtEpochMs = SyncM2PersistenceFixture.DEADLINE_MS,
            createdAtUtc = SyncM2PersistenceFixture.BASE_UTC,
        )

    private suspend fun newActiveFixture(
        label: String,
        bootstrapRequired: Boolean,
    ): SyncM2PersistenceFixture = newFixture(label).also { fixture ->
        fixture.seedIdentity(
            deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            personId = SyncM2PersistenceFixture.PERSON_ID,
        )
        fixture.installActiveAuth(bootstrapRequired = bootstrapRequired)
        fixture.database.syncReplicaDao().insertStreamState(
            fixture.streamState(bootstrapRequired = bootstrapRequired),
        )
    }

    private suspend fun newBootstrapFixture(label: String): SyncM2PersistenceFixture =
        newActiveFixture(label, bootstrapRequired = true).also { fixture ->
            fixture.database.syncReplicaDao().insertBootstrapSession(
                fixture.bootstrapIntent(bootstrapId = uuid(label.hashCode().toLong())).session,
            )
        }

    private fun newFixture(label: String) = SyncM2PersistenceFixture(
        context = context,
        label = "$label-$testId",
    ).also(fixtures::add)

    private suspend fun newPushFixture(label: String): Pair<SyncM2PersistenceFixture, PushRows> {
        val fixture = newFixture(label)
        fixture.seedIdentity(deviceId = PUSH_DEVICE_ID, personId = SyncM2PersistenceFixture.PERSON_ID)
        fixture.installActiveAuth(deviceId = PUSH_DEVICE_ID)
        fixture.database.syncReplicaDao().insertStreamState(
            fixture.streamState(deviceId = PUSH_DEVICE_ID),
        )
        val request = WireRequestCodec.decodePushBatch(
            ONE_OPERATION_PUSH.toByteArray(StandardCharsets.UTF_8),
        )
        val operation = request.operations.single()
        val repository = RoomNotesRepository(
            database = fixture.database,
            collectorVersion = "creation-paths-instrumented-test",
        )
        val recordedAt = OffsetDateTime.parse("2030-01-01T07:00:00+07:00")
        repository.create(
            CreateNoteCommand(
                ids = MutationIds(
                    operationId = UUID.fromString(operation.operationId),
                    captureId = UUID.fromString(operation.captureId),
                    eventId = UUID.fromString(operation.eventId),
                    revisionId = UUID.fromString(operation.revisionId),
                ),
                text = "Protected push fixture",
                effectiveTime = PointTimeResolver.resolveInstant(
                    recordedAt.toInstant(),
                    ZoneId.of("Asia/Novosibirsk"),
                ),
                recordedAt = recordedAt,
            ),
        )
        val pending = requireNotNull(
            fixture.database.noteMutationDao().findOutbox(operation.operationId),
        )
        assertEquals(operation.clientSequence, pending.localSequence)
        assertEquals(
            1,
            fixture.database.outboxDao().installWireMaterial(
                localSequence = pending.localSequence,
                operationId = pending.operationId,
                protocolVersion = "1.0.0",
                materialJcs = "{}".toByteArray(StandardCharsets.UTF_8),
                contentSha256 = operation.operationContentSha256,
                materializedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
            ),
        )
        val exactBody = exactBody(request)
        val evidence = WireRequestCodec.decodeDurablePushEvidence(exactBody)
        val batch = SyncPushBatchEntity(
            batchId = request.batchId,
            endpointId = "sync_push",
            requestIdentity = request.batchId,
            batchContentSha256 = evidence.batchContentSha256,
            operationCount = 1,
            createdAtUtc = SyncM2PersistenceFixture.BASE_UTC,
        )
        val item = SyncPushBatchItemEntity(
            batchId = request.batchId,
            ordinal = 0,
            localSequence = pending.localSequence,
            operationId = pending.operationId,
            wireOperationContentSha256 = operation.operationContentSha256,
        )
        return fixture to PushRows(request, exactBody, batch, listOf(item))
    }

    private fun enrollmentBundle(
        fixture: SyncM2PersistenceFixture,
        attemptRequestId: String,
        session: SyncBootstrapSessionEntity,
    ) = EnrollmentSuccessPersistence(
        attemptRequestId = attemptRequestId,
        authState = fixture.authState(bootstrapRequired = true),
        accessFingerprint = fixture.fingerprint(
            SyncM2PersistenceFixture.EPOCH_ID,
            1,
            "access",
            1,
        ),
        refreshFingerprint = fixture.fingerprint(
            SyncM2PersistenceFixture.EPOCH_ID,
            1,
            "refresh",
            2,
        ),
        streamState = fixture.streamState(bootstrapRequired = true),
        bootstrapSession = session,
    )

    private suspend fun persistDirect(
        fixture: SyncM2PersistenceFixture,
        request: M2WireRequest,
        accessGeneration: Long = 1,
    ) {
        val ring = keyring()
        ring.provisionCurrentKey(
            fixture.database.syncTransportDao().countRequestsReferencingHmacGeneration(1),
        )
        val protected = DurableSyncRequestProtector(context, ring)
            .protectNew(request, persistence(accessGeneration))
        try {
            fixture.database.syncTransportDao().insertRequest(protected)
        } finally {
            protected.rawRequestBody?.fill(0)
            protected.sealedBodyCiphertext?.fill(0)
            protected.sealedBodyNonce?.fill(0)
            protected.rawBodyHmac.fill(0)
        }
    }

    private suspend fun claimRequest(
        fixture: SyncM2PersistenceFixture,
        endpointId: String,
        requestIdentity: String,
    ): String {
        val attemptId = UUID.randomUUID().toString()
        val claim = protectedStore(fixture).verifyAndClaim(
            endpointId = endpointId,
            requestIdentity = requestIdentity,
            attemptId = attemptId,
            attemptedAtEpochMs = SyncM2PersistenceFixture.NOW_MS,
            leaseExpiresAtEpochMs = SyncM2PersistenceFixture.NOW_MS + LEASE_MS,
            updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
        )
        assertTrue(claim is ProtectedRequestClaim.Claimed)
        (claim as ProtectedRequestClaim.Claimed).request.close()
        return attemptId
    }

    private suspend fun assertRawProtectedRequest(
        fixture: SyncM2PersistenceFixture,
        endpointId: String,
        requestIdentity: String,
        exactBody: ByteArray,
    ) {
        val stored = requireNotNull(
            fixture.database.syncTransportDao().findRequest(endpointId, requestIdentity),
        )
        assertEquals(SyncHttpRequestEntity.BODY_STORAGE_RAW, stored.bodyStorageKind)
        assertArrayEquals(exactBody, stored.rawRequestBody)
        assertEquals(exactBody.size.toLong(), stored.requestBodyOctetCount)
        assertEquals(32, stored.rawBodyHmac.size)
        assertEquals(1, stored.hmacKeyGeneration)
        assertVerifiedExact(stored, exactBody)
    }

    private fun assertVerifiedExact(
        stored: SyncHttpRequestEntity?,
        exactBody: ByteArray,
    ) {
        val verified = DurableSyncRequestVerifier(context, keyring())
            .loadVerified(requireNotNull(stored))
        val copy = verified.consumeBody { it.copyOf() }
        try {
            assertArrayEquals(exactBody, copy)
        } finally {
            copy.fill(0)
            verified.close()
        }
    }

    private fun bootstrapReceipt(
        session: SyncBootstrapSessionEntity,
        requestId: String,
        pageId: String,
        nextCursor: String,
        change: ReplicaChangePersistence,
    ) = SyncPageReceiptEntity(
        pageId = pageId,
        endpointId = "sync_bootstrap",
        requestIdentity = requestId,
        bootstrapId = session.bootstrapId,
        pageIndex = 0,
        snapshotId = uuid(790),
        fromCursor = null,
        nextCursor = nextCursor,
        incrementalCursor = cursor(12),
        pageSha256 = sha256Hex("page-$pageId".toByteArray(StandardCharsets.UTF_8)),
        changeCount = 1,
        completeOrHasMore = false,
        state = "staged",
        firstServerSequence = change.serverSequence,
        lastServerSequence = change.serverSequence,
        receivedAtUtc = T1,
        appliedAtUtc = null,
    )

    private fun terminalResponse(
        endpointId: String,
        requestIdentity: String,
        attemptId: String,
        status: Int,
        body: String,
        terminalAtUtc: String,
        errorCode: String?,
    ): TerminalHttpResponsePersistence {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        return TerminalHttpResponsePersistence(
            endpointId = endpointId,
            requestIdentity = requestIdentity,
            expectedAttemptId = attemptId,
            httpStatus = status,
            exactResponseBody = bytes,
            responseSha256 = sha256Hex(bytes),
            terminalAtUtc = terminalAtUtc,
            terminalErrorCode = errorCode,
        )
    }

    private fun exactBody(request: M2WireRequest): ByteArray =
        WireRequestCodec.materialize(request).use { it.copyBody() }

    private fun refreshToken() = WipeableSecret.ascii(
        "lar_SSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSM",
    )

    private fun cursor(seed: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteArray(32) { index -> (seed + index).toByte() },
        )

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun uuid(seed: Long): String = UUID.nameUUIDFromBytes(
        "protected-creation-$seed".toByteArray(StandardCharsets.US_ASCII),
    ).toString()

    private fun keystoreContains(alias: String): Boolean =
        KeyStore.getInstance(ANDROID_KEYSTORE).run {
            load(null)
            containsAlias(alias)
        }

    private fun deleteMarkerArtifacts() {
        val marker = File(context.noBackupFilesDir, markerRelativePath)
        listOf("", ".bak", ".new").forEach { suffix ->
            File(marker.path + suffix).delete()
        }
    }

    private fun storeHmacAsSqliteText(
        fixture: SyncM2PersistenceFixture,
        requestIdentity: String,
    ) {
        fixture.database.openHelper.writableDatabase.apply {
            execSQL("DROP TRIGGER IF EXISTS guard_sync_http_request_body_update")
            execSQL(
                """
                UPDATE sync_http_request
                SET raw_body_hmac = CAST(? AS TEXT)
                WHERE endpoint_id = 'sync_bootstrap' AND request_identity = ?
                """.trimIndent(),
                arrayOf("h".repeat(32), requestIdentity),
            )
        }
    }

    private fun markCurrentFamilyInactive(fixture: SyncM2PersistenceFixture) {
        fixture.database.openHelper.writableDatabase.execSQL(
            """
            UPDATE sync_auth_state
            SET state = 'revoked',
                refresh_token_ciphertext = NULL,
                refresh_token_nonce = NULL,
                refresh_token_key_alias = NULL,
                refresh_token_key_generation = NULL,
                refresh_token_aad_version = NULL,
                updated_at_utc = ?
            WHERE singleton_id = 1
            """.trimIndent(),
            arrayOf(T1),
        )
    }

    private data class PushRows(
        val request: PushBatchRequest,
        val exactBody: ByteArray,
        val batch: SyncPushBatchEntity,
        val items: List<SyncPushBatchItemEntity>,
    )

    private enum class ExistingCandidateMode {
        TEXT_HMAC,
        FUTURE_GENERATION,
        INACTIVE_FAMILY,
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val PUSH_DEVICE_ID = "91000000-0000-4000-8000-000000000003"
        const val T1 = "2030-01-01T00:00:01Z"
        const val T2 = "2030-01-01T00:00:02Z"
        const val LEASE_MS = 60_000L
        const val ONE_OPERATION_PUSH = """{"batch_content_sha256":"6f44b5b9554794330e607f5b5737df7ba5cf1a8b96f3c2b39c44ecc80582c17c","batch_id":"96000000-0000-4000-8000-000000000001","device_id":"91000000-0000-4000-8000-000000000003","message_type":"push_batch_request","operations":[{"body":{"assertion_status":"observed","event_id":"92000000-0000-4000-8000-000000000001","evidence":[{"artifact_id":null,"capture_ref":"#/source/capture_id","excerpt":null,"field_path":"/payload/text","human_confirmed":true,"locator":"android_form:/note/text"}],"identity":{"device_id":null,"installation_id":"91000000-0000-4000-8000-000000000001","local_owner_id":"91000000-0000-4000-8000-000000000002"},"kind":"note","lifecycle":null,"payload":{"text":"Synthetic M2 note."},"persistence_state":"local_pending","quality_flags":[],"record_status":"active","revision":{"actor":"user","content_sha256":"e1c6dbf0a03360af8f14901d5c22d5078e797a4d9163ae5eb45d918a4742b833","correction_reason":null,"created_at":"2030-01-01T07:00:00+07:00","parents":[]},"revision_id":"93000000-0000-4000-8000-000000000001","revision_no":1,"schema_version":"4.0.0","server":{"received_at":null,"server_sequence":null},"source":{"capture_id":"94000000-0000-4000-8000-000000000001","channel":"android_manual","collector":{"name":"life-agent-android","version":"1.0.0-test"},"operation_id":"95000000-0000-4000-8000-000000000001","origin":{"app":"Life Agent Android","device":"Synthetic Android device","provider":null,"user_entered":true},"recorded_at":"2030-01-01T07:00:00+07:00","source_modified_at":null,"source_record_id":null,"source_record_version":null},"time":{"effective_end_utc":null,"effective_start_utc":"2030-01-01T00:00:00Z","end_offset_seconds":null,"local_date":"2030-01-01","original_local_end":null,"original_local_start":"2030-01-01T07:00:00","source_expression":null,"start_offset_seconds":25200,"temporal_precision":"minute","timezone_id":"Asia/Novosibirsk"},"verification_status":"user_confirmed"},"capture":{"capture_id":"94000000-0000-4000-8000-000000000001","content":{"kind":"structured","payload":{"text":"Synthetic M2 note."},"record_type":"note"},"identity":{"device_id":null,"installation_id":"91000000-0000-4000-8000-000000000001","local_owner_id":"91000000-0000-4000-8000-000000000002"},"integrity":{"byte_size":82,"sha256":"59facfd0dc0ecdeb1cd62095e5185045c4651d435cd2c4ebfea6e0d89275eae1"},"operation_id":"95000000-0000-4000-8000-000000000001","persistence_state":"local_pending","schema_version":"4.0.0","source":{"channel":"android_manual","collector":{"name":"life-agent-android","version":"1.0.0-test"},"origin":{"app":"Life Agent Android","device":"Synthetic Android device","provider":null,"source_record_id":null,"source_record_version":null,"user_entered":true},"recorded_at":"2030-01-01T07:00:00+07:00","timezone_id":"+07:00","utc_offset_minutes":420}},"capture_id":"94000000-0000-4000-8000-000000000001","client_sequence":1,"event_id":"92000000-0000-4000-8000-000000000001","event_kind":"note","event_schema_version":"4.0.0","expected_current_revision_id":null,"operation_content_sha256":"81411dbf4319ab8d7b4bbfd1dfcac76ab3b212e4fcd9988907248b4a3b55e87b","operation_id":"95000000-0000-4000-8000-000000000001","operation_kind":"append_event_revision","ordinal":0,"revision_id":"93000000-0000-4000-8000-000000000001"}],"protocol_version":"1.0.0"}"""
    }
}

private class ReplicaCreationFixture {
    private val canonical = CanonicalNoteCodec()

    fun change(sequence: Long): ReplicaChangePersistence {
        val operationId = "95000000-0000-4000-8000-000000000011"
        val captureId = "94000000-0000-4000-8000-000000000011"
        val eventId = "92000000-0000-4000-8000-000000000011"
        val revisionId = "93000000-0000-4000-8000-000000000011"
        val recordedAt = "2030-01-01T07:00:00+07:00"
        val receivedAt = "2030-01-01T00:00:01Z"
        val payload = buildJsonObject { put("text", "Protected bootstrap change") }
        val captureContent = buildJsonObject {
            put("kind", "structured")
            put("record_type", "note")
            put("payload", payload)
        }
        val captureDigest = canonical.canonical(captureContent)
        val time = buildJsonObject {
            put("effective_start_utc", "2030-01-01T00:00:00Z")
            put("effective_end_utc", JsonNull)
            put("original_local_start", "2030-01-01T07:00:00")
            put("original_local_end", JsonNull)
            put("timezone_id", "Asia/Novosibirsk")
            put("start_offset_seconds", 25_200)
            put("end_offset_seconds", JsonNull)
            put("temporal_precision", "minute")
            put("local_date", "2030-01-01")
            put("source_expression", JsonNull)
        }
        val revisionDigest = canonical.canonical(
            buildJsonObject {
                put("event_id", eventId)
                put("revision_id", revisionId)
                put("revision_no", 1)
                put("capture_id", captureId)
                put("operation_id", operationId)
                put("record_status", "active")
                put("effective_time", time)
                put("recorded_at", recordedAt)
                put("payload", payload)
                put("correction_reason", JsonNull)
                put("parent_revision_id", JsonNull)
            },
        )
        val operationDigest = MessageDigest.getInstance("SHA-256")
            .digest("operation-$sequence".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val root = buildJsonObject {
            put("server_sequence", sequence)
            put("change_kind", "event_revision_committed")
            put("result_code", "applied")
            put("operation_id", operationId)
            put("capture_id", captureId)
            put("event_id", eventId)
            put("revision_id", revisionId)
            put("current_revision_id", revisionId)
            put("operation_content_sha256", operationDigest)
            putJsonObject("capture") {
                put("schema_version", "4.0.0")
                put("persistence_state", "authenticated_ingress")
                put("capture_id", captureId)
                put("operation_id", operationId)
                put("identity", identity())
                putJsonObject("source") {
                    put("channel", "android_manual")
                    put("recorded_at", recordedAt)
                    put("timezone_id", "+07:00")
                    put("utc_offset_minutes", 420)
                    putJsonObject("origin") {
                        put("provider", JsonNull)
                        put("app", "Life Agent Android")
                        put("device", "Synthetic Android device")
                        put("source_record_id", JsonNull)
                        put("source_record_version", JsonNull)
                        put("user_entered", true)
                    }
                    put("collector", collector())
                }
                put("content", captureContent)
                putJsonObject("integrity") {
                    put("sha256", captureDigest.sha256)
                    put("byte_size", captureDigest.bytes.size)
                }
            }
            putJsonObject("event") {
                put("schema_version", "4.0.0")
                put("persistence_state", "server_committed")
                put("identity", identity())
                put("event_id", eventId)
                put("revision_id", revisionId)
                put("revision_no", 1)
                put("kind", "note")
                put("assertion_status", "observed")
                put("lifecycle", JsonNull)
                put("record_status", "active")
                put("verification_status", "user_confirmed")
                putJsonObject("source") {
                    put("capture_id", captureId)
                    put("operation_id", operationId)
                    put("channel", "android_manual")
                    put("source_record_id", JsonNull)
                    put("source_record_version", JsonNull)
                    put("source_modified_at", JsonNull)
                    put("recorded_at", recordedAt)
                    putJsonObject("origin") {
                        put("provider", JsonNull)
                        put("app", "Life Agent Android")
                        put("device", "Synthetic Android device")
                        put("user_entered", true)
                    }
                    put("collector", collector())
                }
                put("time", time)
                put("payload", payload)
                putJsonArray("evidence") {
                    add(
                        buildJsonObject {
                            put("capture_ref", "#/source/capture_id")
                            put("field_path", "/payload/text")
                            put("artifact_id", JsonNull)
                            put("locator", "android_form:/note/text")
                            put("excerpt", JsonNull)
                            put("human_confirmed", true)
                        },
                    )
                }
                put("quality_flags", buildJsonArray {})
                putJsonObject("revision") {
                    put("created_at", recordedAt)
                    put("content_sha256", revisionDigest.sha256)
                    put("actor", "user")
                    put("correction_reason", JsonNull)
                    putJsonArray("parents") {}
                }
                putJsonObject("server") {
                    put("received_at", receivedAt)
                    put("server_sequence", sequence)
                }
            }
        }
        return ReplicaChangePersistence(
            serverSequence = sequence,
            operationId = operationId,
            operationContentSha256 = operationDigest,
            captureId = captureId,
            eventId = eventId,
            revisionId = revisionId,
            currentRevisionId = revisionId,
            resultCode = "applied",
            committedAtUtc = receivedAt,
            changeJcs = canonical.canonical(root).bytes,
        )
    }

    private fun identity() = buildJsonObject {
        put("installation_id", "91000000-0000-4000-8000-000000000011")
        put("local_owner_id", "91000000-0000-4000-8000-000000000012")
        put("device_id", "91000000-0000-4000-8000-000000000013")
    }

    private fun collector() = buildJsonObject {
        put("name", "life-agent-android")
        put("version", "creation-paths-instrumented-test")
    }
}
