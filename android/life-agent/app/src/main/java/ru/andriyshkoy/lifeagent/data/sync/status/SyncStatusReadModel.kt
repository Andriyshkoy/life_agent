package ru.andriyshkoy.lifeagent.data.sync.status

import java.time.DateTimeException
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import ru.andriyshkoy.lifeagent.data.local.db.dao.SyncStatusProjectionDao
import ru.andriyshkoy.lifeagent.data.local.db.dao.SyncStatusProjectionRow

internal enum class SyncConnectionStatus {
    LOCAL_ONLY,
    READY,
    REENROLLMENT_REQUIRED,
}

internal enum class SyncBootstrapStatus {
    UNAVAILABLE,
    REQUIRED,
    IN_PROGRESS,
    READY,
    INTEGRITY_HALTED,
}

internal enum class EnrollmentAttemptStatus {
    NONE,
    DISPATCHING,
    COMPLETED,
    FAILED,
    OUTCOME_UNKNOWN,
}

/** Bodyless status surface safe to retain in UI state. */
internal data class SyncStatusSnapshot(
    val connection: SyncConnectionStatus,
    val enrollmentAttempt: EnrollmentAttemptStatus,
    val pendingCount: Int,
    val bootstrap: SyncBootstrapStatus,
    val lastServerConfirmationAt: Instant?,
)

internal fun interface SyncStatusReadModel {
    fun observe(): Flow<SyncStatusSnapshot>
}

internal class RoomSyncStatusReadModel(
    private val projectionDao: SyncStatusProjectionDao,
) : SyncStatusReadModel {
    override fun observe(): Flow<SyncStatusSnapshot> = projectionDao
        .observeProjection()
        .map(::mapSyncStatusProjection)
        .distinctUntilChanged()

    override fun toString(): String = "RoomSyncStatusReadModel(bodyless=true)"
}

internal fun mapSyncStatusProjection(row: SyncStatusProjectionRow): SyncStatusSnapshot {
    require(row.pendingCount >= 0) { "Pending count cannot be negative" }

    val connection = when (row.authState) {
        null -> SyncConnectionStatus.LOCAL_ONLY
        "active", "refresh_in_flight" -> SyncConnectionStatus.READY
        "revoke_pending",
        "quarantined",
        "expired",
        "revoked",
        "integrity_failure",
        -> SyncConnectionStatus.REENROLLMENT_REQUIRED

        else -> SyncConnectionStatus.REENROLLMENT_REQUIRED
    }
    val enrollmentAttempt = when (row.latestEnrollmentAttemptState) {
        null -> EnrollmentAttemptStatus.NONE
        "dispatching" -> EnrollmentAttemptStatus.DISPATCHING
        "completed" -> EnrollmentAttemptStatus.COMPLETED
        "failed" -> EnrollmentAttemptStatus.FAILED
        "outcome_unknown" -> EnrollmentAttemptStatus.OUTCOME_UNKNOWN
        else -> EnrollmentAttemptStatus.OUTCOME_UNKNOWN
    }
    val bootstrap = when {
        row.integrityHalted -> SyncBootstrapStatus.INTEGRITY_HALTED
        row.activeBootstrapState == "staging" -> SyncBootstrapStatus.IN_PROGRESS
        row.authBootstrapRequired ||
            row.streamBootstrapRequired ||
            row.streamPhase == "bootstrap_required" -> SyncBootstrapStatus.REQUIRED

        connection == SyncConnectionStatus.READY -> SyncBootstrapStatus.READY
        else -> SyncBootstrapStatus.UNAVAILABLE
    }
    val lastConfirmation = row.lastServerConfirmationAtUtc?.let { value ->
        try {
            Instant.parse(value)
        } catch (_: DateTimeException) {
            throw IllegalStateException("Sync status timestamp is invalid")
        }
    }
    return SyncStatusSnapshot(
        connection = connection,
        enrollmentAttempt = enrollmentAttempt,
        pendingCount = row.pendingCount,
        bootstrap = bootstrap,
        lastServerConfirmationAt = lastConfirmation,
    )
}
