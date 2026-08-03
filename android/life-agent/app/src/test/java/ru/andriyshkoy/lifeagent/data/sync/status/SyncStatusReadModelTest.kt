package ru.andriyshkoy.lifeagent.data.sync.status

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import ru.andriyshkoy.lifeagent.data.local.db.dao.SyncStatusProjectionRow

class SyncStatusReadModelTest {
    @Test
    fun `empty projection stays local only and bodyless`() {
        val snapshot = mapSyncStatusProjection(row())

        assertEquals(SyncConnectionStatus.LOCAL_ONLY, snapshot.connection)
        assertEquals(EnrollmentAttemptStatus.NONE, snapshot.enrollmentAttempt)
        assertEquals(SyncBootstrapStatus.UNAVAILABLE, snapshot.bootstrap)
        assertEquals(0, snapshot.pendingCount)
        assertEquals(null, snapshot.lastServerConfirmationAt)
    }

    @Test
    fun `active credential maps pending bootstrap and confirmation`() {
        val snapshot = mapSyncStatusProjection(
            row(
                authState = "active",
                pendingCount = 3,
                authBootstrapRequired = true,
                lastServerConfirmationAtUtc = "2026-08-03T04:05:06.789Z",
            ),
        )

        assertEquals(SyncConnectionStatus.READY, snapshot.connection)
        assertEquals(SyncBootstrapStatus.REQUIRED, snapshot.bootstrap)
        assertEquals(3, snapshot.pendingCount)
        assertEquals(
            Instant.parse("2026-08-03T04:05:06.789Z"),
            snapshot.lastServerConfirmationAt,
        )
    }

    @Test
    fun `staging and integrity states take precedence over flags`() {
        assertEquals(
            SyncBootstrapStatus.IN_PROGRESS,
            mapSyncStatusProjection(
                row(authState = "active", activeBootstrapState = "staging"),
            ).bootstrap,
        )
        assertEquals(
            SyncBootstrapStatus.INTEGRITY_HALTED,
            mapSyncStatusProjection(
                row(
                    authState = "active",
                    activeBootstrapState = "staging",
                    integrityHalted = true,
                ),
            ).bootstrap,
        )
    }

    @Test
    fun `unknown durable states fail closed into reenrollment`() {
        val snapshot = mapSyncStatusProjection(
            row(
                authState = "future_state",
                latestEnrollmentAttemptState = "future_attempt",
            ),
        )

        assertEquals(SyncConnectionStatus.REENROLLMENT_REQUIRED, snapshot.connection)
        assertEquals(EnrollmentAttemptStatus.OUTCOME_UNKNOWN, snapshot.enrollmentAttempt)
    }

    @Test
    fun `malformed timestamp is rejected without echoing the value`() {
        val error = assertThrows(IllegalStateException::class.java) {
            mapSyncStatusProjection(row(lastServerConfirmationAtUtc = "not-an-instant"))
        }

        assertEquals("Sync status timestamp is invalid", error.message)
    }

    private fun row(
        authState: String? = null,
        latestEnrollmentAttemptState: String? = null,
        pendingCount: Int = 0,
        authBootstrapRequired: Boolean = false,
        streamPhase: String? = null,
        streamBootstrapRequired: Boolean = false,
        activeBootstrapState: String? = null,
        integrityHalted: Boolean = false,
        lastServerConfirmationAtUtc: String? = null,
    ) = SyncStatusProjectionRow(
        authState = authState,
        latestEnrollmentAttemptState = latestEnrollmentAttemptState,
        pendingCount = pendingCount,
        authBootstrapRequired = authBootstrapRequired,
        streamPhase = streamPhase,
        streamBootstrapRequired = streamBootstrapRequired,
        activeBootstrapState = activeBootstrapState,
        integrityHalted = integrityHalted,
        lastServerConfirmationAtUtc = lastServerConfirmationAtUtc,
    )
}
