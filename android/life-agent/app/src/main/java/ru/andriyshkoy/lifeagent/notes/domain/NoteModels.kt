package ru.andriyshkoy.lifeagent.notes.domain

import ru.andriyshkoy.lifeagent.core.time.ResolvedPointTime
import java.time.OffsetDateTime
import java.util.UUID

enum class NoteRecordStatus(
    val storageValue: String,
) {
    ACTIVE("active"),
    RETRACTED("retracted");

    companion object {
        fun fromStorage(value: String): NoteRecordStatus =
            entries.firstOrNull { it.storageValue == value }
                ?: throw IllegalArgumentException("Unknown note record status")
    }
}

enum class NoteMutationDisposition {
    COMMITTED,
    REPLAYED,
}

data class NoteSnapshot(
    val eventId: UUID,
    val revisionId: UUID,
    val operationId: UUID,
    val revisionNo: Int,
    val text: String,
    val status: NoteRecordStatus,
    val effectiveTime: ResolvedPointTime,
    val recordedAt: OffsetDateTime,
    val createdAt: OffsetDateTime,
    val correctionReason: String?,
)

data class NoteSummary(
    val eventId: UUID,
    val revisionId: UUID,
    val operationId: UUID,
    val text: String,
    val status: NoteRecordStatus,
    val effectiveTime: ResolvedPointTime,
    val recordedAt: OffsetDateTime = effectiveTime.toOffsetDateTime(),
)

data class NoteMutationReceipt(
    val note: NoteSnapshot,
    val disposition: NoteMutationDisposition,
) {
    val replayed: Boolean
        get() = disposition == NoteMutationDisposition.REPLAYED
}

sealed interface NoteMutationOutcome {
    data class Persisted(
        val receipt: NoteMutationReceipt,
    ) : NoteMutationOutcome

    data class AlreadyRetracted(
        val current: NoteSnapshot,
    ) : NoteMutationOutcome
}

data class NoteEventPointer(
    val eventId: UUID,
    val currentRevisionId: UUID,
)

data class CanonicalNoteRevisionSnapshot(
    val eventId: UUID,
    val revisionId: UUID,
    val revisionNo: Int,
    val status: NoteRecordStatus,
    val canonicalJson: String,
    val contentSha256: String,
)

data class NotesExportSnapshot(
    val events: List<NoteEventPointer>,
    val revisions: List<CanonicalNoteRevisionSnapshot>,
)
