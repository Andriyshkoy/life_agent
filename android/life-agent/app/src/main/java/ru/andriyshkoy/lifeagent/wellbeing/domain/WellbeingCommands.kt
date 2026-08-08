package ru.andriyshkoy.lifeagent.wellbeing.domain

import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import ru.andriyshkoy.lifeagent.core.id.MutationIds
import ru.andriyshkoy.lifeagent.core.time.ResolvedPointTime

data class CreateWellbeingCommand(
    val ids: MutationIds,
    val values: List<WellbeingValueSnapshot>,
    val comment: String?,
    val effectiveTime: ResolvedPointTime,
    val recordedAt: OffsetDateTime,
)

data class CorrectWellbeingCommand(
    val ids: MutationIds,
    val expectedCurrentRevisionId: UUID,
    val values: List<WellbeingValueSnapshot>,
    val comment: String?,
    val effectiveTime: ResolvedPointTime,
    val recordedAt: OffsetDateTime,
    val reason: String? = null,
)

data class RetractWellbeingCommand(
    val ids: MutationIds,
    val expectedCurrentRevisionId: UUID,
    val recordedAt: OffsetDateTime,
    val reason: String = USER_UNDO_REASON,
) {
    init {
        require(reason.isNotBlank()) { "A retraction reason is required" }
    }

    companion object {
        const val USER_UNDO_REASON = "user_undo"
    }
}

data class WellbeingOptionDraft(
    val optionId: UUID,
    val label: String,
    val sortOrder: Int,
    val active: Boolean = true,
)

data class CreateWellbeingDimensionCommand(
    val dimensionId: UUID,
    val catalogVersionId: UUID,
    val label: String,
    val sortOrder: Int,
    val options: List<WellbeingOptionDraft>,
    val createdAt: Instant,
)

data class UpdateWellbeingDimensionCommand(
    val dimensionId: UUID,
    val catalogVersionId: UUID,
    val expectedCurrentVersionId: UUID,
    val label: String,
    val sortOrder: Int,
    val active: Boolean,
    /** Complete option set. Existing options must be archived, never omitted. */
    val options: List<WellbeingOptionDraft>,
    val createdAt: Instant,
)

data class ArchiveWellbeingDimensionCommand(
    val dimensionId: UUID,
    val catalogVersionId: UUID,
    val expectedCurrentVersionId: UUID,
    val archivedAt: Instant,
)
