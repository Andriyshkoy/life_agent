package ru.andriyshkoy.lifeagent.ui.notes

import androidx.lifecycle.SavedStateHandle
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.andriyshkoy.lifeagent.core.id.MutationIdsFactory
import ru.andriyshkoy.lifeagent.core.id.UuidGenerator
import ru.andriyshkoy.lifeagent.core.time.PointTimeResolver
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

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelTest {
    private val dispatcher: TestDispatcher = StandardTestDispatcher()
    private val fixedInstant = Instant.parse("2026-07-29T03:00:00Z")
    private val zone = ZoneId.of("Asia/Novosibirsk")
    private val clock = Clock.fixed(fixedInstant, zone)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun doubleSubmitStartsOneCreateAndDoesNotPutTextInSavedState() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeNotesRepository().apply {
            createBehavior = { command ->
                gate.await()
                persisted(command)
            }
        }
        val savedState = SavedStateHandle()
        val viewModel = viewModel(repository, savedState)

        viewModel.dispatch(NoteAction.StartCreate)
        viewModel.dispatch(NoteAction.TextChanged(SENSITIVE_TEXT))
        viewModel.dispatch(NoteAction.Save)
        viewModel.dispatch(NoteAction.Save)
        runCurrent()

        assertEquals(1, repository.createCommands.size)
        assertTrue(viewModel.uiState.value.editor?.isSubmitting == true)
        savedState.keys().forEach { key ->
            assertNotEquals(SENSITIVE_TEXT, savedState.get<Any?>(key))
        }

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(NoteCompletionKind.Created, viewModel.uiState.value.completion?.kind)
        assertTrue(viewModel.uiState.value.lastCommitted is LastNoteUiState.Available)
    }

    @Test
    fun retryUsesTheExactFrozenCreateCommand() = runTest(dispatcher) {
        val repository = FakeNotesRepository()
        var firstAttempt = true
        repository.createBehavior = { command ->
            if (firstAttempt) {
                firstAttempt = false
                error("storage unavailable")
            }
            persisted(command)
        }
        val viewModel = viewModel(repository)

        viewModel.dispatch(NoteAction.StartCreate)
        viewModel.dispatch(NoteAction.TextChanged("Повторить без дубля"))
        viewModel.dispatch(NoteAction.Save)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.editor?.retryAvailable == true)
        val frozen = repository.createCommands.single()

        viewModel.dispatch(NoteAction.RetrySave)
        advanceUntilIdle()

        assertEquals(2, repository.createCommands.size)
        assertEquals(frozen, repository.createCommands.last())
        assertEquals(NoteCompletionKind.Created, viewModel.uiState.value.completion?.kind)
    }

    @Test
    fun failedLastCommittedRetryRecoversWithOnlyOneActiveSubscription() =
        runTest(dispatcher) {
            val repository = FakeNotesRepository()
            val recovered = repository.sampleSnapshot(text = "Восстановленное действие")
            repository.lastCommitted.value = recovered.toSummary()
            repository.observeBehavior = { subscriptionNo ->
                if (subscriptionNo == 1) {
                    flow { error("synthetic observation failure") }
                } else {
                    repository.lastCommitted
                }
            }
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            assertEquals(LastNoteUiState.Failed, viewModel.uiState.value.lastCommitted)
            assertEquals(1, repository.observeCallCount)
            assertEquals(0, repository.activeObserverCount)

            viewModel.dispatch(NoteAction.RetryLastCommitted)
            viewModel.dispatch(NoteAction.RetryLastCommitted)
            runCurrent()

            val available = viewModel.uiState.value.lastCommitted as LastNoteUiState.Available
            assertEquals(recovered.eventId, available.note.eventId)
            assertEquals(2, repository.observeCallCount)
            assertEquals(1, repository.activeObserverCount)
            assertEquals(1, repository.maxActiveObserverCount)

            viewModel.dispatch(NoteAction.RetryLastCommitted)
            runCurrent()

            assertEquals(2, repository.observeCallCount)
            assertEquals(1, repository.activeObserverCount)
            assertEquals(1, repository.maxActiveObserverCount)
        }

    @Test
    fun exitWhileCreateIsCommittingKeepsFrozenCommandUntilCompletion() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val repository = FakeNotesRepository().apply {
                createBehavior = { command ->
                    gate.await()
                    persisted(command)
                }
            }
            val savedState = SavedStateHandle()
            val viewModel = viewModel(repository, savedState)

            viewModel.dispatch(NoteAction.StartCreate)
            viewModel.dispatch(NoteAction.TextChanged("Сохранить один раз"))
            viewModel.dispatch(NoteAction.Save)
            runCurrent()

            val frozenOperationId = savedState.get<String>("note.pending.operation_id")
            viewModel.dispatch(NoteAction.ExitRequested)

            assertTrue(viewModel.uiState.value.editor?.isSubmitting == true)
            assertNull(viewModel.uiState.value.dialog)
            assertEquals(
                frozenOperationId,
                savedState.get<String>("note.pending.operation_id"),
            )

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, repository.createCommands.size)
            assertEquals(
                NoteCompletionKind.Created,
                viewModel.uiState.value.completion?.kind,
            )
            assertFalse(savedState.contains("note.pending.operation_id"))
        }

    @Test
    fun cancelDraftDoesNotCallRepository() = runTest(dispatcher) {
        val repository = FakeNotesRepository()
        val viewModel = viewModel(repository)

        viewModel.dispatch(NoteAction.StartCreate)
        viewModel.dispatch(NoteAction.TextChanged("Не сохранять"))
        viewModel.dispatch(NoteAction.ExitRequested)

        assertTrue(viewModel.uiState.value.dialog is NoteDialogUi.DiscardDraft)

        viewModel.dispatch(NoteAction.ConfirmDiscard)
        advanceUntilIdle()

        assertTrue(repository.createCommands.isEmpty())
        assertNull(viewModel.uiState.value.editor)
        assertEquals(
            NoteCompletionKind.DraftDiscarded,
            viewModel.uiState.value.completion?.kind,
        )
    }

    @Test
    fun correctionKeepsEventAndUsesObservedBaseRevision() = runTest(dispatcher) {
        val repository = FakeNotesRepository()
        val original = repository.sampleSnapshot(text = "До исправления")
        repository.currentByEvent[original.eventId] = original
        repository.lastCommitted.value = original.toSummary()
        repository.correctBehavior = { command -> persisted(command, "После исправления") }
        val viewModel = viewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(NoteAction.StartCorrection(original.eventId))
        advanceUntilIdle()
        viewModel.dispatch(NoteAction.TextChanged("После исправления"))
        viewModel.dispatch(NoteAction.Save)
        advanceUntilIdle()

        val command = repository.correctCommands.single()
        assertEquals(original.eventId, command.ids.eventId)
        assertEquals(original.revisionId, command.expectedCurrentRevisionId)
        assertNotEquals(original.revisionId, command.ids.revisionId)
        assertEquals(NoteCompletionKind.Corrected, viewModel.uiState.value.completion?.kind)
    }

    @Test
    fun retractFailureOffersExactRetryWithoutNewOperation() = runTest(dispatcher) {
        val repository = FakeNotesRepository()
        val original = repository.sampleSnapshot(text = "Отменить один раз")
        repository.currentByEvent[original.eventId] = original
        repository.lastCommitted.value = original.toSummary()
        var firstAttempt = true
        repository.retractBehavior = { command ->
            if (firstAttempt) {
                firstAttempt = false
                error("storage unavailable")
            }
            persisted(command, original)
        }
        val viewModel = viewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(NoteAction.RequestUndo(original.eventId))
        viewModel.dispatch(NoteAction.ConfirmUndo)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.undoRetryAvailable)
        assertTrue(viewModel.uiState.value.dialog is NoteDialogUi.ConfirmUndo)
        val frozen = repository.retractCommands.single()

        viewModel.dispatch(NoteAction.ConfirmUndo)
        advanceUntilIdle()

        assertEquals(2, repository.retractCommands.size)
        assertEquals(frozen, repository.retractCommands.last())
        assertEquals(NoteCompletionKind.Retracted, viewModel.uiState.value.completion?.kind)
    }

    @Test
    fun pendingOperationIsRehydratedFromRepositoryAfterProcessRecreation() =
        runTest(dispatcher) {
            val repository = FakeNotesRepository()
            val snapshot = repository.sampleSnapshot(text = "Уже записано")
            val receipt = NoteMutationReceipt(
                note = snapshot,
                localSequence = 1,
                disposition = NoteMutationDisposition.COMMITTED,
            )
            repository.receiptsByOperation[snapshot.operationId] = receipt
            val savedState = SavedStateHandle(
                mapOf(
                    "note.pending.kind" to "Create",
                    "note.pending.operation_id" to snapshot.operationId.toString(),
                    "note.pending.capture_id" to UUID.randomUUID().toString(),
                    "note.pending.event_id" to snapshot.eventId.toString(),
                    "note.pending.revision_id" to snapshot.revisionId.toString(),
                ),
            )

            val viewModel = viewModel(repository, savedState)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.editorLoading)
            assertEquals(NoteCompletionKind.Created, viewModel.uiState.value.completion?.kind)
            assertTrue(viewModel.uiState.value.lastCommitted is LastNoteUiState.Available)
            assertFalse(savedState.contains("note.pending.operation_id"))
        }

    private fun viewModel(
        repository: FakeNotesRepository,
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): NotesViewModel {
        val ids = (1..100).map {
            UUID.fromString("00000000-0000-0000-0000-${it.toString().padStart(12, '0')}")
        }
        val queue = ArrayDeque(ids)
        return NotesViewModel(
            repository = repository,
            savedStateHandle = savedStateHandle,
            clock = clock,
            zoneIdProvider = { zone },
            mutationIdsFactory = MutationIdsFactory(UuidGenerator(queue::removeFirst)),
        )
    }

    private class FakeNotesRepository : NotesRepository {
        val lastCommitted = MutableStateFlow<NoteSummary?>(null)
        val currentByEvent = mutableMapOf<UUID, NoteSnapshot>()
        val receiptsByOperation = mutableMapOf<UUID, NoteMutationReceipt>()
        val createCommands = mutableListOf<CreateNoteCommand>()
        val correctCommands = mutableListOf<CorrectNoteCommand>()
        val retractCommands = mutableListOf<RetractNoteCommand>()
        var observeBehavior: (Int) -> Flow<NoteSummary?> = { lastCommitted }
        var observeCallCount: Int = 0
            private set
        var activeObserverCount: Int = 0
            private set
        var maxActiveObserverCount: Int = 0
            private set

        var createBehavior: suspend FakeNotesRepository.(CreateNoteCommand) -> NoteMutationOutcome =
            { persisted(it) }
        var correctBehavior:
            suspend FakeNotesRepository.(CorrectNoteCommand) -> NoteMutationOutcome =
            { persisted(it, it.text) }
        var retractBehavior:
            suspend FakeNotesRepository.(RetractNoteCommand) -> NoteMutationOutcome =
            { command ->
                val current = requireNotNull(currentByEvent[command.ids.eventId])
                persisted(command, current)
            }

        override suspend fun create(command: CreateNoteCommand): NoteMutationOutcome {
            createCommands += command
            return createBehavior(command)
        }

        override suspend fun correct(command: CorrectNoteCommand): NoteMutationOutcome {
            correctCommands += command
            return correctBehavior(command)
        }

        override suspend fun retract(command: RetractNoteCommand): NoteMutationOutcome {
            retractCommands += command
            return retractBehavior(command)
        }

        override fun observeLastCommitted(): Flow<NoteSummary?> {
            observeCallCount += 1
            val upstream = observeBehavior(observeCallCount)
            return flow {
                activeObserverCount += 1
                maxActiveObserverCount = maxOf(maxActiveObserverCount, activeObserverCount)
                try {
                    emitAll(upstream)
                } finally {
                    activeObserverCount -= 1
                }
            }
        }

        override suspend fun getByEventId(eventId: UUID): NoteSnapshot? =
            currentByEvent[eventId]

        override suspend fun findByOperationId(operationId: UUID): NoteMutationReceipt? =
            receiptsByOperation[operationId]

        override suspend fun exportSnapshot(): NotesExportSnapshot = NotesExportSnapshot(
            events = emptyList<NoteEventPointer>(),
            revisions = emptyList<CanonicalNoteRevisionSnapshot>(),
        )

        fun persisted(command: CreateNoteCommand): NoteMutationOutcome {
            val snapshot = NoteSnapshot(
                eventId = command.ids.eventId,
                revisionId = command.ids.revisionId,
                operationId = command.ids.operationId,
                revisionNo = 1,
                text = command.text,
                status = NoteRecordStatus.ACTIVE,
                effectiveTime = command.effectiveTime,
                recordedAt = command.recordedAt,
                createdAt = command.recordedAt,
                correctionReason = null,
            )
            return persist(snapshot)
        }

        fun persisted(command: CorrectNoteCommand, text: String): NoteMutationOutcome {
            val snapshot = NoteSnapshot(
                eventId = command.ids.eventId,
                revisionId = command.ids.revisionId,
                operationId = command.ids.operationId,
                revisionNo = 2,
                text = text,
                status = NoteRecordStatus.ACTIVE,
                effectiveTime = command.effectiveTime,
                recordedAt = command.recordedAt,
                createdAt = command.recordedAt,
                correctionReason = command.reason,
            )
            return persist(snapshot)
        }

        fun persisted(
            command: RetractNoteCommand,
            current: NoteSnapshot,
        ): NoteMutationOutcome {
            val snapshot = current.copy(
                revisionId = command.ids.revisionId,
                operationId = command.ids.operationId,
                revisionNo = current.revisionNo + 1,
                status = NoteRecordStatus.RETRACTED,
                recordedAt = command.recordedAt,
                createdAt = command.recordedAt,
                correctionReason = command.reason,
            )
            return persist(snapshot)
        }

        fun sampleSnapshot(text: String): NoteSnapshot {
            val eventId = UUID.randomUUID()
            val revisionId = UUID.randomUUID()
            val operationId = UUID.randomUUID()
            return NoteSnapshot(
                eventId = eventId,
                revisionId = revisionId,
                operationId = operationId,
                revisionNo = 1,
                text = text,
                status = NoteRecordStatus.ACTIVE,
                effectiveTime = PointTimeResolver.resolveInstant(
                    Instant.parse("2026-07-29T03:00:00Z"),
                    ZoneId.of("Asia/Novosibirsk"),
                ),
                recordedAt = OffsetDateTime.parse("2026-07-29T10:00:00+07:00"),
                createdAt = OffsetDateTime.parse("2026-07-29T10:00:00+07:00"),
                correctionReason = null,
            )
        }

        private fun persist(snapshot: NoteSnapshot): NoteMutationOutcome {
            currentByEvent[snapshot.eventId] = snapshot
            val receipt = NoteMutationReceipt(
                note = snapshot,
                localSequence = 1,
                disposition = NoteMutationDisposition.COMMITTED,
            )
            receiptsByOperation[snapshot.operationId] = receipt
            lastCommitted.value = snapshot.toSummary()
            return NoteMutationOutcome.Persisted(receipt)
        }
    }

    companion object {
        private const val SENSITIVE_TEXT = "Чувствительный текст заметки"
    }
}

private fun NoteSnapshot.toSummary(): NoteSummary = NoteSummary(
    eventId = eventId,
    revisionId = revisionId,
    operationId = operationId,
    text = text,
    status = status,
    effectiveTime = effectiveTime,
    localSequence = 1,
)
