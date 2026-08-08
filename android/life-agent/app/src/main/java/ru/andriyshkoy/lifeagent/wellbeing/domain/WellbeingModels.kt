package ru.andriyshkoy.lifeagent.wellbeing.domain

import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import ru.andriyshkoy.lifeagent.core.time.ResolvedPointTime

data class WellbeingValueSnapshot(
    val dimensionId: UUID,
    val dimensionVersion: Int,
    val dimensionLabel: String,
    val optionId: UUID,
    val optionVersion: Int,
    val optionLabel: String,
    val optionSortOrder: Int,
)

data class WellbeingPayload(
    /** Array order is the historical dimension display order for this revision. */
    val values: List<WellbeingValueSnapshot>,
    val comment: String?,
)

enum class WellbeingRecordStatus(
    val storageValue: String,
) {
    ACTIVE("active"),
    RETRACTED("retracted");

    companion object {
        fun fromStorage(value: String): WellbeingRecordStatus =
            entries.firstOrNull { it.storageValue == value }
                ?: throw IllegalArgumentException("Unknown wellbeing record status")
    }
}

enum class WellbeingMutationDisposition {
    COMMITTED,
    REPLAYED,
}

data class WellbeingSnapshot(
    val eventId: UUID,
    val revisionId: UUID,
    val operationId: UUID,
    val revisionNo: Int,
    val payload: WellbeingPayload,
    val status: WellbeingRecordStatus,
    val effectiveTime: ResolvedPointTime,
    val recordedAt: OffsetDateTime,
    val createdAt: OffsetDateTime,
    val correctionReason: String?,
)

data class WellbeingSummary(
    val eventId: UUID,
    val revisionId: UUID,
    val operationId: UUID,
    val payload: WellbeingPayload,
    val status: WellbeingRecordStatus,
    val effectiveTime: ResolvedPointTime,
    val recordedAt: OffsetDateTime,
)

data class WellbeingMutationReceipt(
    val wellbeing: WellbeingSnapshot,
    val disposition: WellbeingMutationDisposition,
) {
    val replayed: Boolean
        get() = disposition == WellbeingMutationDisposition.REPLAYED
}

sealed interface WellbeingMutationOutcome {
    data class Persisted(
        val receipt: WellbeingMutationReceipt,
    ) : WellbeingMutationOutcome

    data class AlreadyRetracted(
        val current: WellbeingSnapshot,
    ) : WellbeingMutationOutcome
}

data class WellbeingOption(
    val optionId: UUID,
    val version: Int,
    val label: String,
    val sortOrder: Int,
    val active: Boolean,
)

data class WellbeingDimension(
    val dimensionId: UUID,
    val catalogVersionId: UUID,
    val version: Int,
    val label: String,
    val sortOrder: Int,
    val active: Boolean,
    val options: List<WellbeingOption>,
) {
    fun snapshot(optionId: UUID): WellbeingValueSnapshot {
        val option = options.firstOrNull { it.optionId == optionId }
            ?: throw InvalidWellbeingException("Option does not belong to the dimension")
        return WellbeingValueSnapshot(
            dimensionId = dimensionId,
            dimensionVersion = version,
            dimensionLabel = label,
            optionId = option.optionId,
            optionVersion = option.version,
            optionLabel = option.label,
            optionSortOrder = option.sortOrder,
        )
    }
}

data class WellbeingEventPointer(
    val eventId: UUID,
    val currentRevisionId: UUID,
)

data class CanonicalWellbeingRevisionSnapshot(
    val eventId: UUID,
    val revisionId: UUID,
    val revisionNo: Int,
    val status: WellbeingRecordStatus,
    val canonicalJson: String,
    val contentSha256: String,
)

data class WellbeingExportSnapshot(
    val events: List<WellbeingEventPointer>,
    val revisions: List<CanonicalWellbeingRevisionSnapshot>,
)

data class WellbeingCatalogItemSnapshot(
    val catalogItemId: UUID,
    val localOwnerId: UUID,
    val catalogKind: String,
    val createdAt: Instant,
)

data class WellbeingCatalogVersionSnapshot(
    val catalogVersionId: UUID,
    val catalogItemId: UUID,
    val version: Int,
    val schemaVersion: String,
    val canonicalPayloadJson: String,
    val contentSha256: String,
    val createdAt: Instant,
)

data class WellbeingCatalogHeadSnapshot(
    val catalogItemId: UUID,
    val currentVersionId: UUID,
    val updatedAt: Instant,
)

data class WellbeingCatalogExportSnapshot(
    val items: List<WellbeingCatalogItemSnapshot>,
    val versions: List<WellbeingCatalogVersionSnapshot>,
    val heads: List<WellbeingCatalogHeadSnapshot>,
)
