package ru.andriyshkoy.lifeagent.ui.wellbeing

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.andriyshkoy.lifeagent.core.id.MutationIds
import ru.andriyshkoy.lifeagent.core.id.MutationIdsFactory
import ru.andriyshkoy.lifeagent.core.time.ResolvedPointTime
import ru.andriyshkoy.lifeagent.ui.time.EventTimestampChoice
import ru.andriyshkoy.lifeagent.ui.time.EventTimestampResolution
import ru.andriyshkoy.lifeagent.ui.time.EventTimestampUiState
import ru.andriyshkoy.lifeagent.ui.time.errorMessage
import ru.andriyshkoy.lifeagent.ui.time.resolveEventTimestamp
import ru.andriyshkoy.lifeagent.wellbeing.domain.CorrectWellbeingCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.CreateWellbeingCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.InvalidWellbeingException
import ru.andriyshkoy.lifeagent.wellbeing.domain.RetractWellbeingCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.RetractedWellbeingCorrectionException
import ru.andriyshkoy.lifeagent.wellbeing.domain.StaleWellbeingRevisionException
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingCatalogRepository
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingDimension
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingIdempotencyConflictException
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingMutationOutcome
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingMutationReceipt
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingNotFoundException
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingPayload
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingRecordStatus
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingRepository
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingSnapshot
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingSummary
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingValueSnapshot

