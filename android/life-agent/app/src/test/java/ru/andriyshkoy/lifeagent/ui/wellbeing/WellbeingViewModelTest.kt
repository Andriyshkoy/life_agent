package ru.andriyshkoy.lifeagent.ui.wellbeing

import androidx.lifecycle.SavedStateHandle
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
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
import ru.andriyshkoy.lifeagent.ui.time.EventTimestampChoice
import ru.andriyshkoy.lifeagent.wellbeing.domain.ArchiveWellbeingDimensionCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.CanonicalWellbeingRevisionSnapshot
import ru.andriyshkoy.lifeagent.wellbeing.domain.CorrectWellbeingCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.CreateWellbeingCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.CreateWellbeingDimensionCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.RetractWellbeingCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.UpdateWellbeingDimensionCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingCatalogExportSnapshot
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingCatalogRepository
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingDimension
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingEventPointer
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingExportSnapshot
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingMutationDisposition
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingMutationOutcome
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingMutationReceipt
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingOption
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingPayload
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingRecordStatus
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingRepository
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingSnapshot
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingSummary

@OptIn(ExperimentalCoroutinesApi::class)
class WellbeingViewModelTest {
    private val dispatcher: TestDispatcher = StandardTestDispatcher()
    private val fixedInstant = Instant.parse("2026-07-29T03:00:00Z")
    private val zone = ZoneId.of("Asia/Novosibirsk")
    private val clock = Clock.fixed(fixedInstant, zone)
    private val dimensions = wellbeingDimensions()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun createStartsEmptyAndCommentAloneCannotSave() = runTest(dispatcher) {
        val repository = FakeWellbeingRepository()
        val viewModel = viewModel(repository)

        viewModel.dispatch(WellbeingAction.StartCreate)
        advanceUntilIdle()

        val initial = requireNotNull(viewModel.uiState.value.editor)
        assertEquals(4, initial.dimensions.size)
        assertEquals(0, initial.selectedCount)
        assertFalse(initial.canSave)

        viewModel.dispatch(WellbeingAction.CommentChanged("Только комментарий"))
        assertFalse(requireNotNull(viewModel.uiState.value.editor).canSave)

        val dimension = dimensions.first()
        val option = dimension.options[2]
        viewModel.dispatch(WellbeingAction.SelectOption(dimension.dimensionId, option.optionId))
        assertTrue(requireNotNull(viewModel.uiState.value.editor).canSave)

        viewModel.dispatch(WellbeingAction.SelectOption(dimension.dimensionId, option.optionId))
        assertEquals(1, requireNotNull(viewModel.uiState.value.editor).selectedCount)

        viewModel.dispatch(WellbeingAction.ClearDimension(dimension.dimensionId))
        assertEquals(0, requireNotNull(viewModel.uiState.value.editor).selectedCount)
        assertFalse(requireNotNull(viewModel.uiState.value.editor).canSave)

        viewModel.dispatch(WellbeingAction.SelectOption(dimension.dimensionId, option.optionId))
        viewModel.dispatch(WellbeingAction.CommentChanged("🙂".repeat(2_001)))
        val tooLong = requireNotNull(viewModel.uiState.value.editor)
        assertEquals(WellbeingCommentError.TooLong, tooLong.commentError)
        assertFalse(tooLong.canSave)
    }

    @Test
    fun doubleSubmitStartsOneCreateAndDoesNotPutCommentInSavedState() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val repository = FakeWellbeingRepository().apply {
                createBehavior = { command ->
                    gate.await()
                    persisted(command)
                }
            }
            val savedState = SavedStateHandle()
            val viewModel = viewModel(repository, savedState)
            startCreateWithSelection(viewModel)
            viewModel.dispatch(WellbeingAction.CommentChanged(SENSITIVE_COMMENT))

            viewModel.dispatch(WellbeingAction.Save)
            viewModel.dispatch(WellbeingAction.Save)
            runCurrent()

