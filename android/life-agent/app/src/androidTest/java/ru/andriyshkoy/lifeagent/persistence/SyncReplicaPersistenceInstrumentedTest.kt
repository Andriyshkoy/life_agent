package ru.andriyshkoy.lifeagent.persistence

import android.content.Context
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.After
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
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabase
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabaseFactory
import ru.andriyshkoy.lifeagent.data.local.db.BootstrapIntentPersistence
import ru.andriyshkoy.lifeagent.data.local.db.ReplicaChangePersistence
import ru.andriyshkoy.lifeagent.data.local.db.ReplicaIntegrityException
import ru.andriyshkoy.lifeagent.data.local.db.SyncPersistenceStore
import ru.andriyshkoy.lifeagent.data.local.db.TerminalHttpResponsePersistence
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalIdentityStateEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalInstallationEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalOwnerEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncAuthStateEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncAuthTokenFingerprintEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncBootstrapSessionEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncHttpRequestEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPageReceiptEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncStreamStateEntity
import ru.andriyshkoy.lifeagent.data.local.serialization.CanonicalNoteCodec
import ru.andriyshkoy.lifeagent.notes.data.RoomNotesRepository
import ru.andriyshkoy.lifeagent.notes.domain.CorrectNoteCommand
import ru.andriyshkoy.lifeagent.notes.domain.CreateNoteCommand

@RunWith(AndroidJUnit4::class)
class SyncReplicaPersistenceInstrumentedTest {
    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var databaseName: String
    private var database: LifeAgentDatabase? = null
    private val fixture = ReplicaInstrumentedFixture()

    @Before
    fun setUp() {
        databaseName = "sync-replica-${UUID.randomUUID()}.db"
        openDatabase()
    }

