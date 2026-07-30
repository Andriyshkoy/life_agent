package ru.andriyshkoy.lifeagent.data.local.db.entity

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncAuthStateEntityTest {
    @Test
    fun `mixed canonical timestamp forms bind to exact epoch milliseconds`() {
        val state = state(
            accessUtc = "2026-07-30T10:00:00Z",
            refreshUtc = "2026-07-30T10:00:00.500Z",
            familyUtc = "2026-07-30T10:00:01Z",
        )

        assertEquals(
            Instant.parse("2026-07-30T10:00:00.500Z").toEpochMilli(),
            state.refreshExpiresAtEpochMs,
        )
    }

    @Test
    fun `expiry representation drift fails closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            state(
                accessUtc = "2026-07-30T10:00:00Z",
                refreshUtc = "2026-07-30T10:00:00.500Z",
                familyUtc = "2026-07-30T10:00:01Z",
                refreshEpochMs =
                    Instant.parse("2026-07-30T10:00:00Z").toEpochMilli(),
            )
        }
    }

    @Test
    fun `refresh expiry must be strictly later than access expiry`() {
        assertThrows(IllegalArgumentException::class.java) {
            state(
                accessUtc = "2026-07-30T10:00:00Z",
                refreshUtc = "2026-07-30T10:00:00Z",
                familyUtc = "2026-07-30T10:00:01Z",
            )
        }
    }

    private fun state(
        accessUtc: String,
        refreshUtc: String,
        familyUtc: String,
        refreshEpochMs: Long = Instant.parse(refreshUtc).toEpochMilli(),
    ) = SyncAuthStateEntity(
        credentialEpochId = "00000000-0000-0000-0000-000000000001",
        installationId = "00000000-0000-0000-0000-000000000002",
        localOwnerId = "00000000-0000-0000-0000-000000000003",
        deviceId = "00000000-0000-0000-0000-000000000004",
        personId = "00000000-0000-0000-0000-000000000005",
        tokenType = "Bearer",
        refreshTokenCiphertext = byteArrayOf(1),
        refreshTokenNonce = ByteArray(12) { 2 },
        refreshTokenKeyAlias = "life_agent_refresh_v1",
        refreshTokenKeyGeneration = 1,
        refreshTokenAadVersion = 1,
        accessExpiresAtUtc = accessUtc,
        accessExpiresAtEpochMs = Instant.parse(accessUtc).toEpochMilli(),
        refreshExpiresAtUtc = refreshUtc,
        refreshExpiresAtEpochMs = refreshEpochMs,
        familyExpiresAtUtc = familyUtc,
        familyExpiresAtEpochMs = Instant.parse(familyUtc).toEpochMilli(),
        generation = 1,
        state = "active",
        bootstrapRequired = true,
        installedAtUtc = "2026-07-30T09:59:00Z",
        updatedAtUtc = "2026-07-30T09:59:00Z",
        failureCode = null,
    )
}