            assertEquals(1, repository.createCommands.size)
            assertTrue(viewModel.uiState.value.editor?.isSubmitting == true)
            savedState.keys().forEach { key ->
                assertNotEquals(SENSITIVE_COMMENT, savedState.get<Any?>(key))
            }

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                WellbeingCompletionKind.Created,
                viewModel.uiState.value.completion?.kind,
            )
        }

    @Test
    fun retryUsesExactFrozenCreateCommand() = runTest(dispatcher) {
        val repository = FakeWellbeingRepository()
        var firstAttempt = true
        repository.createBehavior = { command ->
            if (firstAttempt) {
                firstAttempt = false
                error("storage unavailable")
            }
            persisted(command)
        }
        val viewModel = viewModel(repository)
        startCreateWithSelection(viewModel)

        viewModel.dispatch(WellbeingAction.Save)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.editor?.retryAvailable == true)
        val frozen = repository.createCommands.single()

        viewModel.dispatch(WellbeingAction.RetrySave)
        advanceUntilIdle()

        assertEquals(listOf(frozen, frozen), repository.createCommands)
        assertEquals(
            WellbeingCompletionKind.Created,
            viewModel.uiState.value.completion?.kind,
        )
    }

    @Test
    fun dirtyBackRequiresExplicitDiscardWithoutCallingRepository() = runTest(dispatcher) {
        val repository = FakeWellbeingRepository()
        val viewModel = viewModel(repository)
        startCreateWithSelection(viewModel)

        viewModel.dispatch(WellbeingAction.ExitRequested)

        assertTrue(viewModel.uiState.value.dialog is WellbeingDialogUi.DiscardDraft)
        assertTrue(viewModel.uiState.value.editor != null)

        viewModel.dispatch(WellbeingAction.ConfirmDiscard)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.editor)
        assertTrue(repository.createCommands.isEmpty())
        assertEquals(
            WellbeingCompletionKind.DraftDiscarded,
            viewModel.uiState.value.completion?.kind,
        )
    }

    @Test
    fun correctionKeepsEventAndUsesObservedBaseRevision() = runTest(dispatcher) {
        val repository = FakeWellbeingRepository()
        val original = repository.sampleSnapshot(
            values = listOf(dimensions.first().snapshot(dimensions.first().options[2].optionId)),
            comment = "До исправления",
        )
        repository.currentByEvent[original.eventId] = original
        repository.lastCommitted.value = original.toSummary()
        val viewModel = viewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(WellbeingAction.StartCorrection(original.eventId))
        advanceUntilIdle()
        viewModel.dispatch(WellbeingAction.CommentChanged("После исправления"))
        viewModel.dispatch(WellbeingAction.Save)
        advanceUntilIdle()

        val command = repository.correctCommands.single()
        assertEquals(original.eventId, command.ids.eventId)
        assertEquals(original.revisionId, command.expectedCurrentRevisionId)
        assertNotEquals(original.revisionId, command.ids.revisionId)
        assertEquals("После исправления", command.comment)
        assertEquals(
            WellbeingCompletionKind.Corrected,
            viewModel.uiState.value.completion?.kind,
        )
    }

    @Test
    fun retractionFailureOffersExactRetry() = runTest(dispatcher) {
        val repository = FakeWellbeingRepository()
        val original = repository.sampleSnapshot(
            values = listOf(dimensions.first().snapshot(dimensions.first().options[2].optionId)),
        )
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

        viewModel.dispatch(WellbeingAction.RequestUndo(original.eventId))
        viewModel.dispatch(WellbeingAction.ConfirmUndo)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.undoRetryAvailable)
        assertTrue(viewModel.uiState.value.dialog is WellbeingDialogUi.ConfirmUndo)
        val frozen = repository.retractCommands.single()

        viewModel.dispatch(WellbeingAction.ConfirmUndo)
        advanceUntilIdle()

        assertEquals(listOf(frozen, frozen), repository.retractCommands)
        assertEquals(
            WellbeingCompletionKind.Retracted,
            viewModel.uiState.value.completion?.kind,
        )
    }

    @Test
    fun customFutureTimeIsRejectedBeforeRepositoryCall() = runTest(dispatcher) {
        val repository = FakeWellbeingRepository()
        val viewModel = viewModel(repository)
        startCreateWithSelection(viewModel)
        val futureLocal = LocalDateTime.ofInstant(fixedInstant, zone).plusHours(1)

        viewModel.dispatch(
            WellbeingAction.SelectTimestamp(
                EventTimestampChoice.Custom(futureLocal, zone.id),
            ),
        )
        viewModel.dispatch(WellbeingAction.Save)
        advanceUntilIdle()

        assertTrue(repository.createCommands.isEmpty())
        assertEquals(
            "Время события не может быть в будущем",
            viewModel.uiState.value.editor?.timestamp?.error,
        )
    }

    private suspend fun TestScope.startCreateWithSelection(viewModel: WellbeingViewModel) {
        viewModel.dispatch(WellbeingAction.StartCreate)
        advanceUntilIdle()
        val dimension = dimensions.first()
        viewModel.dispatch(
            WellbeingAction.SelectOption(
                dimensionId = dimension.dimensionId,
                optionId = dimension.options[2].optionId,
            ),
        )
    }

    private fun viewModel(
        repository: FakeWellbeingRepository,
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): WellbeingViewModel {
        val ids = (1..100).map { index ->
            UUID.fromString(
                "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}",
            )
        }
        val queue = ArrayDeque(ids)
        return WellbeingViewModel(
            repository = repository,
            catalogRepository = FakeWellbeingCatalogRepository(dimensions),
            savedStateHandle = savedStateHandle,
            clock = clock,
            zoneIdProvider = { zone },
            mutationIdsFactory = MutationIdsFactory(UuidGenerator(queue::removeFirst)),
        )
    }

    private class FakeWellbeingRepository : WellbeingRepository {
        val lastCommitted = MutableStateFlow<WellbeingSummary?>(null)
        val currentByEvent = mutableMapOf<UUID, WellbeingSnapshot>()
        val receiptsByOperation = mutableMapOf<UUID, WellbeingMutationReceipt>()
        val createCommands = mutableListOf<CreateWellbeingCommand>()
        val correctCommands = mutableListOf<CorrectWellbeingCommand>()
        val retractCommands = mutableListOf<RetractWellbeingCommand>()

        var createBehavior:
            suspend FakeWellbeingRepository.(CreateWellbeingCommand) -> WellbeingMutationOutcome =
            { persisted(it) }
        var correctBehavior:
            suspend FakeWellbeingRepository.(CorrectWellbeingCommand) -> WellbeingMutationOutcome =
            { persisted(it) }
        var retractBehavior:
            suspend FakeWellbeingRepository.(RetractWellbeingCommand) -> WellbeingMutationOutcome =
            { command ->
                val current = requireNotNull(currentByEvent[command.ids.eventId])
                persisted(command, current)
            }

        override suspend fun create(command: CreateWellbeingCommand): WellbeingMutationOutcome {
            createCommands += command
            return createBehavior(command)
        }

        override suspend fun correct(command: CorrectWellbeingCommand): WellbeingMutationOutcome {
            correctCommands += command
            return correctBehavior(command)
        }

        override suspend fun retract(command: RetractWellbeingCommand): WellbeingMutationOutcome {
            retractCommands += command
            return retractBehavior(command)
        }

        override fun observeLastCommitted(): Flow<WellbeingSummary?> = lastCommitted

        override suspend fun getByEventId(eventId: UUID): WellbeingSnapshot? =
            currentByEvent[eventId]

        override suspend fun findByOperationId(operationId: UUID): WellbeingMutationReceipt? =
            receiptsByOperation[operationId]

        override suspend fun exportSnapshot(): WellbeingExportSnapshot = WellbeingExportSnapshot(
            events = emptyList<WellbeingEventPointer>(),
            revisions = emptyList<CanonicalWellbeingRevisionSnapshot>(),
        )

        fun persisted(command: CreateWellbeingCommand): WellbeingMutationOutcome = persist(
            WellbeingSnapshot(
                eventId = command.ids.eventId,
                revisionId = command.ids.revisionId,
                operationId = command.ids.operationId,
                revisionNo = 1,
                payload = WellbeingPayload(command.values, command.comment),
                status = WellbeingRecordStatus.ACTIVE,
                effectiveTime = command.effectiveTime,
                recordedAt = command.recordedAt,
                createdAt = command.recordedAt,
                correctionReason = null,
            ),
        )

        fun persisted(command: CorrectWellbeingCommand): WellbeingMutationOutcome = persist(
            WellbeingSnapshot(
                eventId = command.ids.eventId,
                revisionId = command.ids.revisionId,
                operationId = command.ids.operationId,
                revisionNo = 2,
                payload = WellbeingPayload(command.values, command.comment),
                status = WellbeingRecordStatus.ACTIVE,
                effectiveTime = command.effectiveTime,
                recordedAt = command.recordedAt,
                createdAt = command.recordedAt,
                correctionReason = command.reason,
            ),
        )

        fun persisted(
            command: RetractWellbeingCommand,
            current: WellbeingSnapshot,
        ): WellbeingMutationOutcome = persist(
            current.copy(
                revisionId = command.ids.revisionId,
                operationId = command.ids.operationId,
                revisionNo = current.revisionNo + 1,
                status = WellbeingRecordStatus.RETRACTED,
                recordedAt = command.recordedAt,
                createdAt = command.recordedAt,
                correctionReason = command.reason,
            ),
        )

        fun sampleSnapshot(
            values: List<ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingValueSnapshot>,
            comment: String? = null,
        ): WellbeingSnapshot = WellbeingSnapshot(
            eventId = UUID.randomUUID(),
            revisionId = UUID.randomUUID(),
            operationId = UUID.randomUUID(),
            revisionNo = 1,
            payload = WellbeingPayload(values, comment),
            status = WellbeingRecordStatus.ACTIVE,
            effectiveTime = PointTimeResolver.resolveInstant(
                Instant.parse("2026-07-29T03:00:00Z"),
                ZoneId.of("Asia/Novosibirsk"),
            ),
            recordedAt = OffsetDateTime.parse("2026-07-29T10:00:00+07:00"),
            createdAt = OffsetDateTime.parse("2026-07-29T10:00:00+07:00"),
            correctionReason = null,
        )

        private fun persist(snapshot: WellbeingSnapshot): WellbeingMutationOutcome {
            currentByEvent[snapshot.eventId] = snapshot
            val receipt = WellbeingMutationReceipt(
                wellbeing = snapshot,
                disposition = WellbeingMutationDisposition.COMMITTED,
            )
            receiptsByOperation[snapshot.operationId] = receipt
            lastCommitted.value = snapshot.toSummary()
            return WellbeingMutationOutcome.Persisted(receipt)
        }
    }

    private class FakeWellbeingCatalogRepository(
        dimensions: List<WellbeingDimension>,
    ) : WellbeingCatalogRepository {
        private val state = MutableStateFlow(dimensions)

        override suspend fun ensureSeeded(createdAt: Instant) = Unit

        override fun observeDimensions(includeArchived: Boolean): Flow<List<WellbeingDimension>> =
            state

        override suspend fun getDimension(dimensionId: UUID): WellbeingDimension? =
            state.value.firstOrNull { it.dimensionId == dimensionId }

        override suspend fun create(
            command: CreateWellbeingDimensionCommand,
        ): WellbeingDimension = error("Not used")

        override suspend fun update(
            command: UpdateWellbeingDimensionCommand,
        ): WellbeingDimension = error("Not used")

        override suspend fun archive(
            command: ArchiveWellbeingDimensionCommand,
        ): WellbeingDimension = error("Not used")

        override suspend fun exportSnapshot(): WellbeingCatalogExportSnapshot =
            WellbeingCatalogExportSnapshot(emptyList(), emptyList(), emptyList())
    }

    companion object {
        private const val SENSITIVE_COMMENT = "Чувствительный комментарий"
    }
}

