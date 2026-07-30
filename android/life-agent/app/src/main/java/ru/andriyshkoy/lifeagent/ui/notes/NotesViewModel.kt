package ru.andriyshkoy.lifeagent.ui.notes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.andriyshkoy.lifeagent.core.id.MutationIds
import ru.andriyshkoy.lifeagent.core.id.MutationIdsFactory
import ru.andriyshkoy.lifeagent.core.time.ResolvedPointTime
import ru.andriyshkoy.lifeagent.notes.domain.CorrectNoteCommand
import ru.andriyshkoy.lifeagent.notes.domain.CreateNoteCommand
import ru.andriyshkoy.lifeagent.notes.domain.IdempotencyConflictException
import ru.andriyshkoy.lifeagent.notes.domain.InvalidNoteTextException
import ru.andriyshkoy.lifeagent.notes.domain.NoteMutationOutcome
import ru.andriyshkoy.lifeagent.notes.domain.NoteMutationReceipt
import ru.andriyshkoy.lifeagent.notes.domain.NoteRecordStatus
import ru.andriyshkoy.lifeagent.notes.domain.NoteSnapshot
import ru.andriyshkoy.lifeagent.notes.domain.NoteSummary
import ru.andriyshkoy.lifeagent.notes.domain.NoteTextPolicy
import ru.andriyshkoy.lifeagent.notes.domain.NotesRepository
import ru.andriyshkoy.lifeagent.notes.domain.RetractNoteCommand
import ru.andriyshkoy.lifeagent.notes.domain.StaleNoteRevisionException

