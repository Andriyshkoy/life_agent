package ru.andriyshkoy.lifeagent

import java.io.Closeable
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.andriyshkoy.lifeagent.core.id.MutationIds
import ru.andriyshkoy.lifeagent.core.time.PointTimeResolver
import ru.andriyshkoy.lifeagent.data.sync.runtime.AccessTokenKey
import ru.andriyshkoy.lifeagent.data.sync.runtime.AuthAccessSource
import ru.andriyshkoy.lifeagent.data.sync.runtime.M2AuthRuntimeResult
import ru.andriyshkoy.lifeagent.data.sync.wire.WipeableSecret
import ru.andriyshkoy.lifeagent.data.sync.work.SyncWorkExecutionDisposition
import ru.andriyshkoy.lifeagent.data.sync.work.SyncWorkExecutionPort
import ru.andriyshkoy.lifeagent.data.sync.work.SyncWorkSchedulingResult
import ru.andriyshkoy.lifeagent.notes.domain.CanonicalNoteRevisionSnapshot
import ru.andriyshkoy.lifeagent.notes.domain.CorrectNoteCommand
import ru.andriyshkoy.lifeagent.notes.domain.CreateNoteCommand
import ru.andriyshkoy.lifeagent.notes.domain.NoteEventPointer
import ru.andriyshkoy.lifeagent.notes.domain.NoteMutationDisposition
import ru.andriyshkoy.lifeagent.notes.domain.NoteMutationOutcome
import ru.andriyshkoy.lifeagent.notes.domain.NoteMutationReceipt
import ru.andriyshkoy.lifeagent.notes.domain.NoteRecordStatus
import ru.andriyshkoy.lifeagent.notes.domain.NoteSnapshot
import ru.andriyshkoy.lifeagent.notes.domain.NoteSummary
import ru.andriyshkoy.lifeagent.notes.domain.NotesExportSnapshot
import ru.andriyshkoy.lifeagent.notes.domain.NotesRepository
import ru.andriyshkoy.lifeagent.notes.domain.RetractNoteCommand
import ru.andriyshkoy.lifeagent.ui.sync.ManualSyncEnqueueResult
import ru.andriyshkoy.lifeagent.ui.sync.SyncEnrollmentGatewayResult

class M2AppRuntimeTest {
    @Test
    fun processStorageOpensOnceAndRetainsOneWorkerPort() {
        var opens = 0
        var startupCalls = 0
        val port = SyncWorkExecutionPort { SyncWorkExecutionDisposition.COMPLETE }
        val storage = ProcessScopedAppStorage(
            open = {
                opens += 1
                FakeProcessContainer(port)
            },
            afterOpened = { startupCalls += 1 },
        )

        val first = storage.open().getOrThrow()
        val second = storage.open().getOrThrow()

        assertSame(first, second)
        assertSame(port, first.port)
        assertSame(port, second.port)
        assertEquals(1, opens)
        assertEquals(1, startupCalls)

        storage.closeIfOpened()
        storage.closeIfOpened()
        assertEquals(1, first.closeCalls)
    }

    @Test
    fun failedStartupEnqueueDoesNotPoisonOpenedLocalContainer() {
        val opened = FakeProcessContainer(
            SyncWorkExecutionPort { SyncWorkExecutionDisposition.COMPLETE },
        )
        val storage = ProcessScopedAppStorage(
            open = { opened },
            afterOpened = { throw IllegalStateException("scheduler") },
        )

        assertSame(opened, storage.open().getOrThrow())
        assertSame(opened, storage.open().getOrThrow())
    }

    @Test
    fun persistedNoteMutationsEnqueueOnlyAfterDelegateReturns() = runTest {
        val events = mutableListOf<String>()
        val delegate = FakeNotesRepository(events)
        val repository = PostCommitSyncNotesRepository(delegate) {
            events += "enqueue"
        }

        repository.create(createCommand())
        assertEquals(listOf("create-committed", "enqueue"), events)
        events.clear()

        repository.correct(correctCommand())
        assertEquals(listOf("correct-committed", "enqueue"), events)
        events.clear()

        repository.retract(retractCommand())
        assertEquals(listOf("retract-committed", "enqueue"), events)
    }

