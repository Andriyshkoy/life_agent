package ru.andriyshkoy.lifeagent.data.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncAuthStateEntity

class RefreshTokenProtectorContractTest {
    @Test
    fun aliasIsStableEpochScopedAndDoesNotExposeTheEpoch() {
        val first = refreshTokenAeadAlias(CREDENTIAL_EPOCH_ID)

        assertEquals(first, refreshTokenAeadAlias(CREDENTIAL_EPOCH_ID))
        assertNotEquals(first, refreshTokenAeadAlias(OTHER_CREDENTIAL_EPOCH_ID))
        assertFalse(first.contains(CREDENTIAL_EPOCH_ID))
        assertThrows(IllegalArgumentException::class.java) {
            refreshTokenAeadAlias(CREDENTIAL_EPOCH_ID.uppercase())
        }
    }

    @Test
    fun envelopeFieldsMapOneToOneFromUsableAuthStateAndDiagnosticsStayRedacted() {
        val auth = authState(
            state = "active",
            ciphertext = byteArrayOf(1, 2, 3),
            nonce = ByteArray(12) { 4 },
            keyAlias = "life_agent_refresh_token_aead_v1_fixture",
            keyGeneration = 1,
            aadVersion = 1,
        )

        val envelope = requireNotNull(RefreshTokenEnvelope.from(auth))

        assertArrayEquals(auth.refreshTokenCiphertext, envelope.refreshTokenCiphertext)
        assertArrayEquals(auth.refreshTokenNonce, envelope.refreshTokenNonce)
        assertEquals(auth.refreshTokenKeyAlias, envelope.refreshTokenKeyAlias)
        assertEquals(auth.refreshTokenKeyGeneration, envelope.refreshTokenKeyGeneration)
        assertEquals(auth.refreshTokenAadVersion, envelope.refreshTokenAadVersion)
        assertEquals("RefreshTokenEnvelope(redacted=true)", envelope.toString())
        assertFalse(envelope.toString().contains(envelope.refreshTokenKeyAlias))
    }

    @Test
    fun absentDurableEnvelopeMapsToNull() {
        val auth = authState(
            state = "expired",
            ciphertext = null,
            nonce = null,
            keyAlias = null,
            keyGeneration = null,
            aadVersion = null,
        )

        assertNull(RefreshTokenEnvelope.from(auth))
    }

    private fun authState(
        state: String,
        ciphertext: ByteArray?,
        nonce: ByteArray?,
        keyAlias: String?,
        keyGeneration: Int?,
        aadVersion: Int?,
    ): SyncAuthStateEntity = SyncAuthStateEntity(
        credentialEpochId = CREDENTIAL_EPOCH_ID,
        installationId = "11111111-1111-4111-8111-111111111111",
        localOwnerId = "22222222-2222-4222-8222-222222222222",
        deviceId = "33333333-3333-4333-8333-333333333333",
        personId = "44444444-4444-4444-8444-444444444444",
        tokenType = "Bearer",
        refreshTokenCiphertext = ciphertext,
        refreshTokenNonce = nonce,
        refreshTokenKeyAlias = keyAlias,
        refreshTokenKeyGeneration = keyGeneration,
        refreshTokenAadVersion = aadVersion,
        accessExpiresAtUtc = "2026-08-03T01:00:00Z",
        accessExpiresAtEpochMs = 1_785_718_800_000,
        refreshExpiresAtUtc = "2026-08-03T02:00:00Z",
        refreshExpiresAtEpochMs = 1_785_722_400_000,
        familyExpiresAtUtc = "2026-08-03T03:00:00Z",
        familyExpiresAtEpochMs = 1_785_726_000_000,
        generation = 1,
        state = state,
        bootstrapRequired = false,
        installedAtUtc = "2026-08-03T00:00:00Z",
        updatedAtUtc = "2026-08-03T00:00:00Z",
        failureCode = if (state == "active") null else "credential_expired",
    )

    companion object {
        private const val CREDENTIAL_EPOCH_ID = "55555555-5555-4555-8555-555555555555"
        private const val OTHER_CREDENTIAL_EPOCH_ID =
            "66666666-6666-4666-8666-666666666666"
    }
}
