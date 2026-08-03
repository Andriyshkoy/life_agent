package ru.andriyshkoy.lifeagent.notes.domain

import kotlinx.coroutines.flow.Flow
import java.util.UUID

interface NotesRepository {
    suspend fun create(command: CreateNoteCommand): NoteMutationOutcome

    suspend fun correct(command: CorrectNoteCommand): NoteMutationOutcome

    suspend fun retract(command: RetractNoteCommand): NoteMutationOutcome

    fun observeLastCommitted(): Flow<NoteSummary?>

    suspend fun getByEventId(eventId: UUID): NoteSnapshot?

    suspend fun findByOperationId(operationId: UUID): NoteMutationReceipt?

    suspend fun exportSnapshot(): NotesExportSnapshot
}
