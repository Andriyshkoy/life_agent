package ru.andriyshkoy.lifeagent.data.sync.runtime

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import ru.andriyshkoy.lifeagent.data.sync.wire.WipeableSecret

class AccessTokenVaultTest {
    @Test
    fun claimRequiresTheExactEpochAndGeneration() {
        val vault = AccessTokenVault()
        val stored = secret(FIRST_TOKEN)
        vault.replace(FIRST_KEY, stored)

        assertNull(vault.claim(FIRST_KEY.copy(accessGeneration = 2)))
        assertNull(vault.claim(AccessTokenKey(SECOND_EPOCH, 1)))
        vault.claim(FIRST_KEY).use { claim ->
            checkNotNull(claim)
            assertEquals(FIRST_KEY, claim.key)
            assertSecretEquals(FIRST_TOKEN, claim.bearerAccessToken)
        }

        vault.close()
        assertClosed(stored)
    }

    @Test
    fun claimOwnsAnIndependentCopyWithAnExplicitWipeLifecycle() {
        val vault = AccessTokenVault()
        val stored = secret(FIRST_TOKEN)
        vault.replace(FIRST_KEY, stored)
        val claim = checkNotNull(vault.claim(FIRST_KEY))

        vault.revoke(FIRST_KEY)

        assertClosed(stored)
        assertSecretEquals(FIRST_TOKEN, claim.bearerAccessToken)
        claim.close()
        assertClosed(claim.bearerAccessToken)
        assertNull(vault.claim(FIRST_KEY))
        vault.close()
    }

    @Test
    fun replacementWipesThePreviousOwnedSecret() {
        val vault = AccessTokenVault()
        val previous = secret(FIRST_TOKEN)
        val replacement = secret(SECOND_TOKEN)
        vault.replace(FIRST_KEY, previous)

        vault.replace(FIRST_KEY, replacement)

        assertClosed(previous)
        vault.claim(FIRST_KEY).use { claim ->
            assertSecretEquals(SECOND_TOKEN, checkNotNull(claim).bearerAccessToken)
        }
        vault.close()
        assertClosed(replacement)
    }

    @Test
    fun oversizedReplacementIsWipedAndPreservesTheExistingAuthority() {
        val vault = AccessTokenVault()
        val existing = secret(FIRST_TOKEN)
        val oversized = WipeableSecret.copyOf(ByteArray(48) { 'X'.code.toByte() })
        vault.replace(FIRST_KEY, existing)

        assertThrows(IllegalArgumentException::class.java) {
            vault.replace(FIRST_KEY, oversized)
        }

        assertClosed(oversized)
        vault.claim(FIRST_KEY).use { claim ->
            assertSecretEquals(FIRST_TOKEN, checkNotNull(claim).bearerAccessToken)
        }
        vault.close()
        assertClosed(existing)
    }

    @Test
    fun malformedSameLengthReplacementIsWipedAndPreservesExistingAuthority() {
        val vault = AccessTokenVault()
        val existing = secret(FIRST_TOKEN)
        val malformed = secret("laa_" + "A".repeat(42) + "B")
        vault.replace(FIRST_KEY, existing)

        assertThrows(IllegalArgumentException::class.java) {
            vault.replace(FIRST_KEY, malformed)
        }

        assertClosed(malformed)
        vault.claim(FIRST_KEY).use { claim ->
            assertSecretEquals(FIRST_TOKEN, checkNotNull(claim).bearerAccessToken)
        }
        vault.close()
        assertClosed(existing)
    }

    @Test
    fun revokeEpochAndClearWipeEveryMatchingOwnedSecret() {
        val vault = AccessTokenVault(maxEntries = 4)
        val firstGeneration = secret(FIRST_TOKEN)
        val secondGeneration = secret(SECOND_TOKEN)
        val otherEpoch = secret(THIRD_TOKEN)
        val secondKey = FIRST_KEY.copy(accessGeneration = 2)
        val otherKey = AccessTokenKey(SECOND_EPOCH, 1)
        vault.replace(FIRST_KEY, firstGeneration)
        vault.replace(secondKey, secondGeneration)
        vault.replace(otherKey, otherEpoch)

        assertEquals(2, vault.revokeEpoch(FIRST_EPOCH))

        assertClosed(firstGeneration)
        assertClosed(secondGeneration)
        assertNull(vault.claim(FIRST_KEY))
        assertNull(vault.claim(secondKey))
        vault.claim(otherKey).use { claim ->
            assertSecretEquals(THIRD_TOKEN, checkNotNull(claim).bearerAccessToken)
        }

        vault.clear()
        assertClosed(otherEpoch)
        assertNull(vault.claim(otherKey))
        vault.close()
    }