private fun wellbeingDimensions(): List<WellbeingDimension> = listOf(
    dimension(100, "Общее самочувствие", listOf("Очень плохое", "Плохое", "Нормальное")),
    dimension(200, "Настроение", listOf("Неприятное", "Нейтральное", "Приятное")),
    dimension(300, "Энергия", listOf("Мало энергии", "Средне", "Много энергии")),
    dimension(400, "Стресс", listOf("Низкий", "Умеренный", "Высокий")),
)

private fun dimension(
    id: Int,
    label: String,
    optionLabels: List<String>,
): WellbeingDimension {
    val dimensionId = stableUuid(id)
    return WellbeingDimension(
        dimensionId = dimensionId,
        catalogVersionId = stableUuid(id + 1),
        version = 1,
        label = label,
        sortOrder = id,
        active = true,
        options = optionLabels.mapIndexed { index, optionLabel ->
            WellbeingOption(
                optionId = stableUuid(id + 10 + index),
                version = 1,
                label = optionLabel,
                sortOrder = index,
                active = true,
            )
        },
    )
}

private fun stableUuid(value: Int): UUID = UUID.fromString(
    "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}",
)

private fun WellbeingSnapshot.toSummary(): WellbeingSummary = WellbeingSummary(
    eventId = eventId,
    revisionId = revisionId,
    operationId = operationId,
    payload = payload,
    status = status,
    effectiveTime = effectiveTime,
    recordedAt = recordedAt,
)
