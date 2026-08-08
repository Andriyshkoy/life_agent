package ru.andriyshkoy.lifeagent.wellbeing.domain

import java.util.UUID
import kotlinx.coroutines.flow.Flow

interface WellbeingRepository {
    suspend fun create(command: CreateWellbeingCommand): WellbeingMutationOutcome

    suspend fun correct(command: CorrectWellbeingCommand): WellbeingMutationOutcome

    suspend fun retract(command: RetractWellbeingCommand): WellbeingMutationOutcome

    fun observeLastCommitted(): Flow<WellbeingSummary?>

    suspend fun getByEventId(eventId: UUID): WellbeingSnapshot?

    suspend fun findByOperationId(operationId: UUID): WellbeingMutationReceipt?

    suspend fun exportSnapshot(): WellbeingExportSnapshot
}
