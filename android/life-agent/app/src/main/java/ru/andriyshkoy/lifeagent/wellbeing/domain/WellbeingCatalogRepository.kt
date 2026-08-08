package ru.andriyshkoy.lifeagent.wellbeing.domain

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow

interface WellbeingCatalogRepository {
    suspend fun ensureSeeded(createdAt: Instant)

    fun observeDimensions(includeArchived: Boolean = false): Flow<List<WellbeingDimension>>

    suspend fun getDimension(dimensionId: UUID): WellbeingDimension?

    suspend fun create(command: CreateWellbeingDimensionCommand): WellbeingDimension

    suspend fun update(command: UpdateWellbeingDimensionCommand): WellbeingDimension

    suspend fun archive(command: ArchiveWellbeingDimensionCommand): WellbeingDimension

    suspend fun exportSnapshot(): WellbeingCatalogExportSnapshot
}