    @After
    fun tearDown() {
        closeDatabase()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun nonFinalBootstrapPageFailsClosedWithoutDurableContinuationIntent() =
        runBlocking {
        createLocalPendingNote()
        seedStream(appliedCursor = "cursor-before-bootstrap")
        seedBootstrapSession()

        val root = fixture.change(sequence = 1)
        val firstRequestId = uuid(101)
        val firstPageId = uuid(102)
        val firstAttemptId = uuid(103)
        val firstResponse = terminalResponse(
            endpoint = "sync_bootstrap",
            requestId = firstRequestId,
            attemptId = firstAttemptId,
            body = "bootstrap-page-one",
            terminalAt = "2030-01-01T00:00:10Z",
        )
        seedSendingRequest(firstResponse, firstAttemptId)
        val firstReceipt = bootstrapReceipt(
            requestId = firstRequestId,
            pageId = firstPageId,
            pageIndex = 0,
            fromCursor = null,
            nextCursor = "bootstrap-page-two",
            complete = false,
            changes = listOf(root),
            receivedAt = firstResponse.terminalAtUtc,
        )

        assertTrue(
            runCatching {
                SyncPersistenceStore(requireDatabase()).commitBootstrapPage(
                    response = firstResponse,
                    receipt = firstReceipt,
                    changes = listOf(root),
                )
            }.exceptionOrNull() is IllegalArgumentException,
        )

        closeDatabase()
        openDatabase()
        assertNull(
            requireDatabase().syncReplicaDao().findServerChange(root.operationId),
        )
        assertEquals(0, requireDatabase().syncReplicaDao().findStagedChanges(BOOTSTRAP_ID).size)
        val stream = requireNotNull(
            requireDatabase().syncReplicaDao().findStreamState(),
        )
        assertEquals("cursor-before-bootstrap", stream.appliedCursor)
        assertTrue(stream.bootstrapRequired)
        val session = requireNotNull(
            requireDatabase().syncReplicaDao().findBootstrapSession(BOOTSTRAP_ID),
        )
        assertEquals("staging", session.state)
        assertEquals(1, session.activeSlot)
        val request = requireNotNull(
            requireDatabase().syncTransportDao()
                .findRequest("sync_bootstrap", firstRequestId),
        )
        assertEquals("sending", request.state)
        assertNull(request.terminalAtUtc)
    }

    @Test
    fun progressedBootstrapRejectsNullableWildcardMetadataBeforeReservation() =
        runBlocking {
            seedStream(appliedCursor = "cursor-before-wildcard-session")
            seedBootstrapSession()
            val fromCursor = "retained-bootstrap-page-cursor"
            requireDatabase().openHelper.writableDatabase.execSQL(
                """
                UPDATE sync_bootstrap_session
                SET next_page_index = 1,
                    staged_page_count = 1,
                    staged_body_bytes = 128,
                    next_page_cursor = ?,
                    last_staged_server_sequence = 1,
                    snapshot_id = NULL,
                    candidate_incremental_cursor = NULL
                WHERE bootstrap_id = ?
                """.trimIndent(),
                arrayOf<Any?>(fromCursor, BOOTSTRAP_ID),
            )
            val change = fixture.change(sequence = 2)
            val requestId = uuid(1_050)
            val attemptId = uuid(1_051)
            val pageId = uuid(1_052)
            val response = terminalResponse(
                endpoint = "sync_bootstrap",
                requestId = requestId,
                attemptId = attemptId,
                body = "bootstrap-wildcard-session",
                terminalAt = "2030-01-01T00:09:00Z",
            )
            seedSendingRequest(response, attemptId)

            val failure = assertThrows(ReplicaIntegrityException::class.java) {
                runBlocking {
                    SyncPersistenceStore(requireDatabase()).commitBootstrapPage(
                        response = response,
                        receipt = bootstrapReceipt(
                            requestId = requestId,
                            pageId = pageId,
                            pageIndex = 1,
                            fromCursor = fromCursor,
                            nextCursor = null,
                            complete = true,
                            changes = listOf(change),
                            receivedAt = response.terminalAtUtc,
                        ),
                        changes = listOf(change),
                    )
                }
            }
            assertEquals("bootstrap_shadow_drift", failure.errorCode)
            assertEquals(
                0,
                requireDatabase().syncReplicaDao().countReplicaCursorsByRole(
                    BOOTSTRAP_ID,
                    "incremental",
                ),
            )
            assertNull(requireDatabase().syncReplicaDao().findPageReceipt(pageId))
            val request = requireNotNull(
                requireDatabase().syncTransportDao()
                    .findRequest("sync_bootstrap", requestId),
            )
            assertEquals("sending", request.state)
            assertNull(request.exactResponseBody)
        }

    @Test
    fun bootstrapPromotionPreservesReceiptNewerThanSnapshotThenPullRedeliversIt() =
        runBlocking {
            createLocalPendingNote()
            seedStream(appliedCursor = "cursor-before-initial-bootstrap")
            seedBootstrapSession()
            val root = fixture.change(sequence = 1)
            val second = fixture.change(
                sequence = 2,
                revisionId = REVISION_TWO_ID,
                captureId = CAPTURE_TWO_ID,
                operationId = OPERATION_TWO_ID,
                revisionNo = 2,
                parentRevisionId = REVISION_ONE_ID,
            )
            val third = fixture.change(
                sequence = 3,
                revisionId = REVISION_THREE_ID,
                captureId = CAPTURE_THREE_ID,
                operationId = OPERATION_THREE_ID,
                revisionNo = 3,
                parentRevisionId = REVISION_TWO_ID,
            )
            val store = SyncPersistenceStore(requireDatabase())
            val initialRequestId = uuid(261)
            val initialAttemptId = uuid(262)
            val initialResponse = terminalResponse(
                endpoint = "sync_bootstrap",
                requestId = initialRequestId,
                attemptId = initialAttemptId,
                body = "initial-bootstrap-through-sequence-three",
                terminalAt = "2030-01-01T00:01:10Z",
            )
            seedSendingRequest(initialResponse, initialAttemptId)
            store.commitBootstrapPage(
                response = initialResponse,
                receipt = bootstrapReceipt(
                    requestId = initialRequestId,
                    pageId = uuid(263),
                    pageIndex = 0,
                    fromCursor = null,
                    nextCursor = null,
                    complete = true,
                    changes = listOf(root, second, third),
                    receivedAt = initialResponse.terminalAtUtc,
                ),
                changes = listOf(root, second, third),
            )
            val retainedThird = requireNotNull(
                requireDatabase()
                    .syncReplicaDao()
                    .findServerChange(third.operationId),
            )
            val localRecordedAt =
                OffsetDateTime.parse("2030-01-01T08:00:00+07:00")
            val localRevisionId = uuid(1_004)
            RoomNotesRepository(
                database = requireDatabase(),
                collectorVersion = "replica-instrumented-test",
            ).correct(
                CorrectNoteCommand(
                    ids = MutationIds(
                        operationId = UUID.fromString(uuid(1_001)),
                        captureId = UUID.fromString(uuid(1_002)),
                        eventId = UUID.fromString(third.eventId),
                        revisionId = UUID.fromString(localRevisionId),
                    ),
                    expectedCurrentRevisionId =
                        UUID.fromString(REVISION_THREE_ID),
                    text = "Pending local descendant",
                    effectiveTime = PointTimeResolver.resolveInstant(
                        localRecordedAt.toInstant(),
                        ZoneId.of("Asia/Novosibirsk"),
                    ),
                    recordedAt = localRecordedAt,
                ),
            )
            val localHeadUpdatedAt = localRecordedAt.toInstant().toString()

            // A later bootstrap snapshot can end before a receipt already
            // observed through push. That receipt belongs after the snapshot
            // cursor and must remain available for exact pull redelivery.
            requireDatabase().openHelper.writableDatabase.execSQL(
                """
                UPDATE sync_stream_state
                SET phase = 'bootstrap_required',
                    bootstrap_required = 1,
                    applied_cursor = 'cursor-before-replacement-bootstrap'
                WHERE singleton_id = 1
                """.trimIndent(),
            )
            supersedeWithReplacementBootstrap()
            val replacementRequestId = uuid(264)
            val replacementAttemptId = uuid(265)
            val replacementResponse = terminalResponse(
                endpoint = "sync_bootstrap",
                requestId = replacementRequestId,
                attemptId = replacementAttemptId,
                body = "replacement-snapshot-through-sequence-two",
                terminalAt = "2030-01-01T00:02:10Z",
            )
            seedSendingRequest(replacementResponse, replacementAttemptId)
            store.commitBootstrapPage(
                response = replacementResponse,
                receipt = bootstrapReceipt(
                    requestId = replacementRequestId,
                    pageId = uuid(266),
                    pageIndex = 0,
                    fromCursor = null,
                    nextCursor = null,
                    complete = true,
                    changes = listOf(root, second),
                    receivedAt = replacementResponse.terminalAtUtc,
                    bootstrapId = REPLACEMENT_BOOTSTRAP_ID,
                ),
                changes = listOf(root, second),
            )

            var stream = requireNotNull(
                requireDatabase().syncReplicaDao().findStreamState(),
            )
            assertEquals("incremental", stream.phase)
            assertEquals(2L, stream.lastAppliedServerSequence)
            assertEquals("bootstrap-incremental-cursor", stream.appliedCursor)
            assertEquals(
                retainedThird,
                requireDatabase()
                    .syncReplicaDao()
                    .findServerChange(third.operationId),
            )
            var head = requireNotNull(
                requireDatabase().syncReplicaDao().findEventHead(third.eventId),
            )
            assertEquals(3L, head.serverObservedSequence)
            assertEquals(REVISION_THREE_ID, head.serverCurrentRevisionId)
            assertEquals(localRevisionId, head.currentRevisionId)
            assertEquals(localHeadUpdatedAt, head.updatedAtUtc)

            val pullRequestId = uuid(267)
            val pullAttemptId = uuid(268)
            val pullResponse = terminalResponse(
                endpoint = "sync_pull",
                requestId = pullRequestId,
                attemptId = pullAttemptId,
                body = "pull-redelivers-post-snapshot-sequence-three",
                terminalAt = "2030-01-01T00:03:10Z",
            )
            seedSendingRequest(pullResponse, pullAttemptId)
            val pullReceipt = pullReceipt(
                requestId = pullRequestId,
                pageId = uuid(269),
                fromCursor = "bootstrap-incremental-cursor",
                nextCursor = "cursor-through-sequence-three",
                hasMore = false,
                changes = listOf(third),
                receivedAt = pullResponse.terminalAtUtc,
            )
            store.commitPullPage(pullResponse, pullReceipt, listOf(third))
            store.commitPullPage(pullResponse, pullReceipt, listOf(third))

            stream = requireNotNull(
                requireDatabase().syncReplicaDao().findStreamState(),
            )
            assertEquals("cursor-through-sequence-three", stream.appliedCursor)
            assertEquals(3L, stream.lastAppliedServerSequence)
            assertEquals(
                retainedThird,
                requireDatabase()
                    .syncReplicaDao()
                    .findServerChange(third.operationId),
            )
            head = requireNotNull(
                requireDatabase().syncReplicaDao().findEventHead(third.eventId),
            )
            assertEquals(3L, head.serverObservedSequence)
            assertEquals(REVISION_THREE_ID, head.serverCurrentRevisionId)
            assertEquals(localRevisionId, head.currentRevisionId)
            assertEquals(localHeadUpdatedAt, head.updatedAtUtc)
        }

    @Test
    fun exactBootstrapReplayRejectsCorruptedEventAndHeadProjections() =
        runBlocking {
            val corruptions = listOf(
                "event_owner" to "event_id_collision",
                "event_kind" to "event_id_collision",
                "missing_head" to "replica_materialization_missing",
                "contradictory_head" to "replica_head_drift",
                "inflated_head" to "replica_head_drift",
                "stale_coherent_head" to "replica_head_drift",
                "unbacked_local_current" to "replica_head_drift",
                "receipt_downgrade_missing_head" to "bootstrap_receipt_drift",
            )
            corruptions.forEachIndexed { index, (corruption, expectedError) ->
                if (index > 0) {
                    closeDatabase()
                    context.deleteDatabase(databaseName)
                    openDatabase()
                }
                createLocalPendingNote()
                seedStream(appliedCursor = "cursor-before-corruption-$index")
                seedBootstrapSession()
                val root = fixture.change(sequence = 1)
                val second = fixture.change(
                    sequence = 2,
                    revisionId = REVISION_TWO_ID,
                    captureId = CAPTURE_TWO_ID,
                    operationId = OPERATION_TWO_ID,
                    revisionNo = 2,
                    parentRevisionId = REVISION_ONE_ID,
                )
                val changes = listOf(root, second)
                val requestId = uuid(1_100L + index * 10)
                val attemptId = uuid(1_101L + index * 10)
                val response = terminalResponse(
                    endpoint = "sync_bootstrap",
                    requestId = requestId,
                    attemptId = attemptId,
                    body = "bootstrap-before-$corruption",
                    terminalAt = "2030-01-01T00:10:0${index}Z",
                )
                seedSendingRequest(response, attemptId)
                val receipt = bootstrapReceipt(
                    requestId = requestId,
                    pageId = uuid(1_102L + index * 10),
                    pageIndex = 0,
                    fromCursor = null,
                    nextCursor = null,
                    complete = true,
                    changes = changes,
                    receivedAt = response.terminalAtUtc,
                )
                val store = SyncPersistenceStore(requireDatabase())
                store.commitBootstrapPage(response, receipt, changes)
                val retainedReceipt = requireNotNull(
                    requireDatabase()
                        .syncReplicaDao()
                        .findServerChange(root.operationId),
                )

                val sqlite = requireDatabase().openHelper.writableDatabase
                when (corruption) {
                    "event_owner" -> {
                        val alternateOwner = requireNotNull(
                            requireDatabase().identityDao().findIdentity(),
                        ).localOwnerId
                        sqlite.execSQL(
                            """
                            UPDATE local_life_event
                            SET local_owner_id = '$alternateOwner'
                            WHERE event_id = '${root.eventId}'
                            """.trimIndent(),
                        )
                    }

                    "event_kind" -> sqlite.execSQL(
                        """
                        UPDATE local_life_event
                        SET kind = 'corrupted_note_kind'
                        WHERE event_id = '${root.eventId}'
                        """.trimIndent(),
                    )

                    "missing_head" -> sqlite.execSQL(
                        """
                        DELETE FROM local_event_head
                        WHERE event_id = '${root.eventId}'
                        """.trimIndent(),
                    )

                    "contradictory_head" -> sqlite.execSQL(
                        """
                        UPDATE local_event_head
                        SET server_current_revision_id = NULL
                        WHERE event_id = '${root.eventId}'
                        """.trimIndent(),
                    )

                    "inflated_head" -> sqlite.execSQL(
                        """
                        UPDATE local_event_head
                        SET server_observed_sequence = 3
                        WHERE event_id = '${root.eventId}'
                        """.trimIndent(),
                    )

                    "stale_coherent_head" -> sqlite.execSQL(
                        """
                        UPDATE local_event_head
                        SET current_revision_id = '$REVISION_ONE_ID',
                            server_current_revision_id = '$REVISION_ONE_ID',
                            server_observed_sequence = 1
                        WHERE event_id = '${root.eventId}'
                        """.trimIndent(),
                    )

                    "unbacked_local_current" -> sqlite.execSQL(
                        """
                        UPDATE local_event_head
                        SET current_revision_id = '$REVISION_ONE_ID'
                        WHERE event_id = '${root.eventId}'
                        """.trimIndent(),
                    )

                    "receipt_downgrade_missing_head" -> {
                        sqlite.execSQL(
                            """
                            UPDATE sync_page_receipt
                            SET state = 'staged', applied_at_utc = NULL
                            WHERE page_id = '${receipt.pageId}'
                            """.trimIndent(),
                        )
                        sqlite.execSQL(
                            """
                            DELETE FROM local_event_head
                            WHERE event_id = '${root.eventId}'
                            """.trimIndent(),
                        )
                    }
                }

                val failure = assertThrows(ReplicaIntegrityException::class.java) {
                    runBlocking {
                        store.commitBootstrapPage(response, receipt, changes)
                    }
                }
                assertEquals(expectedError, failure.errorCode)
                assertEquals(
                    retainedReceipt,
                    requireDatabase()
                        .syncReplicaDao()
                        .findServerChange(root.operationId),
                )
                val stream = requireNotNull(
                    requireDatabase().syncReplicaDao().findStreamState(),
                )
                assertEquals("integrity_halted", stream.phase)
                assertEquals(expectedError, stream.integrityErrorCode)
                assertEquals(
                    response.terminalAtUtc,
                    requireNotNull(
                        requireDatabase()
                            .syncTransportDao()
                            .findRequest("sync_bootstrap", requestId),
                    ).terminalAtUtc,
                )
            }
        }

    @Test
    fun bootstrapSecondRootCollisionPreservesLocalPendingEvent() = runBlocking {
        createLocalPendingNote()
        val identity = requireNotNull(requireDatabase().identityDao().findIdentity())
        val originalHead = requireNotNull(
            requireDatabase()
                .syncReplicaDao()
                .findEventHead(CURRENT_LOCAL_EVENT_ID),
        )
        val originalOutbox = requireNotNull(
            requireDatabase()
                .noteMutationDao()
                .findOutbox(CURRENT_LOCAL_OPERATION_ID),
        )
        seedStream(appliedCursor = "cursor-before-root-collision")
        seedBootstrapSession()
        val collidingRoot = fixture.change(
            sequence = 1,
            eventId = CURRENT_LOCAL_EVENT_ID,
            installationId = identity.installationId,
            localOwnerId = identity.localOwnerId,
            deviceId = requireNotNull(identity.serverDeviceId),
        )
        val requestId = uuid(1_200)
        val attemptId = uuid(1_201)
        val pageId = uuid(1_202)
        val response = terminalResponse(
            endpoint = "sync_bootstrap",
            requestId = requestId,
            attemptId = attemptId,
            body = "bootstrap-second-root-collision",
            terminalAt = "2030-01-01T00:20:00Z",
        )
        seedSendingRequest(response, attemptId)
        val failure = assertThrows(ReplicaIntegrityException::class.java) {
            runBlocking {
                SyncPersistenceStore(requireDatabase()).commitBootstrapPage(
                    response = response,
                    receipt = bootstrapReceipt(
                        requestId = requestId,
                        pageId = pageId,
                        pageIndex = 0,
                        fromCursor = null,
                        nextCursor = null,
                        complete = true,
                        changes = listOf(collidingRoot),
                        receivedAt = response.terminalAtUtc,
                    ),
                    changes = listOf(collidingRoot),
                )
            }
        }
        assertEquals("event_id_collision", failure.errorCode)

        assertEquals(
            originalHead,
            requireDatabase()
                .syncReplicaDao()
                .findEventHead(CURRENT_LOCAL_EVENT_ID),
        )
        val retainedOutbox = requireNotNull(
            requireDatabase()
                .noteMutationDao()
                .findOutbox(CURRENT_LOCAL_OPERATION_ID),
        )
        assertEquals(originalOutbox.operationId, retainedOutbox.operationId)
        assertEquals(originalOutbox.captureId, retainedOutbox.captureId)
        assertEquals(originalOutbox.eventId, retainedOutbox.eventId)
        assertEquals(originalOutbox.revisionId, retainedOutbox.revisionId)
        assertEquals(originalOutbox.state, retainedOutbox.state)
        assertEquals(originalOutbox.activeBatchId, retainedOutbox.activeBatchId)
        assertEquals(
            originalOutbox.legacyOperationContentSha256,
            retainedOutbox.legacyOperationContentSha256,
        )
        assertEquals(
            originalOutbox.legacyOperationJcs.toList(),
            retainedOutbox.legacyOperationJcs.toList(),
        )
        assertNull(
            requireDatabase()
                .syncReplicaDao()
                .findCapture(collidingRoot.captureId),
        )
        assertNull(
            requireDatabase()
                .syncReplicaDao()
                .findServerChange(collidingRoot.operationId),
        )
        assertNull(requireDatabase().syncReplicaDao().findPageReceipt(pageId))
        assertEquals(
            0,
            requireDatabase().syncReplicaDao().countReplicaCursor(
                BOOTSTRAP_ID,
                "bootstrap-incremental-cursor",
            ),
        )
        val request = requireNotNull(
            requireDatabase()
                .syncTransportDao()
                .findRequest("sync_bootstrap", requestId),
        )
        assertEquals("sending", request.state)
        assertNull(request.exactResponseBody)
        val stream = requireNotNull(
            requireDatabase().syncReplicaDao().findStreamState(),
        )
        assertEquals("integrity_halted", stream.phase)
        assertEquals("event_id_collision", stream.integrityErrorCode)
        assertEquals("cursor-before-root-collision", stream.appliedCursor)
    }

    @Test
    fun bootstrapCannotRecreateStaleHeadBehindNewerRetainedReceipt() =
        runBlocking {
            createLocalPendingNote()
            seedStream(appliedCursor = "cursor-before-initial-head")
            seedBootstrapSession()
            val root = fixture.change(sequence = 1)
            val second = fixture.change(
                sequence = 2,
                revisionId = REVISION_TWO_ID,
                captureId = CAPTURE_TWO_ID,
                operationId = OPERATION_TWO_ID,
                revisionNo = 2,
                parentRevisionId = REVISION_ONE_ID,
            )
            val initialRequestId = uuid(1_400)
            val initialAttemptId = uuid(1_401)
            val initialResponse = terminalResponse(
                endpoint = "sync_bootstrap",
                requestId = initialRequestId,
                attemptId = initialAttemptId,
                body = "initial-head-through-sequence-two",
                terminalAt = "2030-01-01T00:40:00Z",
            )
            seedSendingRequest(initialResponse, initialAttemptId)
            SyncPersistenceStore(requireDatabase()).commitBootstrapPage(
                response = initialResponse,
                receipt = bootstrapReceipt(
                    requestId = initialRequestId,
                    pageId = uuid(1_402),
                    pageIndex = 0,
                    fromCursor = null,
                    nextCursor = null,
                    complete = true,
                    changes = listOf(root, second),
                    receivedAt = initialResponse.terminalAtUtc,
                ),
                changes = listOf(root, second),
            )
            requireDatabase().openHelper.writableDatabase.execSQL(
                """
                DELETE FROM local_event_head
                WHERE event_id = '${root.eventId}'
                """.trimIndent(),
            )
            requireDatabase().openHelper.writableDatabase.execSQL(
                """
                UPDATE sync_stream_state
                SET phase = 'bootstrap_required',
                    bootstrap_required = 1,
                    applied_cursor = 'cursor-before-stale-head-rebuild'
                WHERE singleton_id = 1
                """.trimIndent(),
            )
            supersedeWithReplacementBootstrap()

            val replacementRequestId = uuid(1_403)
            val replacementAttemptId = uuid(1_404)
            val replacementPageId = uuid(1_405)
            val replacementResponse = terminalResponse(
                endpoint = "sync_bootstrap",
                requestId = replacementRequestId,
                attemptId = replacementAttemptId,
                body = "replacement-snapshot-through-sequence-one",
                terminalAt = "2030-01-01T00:41:00Z",
            )
            seedSendingRequest(replacementResponse, replacementAttemptId)
            val failure = assertThrows(ReplicaIntegrityException::class.java) {
                runBlocking {
                    SyncPersistenceStore(requireDatabase()).commitBootstrapPage(
                        response = replacementResponse,
                        receipt = bootstrapReceipt(
                            requestId = replacementRequestId,
                            pageId = replacementPageId,
                            pageIndex = 0,
                            fromCursor = null,
                            nextCursor = null,
                            complete = true,
                            changes = listOf(root),
                            receivedAt = replacementResponse.terminalAtUtc,
                            bootstrapId = REPLACEMENT_BOOTSTRAP_ID,
                        ),
                        changes = listOf(root),
                    )
                }
            }
            assertEquals("replica_head_drift", failure.errorCode)
            assertNull(
                requireDatabase().syncReplicaDao().findEventHead(root.eventId),
            )
            assertNotNull(
                requireDatabase()
                    .syncReplicaDao()
                    .findServerChange(second.operationId),
            )
            assertNull(
                requireDatabase()
                    .syncReplicaDao()
                    .findPageReceipt(replacementPageId),
            )
            val request = requireNotNull(
                requireDatabase()
                    .syncTransportDao()
                    .findRequest("sync_bootstrap", replacementRequestId),
            )
            assertEquals("sending", request.state)
            assertNull(request.exactResponseBody)
            val stream = requireNotNull(
                requireDatabase().syncReplicaDao().findStreamState(),
            )
            assertEquals("integrity_halted", stream.phase)
            assertEquals("replica_head_drift", stream.integrityErrorCode)
            assertEquals(
                "cursor-before-stale-head-rebuild",
                stream.appliedCursor,
            )
        }

    @Test
    fun replacementOwnerDescendantKeepsRootOwnerAndPerRevisionProvenance() =
        runBlocking {
            createLocalPendingNote()
            val replacementIdentity = requireNotNull(
                requireDatabase().identityDao().findIdentity(),
            )
            val replacementDeviceId =
                requireNotNull(replacementIdentity.serverDeviceId)
            seedStream(appliedCursor = "cursor-before-replacement-owner")
            val root = fixture.change(sequence = 1)
            commitSinglePageBootstrap(root)

            val descendant = fixture.change(
                sequence = 2,
                revisionId = REVISION_TWO_ID,
                captureId = CAPTURE_TWO_ID,
                operationId = OPERATION_TWO_ID,
                revisionNo = 2,
                parentRevisionId = REVISION_ONE_ID,
                installationId = replacementIdentity.installationId,
                localOwnerId = replacementIdentity.localOwnerId,
                deviceId = replacementDeviceId,
            )
            val requestId = uuid(1_300)
            val attemptId = uuid(1_301)
            val response = terminalResponse(
                endpoint = "sync_pull",
                requestId = requestId,
                attemptId = attemptId,
                body = "replacement-owner-descendant",
                terminalAt = "2030-01-01T00:30:00Z",
            )
            seedSendingRequest(response, attemptId)
            val receipt = pullReceipt(
                requestId = requestId,
                pageId = uuid(1_302),
                fromCursor = "bootstrap-incremental-cursor",
                nextCursor = "cursor-through-replacement-descendant",
                hasMore = false,
                changes = listOf(descendant),
                receivedAt = response.terminalAtUtc,
            )
            val store = SyncPersistenceStore(requireDatabase())
            store.commitPullPage(response, receipt, listOf(descendant))
            store.commitPullPage(response, receipt, listOf(descendant))

            val event = requireNotNull(
                requireDatabase().syncReplicaDao().findEvent(root.eventId),
            )
            assertEquals(HISTORICAL_OWNER_ID, event.localOwnerId)
            assertEquals(
                HISTORICAL_OWNER_ID,
                requireNotNull(
                    requireDatabase()
                        .syncReplicaDao()
                        .findCapture(root.captureId),
                ).localOwnerId,
            )
            assertEquals(
                replacementIdentity.localOwnerId,
                requireNotNull(
                    requireDatabase()
                        .syncReplicaDao()
                        .findCapture(descendant.captureId),
                ).localOwnerId,
            )
            assertEquals(
                SUBMITTING_DEVICE_ID,
                requireNotNull(
                    requireDatabase()
                        .syncReplicaDao()
                        .findInstallation(HISTORICAL_INSTALLATION_ID),
                ).serverDeviceId,
            )
            assertEquals(
                replacementDeviceId,
                requireNotNull(
                    requireDatabase()
                        .syncReplicaDao()
                        .findInstallation(replacementIdentity.installationId),
                ).serverDeviceId,
            )
            assertEquals(
                HISTORICAL_INSTALLATION_ID,
                requireNotNull(
                    requireDatabase()
                        .syncReplicaDao()
                        .findOwner(HISTORICAL_OWNER_ID),
                ).installationId,
            )
            assertEquals(
                replacementIdentity.installationId,
                requireNotNull(
                    requireDatabase()
                        .syncReplicaDao()
                        .findOwner(replacementIdentity.localOwnerId),
                ).installationId,
            )
            val stream = requireNotNull(
                requireDatabase().syncReplicaDao().findStreamState(),
            )
            assertEquals("incremental", stream.phase)
            assertEquals(2L, stream.lastAppliedServerSequence)
            assertEquals(
                "cursor-through-replacement-descendant",
                stream.appliedCursor,
            )
            assertNull(stream.integrityErrorCode)
        }

    @Test
    fun pullPageAndCursorCommitTogetherThenDivergenceHaltsWithoutPartialApply() =
        runBlocking {
            seedStream(appliedCursor = "pull-cursor-zero")
            val root = fixture.change(sequence = 1)
            commitSinglePageBootstrap(root)

            val second = fixture.change(
                sequence = 2,
                revisionId = REVISION_TWO_ID,
                captureId = CAPTURE_TWO_ID,
                operationId = OPERATION_TWO_ID,
                revisionNo = 2,
                parentRevisionId = REVISION_ONE_ID,
            )
            val requestId = uuid(201)
            val pageId = uuid(202)
            val attemptId = uuid(203)
            val response = terminalResponse(
                endpoint = "sync_pull",
                requestId = requestId,
                attemptId = attemptId,
                body = "pull-page-two",
                terminalAt = "2030-01-01T00:01:10Z",
            )
            seedSendingRequest(response, attemptId)
            val receipt = pullReceipt(
                requestId = requestId,
                pageId = pageId,
                fromCursor = "bootstrap-incremental-cursor",
                nextCursor = "pull-cursor-two",
                hasMore = false,
                changes = listOf(second),
                receivedAt = response.terminalAtUtc,
            )
            val store = SyncPersistenceStore(requireDatabase())
            store.commitPullPage(response, receipt, listOf(second))
            store.commitPullPage(response, receipt, listOf(second))

            var stream = requireNotNull(requireDatabase().syncReplicaDao().findStreamState())
            assertEquals("pull-cursor-two", stream.appliedCursor)
            assertEquals(2L, stream.lastAppliedServerSequence)
            assertNotNull(
                requireDatabase().syncReplicaDao().findServerChange(second.operationId),
            )

            val malformed = fixture.change(
                sequence = 3,
                revisionId = REVISION_THREE_ID,
                captureId = CAPTURE_THREE_ID,
                operationId = OPERATION_THREE_ID,
                revisionNo = 2,
                parentRevisionId = uuid(999),
            )
            val badRequestId = uuid(204)
            val badPageId = uuid(205)
            val badAttemptId = uuid(206)
            val badResponse = terminalResponse(
                endpoint = "sync_pull",
                requestId = badRequestId,
                attemptId = badAttemptId,
                body = "invalid-pull-page",
                terminalAt = "2030-01-01T00:02:10Z",
            )
            seedSendingRequest(badResponse, badAttemptId)
            val badReceipt = pullReceipt(
                requestId = badRequestId,
                pageId = badPageId,
                fromCursor = "pull-cursor-two",
                nextCursor = "pull-cursor-three",
                hasMore = false,
                changes = listOf(malformed),
                receivedAt = badResponse.terminalAtUtc,
            )

            assertThrows(ReplicaIntegrityException::class.java) {
                runBlocking {
                    store.commitPullPage(
                        badResponse,
                        badReceipt,
                        listOf(malformed),
                    )
                }
            }
            stream = requireNotNull(requireDatabase().syncReplicaDao().findStreamState())
            assertEquals("pull-cursor-two", stream.appliedCursor)
            assertEquals(2L, stream.lastAppliedServerSequence)
            assertEquals("integrity_halted", stream.phase)
            assertEquals("replica_topology_invalid", stream.integrityErrorCode)
            assertNull(requireDatabase().syncReplicaDao().findPageReceipt(badPageId))
            assertNull(
                requireDatabase().syncReplicaDao().findServerChange(malformed.operationId),
            )
            assertEquals(
                "sending",
                requireNotNull(
                    requireDatabase()
                        .syncTransportDao()
                        .findRequest("sync_pull", badRequestId),
                ).state,
            )
        }

    @Test
    fun pullExactReplayRejectsRetainedReceiptStateDrift() = runBlocking {
        seedStream(appliedCursor = "pull-receipt-cursor-zero")
        val root = fixture.change(sequence = 1)
        commitSinglePageBootstrap(root)
        val second = fixture.change(
            sequence = 2,
            revisionId = REVISION_TWO_ID,
            captureId = CAPTURE_TWO_ID,
            operationId = OPERATION_TWO_ID,
            revisionNo = 2,
            parentRevisionId = REVISION_ONE_ID,
        )
        val requestId = uuid(211)
        val pageId = uuid(212)
        val attemptId = uuid(213)
        val response = terminalResponse(
            endpoint = "sync_pull",
            requestId = requestId,
            attemptId = attemptId,
            body = "pull-receipt-state-drift",
            terminalAt = "2030-01-01T00:01:20Z",
        )
        seedSendingRequest(response, attemptId)
        val receipt = pullReceipt(
            requestId = requestId,
            pageId = pageId,
            fromCursor = "bootstrap-incremental-cursor",
            nextCursor = "pull-receipt-cursor-two",
            hasMore = false,
            changes = listOf(second),
            receivedAt = response.terminalAtUtc,
        )
        val store = SyncPersistenceStore(requireDatabase())
        store.commitPullPage(response, receipt, listOf(second))
        requireDatabase().openHelper.writableDatabase.execSQL(
            """
            UPDATE sync_page_receipt
            SET state = 'staged', applied_at_utc = NULL
            WHERE page_id = '$pageId'
            """.trimIndent(),
        )

        val failure = assertThrows(ReplicaIntegrityException::class.java) {
            runBlocking {
                store.commitPullPage(response, receipt, listOf(second))
            }
        }
        assertEquals("pull_receipt_drift", failure.errorCode)
        val stream = requireNotNull(requireDatabase().syncReplicaDao().findStreamState())
        assertEquals("integrity_halted", stream.phase)
        assertEquals("pull_receipt_drift", stream.integrityErrorCode)
        assertEquals("pull-receipt-cursor-two", stream.appliedCursor)
        assertEquals(2L, stream.lastAppliedServerSequence)
        val retainedReceipt = requireNotNull(
            requireDatabase().syncReplicaDao().findPageReceipt(pageId),
        )
        assertEquals("staged", retainedReceipt.state)
        assertNull(retainedReceipt.appliedAtUtc)
    }

    @Test
    fun pullRedeliveryBehindPushedHeadKeepsHeadAndRejectsSkippedAck() = runBlocking {
        seedStream(appliedCursor = "cursor-before-bootstrap")
        seedBootstrapSession()
        val root = fixture.change(sequence = 1)
        val second = fixture.change(
            sequence = 2,
            revisionId = REVISION_TWO_ID,
            captureId = CAPTURE_TWO_ID,
            operationId = OPERATION_TWO_ID,
            revisionNo = 2,
            parentRevisionId = REVISION_ONE_ID,
        )
        val bootstrapRequestId = uuid(251)
        val bootstrapAttemptId = uuid(252)
        val bootstrapResponse = terminalResponse(
            endpoint = "sync_bootstrap",
            requestId = bootstrapRequestId,
            attemptId = bootstrapAttemptId,
            body = "bootstrap-with-head-ahead",
            terminalAt = "2030-01-01T00:01:10Z",
        )
        seedSendingRequest(bootstrapResponse, bootstrapAttemptId)
        SyncPersistenceStore(requireDatabase()).commitBootstrapPage(
            response = bootstrapResponse,
            receipt = bootstrapReceipt(
                requestId = bootstrapRequestId,
                pageId = uuid(253),
                pageIndex = 0,
                fromCursor = null,
                nextCursor = null,
                complete = true,
                changes = listOf(root, second),
                receivedAt = bootstrapResponse.terminalAtUtc,
            ),
            changes = listOf(root, second),
        )

        // Model a durable push receipt/head observed ahead of the last pull
        // cursor: receipts stay at 1..2 while the applied cursor is at zero.
        requireDatabase().openHelper.writableDatabase.execSQL(
            """
            UPDATE sync_stream_state
            SET applied_cursor = 'lagging-pull-cursor',
                last_applied_server_sequence = 0
            WHERE singleton_id = 1
            """.trimIndent(),
        )
        val pullRequestId = uuid(254)
        val pullAttemptId = uuid(255)
        val pullResponse = terminalResponse(
            endpoint = "sync_pull",
            requestId = pullRequestId,
            attemptId = pullAttemptId,
            body = "pull-redelivers-sequence-one",
            terminalAt = "2030-01-01T00:02:10Z",
        )
        seedSendingRequest(pullResponse, pullAttemptId)
        SyncPersistenceStore(requireDatabase()).commitPullPage(
            response = pullResponse,
            receipt = pullReceipt(
                requestId = pullRequestId,
                pageId = uuid(256),
                fromCursor = "lagging-pull-cursor",
                nextCursor = "cursor-through-sequence-one",
                hasMore = true,
                changes = listOf(root),
                receivedAt = pullResponse.terminalAtUtc,
            ),
            changes = listOf(root),
        )

        val stream = requireNotNull(requireDatabase().syncReplicaDao().findStreamState())
        assertEquals("cursor-through-sequence-one", stream.appliedCursor)
        assertEquals(1L, stream.lastAppliedServerSequence)
        val head = requireNotNull(
            requireDatabase().syncReplicaDao().findEventHead(root.eventId),
        )
        assertEquals(2L, head.serverObservedSequence)
        assertEquals(REVISION_TWO_ID, head.serverCurrentRevisionId)

        // Without exact coverage of retained ACKs, this page could skip
        // sequence 2 and validate its CAS against the stale sequence-1 head.
        val divergent = fixture.change(
            sequence = 3,
            revisionId = REVISION_THREE_ID,
            captureId = CAPTURE_THREE_ID,
            operationId = OPERATION_THREE_ID,
            revisionNo = 2,
            parentRevisionId = REVISION_ONE_ID,
        )
        val badRequestId = uuid(257)
        val badAttemptId = uuid(258)
        val badPageId = uuid(259)
        val badResponse = terminalResponse(
            endpoint = "sync_pull",
            requestId = badRequestId,
            attemptId = badAttemptId,
            body = "pull-skips-retained-sequence-two",
            terminalAt = "2030-01-01T00:03:10Z",
        )
        seedSendingRequest(badResponse, badAttemptId)
        val badReceipt = pullReceipt(
            requestId = badRequestId,
            pageId = badPageId,
            fromCursor = "cursor-through-sequence-one",
            nextCursor = "cursor-through-sequence-three",
            hasMore = false,
            changes = listOf(divergent),
            receivedAt = badResponse.terminalAtUtc,
        )

        val failure = assertThrows(ReplicaIntegrityException::class.java) {
            runBlocking {
                SyncPersistenceStore(requireDatabase()).commitPullPage(
                    response = badResponse,
                    receipt = badReceipt,
                    changes = listOf(divergent),
                )
            }
        }
        assertEquals("pull_page_drift", failure.errorCode)
        val halted = requireNotNull(requireDatabase().syncReplicaDao().findStreamState())
        assertEquals("cursor-through-sequence-one", halted.appliedCursor)
        assertEquals(1L, halted.lastAppliedServerSequence)
        assertEquals("integrity_halted", halted.phase)
        assertEquals("pull_page_drift", halted.integrityErrorCode)
        assertNull(requireDatabase().syncReplicaDao().findPageReceipt(badPageId))
        assertNull(
            requireDatabase().syncReplicaDao().findServerChange(divergent.operationId),
        )
    }

    @Test
    fun cursorExpiredDiscardsShadowAndBlocksStalePull() = runBlocking {
        seedStream(appliedCursor = "committed-before-shadow")
        seedBootstrapSession()
        val root = fixture.change(sequence = 1)
        val pageRequestId = uuid(301)
        val pageAttemptId = uuid(302)
        val pageResponse = terminalResponse(
            endpoint = "sync_bootstrap",
            requestId = pageRequestId,
            attemptId = pageAttemptId,
            body = "bootstrap-stage",
            terminalAt = "2030-01-01T00:00:10Z",
        )
        seedSendingRequest(pageResponse, pageAttemptId)
        assertEquals(
            1,
            requireDatabase().syncTransportDao().storeTerminalResponse(
                endpointId = pageResponse.endpointId,
                requestIdentity = pageResponse.requestIdentity,
                expectedAttemptId = pageResponse.expectedAttemptId,
                httpStatus = pageResponse.httpStatus,
                exactResponseBody = pageResponse.exactResponseBody,
                responseSha256 = pageResponse.responseSha256,
                terminalAtUtc = pageResponse.terminalAtUtc,
                terminalErrorCode = pageResponse.terminalErrorCode,
            ),
        )
        val stagedReceipt = bootstrapReceipt(
            requestId = pageRequestId,
            pageId = uuid(303),
            pageIndex = 0,
            fromCursor = null,
            nextCursor = "expired-continuation",
            complete = false,
            changes = listOf(root),
            receivedAt = pageResponse.terminalAtUtc,
        )
        requireDatabase().syncReplicaDao().stageBootstrapPage(
            receipt = stagedReceipt,
            changes = listOf(
                root.asStaged(
                    bootstrapId = BOOTSTRAP_ID,
                    pageId = stagedReceipt.pageId,
                ),
            ),
            responseBodyBytes = pageResponse.exactResponseBody.size.toLong(),
        )

        val expiredRequestId = uuid(304)
        val expiredAttemptId = uuid(305)
        val expired = terminalResponse(
            endpoint = "sync_bootstrap",
            requestId = expiredRequestId,
            attemptId = expiredAttemptId,
            body = "cursor-expired",
            terminalAt = "2030-01-01T00:01:10Z",
            httpStatus = 410,
            errorCode = "cursor_expired",
        )
        seedSendingRequest(expired, expiredAttemptId)
        val store = SyncPersistenceStore(requireDatabase())
        val replacementIntent = bootstrapIntent(
            bootstrapId = uuid(308),
            requestId = uuid(309),
        )
        store.commitBootstrapCursorExpired(
            expired,
            BOOTSTRAP_ID,
            replacementIntent,
        )
        store.commitBootstrapCursorExpired(
            expired.copy(terminalAtUtc = "2030-01-01T00:01:40Z"),
            BOOTSTRAP_ID,
            replacementIntent,
        )

        val expiredSession = requireNotNull(
            requireDatabase().syncReplicaDao().findBootstrapSession(BOOTSTRAP_ID),
        )
        assertEquals("expired", expiredSession.state)
        assertNull(expiredSession.activeSlot)
        assertEquals(
            0,
            requireDatabase().syncReplicaDao().findStagedChanges(BOOTSTRAP_ID).size,
        )
        val expiryReceipt = requireNotNull(
            requireDatabase().syncReplicaDao().findPageReceiptByRequest(
                "sync_bootstrap",
                expiredRequestId,
            ),
        )
        assertEquals(BOOTSTRAP_ID, expiryReceipt.bootstrapId)
        assertEquals("expired", expiryReceipt.state)
        assertEquals(expired.responseSha256, expiryReceipt.pageSha256)
        assertEquals(expired.terminalAtUtc, expiryReceipt.receivedAtUtc)
        assertNull(requireDatabase().syncReplicaDao().findPageReceipt(uuid(303)))
        assertNull(
            requireDatabase().syncReplicaDao().findServerChange(root.operationId),
        )
        var stream = requireNotNull(requireDatabase().syncReplicaDao().findStreamState())
        assertEquals("committed-before-shadow", stream.appliedCursor)
        assertEquals("bootstrap_required", stream.phase)
        assertEquals(true, stream.bootstrapRequired)

        val wrongBinding = assertThrows(ReplicaIntegrityException::class.java) {
            runBlocking {
                store.commitBootstrapCursorExpired(
                    expired,
                    uuid(399),
                    replacementIntent,
                )
            }
        }
        assertEquals("bootstrap_session_missing", wrongBinding.errorCode)
        assertEquals(
            "expired",
            requireNotNull(
                requireDatabase().syncReplicaDao().findBootstrapSession(BOOTSTRAP_ID),
            ).state,
        )

        val stalePullRequestId = uuid(306)
        val stalePullAttemptId = uuid(307)
        val stalePull = terminalResponse(
            endpoint = "sync_pull",
            requestId = stalePullRequestId,
            attemptId = stalePullAttemptId,
            body = "stale-pull-after-cursor-expiry",
            terminalAt = "2030-01-01T00:02:10Z",
        )
        seedSendingRequest(stalePull, stalePullAttemptId)
        val staleReceipt = pullReceipt(
            requestId = stalePullRequestId,
            pageId = uuid(308),
            fromCursor = "committed-before-shadow",
            nextCursor = "committed-before-shadow",
            hasMore = false,
            changes = emptyList(),
            receivedAt = stalePull.terminalAtUtc,
        )
        assertThrows(ReplicaIntegrityException::class.java) {
            runBlocking {
                store.commitPullPage(stalePull, staleReceipt, emptyList())
            }
        }
        stream = requireNotNull(requireDatabase().syncReplicaDao().findStreamState())
        assertEquals("committed-before-shadow", stream.appliedCursor)
        assertEquals("integrity_halted", stream.phase)
        assertEquals("bootstrap_required", stream.integrityErrorCode)
        assertNull(requireDatabase().syncReplicaDao().findPageReceipt(staleReceipt.pageId))
    }

    @Test
    fun lateBootstrapPageCannotHaltReplacementBootstrap() = runBlocking {
        seedStream(appliedCursor = "committed-before-late-page")
        seedBootstrapSession()
        val root = fixture.change(sequence = 1)
        val pageRequestId = uuid(341)
        val pageAttemptId = uuid(342)
        val pageId = uuid(343)
        val response = terminalResponse(
            endpoint = "sync_bootstrap",
            requestId = pageRequestId,
            attemptId = pageAttemptId,
            body = "late-bootstrap-page",
            terminalAt = "2030-01-01T00:01:10Z",
        )
        seedSendingRequest(response, pageAttemptId)
        val receipt = bootstrapReceipt(
            requestId = pageRequestId,
            pageId = pageId,
            pageIndex = 0,
            fromCursor = null,
            nextCursor = null,
            complete = true,
            changes = listOf(root),
            receivedAt = response.terminalAtUtc,
        )
        supersedeWithReplacementBootstrap()

        SyncPersistenceStore(requireDatabase()).commitBootstrapPage(
            response,
            receipt,
            listOf(root),
        )
        val replacement = requireNotNull(
            requireDatabase()
                .syncReplicaDao()
                .findBootstrapSession(REPLACEMENT_BOOTSTRAP_ID),
        )
        assertEquals("staging", replacement.state)
        assertEquals(1, replacement.activeSlot)
        val stream = requireNotNull(requireDatabase().syncReplicaDao().findStreamState())
        assertNull(stream.integrityErrorCode)
        assertEquals(
            "sending",
            requireNotNull(
                requireDatabase()
                    .syncTransportDao()
                    .findRequest("sync_bootstrap", pageRequestId),
            ).state,
        )
        assertNull(requireDatabase().syncReplicaDao().findPageReceipt(pageId))
        assertNull(
            requireDatabase().syncReplicaDao().findServerChange(root.operationId),
        )
    }

    @Test
    fun lateCursorExpiredCannotDiscardReplacementBootstrap() = runBlocking {
        seedStream(appliedCursor = "committed-before-replacement")
        seedBootstrapSession()
        val expiredRequestId = uuid(351)
        val expiredAttemptId = uuid(352)
        val expired = terminalResponse(
            endpoint = "sync_bootstrap",
            requestId = expiredRequestId,
            attemptId = expiredAttemptId,
            body = "late-cursor-expired",
            terminalAt = "2030-01-01T00:01:10Z",
            httpStatus = 410,
            errorCode = "cursor_expired",
        )
        seedSendingRequest(expired, expiredAttemptId)
        supersedeWithReplacementBootstrap()

        SyncPersistenceStore(requireDatabase()).commitBootstrapCursorExpired(
            expired,
            BOOTSTRAP_ID,
            bootstrapIntent(
                bootstrapId = uuid(353),
                requestId = uuid(354),
            ),
        )
        val replacement = requireNotNull(
            requireDatabase()
                .syncReplicaDao()
                .findBootstrapSession(REPLACEMENT_BOOTSTRAP_ID),
        )
        assertEquals("staging", replacement.state)
        assertEquals(1, replacement.activeSlot)
        assertNull(
            requireDatabase().syncReplicaDao().findPageReceiptByRequest(
                "sync_bootstrap",
                expiredRequestId,
            ),
        )
        val stream = requireNotNull(requireDatabase().syncReplicaDao().findStreamState())
        assertNull(stream.integrityErrorCode)
    }

    @Test
    fun lateCursorInvalidCannotHaltReplacementBootstrap() = runBlocking {
        seedStream(appliedCursor = "committed-before-late-cursor-invalid")
        seedBootstrapSession()
        val invalidRequestId = uuid(355)
        val invalidAttemptId = uuid(356)
        val invalid = terminalResponse(
            endpoint = "sync_bootstrap",
            requestId = invalidRequestId,
            attemptId = invalidAttemptId,
            body = "late-cursor-invalid",
            terminalAt = "2030-01-01T00:01:20Z",
            httpStatus = 400,
            errorCode = "cursor_invalid",
        )
        seedSendingRequest(invalid, invalidAttemptId)
        supersedeWithReplacementBootstrap()

        SyncPersistenceStore(requireDatabase()).commitCursorInvalid(invalid)

        val replacement = requireNotNull(
            requireDatabase()
                .syncReplicaDao()
                .findBootstrapSession(REPLACEMENT_BOOTSTRAP_ID),
        )
        assertEquals("staging", replacement.state)
        assertEquals(1, replacement.activeSlot)
        val stream = requireNotNull(requireDatabase().syncReplicaDao().findStreamState())
        assertEquals("bootstrap_required", stream.phase)
        assertNull(stream.integrityErrorCode)
        val request = requireNotNull(
            requireDatabase()
                .syncTransportDao()
                .findRequest("sync_bootstrap", invalidRequestId),
        )
        assertEquals("terminal_local", request.state)
        assertEquals("bootstrap_superseded", request.terminalErrorCode)
        assertEquals(invalid.terminalAtUtc, request.terminalAtUtc)
        assertNull(request.activeAttemptId)
        assertNull(request.leaseExpiresAtEpochMs)
        assertNull(request.nextAttemptAtEpochMs)
        assertNull(request.terminalHttpStatus)
        assertNull(request.exactResponseBody)
        assertNull(request.responseSha256)
        assertEquals(
            0,
            requireDatabase().syncTransportDao().claimAttempt(
                endpointId = "sync_bootstrap",
                requestIdentity = invalidRequestId,
                credentialEpochId = CREDENTIAL_EPOCH_ID,
                accessGenerationUsed = 1,
                attemptId = uuid(359),
                attemptedAtEpochMs = 3,
                leaseExpiresAtEpochMs = 4,
                updatedAtUtc = "2030-01-01T00:01:30Z",
            ),
        )
    }

    @Test
    fun activeBootstrapCursorInvalidHaltsAndReplaysExactly() = runBlocking {
        seedStream(appliedCursor = "committed-before-active-cursor-invalid")
        seedBootstrapSession()
        val invalidRequestId = uuid(357)
        val invalidAttemptId = uuid(358)
        val invalid = terminalResponse(
            endpoint = "sync_bootstrap",
            requestId = invalidRequestId,
            attemptId = invalidAttemptId,
            body = "active-bootstrap-cursor-invalid",
            terminalAt = "2030-01-01T00:01:30Z",
            httpStatus = 400,
            errorCode = "cursor_invalid",
        )
        seedSendingRequest(invalid, invalidAttemptId)
        val store = SyncPersistenceStore(requireDatabase())
        store.commitCursorInvalid(invalid)
        store.commitCursorInvalid(
            invalid.copy(terminalAtUtc = "2030-01-01T00:01:40Z"),
        )

        val stream = requireNotNull(requireDatabase().syncReplicaDao().findStreamState())
        assertEquals("integrity_halted", stream.phase)
        assertEquals("cursor_invalid", stream.integrityErrorCode)
        assertEquals("committed-before-active-cursor-invalid", stream.appliedCursor)
        assertEquals(invalid.terminalAtUtc, stream.updatedAtUtc)
        val request = requireNotNull(
            requireDatabase()
                .syncTransportDao()
                .findRequest("sync_bootstrap", invalidRequestId),
        )
        assertEquals("terminal", request.state)
        assertEquals(invalid.terminalAtUtc, request.terminalAtUtc)
        assertEquals(invalid.responseSha256, request.responseSha256)
        assertEquals(
            invalid.exactResponseBody.toList(),
            requireNotNull(request.exactResponseBody).toList(),
        )
    }

    @Test
    fun terminalCursorInvalidReplayVerifiesReceiptWithoutHaltingReplacementBootstrap() =
        runBlocking {
            seedStream(appliedCursor = "committed-before-terminal-replay")
            seedBootstrapSession()
            val invalidRequestId = uuid(381)
            val invalidAttemptId = uuid(382)
            val invalid = terminalResponse(
                endpoint = "sync_bootstrap",
                requestId = invalidRequestId,
                attemptId = invalidAttemptId,
                body = "terminal-bootstrap-cursor-invalid",
                terminalAt = "2030-01-01T00:01:40Z",
                httpStatus = 400,
                errorCode = "cursor_invalid",
            )
            seedSendingRequest(invalid, invalidAttemptId)
            val store = SyncPersistenceStore(requireDatabase())
            store.commitCursorInvalid(invalid)
            supersedeWithReplacementBootstrap()
            requireDatabase().openHelper.writableDatabase.execSQL(
                """
                UPDATE sync_stream_state
                SET phase = 'bootstrap_required',
                    bootstrap_required = 1,
                    integrity_error_code = NULL,
                    updated_at_utc = '2030-01-01T00:01:50Z'
                WHERE singleton_id = 1
                """.trimIndent(),
            )

            store.commitCursorInvalid(
                invalid.copy(terminalAtUtc = "2030-01-01T00:02:00Z"),
            )
            var stream = requireNotNull(
                requireDatabase().syncReplicaDao().findStreamState(),
            )
            assertEquals("bootstrap_required", stream.phase)
            assertNull(stream.integrityErrorCode)
            val replacement = requireNotNull(
                requireDatabase()
                    .syncReplicaDao()
                    .findBootstrapSession(REPLACEMENT_BOOTSTRAP_ID),
            )
            assertEquals("staging", replacement.state)
            assertEquals(1, replacement.activeSlot)

            val divergentBody =
                "terminal-bootstrap-cursor-invalid-drift"
                    .toByteArray(StandardCharsets.UTF_8)
            val divergent = invalid.copy(
                exactResponseBody = divergentBody,
                responseSha256 = sha256(divergentBody),
                terminalAtUtc = "2030-01-01T00:02:10Z",
            )
            val failure = assertThrows(ReplicaIntegrityException::class.java) {
                runBlocking {
                    store.commitCursorInvalid(divergent)
                }
            }
            assertEquals("terminal_response_drift", failure.errorCode)
            stream = requireNotNull(requireDatabase().syncReplicaDao().findStreamState())
            assertEquals("bootstrap_required", stream.phase)
            assertNull(stream.integrityErrorCode)
            val retained = requireNotNull(
                requireDatabase()
                    .syncTransportDao()
                    .findRequest("sync_bootstrap", invalidRequestId),
            )
            assertEquals("terminal", retained.state)
            assertEquals(invalid.terminalAtUtc, retained.terminalAtUtc)
            assertEquals(invalid.responseSha256, retained.responseSha256)
            assertEquals(
                invalid.exactResponseBody.toList(),
                requireNotNull(retained.exactResponseBody).toList(),
            )
        }

    @Test
    fun activeBootstrapCursorInvalidBindingDriftHaltsWithoutInstallingResponse() =
        runBlocking {
            seedStream(appliedCursor = "committed-before-binding-drift")
            seedBootstrapSession()
            val invalidRequestId = uuid(385)
            val invalidAttemptId = uuid(386)
            val invalid = terminalResponse(
                endpoint = "sync_bootstrap",
                requestId = invalidRequestId,
                attemptId = invalidAttemptId,
                body = "active-bootstrap-binding-drift",
                terminalAt = "2030-01-01T00:02:20Z",
                httpStatus = 400,
                errorCode = "cursor_invalid",
            )
            seedSendingRequest(invalid, invalidAttemptId)
            val mismatchedRequestBody = buildJsonObject {
                put("protocol_version", "1.0.0")
                put("message_type", "bootstrap_request")
                put("request_id", uuid(999))
                put("bootstrap_id", BOOTSTRAP_ID)
                put("device_id", REPLACEMENT_DEVICE_ID)
                put("page_size", 100)
                put("page_cursor", JsonNull)
            }.toString().toByteArray(StandardCharsets.UTF_8)
            requireDatabase().openHelper.writableDatabase.execSQL(
                """
                UPDATE sync_http_request
                SET raw_request_body = ?
                WHERE endpoint_id = 'sync_bootstrap'
                  AND request_identity = '$invalidRequestId'
                """.trimIndent(),
                arrayOf(mismatchedRequestBody),
            )

            val failure = assertThrows(ReplicaIntegrityException::class.java) {
                runBlocking {
                    SyncPersistenceStore(requireDatabase()).commitCursorInvalid(invalid)
                }
            }
            assertEquals("bootstrap_request_binding_drift", failure.errorCode)
            val stream = requireNotNull(
                requireDatabase().syncReplicaDao().findStreamState(),
            )
            assertEquals("integrity_halted", stream.phase)
            assertEquals("bootstrap_request_binding_drift", stream.integrityErrorCode)
            assertEquals("committed-before-binding-drift", stream.appliedCursor)
            val request = requireNotNull(
                requireDatabase()
                    .syncTransportDao()
                    .findRequest("sync_bootstrap", invalidRequestId),
            )
            assertEquals("sending", request.state)
            assertNull(request.terminalHttpStatus)
            assertNull(request.exactResponseBody)
            assertNull(request.responseSha256)
        }

    @Test
    fun stalePullCallbackDoesNotHaltReplacementStream() = runBlocking {
        seedStream(appliedCursor = "old-device-cursor")
        val staleRequestId = uuid(361)
        val staleAttemptId = uuid(362)
        val staleResponse = terminalResponse(
            endpoint = "sync_pull",
            requestId = staleRequestId,
            attemptId = staleAttemptId,
            body = "stale-device-pull",
            terminalAt = "2030-01-01T00:01:10Z",
        )
        seedSendingRequest(staleResponse, staleAttemptId)
        requireDatabase().openHelper.writableDatabase.execSQL(
            """
            UPDATE sync_stream_state
            SET credential_epoch_id = '$REPLACEMENT_CREDENTIAL_EPOCH_ID',
                device_id = '$NEWER_DEVICE_ID',
                phase = 'incremental',
                bootstrap_required = 0
            WHERE singleton_id = 1
            """.trimIndent(),
        )
        val receipt = pullReceipt(
            requestId = staleRequestId,
            pageId = uuid(363),
            fromCursor = "old-device-cursor",
            nextCursor = "old-device-cursor",
            hasMore = false,
            changes = emptyList(),
            receivedAt = staleResponse.terminalAtUtc,
        )

        SyncPersistenceStore(requireDatabase()).commitPullPage(
            staleResponse,
            receipt,
            emptyList(),
        )
        val replacement = requireNotNull(
            requireDatabase().syncReplicaDao().findStreamState(),
        )
        assertEquals(REPLACEMENT_CREDENTIAL_EPOCH_ID, replacement.credentialEpochId)
        assertEquals(NEWER_DEVICE_ID, replacement.deviceId)
        assertEquals("incremental", replacement.phase)
        assertNull(replacement.integrityErrorCode)
        assertEquals(
            "sending",
            requireNotNull(
                requireDatabase()
                    .syncTransportDao()
                    .findRequest("sync_pull", staleRequestId),
            ).state,
        )
    }

    @Test
    fun lateTakenOverPullAttemptIsBenignNoOp() = runBlocking {
        seedStream(appliedCursor = "cursor-before-takeover")
        val requestId = uuid(371)
        val staleAttemptId = uuid(372)
        val activeAttemptId = uuid(373)
        val response = terminalResponse(
            endpoint = "sync_pull",
            requestId = requestId,
            attemptId = staleAttemptId,
            body = "late-taken-over-pull",
            terminalAt = "2030-01-01T00:01:10Z",
        )
        seedSendingRequest(response, staleAttemptId)
        requireDatabase().openHelper.writableDatabase.execSQL(
            """
            UPDATE sync_http_request
            SET active_attempt_id = '$activeAttemptId',
                attempt_count = 2
            WHERE endpoint_id = 'sync_pull'
              AND request_identity = '$requestId'
            """.trimIndent(),
        )
        requireDatabase().openHelper.writableDatabase.execSQL(
            """
            UPDATE sync_stream_state
            SET phase = 'incremental', bootstrap_required = 0
            WHERE singleton_id = 1
            """.trimIndent(),
        )
        val receipt = pullReceipt(
            requestId = requestId,
            pageId = uuid(374),
            fromCursor = "cursor-before-takeover",
            nextCursor = "cursor-before-takeover",
            hasMore = false,
            changes = emptyList(),
            receivedAt = response.terminalAtUtc,
        )

        SyncPersistenceStore(requireDatabase()).commitPullPage(
            response,
            receipt,
            emptyList(),
        )

        val request = requireNotNull(
            requireDatabase()
                .syncTransportDao()
                .findRequest("sync_pull", requestId),
        )
        assertEquals("sending", request.state)
        assertEquals(activeAttemptId, request.activeAttemptId)
        assertNull(request.exactResponseBody)
        assertNull(requireDatabase().syncReplicaDao().findPageReceipt(receipt.pageId))
        val stream = requireNotNull(requireDatabase().syncReplicaDao().findStreamState())
        assertEquals("incremental", stream.phase)
        assertEquals("cursor-before-takeover", stream.appliedCursor)
        assertNull(stream.integrityErrorCode)
    }

    @Test
    fun cursorInvalidHaltsWithoutChangingCommittedCursor() = runBlocking {
        seedStream(appliedCursor = "committed-before-invalid-cursor")
        val invalidRequestId = uuid(306)
        val invalidAttemptId = uuid(307)
        val invalid = terminalResponse(
            endpoint = "sync_pull",
            requestId = invalidRequestId,
            attemptId = invalidAttemptId,
            body = "cursor-invalid",
            terminalAt = "2030-01-01T00:02:10Z",
            httpStatus = 400,
            errorCode = "cursor_invalid",
        )
        seedSendingRequest(invalid, invalidAttemptId)
        val store = SyncPersistenceStore(requireDatabase())
        store.commitCursorInvalid(invalid)
        store.commitCursorInvalid(
            invalid.copy(terminalAtUtc = "2030-01-01T00:02:40Z"),
        )

        var stream = requireNotNull(requireDatabase().syncReplicaDao().findStreamState())
        assertEquals("integrity_halted", stream.phase)
        assertEquals("cursor_invalid", stream.integrityErrorCode)
        assertEquals("committed-before-invalid-cursor", stream.appliedCursor)
        assertEquals(invalid.terminalAtUtc, stream.updatedAtUtc)

        // Model a recovered process that retained the first terminal bytes
        // before restoring its stream phase. A divergent replay must fail
        // closed and durably halt without replacing the first observation.
        requireDatabase().openHelper.writableDatabase.execSQL(
            """
            UPDATE sync_stream_state
            SET phase = 'incremental',
                integrity_error_code = NULL,
                updated_at_utc = '2030-01-01T00:02:50Z'
            WHERE singleton_id = 1
            """.trimIndent(),
        )
        val divergentBody = "cursor-invalid-drift"
            .toByteArray(StandardCharsets.UTF_8)
        val divergent = invalid.copy(
            exactResponseBody = divergentBody,
            responseSha256 = sha256(divergentBody),
            terminalAtUtc = "2030-01-01T00:03:10Z",
        )
        val failure = assertThrows(ReplicaIntegrityException::class.java) {
            runBlocking {
                store.commitCursorInvalid(divergent)
            }
        }
        assertEquals("terminal_response_drift", failure.errorCode)

        stream = requireNotNull(requireDatabase().syncReplicaDao().findStreamState())
        assertEquals("integrity_halted", stream.phase)
        assertEquals("terminal_response_drift", stream.integrityErrorCode)
        assertEquals("committed-before-invalid-cursor", stream.appliedCursor)
        assertEquals(divergent.terminalAtUtc, stream.updatedAtUtc)
        val retainedRequest = requireNotNull(
            requireDatabase()
                .syncTransportDao()
                .findRequest("sync_pull", invalidRequestId),
        )
        assertEquals(invalid.terminalAtUtc, retainedRequest.terminalAtUtc)
        assertEquals(invalid.responseSha256, retainedRequest.responseSha256)
        assertEquals(
            invalid.exactResponseBody.toList(),
            requireNotNull(retainedRequest.exactResponseBody).toList(),
        )
    }

    @Test
    fun cursorInvalidExactReplayAfterStreamResetFailsClosed() = runBlocking {
        seedStream(appliedCursor = "committed-before-exact-invalid-replay")
        val invalidRequestId = uuid(375)
        val invalidAttemptId = uuid(376)
        val invalid = terminalResponse(
            endpoint = "sync_pull",
            requestId = invalidRequestId,
            attemptId = invalidAttemptId,
            body = "cursor-invalid-exact-replay",
            terminalAt = "2030-01-01T00:04:10Z",
            httpStatus = 400,
            errorCode = "cursor_invalid",
        )
        seedSendingRequest(invalid, invalidAttemptId)
        val store = SyncPersistenceStore(requireDatabase())
        store.commitCursorInvalid(invalid)

        requireDatabase().openHelper.writableDatabase.execSQL(
            """
            UPDATE sync_stream_state
            SET phase = 'incremental',
                integrity_error_code = NULL,
                updated_at_utc = '2030-01-01T00:04:20Z'
            WHERE singleton_id = 1
            """.trimIndent(),
        )
        val replay = invalid.copy(terminalAtUtc = "2030-01-01T00:04:30Z")
        val failure = assertThrows(ReplicaIntegrityException::class.java) {
            runBlocking {
                store.commitCursorInvalid(replay)
            }
        }
        assertEquals("cursor_invalid", failure.errorCode)

        val stream = requireNotNull(requireDatabase().syncReplicaDao().findStreamState())
        assertEquals("integrity_halted", stream.phase)
        assertEquals("cursor_invalid", stream.integrityErrorCode)
        assertEquals("committed-before-exact-invalid-replay", stream.appliedCursor)
        assertEquals(replay.terminalAtUtc, stream.updatedAtUtc)
        val retainedRequest = requireNotNull(
            requireDatabase()
                .syncTransportDao()
                .findRequest("sync_pull", invalidRequestId),
        )
        assertEquals(invalid.terminalAtUtc, retainedRequest.terminalAtUtc)
        assertEquals(invalid.responseSha256, retainedRequest.responseSha256)
        assertEquals(
            invalid.exactResponseBody.toList(),
            requireNotNull(retainedRequest.exactResponseBody).toList(),
        )
    }

    private suspend fun commitSinglePageBootstrap(change: ReplicaChangePersistence) {
        seedBootstrapSession()
        val requestId = uuid(401)
        val attemptId = uuid(402)
        val response = terminalResponse(
            endpoint = "sync_bootstrap",
            requestId = requestId,
            attemptId = attemptId,
            body = "single-bootstrap-page",
            terminalAt = "2030-01-01T00:00:10Z",
        )
        seedSendingRequest(response, attemptId)
        SyncPersistenceStore(requireDatabase()).commitBootstrapPage(
            response = response,
            receipt = bootstrapReceipt(
                requestId = requestId,
                pageId = uuid(403),
                pageIndex = 0,
                fromCursor = null,
                nextCursor = null,
                complete = true,
                changes = listOf(change),
                receivedAt = response.terminalAtUtc,
            ),
            changes = listOf(change),
        )
    }

    private suspend fun createLocalPendingNote() {
        val recordedAt = OffsetDateTime.parse("2029-12-31T23:00:00+07:00")
        RoomNotesRepository(
            database = requireDatabase(),
            collectorVersion = "replica-instrumented-test",
        ).create(
            CreateNoteCommand(
                ids = MutationIds(
                    operationId = UUID.fromString(CURRENT_LOCAL_OPERATION_ID),
                    captureId = UUID.fromString(CURRENT_LOCAL_CAPTURE_ID),
                    eventId = UUID.fromString(CURRENT_LOCAL_EVENT_ID),
                    revisionId = UUID.fromString(CURRENT_LOCAL_REVISION_ID),
                ),
                text = "Preserved local pending note",
                effectiveTime = PointTimeResolver.resolveInstant(
                    recordedAt.toInstant(),
                    ZoneId.of("Asia/Novosibirsk"),
                ),
                recordedAt = recordedAt,
            ),
        )
        val identity = requireNotNull(requireDatabase().identityDao().findIdentity())
        requireDatabase().identityDao().bindCurrentServerIdentity(
            installationId = identity.installationId,
            localOwnerId = identity.localOwnerId,
            deviceId = REPLACEMENT_DEVICE_ID,
            personId = uuid(77),
        )
    }

    private suspend fun seedStream(appliedCursor: String) {
        val identityDao = requireDatabase().identityDao()
        if (identityDao.findIdentity() == null) {
            identityDao.insertInstallation(
                LocalInstallationEntity(
                    installationId = CURRENT_INSTALLATION_ID,
                    createdAtUtc = "2030-01-01T00:00:00Z",
                ),
            )
            identityDao.insertOwner(
                LocalOwnerEntity(
                    localOwnerId = CURRENT_OWNER_ID,
                    installationId = CURRENT_INSTALLATION_ID,
                    createdAtUtc = "2030-01-01T00:00:00Z",
                ),
            )
            identityDao.insertIdentityState(
                LocalIdentityStateEntity(
                    installationId = CURRENT_INSTALLATION_ID,
                    localOwnerId = CURRENT_OWNER_ID,
                    selectedAtUtc = "2030-01-01T00:00:00Z",
                ),
            )
        }
        val identity = requireNotNull(identityDao.findIdentity())
        identityDao.bindCurrentServerIdentity(
            installationId = identity.installationId,
            localOwnerId = identity.localOwnerId,
            deviceId = REPLACEMENT_DEVICE_ID,
            personId = uuid(77),
        )
        if (requireDatabase().syncAuthDao().findState() == null) {
            requireDatabase().syncAuthDao().installEnrollment(
                state = SyncAuthStateEntity(
                    credentialEpochId = CREDENTIAL_EPOCH_ID,
                    installationId = identity.installationId,
                    localOwnerId = identity.localOwnerId,
                    deviceId = REPLACEMENT_DEVICE_ID,
                    personId = uuid(77),
                    tokenType = "Bearer",
                    refreshTokenCiphertext = byteArrayOf(1, 2, 3),
                    refreshTokenNonce = ByteArray(12) { 4 },
                    refreshTokenKeyAlias = "replica-test-refresh",
                    refreshTokenKeyGeneration = 1,
                    refreshTokenAadVersion = 1,
                    accessExpiresAtUtc = "2031-01-01T00:00:00Z",
                    accessExpiresAtEpochMs =
                        Instant.parse("2031-01-01T00:00:00Z").toEpochMilli(),
                    refreshExpiresAtUtc = "2031-02-01T00:00:00Z",
                    refreshExpiresAtEpochMs =
                        Instant.parse("2031-02-01T00:00:00Z").toEpochMilli(),
                    familyExpiresAtUtc = "2031-03-01T00:00:00Z",
                    familyExpiresAtEpochMs =
                        Instant.parse("2031-03-01T00:00:00Z").toEpochMilli(),
                    generation = 1,
                    state = "active",
                    bootstrapRequired = true,
                    installedAtUtc = "2030-01-01T00:00:00Z",
                    updatedAtUtc = "2030-01-01T00:00:00Z",
                    failureCode = null,
                ),
                accessFingerprint = SyncAuthTokenFingerprintEntity(
                    credentialEpochId = CREDENTIAL_EPOCH_ID,
                    generation = 1,
                    tokenKind = "access",
                    tokenHmac = ByteArray(32) { 5 },
                    hmacKeyGeneration = 1,
                    createdAtUtc = "2030-01-01T00:00:00Z",
                ),
                refreshFingerprint = SyncAuthTokenFingerprintEntity(
                    credentialEpochId = CREDENTIAL_EPOCH_ID,
                    generation = 1,
                    tokenKind = "refresh",
                    tokenHmac = ByteArray(32) { 6 },
                    hmacKeyGeneration = 1,
                    createdAtUtc = "2030-01-01T00:00:00Z",
                ),
            )
        }
        requireDatabase().syncReplicaDao().insertStreamState(
            SyncStreamStateEntity(
                credentialEpochId = CREDENTIAL_EPOCH_ID,
                deviceId = REPLACEMENT_DEVICE_ID,
                phase = "bootstrap_required",
                bootstrapRequired = true,
                appliedCursor = appliedCursor,
                lastAppliedServerSequence = 0,
                highWatermarkHint = null,
                integrityErrorCode = null,
                updatedAtUtc = "2030-01-01T00:00:00Z",
            ),
        )
    }

    private suspend fun seedBootstrapSession() {
        requireDatabase().syncReplicaDao().insertBootstrapSession(
            SyncBootstrapSessionEntity(
                bootstrapId = BOOTSTRAP_ID,
                credentialEpochId = CREDENTIAL_EPOCH_ID,
                deviceId = REPLACEMENT_DEVICE_ID,
                state = "staging",
                activeSlot = 1,
                snapshotId = null,
                nextPageCursor = null,
                candidateIncrementalCursor = null,
                nextPageIndex = 0,
                lastStagedServerSequence = null,
                createdAtUtc = "2030-01-01T00:00:00Z",
                updatedAtUtc = "2030-01-01T00:00:00Z",
            ),
        )
    }

    private fun bootstrapIntent(
        bootstrapId: String,
        requestId: String,
    ): BootstrapIntentPersistence {
        val createdAtUtc = "2030-01-01T00:01:11Z"
        val body = buildJsonObject {
            put("protocol_version", "1.0.0")
            put("message_type", "bootstrap_request")
            put("request_id", requestId)
            put("bootstrap_id", bootstrapId)
            put("device_id", REPLACEMENT_DEVICE_ID)
            put("page_size", 100)
            put("page_cursor", JsonNull)
        }.toString().toByteArray(StandardCharsets.UTF_8)
        return BootstrapIntentPersistence(
            session = SyncBootstrapSessionEntity(
                bootstrapId = bootstrapId,
                credentialEpochId = CREDENTIAL_EPOCH_ID,
                deviceId = REPLACEMENT_DEVICE_ID,
                state = "staging",
                activeSlot = 1,
                snapshotId = null,
                nextPageCursor = null,
                candidateIncrementalCursor = null,
                nextPageIndex = 0,
                lastStagedServerSequence = null,
                createdAtUtc = createdAtUtc,
                updatedAtUtc = createdAtUtc,
            ),
            firstRequest = SyncHttpRequestEntity(
                endpointId = "sync_bootstrap",
                requestIdentity = requestId,
                protocolVersion = "1.0.0",
                credentialEpochId = CREDENTIAL_EPOCH_ID,
                deviceId = REPLACEMENT_DEVICE_ID,
                idempotencyKey = null,
                rawRequestBody = body,
                rawBodyHmac = ByteArray(32) { 8 },
                hmacKeyGeneration = 1,
                state = "ready",
                attemptBudget = 8,
                deadlineAtEpochMs =
                    Instant.parse("2030-01-01T00:31:11Z").toEpochMilli(),
                nextAttemptAtEpochMs = null,
                lastAttemptAtEpochMs = null,
                leaseExpiresAtEpochMs = null,
                accessGenerationUsed = 1,
                terminalHttpStatus = null,
                exactResponseBody = null,
                responseSha256 = null,
                terminalAtUtc = null,
                terminalErrorCode = null,
                createdAtUtc = createdAtUtc,
                updatedAtUtc = createdAtUtc,
            ),
        )
    }

    private suspend fun supersedeWithReplacementBootstrap() {
        requireDatabase().openHelper.writableDatabase.execSQL(
            """
            UPDATE sync_bootstrap_session
            SET state = 'superseded', active_slot = NULL
            WHERE bootstrap_id = '$BOOTSTRAP_ID'
            """.trimIndent(),
        )
        requireDatabase().syncReplicaDao().insertBootstrapSession(
            SyncBootstrapSessionEntity(
                bootstrapId = REPLACEMENT_BOOTSTRAP_ID,
                credentialEpochId = CREDENTIAL_EPOCH_ID,
                deviceId = REPLACEMENT_DEVICE_ID,
                state = "staging",
                activeSlot = 1,
                snapshotId = null,
                nextPageCursor = null,
                candidateIncrementalCursor = null,
                nextPageIndex = 0,
                lastStagedServerSequence = null,
                createdAtUtc = "2030-01-01T00:01:00Z",
                updatedAtUtc = "2030-01-01T00:01:00Z",
            ),
        )
    }

    private suspend fun seedSendingRequest(
        response: TerminalHttpResponsePersistence,
        attemptId: String,
    ) {
        val requestBody =
            if (response.endpointId == "sync_bootstrap") {
                val session = requireNotNull(
                    requireDatabase()
                        .syncReplicaDao()
                        .findBootstrapSessionWithActiveSlot(),
                )
                buildJsonObject {
                    put("protocol_version", "1.0.0")
                    put("message_type", "bootstrap_request")
                    put("request_id", response.requestIdentity)
                    put("bootstrap_id", session.bootstrapId)
                    put("device_id", REPLACEMENT_DEVICE_ID)
                    put("page_size", 100)
                    put(
                        "page_cursor",
                        session.nextPageCursor?.let(::JsonPrimitive) ?: JsonNull,
                    )
                }.toString().toByteArray(StandardCharsets.UTF_8)
            } else {
                "request-${response.requestIdentity}"
                    .toByteArray(StandardCharsets.UTF_8)
            }
        requireDatabase().syncTransportDao().insertRequest(
            SyncHttpRequestEntity(
                endpointId = response.endpointId,
                requestIdentity = response.requestIdentity,
                protocolVersion = "1.0.0",
                credentialEpochId = CREDENTIAL_EPOCH_ID,
                deviceId = REPLACEMENT_DEVICE_ID,
                idempotencyKey = null,
                rawRequestBody = requestBody,
                rawBodyHmac = ByteArray(32) { 7 },
                hmacKeyGeneration = 1,
                state = "sending",
                attemptCount = 1,
                attemptBudget = 8,
                deadlineAtEpochMs = Instant.parse("2030-01-01T01:00:00Z").toEpochMilli(),
                nextAttemptAtEpochMs = null,
                lastAttemptAtEpochMs = 1,
                leaseExpiresAtEpochMs = 2,
                activeAttemptId = attemptId,
                accessGenerationUsed = 1,
                terminalHttpStatus = null,
                exactResponseBody = null,
                responseSha256 = null,
                terminalAtUtc = null,
                terminalErrorCode = null,
                createdAtUtc = "2030-01-01T00:00:00Z",
                updatedAtUtc = "2030-01-01T00:00:00Z",
            ),
        )
    }

    private fun bootstrapReceipt(
        requestId: String,
        pageId: String,
        pageIndex: Int,
        fromCursor: String?,
        nextCursor: String?,
        complete: Boolean,
        changes: List<ReplicaChangePersistence>,
        receivedAt: String,
        bootstrapId: String = BOOTSTRAP_ID,
    ) = SyncPageReceiptEntity(
        pageId = pageId,
        endpointId = "sync_bootstrap",
        requestIdentity = requestId,
        bootstrapId = bootstrapId,
        pageIndex = pageIndex,
        snapshotId = SNAPSHOT_ID,
        fromCursor = fromCursor,
        nextCursor = nextCursor,
        incrementalCursor = "bootstrap-incremental-cursor",
        pageSha256 = sha256("page-$pageId"),
        changeCount = changes.size,
        completeOrHasMore = complete,
        state = "staged",
        firstServerSequence = changes.firstOrNull()?.serverSequence,
        lastServerSequence = changes.lastOrNull()?.serverSequence,
        receivedAtUtc = receivedAt,
        appliedAtUtc = null,
    )

    private fun pullReceipt(
        requestId: String,
        pageId: String,
        fromCursor: String,
        nextCursor: String,
        hasMore: Boolean,
        changes: List<ReplicaChangePersistence>,
        receivedAt: String,
    ) = SyncPageReceiptEntity(
        pageId = pageId,
        endpointId = "sync_pull",
        requestIdentity = requestId,
        bootstrapId = null,
        pageIndex = 0,
        snapshotId = null,
        fromCursor = fromCursor,
        nextCursor = nextCursor,
        incrementalCursor = null,
        pageSha256 = sha256("page-$pageId"),
        changeCount = changes.size,
        completeOrHasMore = hasMore,
        state = "applied",
        firstServerSequence = changes.firstOrNull()?.serverSequence,
        lastServerSequence = changes.lastOrNull()?.serverSequence,
        receivedAtUtc = receivedAt,
        appliedAtUtc = receivedAt,
    )

    private fun terminalResponse(
        endpoint: String,
        requestId: String,
        attemptId: String,
        body: String,
        terminalAt: String,
        httpStatus: Int = 200,
        errorCode: String? = null,
    ): TerminalHttpResponsePersistence {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        return TerminalHttpResponsePersistence(
            endpointId = endpoint,
            requestIdentity = requestId,
            expectedAttemptId = attemptId,
            httpStatus = httpStatus,
            exactResponseBody = bytes,
            responseSha256 = sha256(bytes),
            terminalAtUtc = terminalAt,
            terminalErrorCode = errorCode,
        )
    }

    private fun openDatabase() {
        database = LifeAgentDatabaseFactory.create(
            context = context,
            openHelperFactory = FrameworkSQLiteOpenHelperFactory(),
            databaseName = databaseName,
        )
    }

    private fun closeDatabase() {
        database?.close()
        database = null
    }

    private fun requireDatabase(): LifeAgentDatabase =
        requireNotNull(database) { "Replica test database is closed" }

    private fun sha256(value: String): String =
        sha256(value.toByteArray(StandardCharsets.UTF_8))

    private fun sha256(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun uuid(value: Long): String = UUID(0, value).toString()

    private companion object {
        const val CREDENTIAL_EPOCH_ID = "90000000-0000-4000-8000-000000000001"
        const val CURRENT_INSTALLATION_ID =
            "90000000-0000-4000-8000-000000000101"
        const val CURRENT_OWNER_ID =
            "90000000-0000-4000-8000-000000000102"
        const val BOOTSTRAP_ID = "90000000-0000-4000-8000-000000000002"
        const val SNAPSHOT_ID = "90000000-0000-4000-8000-000000000003"
        const val REPLACEMENT_BOOTSTRAP_ID = "90000000-0000-4000-8000-000000000004"
        const val REPLACEMENT_CREDENTIAL_EPOCH_ID =
            "90000000-0000-4000-8000-000000000005"
        const val HISTORICAL_INSTALLATION_ID = "91000000-0000-4000-8000-000000000001"
        const val HISTORICAL_OWNER_ID = "91000000-0000-4000-8000-000000000002"
        const val SUBMITTING_DEVICE_ID = "91000000-0000-4000-8000-000000000003"
        const val REPLACEMENT_DEVICE_ID = "91000000-0000-4000-8000-000000000004"
        const val NEWER_DEVICE_ID = "91000000-0000-4000-8000-000000000005"
        const val REVISION_ONE_ID = "93000000-0000-4000-8000-000000000001"
        const val REVISION_TWO_ID = "93000000-0000-4000-8000-000000000002"
        const val REVISION_THREE_ID = "93000000-0000-4000-8000-000000000003"
        const val CAPTURE_TWO_ID = "94000000-0000-4000-8000-000000000002"
        const val CAPTURE_THREE_ID = "94000000-0000-4000-8000-000000000003"
        const val OPERATION_TWO_ID = "95000000-0000-4000-8000-000000000002"
        const val OPERATION_THREE_ID = "95000000-0000-4000-8000-000000000003"
        const val CURRENT_LOCAL_OPERATION_ID = "96000000-0000-4000-8000-000000000001"
        const val CURRENT_LOCAL_CAPTURE_ID = "96000000-0000-4000-8000-000000000002"
        const val CURRENT_LOCAL_EVENT_ID = "96000000-0000-4000-8000-000000000003"
        const val CURRENT_LOCAL_REVISION_ID = "96000000-0000-4000-8000-000000000004"
    }
}

private class ReplicaInstrumentedFixture {
    private val canonical = CanonicalNoteCodec()

    fun change(
        sequence: Long,
        revisionId: String = REVISION_ONE_ID,
        captureId: String = CAPTURE_ONE_ID,
        operationId: String = OPERATION_ONE_ID,
        revisionNo: Int = 1,
        parentRevisionId: String? = null,
        resultCode: String = "applied",
        currentRevisionId: String = revisionId,
        eventId: String = EVENT_ID,
        installationId: String = HISTORICAL_INSTALLATION_ID,
        localOwnerId: String = HISTORICAL_OWNER_ID,
        deviceId: String = SUBMITTING_DEVICE_ID,
    ): ReplicaChangePersistence {
        val recordedAt = "2030-01-01T07:0${sequence - 1}:00+07:00"
        val receivedAt = "2030-01-01T00:0${sequence - 1}:01Z"
        val payload = buildJsonObject { put("text", "Replica change $sequence") }
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
        val correctionReason =
            parentRevisionId?.let { JsonPrimitive("test correction") } ?: JsonNull
        val revisionDigest = canonical.canonical(
            buildJsonObject {
                put("event_id", eventId)
                put("revision_id", revisionId)
                put("revision_no", revisionNo)
                put("capture_id", captureId)
                put("operation_id", operationId)
                put("record_status", "active")
                put("effective_time", time)
                put("recorded_at", recordedAt)
                put("payload", payload)
                put("correction_reason", correctionReason)
                put(
                    "parent_revision_id",
                    parentRevisionId?.let(::JsonPrimitive) ?: JsonNull,
                )
            },
        )
        val root = buildJsonObject {
            put("server_sequence", sequence)
            put("change_kind", "event_revision_committed")
            put("result_code", resultCode)
            put("operation_id", operationId)
            put("capture_id", captureId)
            put("event_id", eventId)
            put("revision_id", revisionId)
            put("current_revision_id", currentRevisionId)
            put("operation_content_sha256", operationDigest(sequence))
            putJsonObject("capture") {
                put("schema_version", "4.0.0")
                put("persistence_state", "authenticated_ingress")
                put("capture_id", captureId)
                put("operation_id", operationId)
                put(
                    "identity",
                    identity(installationId, localOwnerId, deviceId),
                )
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
                put(
                    "identity",
                    identity(installationId, localOwnerId, deviceId),
                )
                put("event_id", eventId)
                put("revision_id", revisionId)
                put("revision_no", revisionNo)
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
                    put("correction_reason", correctionReason)
                    putJsonArray("parents") {
                        parentRevisionId?.let { parent ->
                            add(
                                buildJsonObject {
                                    put("revision_id", parent)
                                    put("relation", "supersedes")
                                },
                            )
                        }
                    }
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
            operationContentSha256 = operationDigest(sequence),
            captureId = captureId,
            eventId = eventId,
            revisionId = revisionId,
            currentRevisionId = currentRevisionId,
            resultCode = resultCode,
            committedAtUtc = receivedAt,
            changeJcs = canonical.canonical(root).bytes,
        )
    }

    private fun identity(
        installationId: String,
        localOwnerId: String,
        deviceId: String,
    ) = buildJsonObject {
        put("installation_id", installationId)
        put("local_owner_id", localOwnerId)
        put("device_id", deviceId)
    }

    private fun collector() = buildJsonObject {
        put("name", "life-agent-android")
        put("version", "replica-instrumented-test")
    }

    private fun operationDigest(sequence: Long): String =
        sequence.toString(16).padStart(64, '0')

    private companion object {
        const val HISTORICAL_INSTALLATION_ID = "91000000-0000-4000-8000-000000000001"
        const val HISTORICAL_OWNER_ID = "91000000-0000-4000-8000-000000000002"
        const val SUBMITTING_DEVICE_ID = "91000000-0000-4000-8000-000000000003"
        const val EVENT_ID = "92000000-0000-4000-8000-000000000001"
        const val REVISION_ONE_ID = "93000000-0000-4000-8000-000000000001"
        const val CAPTURE_ONE_ID = "94000000-0000-4000-8000-000000000001"
        const val OPERATION_ONE_ID = "95000000-0000-4000-8000-000000000001"
    }
}