    @Test
    fun schedulerFailureCannotTurnCommittedNoteIntoFailure() = runTest {
        val events = mutableListOf<String>()
        val delegate = FakeNotesRepository(events)
        val repository = PostCommitSyncNotesRepository(delegate) {
            events += "enqueue"
            throw IllegalStateException("scheduler")
        }

        val outcome = repository.create(createCommand())

        assertTrue(outcome is NoteMutationOutcome.Persisted)
        assertEquals(listOf("create-committed", "enqueue"), events)
    }

    @Test
    fun enrollmentAdapterDisablesReplacementAndWipesOwnedCode() = runTest {
        var replaceActiveDevice = true
        lateinit var received: WipeableSecret
        val gateway = ProductionSyncEnrollmentGateway(
            M2EnrollmentRuntimeBoundary { secret, replace ->
                received = secret
                replaceActiveDevice = replace
                M2AuthRuntimeResult.AccessReady(
                    key = AccessTokenKey(CREDENTIAL_EPOCH_ID, 1),
                    source = AuthAccessSource.ENROLLMENT,
                )
            },
        )
        val code = WipeableSecret.ascii(VALID_ENROLLMENT_CODE)

        assertEquals(SyncEnrollmentGatewayResult.CONNECTED, gateway.enroll(code))
        assertFalse(replaceActiveDevice)
        assertThrows(IllegalStateException::class.java) { received.size }
    }

    @Test
    fun enrollmentAdapterMapsOnlyClosedUiOutcomes() = runTest {
        val cases = listOf(
            M2AuthRuntimeResult.DurableCredentialsCommitted(
                AccessTokenKey(CREDENTIAL_EPOCH_ID, 2),
            ) to SyncEnrollmentGatewayResult.CONNECTED,
            M2AuthRuntimeResult.Rejected to SyncEnrollmentGatewayResult.REJECTED,
            M2AuthRuntimeResult.ManualReenrollmentRequired to
                SyncEnrollmentGatewayResult.NEW_CODE_REQUIRED,
            M2AuthRuntimeResult.Busy to SyncEnrollmentGatewayResult.BUSY,
            M2AuthRuntimeResult.LocalUnavailable to
                SyncEnrollmentGatewayResult.LOCAL_UNAVAILABLE,
            M2AuthRuntimeResult.Unenrolled to
                SyncEnrollmentGatewayResult.LOCAL_UNAVAILABLE,
            M2AuthRuntimeResult.RecoveryComplete(0) to
                SyncEnrollmentGatewayResult.LOCAL_UNAVAILABLE,
        )

        cases.forEach { (runtimeResult, expected) ->
            val gateway = ProductionSyncEnrollmentGateway(
                M2EnrollmentRuntimeBoundary { _, replace ->
                    assertFalse(replace)
                    runtimeResult
                },
            )
            assertEquals(
                expected,
                gateway.enroll(WipeableSecret.ascii(VALID_ENROLLMENT_CODE)),
            )
        }
    }

    @Test
    fun manualAdapterPreservesSchedulerPresenceAndFailureStates() {
        val cases = listOf(
            SyncWorkSchedulingResult.ENQUEUED to ManualSyncEnqueueResult.QUEUED,
            SyncWorkSchedulingResult.NOT_CONFIGURED to
                ManualSyncEnqueueResult.NOT_CONFIGURED,
            SyncWorkSchedulingResult.MISCONFIGURED to
                ManualSyncEnqueueResult.MISCONFIGURED,
        )
        cases.forEach { (schedulerResult, expected) ->
            val gateway = ProductionManualSyncEnqueueGateway { schedulerResult }
            assertEquals(expected, gateway.enqueueNow())
        }
        assertEquals(
            ManualSyncEnqueueResult.FAILED,
            ProductionManualSyncEnqueueGateway {
                throw IllegalStateException("scheduler")
            }.enqueueNow(),
        )
    }