class NotesViewModel(
    private val repository: NotesRepository,
    private val savedStateHandle: SavedStateHandle,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() },
    private val mutationIdsFactory: MutationIdsFactory = MutationIdsFactory(),
) : ViewModel(), NotesController {
    private val mutableState = MutableStateFlow(
        NotesUiState(
            lastCommitted = LastNoteUiState.Loading,
            persistenceAvailable = true,
            editorLoading = savedStateHandle.contains(PENDING_OPERATION_ID),
        ),
    )
    override val uiState: StateFlow<NotesUiState> = mutableState.asStateFlow()

    private var correctionBaseRevisionId: UUID? = null
    private var correctionEventId: UUID? = null
    private var preparedMutation: PreparedMutation? = null
    private var undoTarget: NoteSummaryUi? = null
    private var editorLoadJob: Job? = null
    private var lastCommittedObservationJob: Job? = null

    init {
        observeLastCommitted()
        recoverPendingMutation()
    }

    override fun dispatch(action: NoteAction) {
        when (action) {
            NoteAction.StartCreate -> startCreate()
            is NoteAction.StartCorrection -> startCorrection(action.eventId)
            NoteAction.RetryLastCommitted -> retryLastCommitted()
            is NoteAction.TextChanged -> changeText(action.value)
            NoteAction.OpenTimestampPicker -> updateEditor {
                it.copy(timestamp = it.timestamp.copy(pickerVisible = true))
            }

            NoteAction.DismissTimestampPicker -> updateEditor {
                it.copy(timestamp = it.timestamp.copy(pickerVisible = false))
            }

            is NoteAction.SelectTimestamp -> selectTimestamp(action.choice)
            is NoteAction.SelectOverlapOffset -> selectOverlapOffset(action.offsetSeconds)
            NoteAction.Save -> save()
            NoteAction.RetrySave -> retrySave()
            NoteAction.ExitRequested -> requestExit()
            NoteAction.ConfirmDiscard -> discardDraft()
            is NoteAction.RequestUndo -> requestUndo(action.eventId)
            NoteAction.ConfirmUndo -> confirmUndo()
            NoteAction.DismissDialog -> {
                mutableState.update { it.copy(dialog = null) }
            }

            NoteAction.CompletionConsumed -> {
                mutableState.update { it.copy(completion = null) }
            }
        }
    }

    private fun observeLastCommitted(showLoading: Boolean = false) {
        if (showLoading) {
            mutableState.update { it.copy(lastCommitted = LastNoteUiState.Loading) }
        }
        val previousObservation = lastCommittedObservationJob
        lastCommittedObservationJob = viewModelScope.launch {
            previousObservation?.cancelAndJoin()
            try {
                repository.observeLastCommitted().collect { summary ->
                    mutableState.update {
                        it.copy(
                            lastCommitted = summary
                                ?.let { value -> LastNoteUiState.Available(value.toUi()) }
                                ?: LastNoteUiState.Empty,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.update {
                    it.copy(lastCommitted = LastNoteUiState.Failed)
                }
            }
        }
    }

    private fun retryLastCommitted() {
        if (mutableState.value.lastCommitted != LastNoteUiState.Failed) return
        observeLastCommitted(showLoading = true)
    }

    private fun recoverPendingMutation() {
        val operationId = savedStateHandle.get<String>(PENDING_OPERATION_ID)
            ?.toUuidOrNull()
            ?: run {
                clearFrozenSubmission()
                mutableState.update { it.copy(editorLoading = false) }
                return
            }
        editorLoadJob = viewModelScope.launch {
            try {
                val receipt = repository.findByOperationId(operationId)
                if (receipt != null) {
                    val kind = savedStateHandle.get<String>(PENDING_KIND)
                        ?.let { PendingKind.valueOfOrNull(it) }
                        ?: PendingKind.Create
                    publishSuccess(receipt, kind)
                } else {
                    clearFrozenSubmission()
                    mutableState.update { it.copy(editorLoading = false) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.update {
                    it.copy(
                        editorLoading = false,
                        lastCommitted = LastNoteUiState.Failed,
                        completion = NoteCompletionUi(
                            id = UUID.randomUUID(),
                            kind = NoteCompletionKind.DraftDiscarded,
                            message = "Не удалось проверить незавершённое сохранение",
                        ),
                    )
                }
            }
        }
    }

    private fun startCreate() {
        val current = mutableState.value
        if (current.editor != null || current.editorLoading) return
        correctionBaseRevisionId = null
        correctionEventId = null
        undoTarget = null
        preparedMutation = null
        mutableState.update {
            it.copy(
                editor = NoteEditorUiState(
                    mode = NoteEditorMode.Create,
                    timestamp = NoteTimestampUiState(
                        defaultTimezoneId = zoneIdProvider().id,
                    ),
                    persistenceAvailable = true,
                ),
                dialog = null,
            )
        }
    }

    private fun startCorrection(eventId: UUID) {
        if (mutableState.value.editorLoading) return
        mutableState.update {
            it.copy(
                editor = null,
                editorLoading = true,
                dialog = null,
            )
        }
        editorLoadJob?.cancel()
        editorLoadJob = viewModelScope.launch {
            try {
                val note = repository.getByEventId(eventId)
                if (note == null || note.status == NoteRecordStatus.RETRACTED) {
                    mutableState.update {
                        it.copy(
                            editorLoading = false,
                            completion = NoteCompletionUi(
                                id = UUID.randomUUID(),
                                kind = NoteCompletionKind.DraftDiscarded,
                                message = "Заметка уже недоступна для исправления",
                            ),
                        )
                    }
                    return@launch
                }
                correctionBaseRevisionId = note.revisionId
                correctionEventId = note.eventId
                preparedMutation = null
                clearFrozenSubmission()
                mutableState.update {
                    it.copy(
                        editorLoading = false,
                        editor = NoteEditorUiState(
                            mode = NoteEditorMode.Correct,
                            text = note.text,
                            timestamp = NoteTimestampUiState(
                                choice = NoteTimestampChoice.Custom(
                                    localDateTime = note.effectiveTime.originalLocal,
                                    zoneId = note.effectiveTime.timezoneId.id,
                                    preferredOffsetSeconds = note.effectiveTime.offset.totalSeconds,
                                ),
                                defaultTimezoneId = note.effectiveTime.timezoneId.id,
                            ),
                            persistenceAvailable = true,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.update {
                    it.copy(
                        editorLoading = false,
                        completion = NoteCompletionUi(
                            id = UUID.randomUUID(),
                            kind = NoteCompletionKind.DraftDiscarded,
                            message = "Не удалось открыть заметку для исправления",
                        ),
                    )
                }
            }
        }
    }

    private fun changeText(text: String) {
        val editor = mutableState.value.editor ?: return
        if (editor.isSubmitting || editor.retryAvailable) return
        updateEditor {
            it.copy(
                text = text,
                textError = when {
                    noteTextCodePointCount(text) > NoteTextPolicy.MAX_CODE_POINTS ->
                        NoteTextError.TooLong

                    text.isNotEmpty() -> null
                    else -> it.textError
                },
                formError = null,
            )
        }
    }

    private fun selectTimestamp(choice: NoteTimestampChoice) {
        val editor = mutableState.value.editor ?: return
        if (editor.isSubmitting || editor.retryAvailable) return
        val resolution = resolveNoteTimestamp(
            choice = choice,
            now = clock.instant(),
            defaultZoneId = zoneIdProvider(),
        )
        updateEditor {
            it.copy(
                timestamp = it.timestamp.copy(
                    choice = choice,
                    defaultTimezoneId = zoneIdProvider().id,
                    pickerVisible = false,
                    error = resolution.errorMessage(),
                    overlapOffsetsSeconds = (resolution as? NoteTimestampResolution.Overlap)
                        ?.offsets
                        ?.map { it.totalSeconds }
                        .orEmpty(),
                ),
                formError = null,
            )
        }
    }

    private fun selectOverlapOffset(offsetSeconds: Int) {
        val choice = mutableState.value.editor
            ?.timestamp
            ?.choice as? NoteTimestampChoice.Custom ?: return
        selectTimestamp(choice.copy(preferredOffsetSeconds = offsetSeconds))
    }

    private fun save() {
        val editor = mutableState.value.editor ?: return
        if (editor.isSubmitting || editor.retryAvailable || preparedMutation != null) return
        val textError = validateText(editor.text)
        if (textError != null) {
            updateEditor { it.copy(textError = textError) }
            return
        }

        val frozenNow = clock.instant()
        val resolution = resolveNoteTimestamp(
            choice = editor.timestamp.choice,
            now = frozenNow,
            defaultZoneId = zoneIdProvider(),
        )
        if (resolution !is NoteTimestampResolution.Valid) {
            updateEditor {
                it.copy(
                    timestamp = it.timestamp.copy(
                        error = resolution.errorMessage(),
                        overlapOffsetsSeconds =
                            (resolution as? NoteTimestampResolution.Overlap)
                                ?.offsets
                                ?.map { it.totalSeconds }
                                .orEmpty(),
                    ),
                )
            }
            return
        }

        val zone = zoneIdProvider()
        val recordedAt = frozenNow.atZone(zone).toOffsetDateTime()
        val mutation = when (editor.mode) {
            NoteEditorMode.Create -> {
                val command = CreateNoteCommand(
                    ids = mutationIdsFactory.forNewEvent(),
                    text = editor.text,
                    effectiveTime = resolution.value,
                    recordedAt = recordedAt,
                )
                PreparedMutation.Create(command)
            }

            NoteEditorMode.Correct -> {
                val eventId = correctionEventId
                    ?: run {
                        updateEditor {
                            it.copy(formError = "Заметка изменилась. Открой исправление заново.")
                        }
                        return
                    }
                val baseRevisionId = correctionBaseRevisionId
                    ?: run {
                        updateEditor {
                            it.copy(formError = "Не удалось определить исходную revision")
                        }
                        return
                    }
                val command = CorrectNoteCommand(
                    ids = mutationIdsFactory.forExistingEvent(eventId),
                    expectedCurrentRevisionId = baseRevisionId,
                    text = editor.text,
                    effectiveTime = resolution.value,
                    recordedAt = recordedAt,
                )
                PreparedMutation.Correct(command)
            }
        }
        prepareAndExecute(mutation)
    }

    private fun retrySave() {
        val mutation = preparedMutation ?: return
        if (mutableState.value.editor?.isSubmitting == true) return
        execute(mutation)
    }

    private fun requestExit() {
        val editor = mutableState.value.editor
        if (editor == null) {
            if (mutableState.value.editorLoading) {
                editorLoadJob?.cancel()
                editorLoadJob = null
                mutableState.update {
                    it.copy(
                        editorLoading = false,
                        completion = NoteCompletionUi(
                            id = UUID.randomUUID(),
                            kind = NoteCompletionKind.DraftDiscarded,
                            message = null,
                        ),
                    )
                }
            }
            return
        }
        // A local transaction can already be committing. Keep the frozen command intact
        // until the repository reports either success or a retryable failure.
        if (editor.isSubmitting) return
        if (editor.text.isBlank() && preparedMutation == null) {
            discardDraft()
        } else {
            mutableState.update { it.copy(dialog = NoteDialogUi.DiscardDraft) }
        }
    }

    private fun discardDraft() {
        preparedMutation = null
        correctionBaseRevisionId = null
        correctionEventId = null
        clearFrozenSubmission()
        mutableState.update {
            it.copy(
                editor = null,
                dialog = null,
                completion = NoteCompletionUi(
                    id = UUID.randomUUID(),
                    kind = NoteCompletionKind.DraftDiscarded,
                    message = null,
                ),
            )
        }
    }

    private fun requestUndo(eventId: UUID) {
        if (mutableState.value.mutationInProgress) return
        val current = (mutableState.value.lastCommitted as? LastNoteUiState.Available)
            ?.note
            ?.takeIf { it.eventId == eventId && it.status == NoteRecordStatusUi.Active }
            ?: return
        undoTarget = current
        mutableState.update {
            it.copy(dialog = NoteDialogUi.ConfirmUndo(current))
        }
    }

    private fun confirmUndo() {
        val retry = preparedMutation
        if (retry is PreparedMutation.Retract) {
            if (!mutableState.value.mutationInProgress) {
                mutableState.update {
                    it.copy(dialog = null, undoRetryAvailable = false)
                }
                execute(retry)
            }
            return
        }
        val target = undoTarget ?: return
        if (preparedMutation != null) return
        val frozenNow = clock.instant()
        val command = RetractNoteCommand(
            ids = mutationIdsFactory.forExistingEvent(target.eventId),
            expectedCurrentRevisionId = target.revisionId,
            recordedAt = frozenNow.atZone(zoneIdProvider()).toOffsetDateTime(),
        )
        mutableState.update {
            it.copy(dialog = null, undoRetryAvailable = false)
        }
        prepareAndExecute(PreparedMutation.Retract(command))
    }

    private fun prepareAndExecute(mutation: PreparedMutation) {
        preparedMutation = mutation
        freezeSubmission(mutation)
        execute(mutation)
    }

    private fun execute(mutation: PreparedMutation) {
        mutableState.update { state ->
            state.copy(
                mutationInProgress = true,
                editor = state.editor?.copy(
                    isSubmitting = true,
                    retryAvailable = false,
                    formError = null,
                ),
            )
        }
        viewModelScope.launch {
            try {
                when (val outcome = mutation.execute(repository)) {
                    is NoteMutationOutcome.Persisted -> publishSuccess(
                        receipt = outcome.receipt,
                        kind = mutation.kind,
                    )

                    is NoteMutationOutcome.AlreadyRetracted -> {
                        publishAlreadyRetracted(outcome.current)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: InvalidNoteTextException) {
                clearPreparedMutation()
                mutableState.update { it.copy(mutationInProgress = false) }
                updateEditor {
                    it.copy(
                        isSubmitting = false,
                        textError = validateText(it.text) ?: NoteTextError.Empty,
                    )
                }
            } catch (_: StaleNoteRevisionException) {
                clearPreparedMutation()
                if (mutation is PreparedMutation.Retract) {
                    undoTarget = null
                    mutableState.update {
                        it.copy(
                            mutationInProgress = false,
                            dialog = null,
                            undoRetryAvailable = false,
                            completion = NoteCompletionUi(
                                id = UUID.randomUUID(),
                                kind = NoteCompletionKind.DraftDiscarded,
                                message = "Заметка изменилась — открой её действие заново",
                            ),
                        )
                    }
                } else {
                    mutableState.update { it.copy(mutationInProgress = false) }
                    updateEditor {
                        it.copy(
                            isSubmitting = false,
                            formError = "Заметка изменилась. Открой исправление заново.",
                        )
                    }
                }
            } catch (_: IdempotencyConflictException) {
                clearPreparedMutation()
                if (mutation is PreparedMutation.Retract) {
                    undoTarget = null
                    mutableState.update {
                        it.copy(
                            mutationInProgress = false,
                            dialog = null,
                            undoRetryAvailable = false,
                            completion = NoteCompletionUi(
                                id = UUID.randomUUID(),
                                kind = NoteCompletionKind.DraftDiscarded,
                                message = "Не удалось безопасно повторить отмену",
                            ),
                        )
                    }
                } else {
                    mutableState.update { it.copy(mutationInProgress = false) }
                    updateEditor {
                        it.copy(
                            isSubmitting = false,
                            formError = "Не удалось безопасно повторить операцию",
                        )
                    }
                }
            } catch (_: Throwable) {
                if (mutation is PreparedMutation.Retract) {
                    val target = undoTarget
                    mutableState.update { state ->
                        state.copy(
                            mutationInProgress = false,
                            undoRetryAvailable = true,
                            dialog = target?.let(NoteDialogUi::ConfirmUndo),
                        )
                    }
                } else {
                    mutableState.update { state ->
                        state.copy(
                            mutationInProgress = false,
                            editor = state.editor?.copy(
                                isSubmitting = false,
                                retryAvailable = true,
                                formError = null,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun publishSuccess(
        receipt: NoteMutationReceipt,
        kind: PendingKind,
    ) {
        val uiNote = receipt.note.toUi()
        clearPreparedMutation()
        correctionBaseRevisionId = null
        correctionEventId = null
        undoTarget = null
        mutableState.update {
            it.copy(
                editor = null,
                editorLoading = false,
                dialog = null,
                mutationInProgress = false,
                undoRetryAvailable = false,
                lastCommitted = LastNoteUiState.Available(uiNote),
                completion = NoteCompletionUi(
                    id = receipt.note.operationId,
                    kind = kind.completionKind,
                    message = kind.successMessage,
                ),
            )
        }
    }

    private fun publishAlreadyRetracted(snapshot: NoteSnapshot) {
        val uiNote = snapshot.toUi()
        clearPreparedMutation()
        undoTarget = null
        mutableState.update {
            it.copy(
                editor = null,
                dialog = null,
                mutationInProgress = false,
                undoRetryAvailable = false,
                lastCommitted = LastNoteUiState.Available(uiNote),
                completion = NoteCompletionUi(
                    id = snapshot.operationId,
                    kind = NoteCompletionKind.Retracted,
                    message = "Заметка уже отменена",
                ),
            )
        }
    }

    private fun clearPreparedMutation() {
        preparedMutation = null
        clearFrozenSubmission()
    }

    private fun freezeSubmission(mutation: PreparedMutation) {
        val ids = mutation.ids
        savedStateHandle[PENDING_KIND] = mutation.kind.name
        savedStateHandle[PENDING_OPERATION_ID] = ids.operationId.toString()
        savedStateHandle[PENDING_CAPTURE_ID] = ids.captureId.toString()
        savedStateHandle[PENDING_EVENT_ID] = ids.eventId.toString()
        savedStateHandle[PENDING_REVISION_ID] = ids.revisionId.toString()
        savedStateHandle[PENDING_RECORDED_AT] = mutation.recordedAt.toString()
        mutation.effectiveTime?.let { time ->
            savedStateHandle[PENDING_EFFECTIVE_AT] = time.effectiveAt.toString()
            savedStateHandle[PENDING_ORIGINAL_LOCAL] = time.originalLocal.toString()
            savedStateHandle[PENDING_TIMEZONE] = time.timezoneId.id
            savedStateHandle[PENDING_OFFSET_SECONDS] = time.offset.totalSeconds
        }
        mutation.expectedRevisionId?.let {
            savedStateHandle[PENDING_EXPECTED_REVISION_ID] = it.toString()
        }
    }

    private fun clearFrozenSubmission() {
        FROZEN_KEYS.forEach { key -> savedStateHandle.remove<Any?>(key) }
    }

    private fun updateEditor(transform: (NoteEditorUiState) -> NoteEditorUiState) {
        mutableState.update { state ->
            val editor = state.editor ?: return@update state
            state.copy(editor = transform(editor))
        }
    }

    private fun validateText(text: String): NoteTextError? = when {
        text.isBlank() -> NoteTextError.Empty
        noteTextCodePointCount(text) > NoteTextPolicy.MAX_CODE_POINTS -> NoteTextError.TooLong
        else -> null
    }

    private sealed interface PreparedMutation {
        val ids: MutationIds
        val kind: PendingKind
        val recordedAt: OffsetDateTime
        val effectiveTime: ResolvedPointTime?
        val expectedRevisionId: UUID?

        suspend fun execute(repository: NotesRepository): NoteMutationOutcome

        data class Create(val command: CreateNoteCommand) : PreparedMutation {
            override val ids: MutationIds = command.ids
            override val kind: PendingKind = PendingKind.Create
            override val recordedAt: OffsetDateTime = command.recordedAt
            override val effectiveTime: ResolvedPointTime = command.effectiveTime
            override val expectedRevisionId: UUID? = null

            override suspend fun execute(repository: NotesRepository): NoteMutationOutcome =
                repository.create(command)
        }

        data class Correct(val command: CorrectNoteCommand) : PreparedMutation {
            override val ids: MutationIds = command.ids
            override val kind: PendingKind = PendingKind.Correct
            override val recordedAt: OffsetDateTime = command.recordedAt
            override val effectiveTime: ResolvedPointTime = command.effectiveTime
            override val expectedRevisionId: UUID = command.expectedCurrentRevisionId

            override suspend fun execute(repository: NotesRepository): NoteMutationOutcome =
                repository.correct(command)
        }

        data class Retract(val command: RetractNoteCommand) : PreparedMutation {
            override val ids: MutationIds = command.ids
            override val kind: PendingKind = PendingKind.Retract
            override val recordedAt: OffsetDateTime = command.recordedAt
            override val effectiveTime: ResolvedPointTime? = null
            override val expectedRevisionId: UUID = command.expectedCurrentRevisionId

            override suspend fun execute(repository: NotesRepository): NoteMutationOutcome =
                repository.retract(command)
        }
    }

    private enum class PendingKind(
        val completionKind: NoteCompletionKind,
        val successMessage: String,
    ) {
        Create(NoteCompletionKind.Created, "Заметка сохранена на устройстве"),
        Correct(NoteCompletionKind.Corrected, "Исправление сохранено на устройстве"),
        Retract(NoteCompletionKind.Retracted, "Отмена сохранена на устройстве");

        companion object {
            fun valueOfOrNull(value: String): PendingKind? =
                entries.firstOrNull { it.name == value }
        }
    }

    companion object {
        private const val PENDING_KIND = "note.pending.kind"
        private const val PENDING_OPERATION_ID = "note.pending.operation_id"
        private const val PENDING_CAPTURE_ID = "note.pending.capture_id"
        private const val PENDING_EVENT_ID = "note.pending.event_id"
        private const val PENDING_REVISION_ID = "note.pending.revision_id"
        private const val PENDING_RECORDED_AT = "note.pending.recorded_at"
        private const val PENDING_EFFECTIVE_AT = "note.pending.effective_at"
        private const val PENDING_ORIGINAL_LOCAL = "note.pending.original_local"
        private const val PENDING_TIMEZONE = "note.pending.timezone"
        private const val PENDING_OFFSET_SECONDS = "note.pending.offset_seconds"
        private const val PENDING_EXPECTED_REVISION_ID = "note.pending.expected_revision_id"

        private val FROZEN_KEYS = listOf(
            PENDING_KIND,
            PENDING_OPERATION_ID,
            PENDING_CAPTURE_ID,
            PENDING_EVENT_ID,
            PENDING_REVISION_ID,
            PENDING_RECORDED_AT,
            PENDING_EFFECTIVE_AT,
            PENDING_ORIGINAL_LOCAL,
            PENDING_TIMEZONE,
            PENDING_OFFSET_SECONDS,
            PENDING_EXPECTED_REVISION_ID,
        )
    }
}

private fun NoteSummary.toUi(): NoteSummaryUi = NoteSummaryUi(
    eventId = eventId,
    revisionId = revisionId,
    text = text,
    effectiveAt = effectiveTime.effectiveAt,
    originalLocalDateTime = effectiveTime.originalLocal,
    timezoneId = effectiveTime.timezoneId.id,
    offsetSeconds = effectiveTime.offset.totalSeconds,
    status = status.toUi(),
)

private fun NoteSnapshot.toUi(): NoteSummaryUi = NoteSummaryUi(
    eventId = eventId,
    revisionId = revisionId,
    text = text,
    effectiveAt = effectiveTime.effectiveAt,
    originalLocalDateTime = effectiveTime.originalLocal,
    timezoneId = effectiveTime.timezoneId.id,
    offsetSeconds = effectiveTime.offset.totalSeconds,
    status = status.toUi(),
)

private fun NoteRecordStatus.toUi(): NoteRecordStatusUi = when (this) {
    NoteRecordStatus.ACTIVE -> NoteRecordStatusUi.Active
    NoteRecordStatus.RETRACTED -> NoteRecordStatusUi.Retracted
}

private fun String.toUuidOrNull(): UUID? = try {
    UUID.fromString(this)
} catch (_: IllegalArgumentException) {
    null
}
