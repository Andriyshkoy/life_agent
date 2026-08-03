package ru.andriyshkoy.lifeagent.notes.domain

import ru.andriyshkoy.lifeagent.core.id.MutationIds
import ru.andriyshkoy.lifeagent.core.time.ResolvedPointTime
import java.time.OffsetDateTime
import java.util.UUID

data class CreateNoteCommand(
    val ids: MutationIds,
    val text: String,
    val effectiveTime: ResolvedPointTime,
    val recordedAt: OffsetDateTime,
)

data class CorrectNoteCommand(
    val ids: MutationIds,
    val expectedCurrentRevisionId: UUID,
    val text: String,
    val effectiveTime: ResolvedPointTime,
    val recordedAt: OffsetDateTime,
    val reason: String? = null,
)

data class RetractNoteCommand(
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