    @Test
    fun deterministicCapacityEvictionWipesTheOldestInsertion() {
        val vault = AccessTokenVault(maxEntries = 2)
        val oldest = secret(FIRST_TOKEN)
        val middle = secret(SECOND_TOKEN)
        val newest = secret(THIRD_TOKEN)
        val middleKey = FIRST_KEY.copy(accessGeneration = 2)
        val newestKey = FIRST_KEY.copy(accessGeneration = 3)
        vault.replace(FIRST_KEY, oldest)
        vault.replace(middleKey, middle)
        vault.claim(FIRST_KEY)?.close()

        vault.replace(newestKey, newest)

        assertClosed(oldest)
        assertNull(vault.claim(FIRST_KEY))
        vault.claim(middleKey).use { claim ->
            assertSecretEquals(SECOND_TOKEN, checkNotNull(claim).bearerAccessToken)
        }
        vault.claim(newestKey).use { claim ->
            assertSecretEquals(THIRD_TOKEN, checkNotNull(claim).bearerAccessToken)
        }
        vault.close()
        assertClosed(middle)
        assertClosed(newest)
    }

    @Test
    fun closeDeniesFurtherClaimsAndWipesStoredSecrets() {
        val vault = AccessTokenVault()
        val stored = secret(FIRST_TOKEN)
        vault.replace(FIRST_KEY, stored)

        vault.close()
        vault.close()

        assertClosed(stored)
        assertNull(vault.claim(FIRST_KEY))
        val rejected = secret(SECOND_TOKEN)
        assertThrows(IllegalStateException::class.java) {
            vault.replace(FIRST_KEY, rejected)
        }
        assertClosed(rejected)
    }

    @Test
    fun keysAndCapacityAreStrictlyValidated() {
        assertThrows(IllegalArgumentException::class.java) {
            AccessTokenKey("not-a-canonical-uuid", 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccessTokenKey(FIRST_EPOCH, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccessTokenVault(maxEntries = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccessTokenVault(maxEntries = 5)
        }
    }

    @Test
    fun diagnosticsNeverExposeIdentifiersOrTokenMaterial() {
        val vault = AccessTokenVault()
        vault.replace(FIRST_KEY, secret(FIRST_TOKEN))
        val claim = checkNotNull(vault.claim(FIRST_KEY))
        val diagnostics = listOf(FIRST_KEY, vault, claim).joinToString("|")

        assertEquals(
            "AccessTokenKey(redacted=true)|" +
                "AccessTokenVault(redacted=true)|" +
                "AccessTokenClaim(redacted=true)",
            diagnostics,
        )
        assertFalse(diagnostics.contains(FIRST_EPOCH))
        assertFalse(diagnostics.contains(FIRST_TOKEN))
        assertFalse(diagnostics.contains("credentialEpochId"))

        claim.close()
        vault.close()
    }

    private fun secret(text: String): WipeableSecret = WipeableSecret.ascii(text)

    private fun assertSecretEquals(expected: String, actual: WipeableSecret) {
        val expectedBytes = expected.toByteArray(StandardCharsets.US_ASCII)
        try {
            actual.useBytes { bytes -> assertArrayEquals(expectedBytes, bytes) }
        } finally {
            expectedBytes.fill(0)
        }
    }

    private fun assertClosed(secret: WipeableSecret) {
        assertThrows(IllegalStateException::class.java) { secret.copyBytes() }
    }

    private companion object {
        const val FIRST_EPOCH = "97000000-0000-4000-8000-000000000001"
        const val SECOND_EPOCH = "97000000-0000-4000-8000-000000000002"
        val FIRST_TOKEN = "laa_" + "A".repeat(43)
        val SECOND_TOKEN = "laa_" + "B".repeat(42) + "Q"
        val THIRD_TOKEN = "laa_" + "C".repeat(42) + "g"
        val FIRST_KEY = AccessTokenKey(FIRST_EPOCH, 1)
    }
}