class WellbeingViewModel(
    private val repository: WellbeingRepository,
    private val catalogRepository: WellbeingCatalogRepository,
    private val savedStateHandle: SavedStateHandle,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() },
    private val mutationIdsFactory: MutationIdsFactory = MutationIdsFactory(),
) : ViewModel(), WellbeingController {
    private val mutableState = MutableStateFlow(
        WellbeingUiState(
            lastCommitted = LastWellbeingUiState.Loading,
            catalog = WellbeingCatalogUiState.Loading,
            persistenceAvailable = true,
            editorLoading = savedStateHandle.contains(PENDING_OPERATION_ID),
        ),
    )
    override val uiState: StateFlow<WellbeingUiState> = mutableState.asStateFlow()

    private var catalogDimensions: List<WellbeingDimension> = emptyList()
    private var catalogObservationJob: Job? = null
    private var lastCommittedObservationJob: Job? = null
    private var editorLoadJob: Job? = null
    private var correctionBaseRevisionId: UUID? = null
    private var correctionEventId: UUID? = null
    private var preparedMutation: PreparedMutation? = null
    private var undoTarget: WellbeingSummaryUi? = null
    private var pendingEditorRequest: EditorRequest? = null

    init {
        observeCatalog()
        observeLastCommitted()
        recoverPendingMutation()
    }

    override fun dispatch(action: WellbeingAction) {
        when (action) {
            WellbeingAction.StartCreate -> loadEditor(EditorRequest.Create)
            is WellbeingAction.StartCorrection -> loadEditor(
                EditorRequest.Correct(action.eventId),
            )

            WellbeingAction.RetryEditorLoad -> pendingEditorRequest?.let(::loadEditor)
            WellbeingAction.RetryLastCommitted -> retryLastCommitted()
            WellbeingAction.RetryCatalog -> observeCatalog(showLoading = true)
            is WellbeingAction.SelectOption -> selectOption(action.dimensionId, action.optionId)
            is WellbeingAction.ClearDimension -> clearDimension(action.dimensionId)
            is WellbeingAction.CommentChanged -> changeComment(action.value)
            WellbeingAction.OpenTimestampPicker -> updateEditor {
                if (it.isSubmitting || it.retryAvailable) it else {
                    it.copy(timestamp = it.timestamp.copy(pickerVisible = true))
                }
            }

            WellbeingAction.DismissTimestampPicker -> updateEditor {
                it.copy(timestamp = it.timestamp.copy(pickerVisible = false))
            }

            is WellbeingAction.SelectTimestamp -> selectTimestamp(action.choice)
            is WellbeingAction.SelectOverlapOffset -> selectOverlapOffset(action.offsetSeconds)
            WellbeingAction.Save -> save()
            WellbeingAction.RetrySave -> retrySave()
            WellbeingAction.ExitRequested -> requestExit()
            WellbeingAction.ConfirmDiscard -> discardDraft()
            is WellbeingAction.RequestUndo -> requestUndo(action.eventId)
            WellbeingAction.ConfirmUndo -> confirmUndo()
            WellbeingAction.DismissDialog -> mutableState.update { it.copy(dialog = null) }
            WellbeingAction.CompletionConsumed -> mutableState.update {
                it.copy(completion = null)
            }
        }
    }

    private fun observeCatalog(showLoading: Boolean = false) {
        if (showLoading) {
            mutableState.update { it.copy(catalog = WellbeingCatalogUiState.Loading) }
        }
        val previous = catalogObservationJob
        catalogObservationJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            try {
                catalogRepository.ensureSeeded(clock.instant())
                catalogRepository.observeDimensions().collect { dimensions ->
                    catalogDimensions = dimensions.sortedBy(WellbeingDimension::sortOrder)
                    mutableState.update {
                        it.copy(
                            catalog = WellbeingCatalogUiState.Available(
                                catalogDimensions.map { dimension -> dimension.toUi() },
                            ),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.update { it.copy(catalog = WellbeingCatalogUiState.Failed) }
            }
        }
    }

    private fun observeLastCommitted(showLoading: Boolean = false) {
        if (showLoading) {
            mutableState.update { it.copy(lastCommitted = LastWellbeingUiState.Loading) }
        }
        val previous = lastCommittedObservationJob
        lastCommittedObservationJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            try {
                repository.observeLastCommitted().collect { summary ->
                    mutableState.update {
                        it.copy(
                            lastCommitted = summary
                                ?.let { value -> LastWellbeingUiState.Available(value.toUi()) }
                                ?: LastWellbeingUiState.Empty,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.update { it.copy(lastCommitted = LastWellbeingUiState.Failed) }
            }
        }
    }

    private fun retryLastCommitted() {
        if (mutableState.value.lastCommitted != LastWellbeingUiState.Failed) return
        observeLastCommitted(showLoading = true)
    }

    private fun recoverPendingMutation() {
        val operationId = savedStateHandle.get<String>(PENDING_OPERATION_ID)?.toUuidOrNull()
            ?: run {
                clearFrozenSubmission()
                mutableState.update { it.copy(editorLoading = false) }
                return
            }
        viewModelScope.launch {
            try {
                val receipt = repository.findByOperationId(operationId)
                if (receipt == null) {
                    clearFrozenSubmission()
                    mutableState.update {
                        it.copy(
                            editorLoading = false,
                            completion = WellbeingCompletionUi(
                                id = UUID.randomUUID(),
                                kind = WellbeingCompletionKind.DraftDiscarded,
                                message = "Не удалось подтвердить незавершённое сохранение",
                            ),
                        )
                    }
                } else {
                    val kind = savedStateHandle.get<String>(PENDING_KIND)
                        ?.let(PendingKind::valueOfOrNull)
                        ?: PendingKind.Create
                    publishSuccess(receipt, kind)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                clearFrozenSubmission()
                mutableState.update {
                    it.copy(
                        editorLoading = false,
                        completion = WellbeingCompletionUi(
                            id = UUID.randomUUID(),
                            kind = WellbeingCompletionKind.DraftDiscarded,
                            message = "Не удалось проверить незавершённое сохранение",
                        ),
                    )
                }
            }
        }
    }

    private fun loadEditor(request: EditorRequest) {
        if (mutableState.value.editorLoading || mutableState.value.mutationInProgress) return
        pendingEditorRequest = request
        mutableState.update {
            it.copy(editor = null, editorLoading = true, editorLoadError = null, dialog = null)
        }
        editorLoadJob?.cancel()
        editorLoadJob = viewModelScope.launch {
            try {
                catalogRepository.ensureSeeded(clock.instant())
                val dimensions = catalogRepository.observeDimensions().first()
                    .sortedBy(WellbeingDimension::sortOrder)
                if (dimensions.isEmpty()) {
                    throw IllegalStateException("Wellbeing catalog is empty")
                }
                catalogDimensions = dimensions
                val editor = when (request) {
                    EditorRequest.Create -> newEditor(dimensions)
                    is EditorRequest.Correct -> correctionEditor(request.eventId, dimensions)
                }
                if (editor == null) {
                    mutableState.update {
                        it.copy(
                            editorLoading = false,
                            completion = WellbeingCompletionUi(
                                id = UUID.randomUUID(),
                                kind = WellbeingCompletionKind.DraftDiscarded,
                                message = "Запись уже недоступна для исправления",
                            ),
                        )
                    }
                    return@launch
                }
                pendingEditorRequest = request
                mutableState.update {
                    it.copy(
                        editor = editor,
                        editorLoading = false,
                        editorLoadError = null,
                        catalog = WellbeingCatalogUiState.Available(
                            dimensions.map { dimension -> dimension.toUi() },
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.update {
                    it.copy(
                        editorLoading = false,
                        editorLoadError = "Не удалось открыть форму самочувствия",
                    )
                }
            }
        }
    }

    private fun newEditor(dimensions: List<WellbeingDimension>) = WellbeingEditorUiState(
        mode = WellbeingEditorMode.Create,
        dimensions = dimensions.map { it.toUi() },
        timestamp = EventTimestampUiState(defaultTimezoneId = zoneIdProvider().id),
        persistenceAvailable = true,
    )

    private suspend fun correctionEditor(
        eventId: UUID,
        dimensions: List<WellbeingDimension>,
    ): WellbeingEditorUiState? {
        val wellbeing = repository.getByEventId(eventId) ?: return null
        if (wellbeing.status == WellbeingRecordStatus.RETRACTED) return null
        correctionBaseRevisionId = wellbeing.revisionId
        correctionEventId = wellbeing.eventId
        preparedMutation = null
        clearFrozenSubmission()
        return WellbeingEditorUiState(
            mode = WellbeingEditorMode.Correct,
            dimensions = buildEditorDimensions(dimensions, wellbeing.payload),
            comment = wellbeing.payload.comment.orEmpty(),
            timestamp = EventTimestampUiState(
                choice = EventTimestampChoice.Custom(
                    localDateTime = wellbeing.effectiveTime.originalLocal,
                    zoneId = wellbeing.effectiveTime.timezoneId.id,
                    preferredOffsetSeconds = wellbeing.effectiveTime.offset.totalSeconds,
                ),
                defaultTimezoneId = wellbeing.effectiveTime.timezoneId.id,
            ),
            persistenceAvailable = true,
            isDirty = false,
        )
    }

    private fun selectOption(dimensionId: UUID, optionId: UUID) {
        val editor = mutableState.value.editor ?: return
        if (editor.isSubmitting || editor.retryAvailable) return
        val catalogDimension = catalogDimensions.firstOrNull {
            it.dimensionId == dimensionId && it.active
        } ?: return
        val option = catalogDimension.options.firstOrNull {
            it.optionId == optionId && it.active
        } ?: return
        updateEditor { current ->
            current.copy(
                dimensions = current.dimensions.map { dimension ->
                    if (dimension.dimensionId != dimensionId) return@map dimension
                    dimension.copy(
                        selectedSnapshot = catalogDimension.snapshot(option.optionId),
                    )
                },
                isDirty = true,
                formError = null,
            )
        }
    }

    private fun clearDimension(dimensionId: UUID) {
        val editor = mutableState.value.editor ?: return
        if (editor.isSubmitting || editor.retryAvailable) return
        updateEditor { current ->
            current.copy(
                dimensions = current.dimensions.map { dimension ->
                    if (dimension.dimensionId == dimensionId) {
                        dimension.copy(selectedSnapshot = null)
                    } else {
                        dimension
                    }
                },
                isDirty = true,
                formError = null,
            )
        }
    }

    private fun changeComment(value: String) {
        val editor = mutableState.value.editor ?: return
        if (editor.isSubmitting || editor.retryAvailable) return
        updateEditor {
            it.copy(
                comment = value,
                commentError = if (wellbeingCodePointCount(value) > WELLBEING_COMMENT_MAX_LENGTH) {
                    WellbeingCommentError.TooLong
                } else {
                    null
                },
                isDirty = true,
                formError = null,
            )
        }
    }

    private fun selectTimestamp(choice: EventTimestampChoice) {
        val editor = mutableState.value.editor ?: return
        if (editor.isSubmitting || editor.retryAvailable) return
        val resolution = resolveEventTimestamp(choice, clock.instant(), zoneIdProvider())
        val futureError = (resolution as? EventTimestampResolution.Valid)
            ?.takeIf { it.value.effectiveAt > clock.instant() }
            ?.let { "Время события не может быть в будущем" }
        updateEditor {
            it.copy(
                timestamp = it.timestamp.copy(
                    choice = choice,
                    defaultTimezoneId = zoneIdProvider().id,
                    pickerVisible = false,
                    error = futureError ?: resolution.errorMessage(),
                    overlapOffsetsSeconds = (resolution as? EventTimestampResolution.Overlap)
                        ?.offsets
                        ?.map { offset -> offset.totalSeconds }
                        .orEmpty(),
                ),
                isDirty = true,
                formError = null,
            )
        }
    }

    private fun selectOverlapOffset(offsetSeconds: Int) {
        val choice = mutableState.value.editor?.timestamp?.choice
            as? EventTimestampChoice.Custom ?: return
        selectTimestamp(choice.copy(preferredOffsetSeconds = offsetSeconds))
    }

    private fun save() {
        val editor = mutableState.value.editor ?: return
        if (editor.isSubmitting || editor.retryAvailable || preparedMutation != null) return
        val values = editor.dimensions.mapNotNull(WellbeingDimensionUi::selectedSnapshot)
        if (values.isEmpty()) {
            updateEditor { it.copy(formError = "Отметь хотя бы один показатель") }
            return
        }
        if (wellbeingCodePointCount(editor.comment) > WELLBEING_COMMENT_MAX_LENGTH) {
            updateEditor { it.copy(commentError = WellbeingCommentError.TooLong) }
            return
        }
        val frozenNow = clock.instant()
        val resolution = resolveEventTimestamp(
            choice = editor.timestamp.choice,
            now = frozenNow,
            defaultZoneId = zoneIdProvider(),
        )
        if (resolution !is EventTimestampResolution.Valid ||
            resolution.value.effectiveAt > frozenNow
        ) {
            updateEditor {
                it.copy(
                    timestamp = it.timestamp.copy(
                        error = if (resolution is EventTimestampResolution.Valid) {
                            "Время события не может быть в будущем"
                        } else {
                            resolution.errorMessage()
                        },
                        overlapOffsetsSeconds =
                            (resolution as? EventTimestampResolution.Overlap)
                                ?.offsets
                                ?.map { offset -> offset.totalSeconds }
                                .orEmpty(),
                    ),
                )
            }
            return
        }
        val recordedAt = frozenNow.atZone(zoneIdProvider()).toOffsetDateTime()
        val normalizedComment = editor.comment.trim().ifEmpty { null }
        val mutation = when (editor.mode) {
            WellbeingEditorMode.Create -> PreparedMutation.Create(
                CreateWellbeingCommand(
                    ids = mutationIdsFactory.forNewEvent(),
                    values = values,
                    comment = normalizedComment,
                    effectiveTime = resolution.value,
                    recordedAt = recordedAt,
                ),
            )

            WellbeingEditorMode.Correct -> {
                val eventId = correctionEventId ?: run {
                    updateEditor {
                        it.copy(formError = "Запись изменилась. Открой исправление заново.")
                    }
                    return
                }
                val baseRevisionId = correctionBaseRevisionId ?: run {
                    updateEditor { it.copy(formError = "Не удалось определить исходную запись") }
                    return
                }
                PreparedMutation.Correct(
                    CorrectWellbeingCommand(
                        ids = mutationIdsFactory.forExistingEvent(eventId),
                        expectedCurrentRevisionId = baseRevisionId,
                        values = values,
                        comment = normalizedComment,
                        effectiveTime = resolution.value,
                        recordedAt = recordedAt,
                    ),
                )
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
            if (mutableState.value.editorLoading || mutableState.value.editorLoadError != null) {
                editorLoadJob?.cancel()
                mutableState.update {
                    it.copy(
                        editorLoading = false,
                        editorLoadError = null,
                        completion = WellbeingCompletionUi(
                            id = UUID.randomUUID(),
                            kind = WellbeingCompletionKind.DraftDiscarded,
                            message = null,
                        ),
                    )
                }
            }
            return
        }
        if (editor.isSubmitting) return
        if (!editor.isDirty && preparedMutation == null) {
            discardDraft()
        } else {
            mutableState.update { it.copy(dialog = WellbeingDialogUi.DiscardDraft) }
        }
    }

    private fun discardDraft() {
        preparedMutation = null
        correctionBaseRevisionId = null
        correctionEventId = null
        pendingEditorRequest = null
        clearFrozenSubmission()
        mutableState.update {
            it.copy(
                editor = null,
                editorLoading = false,
                editorLoadError = null,
                dialog = null,
                completion = WellbeingCompletionUi(
                    id = UUID.randomUUID(),
                    kind = WellbeingCompletionKind.DraftDiscarded,
                    message = null,
                ),
            )
        }
    }

    private fun requestUndo(eventId: UUID) {
        if (mutableState.value.mutationInProgress) return
        val current = (mutableState.value.lastCommitted as? LastWellbeingUiState.Available)
            ?.wellbeing
            ?.takeIf { it.eventId == eventId && it.status == WellbeingRecordStatusUi.Active }
            ?: return
        undoTarget = current
        mutableState.update { it.copy(dialog = WellbeingDialogUi.ConfirmUndo(current)) }
    }

    private fun confirmUndo() {
        val retry = preparedMutation
        if (retry is PreparedMutation.Retract) {
            if (!mutableState.value.mutationInProgress) {
                mutableState.update { it.copy(dialog = null, undoRetryAvailable = false) }
                execute(retry)
            }
            return
        }
        val target = undoTarget ?: return
        if (preparedMutation != null) return
        val command = RetractWellbeingCommand(
            ids = mutationIdsFactory.forExistingEvent(target.eventId),
            expectedCurrentRevisionId = target.revisionId,
            recordedAt = clock.instant().atZone(zoneIdProvider()).toOffsetDateTime(),
        )
        mutableState.update { it.copy(dialog = null, undoRetryAvailable = false) }
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
                    is WellbeingMutationOutcome.Persisted -> publishSuccess(
                        outcome.receipt,
                        mutation.kind,
                    )

                    is WellbeingMutationOutcome.AlreadyRetracted ->
                        publishAlreadyRetracted(outcome.current)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: InvalidWellbeingException) {
                clearPreparedMutation()
                mutableState.update { state ->
                    state.copy(
                        mutationInProgress = false,
                        editor = state.editor?.copy(
                            isSubmitting = false,
                            formError = "Проверь выбранные значения",
                        ),
                    )
                }
            } catch (_: RetractedWellbeingCorrectionException) {
                publishTargetUnavailable("Запись уже отменена и недоступна для исправления")
            } catch (_: WellbeingNotFoundException) {
                publishTargetUnavailable("Запись больше недоступна")
            } catch (_: StaleWellbeingRevisionException) {
                clearPreparedMutation()
                if (mutation is PreparedMutation.Retract) {
                    undoTarget = null
                    mutableState.update {
                        it.copy(
                            mutationInProgress = false,
                            dialog = null,
                            undoRetryAvailable = false,
                            completion = WellbeingCompletionUi(
                                id = UUID.randomUUID(),
                                kind = WellbeingCompletionKind.DraftDiscarded,
                                message = "Запись изменилась — открой её действие заново",
                            ),
                        )
                    }
                } else {
                    mutableState.update { state ->
                        state.copy(
                            mutationInProgress = false,
                            editor = state.editor?.copy(
                                isSubmitting = false,
                                formError = "Запись изменилась. Открой исправление заново.",
                            ),
                        )
                    }
                }
            } catch (_: WellbeingIdempotencyConflictException) {
                clearPreparedMutation()
                if (mutation is PreparedMutation.Retract) {
                    undoTarget = null
                    mutableState.update {
                        it.copy(
                            mutationInProgress = false,
                            dialog = null,
                            undoRetryAvailable = false,
                            completion = WellbeingCompletionUi(
                                id = UUID.randomUUID(),
                                kind = WellbeingCompletionKind.DraftDiscarded,
                                message = "Не удалось безопасно повторить отмену",
                            ),
                        )
                    }
                } else {
                    mutableState.update { state ->
                        state.copy(
                            mutationInProgress = false,
                            editor = state.editor?.copy(
                                isSubmitting = false,
                                formError = "Не удалось безопасно повторить сохранение",
                            ),
                        )
                    }
                }
            } catch (_: Throwable) {
                if (mutation is PreparedMutation.Retract) {
                    mutableState.update {
                        it.copy(
                            mutationInProgress = false,
                            undoRetryAvailable = true,
                            dialog = undoTarget?.let(WellbeingDialogUi::ConfirmUndo),
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

    private fun publishTargetUnavailable(message: String) {
        clearPreparedMutation()
        correctionBaseRevisionId = null
        correctionEventId = null
        pendingEditorRequest = null
        undoTarget = null
        mutableState.update {
            it.copy(
                editor = null,
                editorLoading = false,
                editorLoadError = null,
                dialog = null,
                mutationInProgress = false,
                undoRetryAvailable = false,
                completion = WellbeingCompletionUi(
                    id = UUID.randomUUID(),
                    kind = WellbeingCompletionKind.DraftDiscarded,
                    message = message,
                ),
            )
        }
    }

    private fun publishSuccess(receipt: WellbeingMutationReceipt, kind: PendingKind) {
        val uiWellbeing = receipt.wellbeing.toUi()
        clearPreparedMutation()
        correctionBaseRevisionId = null
        correctionEventId = null
        pendingEditorRequest = null
        undoTarget = null
        mutableState.update {
            it.copy(
                editor = null,
                editorLoading = false,
                editorLoadError = null,
                dialog = null,
                mutationInProgress = false,
                undoRetryAvailable = false,
                lastCommitted = LastWellbeingUiState.Available(uiWellbeing),
                completion = WellbeingCompletionUi(
                    id = receipt.wellbeing.operationId,
                    kind = kind.completionKind,
                    message = kind.successMessage,
                ),
            )
        }
    }

    private fun publishAlreadyRetracted(snapshot: WellbeingSnapshot) {
        val uiWellbeing = snapshot.toUi()
        clearPreparedMutation()
        undoTarget = null
        mutableState.update {
            it.copy(
                editor = null,
                dialog = null,
                mutationInProgress = false,
                undoRetryAvailable = false,
                lastCommitted = LastWellbeingUiState.Available(uiWellbeing),
                completion = WellbeingCompletionUi(
                    id = snapshot.operationId,
                    kind = WellbeingCompletionKind.Retracted,
                    message = "Запись самочувствия уже отменена",
                ),
            )
        }
    }

    private fun clearPreparedMutation() {
        preparedMutation = null
        clearFrozenSubmission()
    }

    private fun freezeSubmission(mutation: PreparedMutation) {
        savedStateHandle[PENDING_KIND] = mutation.kind.name
        savedStateHandle[PENDING_OPERATION_ID] = mutation.ids.operationId.toString()
    }

    private fun clearFrozenSubmission() {
        FROZEN_KEYS.forEach { savedStateHandle.remove<Any?>(it) }
    }

    private fun updateEditor(transform: (WellbeingEditorUiState) -> WellbeingEditorUiState) {
        mutableState.update { state ->
            val editor = state.editor ?: return@update state
            state.copy(editor = transform(editor))
        }
    }

    private sealed interface PreparedMutation {
        val ids: MutationIds
        val kind: PendingKind
        val recordedAt: OffsetDateTime
        val effectiveTime: ResolvedPointTime?

        suspend fun execute(repository: WellbeingRepository): WellbeingMutationOutcome

        data class Create(val command: CreateWellbeingCommand) : PreparedMutation {
            override val ids = command.ids
            override val kind = PendingKind.Create
            override val recordedAt = command.recordedAt
            override val effectiveTime = command.effectiveTime
            override suspend fun execute(repository: WellbeingRepository) =
                repository.create(command)
        }

        data class Correct(val command: CorrectWellbeingCommand) : PreparedMutation {
            override val ids = command.ids
            override val kind = PendingKind.Correct
            override val recordedAt = command.recordedAt
            override val effectiveTime = command.effectiveTime
            override suspend fun execute(repository: WellbeingRepository) =
                repository.correct(command)
        }

        data class Retract(val command: RetractWellbeingCommand) : PreparedMutation {
            override val ids = command.ids
            override val kind = PendingKind.Retract
            override val recordedAt = command.recordedAt
            override val effectiveTime: ResolvedPointTime? = null
            override suspend fun execute(repository: WellbeingRepository) =
                repository.retract(command)
        }
    }

    private enum class PendingKind(
        val completionKind: WellbeingCompletionKind,
        val successMessage: String,
    ) {
        Create(
            WellbeingCompletionKind.Created,
            "Самочувствие сохранено на устройстве",
        ),
        Correct(
            WellbeingCompletionKind.Corrected,
            "Самочувствие исправлено на устройстве",
        ),
        Retract(
            WellbeingCompletionKind.Retracted,
            "Запись самочувствия отменена",
        );

        companion object {
            fun valueOfOrNull(value: String): PendingKind? = entries.firstOrNull {
                it.name == value
            }
        }
    }

    private sealed interface EditorRequest {
        data object Create : EditorRequest
        data class Correct(val eventId: UUID) : EditorRequest
    }

    companion object {
        private const val PENDING_KIND = "wellbeing.pending.kind"
        private const val PENDING_OPERATION_ID = "wellbeing.pending.operation_id"
        private val FROZEN_KEYS = listOf(PENDING_KIND, PENDING_OPERATION_ID)
    }
}

private fun WellbeingDimension.toUi(
    selected: WellbeingValueSnapshot? = null,
): WellbeingDimensionUi = WellbeingDimensionUi(
    dimensionId = dimensionId,
    label = selected?.dimensionLabel ?: label,
    sortOrder = sortOrder,
    options = options
        .filter { it.active || it.optionId == selected?.optionId }
        .sortedBy { it.sortOrder }
        .map { option ->
            WellbeingOptionUi(
                optionId = option.optionId,
                label = if (option.optionId == selected?.optionId) {
                    selected.optionLabel
                } else {
                    option.label
                },
                sortOrder = option.sortOrder,
                active = option.active,
            )
        }
        .let { currentOptions ->
            if (selected == null || currentOptions.any { it.optionId == selected.optionId }) {
                currentOptions
            } else {
                currentOptions + WellbeingOptionUi(
                    optionId = selected.optionId,
                    label = selected.optionLabel,
                    sortOrder = selected.optionSortOrder,
                    active = false,
                )
            }
        },
    selectedSnapshot = selected,
    active = active,
)

private fun buildEditorDimensions(
    catalog: List<WellbeingDimension>,
    payload: WellbeingPayload,
): List<WellbeingDimensionUi> {
    val selectedByDimension = payload.values.associateBy(WellbeingValueSnapshot::dimensionId)
    val current = catalog.map { dimension ->
        dimension.toUi(selectedByDimension[dimension.dimensionId])
    }.toMutableList()
    payload.values.forEachIndexed { index, snapshot ->
        if (current.none { it.dimensionId == snapshot.dimensionId }) {
            current += WellbeingDimensionUi(
                dimensionId = snapshot.dimensionId,
                label = snapshot.dimensionLabel,
                sortOrder = catalog.size + index,
                options = listOf(
                    WellbeingOptionUi(
                        optionId = snapshot.optionId,
                        label = snapshot.optionLabel,
                        sortOrder = snapshot.optionSortOrder,
                        active = false,
                    ),
                ),
                selectedSnapshot = snapshot,
                active = false,
            )
        }
    }
    return current.sortedBy(WellbeingDimensionUi::sortOrder)
}

private fun WellbeingSummary.toUi() = WellbeingSummaryUi(
    eventId = eventId,
    revisionId = revisionId,
    values = payload.values,
    comment = payload.comment,
    effectiveAt = effectiveTime.effectiveAt,
    recordedAt = recordedAt.toInstant(),
    originalLocalDateTime = effectiveTime.originalLocal,
    timezoneId = effectiveTime.timezoneId.id,
    offsetSeconds = effectiveTime.offset.totalSeconds,
    status = status.toUi(),
)

private fun WellbeingSnapshot.toUi() = WellbeingSummaryUi(
    eventId = eventId,
    revisionId = revisionId,
    values = payload.values,
    comment = payload.comment,
    effectiveAt = effectiveTime.effectiveAt,
    recordedAt = recordedAt.toInstant(),
    originalLocalDateTime = effectiveTime.originalLocal,
    timezoneId = effectiveTime.timezoneId.id,
    offsetSeconds = effectiveTime.offset.totalSeconds,
    status = status.toUi(),
)

private fun WellbeingRecordStatus.toUi() = when (this) {
    WellbeingRecordStatus.ACTIVE -> WellbeingRecordStatusUi.Active
    WellbeingRecordStatus.RETRACTED -> WellbeingRecordStatusUi.Retracted
}

private fun String.toUuidOrNull(): UUID? = try {
    UUID.fromString(this)
} catch (_: IllegalArgumentException) {
    null
}