    private class FakeProcessContainer(
        val port: SyncWorkExecutionPort,
    ) : Closeable {
        var closeCalls: Int = 0

        override fun close() {
            closeCalls += 1
        }
    }

    private class FakeNotesRepository(
        private val events: MutableList<String>,
    ) : NotesRepository {
        override suspend fun create(command: CreateNoteCommand): NoteMutationOutcome {
            events += "create-committed"
            return persistedOutcome()
        }

        override suspend fun correct(command: CorrectNoteCommand): NoteMutationOutcome {
            events += "correct-committed"
            return persistedOutcome()
        }

        override suspend fun retract(command: RetractNoteCommand): NoteMutationOutcome {
            events += "retract-committed"
            return persistedOutcome()
        }

        override fun observeLastCommitted(): Flow<NoteSummary?> = flowOf(null)

        override suspend fun getByEventId(eventId: UUID): NoteSnapshot? = null

        override suspend fun findByOperationId(operationId: UUID): NoteMutationReceipt? = null

        override suspend fun exportSnapshot(): NotesExportSnapshot = NotesExportSnapshot(
            events = emptyList<NoteEventPointer>(),
            revisions = emptyList<CanonicalNoteRevisionSnapshot>(),
        )
    }

    companion object {
        private const val VALID_ENROLLMENT_CODE =
            "ABCD-EFGH-JKLM-NPQR-STUV-WXYZ-2345"
        private const val CREDENTIAL_EPOCH_ID = "00000000-0000-4000-8000-000000000001"
        private val RECORDED_AT = OffsetDateTime.parse("2026-08-03T05:06:07Z")
        private val EFFECTIVE_TIME = PointTimeResolver.resolveInstant(
            RECORDED_AT.toInstant(),
            ZoneOffset.UTC,
        )

        private fun ids(): MutationIds = MutationIds(
            operationId = UUID.fromString("00000000-0000-4000-8000-000000000011"),
            captureId = UUID.fromString("00000000-0000-4000-8000-000000000012"),
            eventId = UUID.fromString("00000000-0000-4000-8000-000000000013"),
            revisionId = UUID.fromString("00000000-0000-4000-8000-000000000014"),
        )

        private fun createCommand() = CreateNoteCommand(
            ids = ids(),
            text = "note",
            effectiveTime = EFFECTIVE_TIME,
            recordedAt = RECORDED_AT,
        )

        private fun correctCommand() = CorrectNoteCommand(
            ids = ids(),
            expectedCurrentRevisionId = ids().revisionId,
            text = "corrected",
            effectiveTime = EFFECTIVE_TIME,
            recordedAt = RECORDED_AT,
        )

        private fun retractCommand() = RetractNoteCommand(
            ids = ids(),
            expectedCurrentRevisionId = ids().revisionId,
            recordedAt = RECORDED_AT,
        )

        private fun persistedOutcome(): NoteMutationOutcome.Persisted {
            val ids = ids()
            return NoteMutationOutcome.Persisted(
                NoteMutationReceipt(
                    note = NoteSnapshot(
                        eventId = ids.eventId,
                        revisionId = ids.revisionId,
                        operationId = ids.operationId,
                        revisionNo = 1,
                        text = "note",
                        status = NoteRecordStatus.ACTIVE,
                        effectiveTime = EFFECTIVE_TIME,
                        recordedAt = RECORDED_AT,
                        createdAt = RECORDED_AT,
                        correctionReason = null,
                    ),
                    localSequence = 1,
                    disposition = NoteMutationDisposition.COMMITTED,
                ),
            )
        }
    }
}
