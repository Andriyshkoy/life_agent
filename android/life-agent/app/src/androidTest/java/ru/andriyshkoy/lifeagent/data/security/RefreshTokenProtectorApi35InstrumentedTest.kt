package ru.andriyshkoy.lifeagent.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.crypto.KeyGenerator
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncAuthStateEntity
import ru.andriyshkoy.lifeagent.data.sync.wire.WipeableSecret

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class RefreshTokenProtectorApi35InstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var credentialEpochId: String
    private lateinit var alternateCredentialEpochId: String
    private lateinit var deviceId: String
    private lateinit var alternateDeviceId: String

    @Before
    fun setUp() {
        credentialEpochId = UUID.randomUUID().toString()
        alternateCredentialEpochId = UUID.randomUUID().toString()
        deviceId = UUID.randomUUID().toString()
        alternateDeviceId = UUID.randomUUID().toString()
        deleteKnownAliases()
    }

    @After
    fun tearDown() {
        deleteKnownAliases()
    }

    @Test
    fun sealTransfersOwnershipAndNewProtectorInstanceCanOpenExactEnvelope() {
        val ownedToken = validRefreshToken()
        val ownedStorage = ownedToken.storageReference()
        val firstProtector = RefreshTokenProtector(context)

        val envelope = firstProtector.seal(
            credentialEpochId = credentialEpochId,
            deviceId = deviceId,
            generation = 1,
            durableReferenceCount = 0,
            ownedRefreshToken = ownedToken,
        )

        assertTrue(ownedStorage.all { it == 0.toByte() })
        assertThrows(IllegalStateException::class.java) { ownedToken.copyBytes() }
        assertEquals(refreshTokenAeadAlias(credentialEpochId), envelope.refreshTokenKeyAlias)
        assertFalse(envelope.refreshTokenKeyAlias.contains(credentialEpochId))
        assertEquals("RefreshTokenEnvelope(redacted=true)", envelope.toString())
        assertEquals(
            "RefreshTokenProtector(redacted=true)",
            firstProtector.toString(),
        )

        val reopened = RefreshTokenProtector(context).open(
            credentialEpochId = credentialEpochId,
            deviceId = deviceId,
            generation = 1,
            envelope = envelope,
        )
        val reopenedStorage = reopened.storageReference()
        try {
            reopened.useBytes { plaintext ->
                assertArrayEquals(VALID_REFRESH_TOKEN_BYTES, plaintext)
            }
        } finally {
            reopened.close()
        }
        assertTrue(reopenedStorage.all { it == 0.toByte() })
        assertThrows(IllegalStateException::class.java) { reopened.copyBytes() }
    }

    @Test
    fun exactEpochDeviceAndGenerationAreAuthenticated() {
        val envelope = seal(generation = 7)
        val protector = RefreshTokenProtector(context)

        assertIntegrityFailure(SensitivePayloadIntegrityFailure.METADATA_INVALID) {
            protector.open(
                credentialEpochId = alternateCredentialEpochId,
                deviceId = deviceId,
                generation = 7,
                envelope = envelope,
            )
        }
        assertIntegrityFailure(SensitivePayloadIntegrityFailure.AEAD_AUTH_FAILED) {
            protector.open(
                credentialEpochId = credentialEpochId,
                deviceId = alternateDeviceId,
                generation = 7,
                envelope = envelope,
            )
        }
        assertIntegrityFailure(SensitivePayloadIntegrityFailure.AEAD_AUTH_FAILED) {
            protector.open(
                credentialEpochId = credentialEpochId,
                deviceId = deviceId,
                generation = 8,
                envelope = envelope,
            )
        }
    }

    @Test
    fun ciphertextNonceAndEnvelopeMetadataTamperingAreRejected() {
        val envelope = seal(generation = 3)
        val protector = RefreshTokenProtector(context)

        assertIntegrityFailure(SensitivePayloadIntegrityFailure.AEAD_AUTH_FAILED) {
            protector.openExact(envelope.withCiphertextBitFlipped(), generation = 3)
        }
        assertIntegrityFailure(SensitivePayloadIntegrityFailure.AEAD_AUTH_FAILED) {
            protector.openExact(envelope.withNonceBitFlipped(), generation = 3)
        }
        assertIntegrityFailure(SensitivePayloadIntegrityFailure.METADATA_INVALID) {
            protector.openExact(
                envelope.copy(
                    refreshTokenKeyAlias = refreshTokenAeadAlias(alternateCredentialEpochId),
                ),
                generation = 3,
            )
        }
        assertIntegrityFailure(SensitivePayloadIntegrityFailure.METADATA_INVALID) {
            protector.openExact(
                envelope.copy(
                    refreshTokenKeyGeneration = envelope.refreshTokenKeyGeneration + 1,
                ),
                generation = 3,
            )
        }
        assertIntegrityFailure(SensitivePayloadIntegrityFailure.METADATA_INVALID) {
            protector.openExact(
                envelope.copy(refreshTokenAadVersion = envelope.refreshTokenAadVersion + 1),
                generation = 3,
            )
        }
    }

    @Test
    fun missingKeyOpenFailsWithoutRecreatingAlias() {
        val envelope = seal(generation = 1)
        assertTrue(keystoreContains(envelope.refreshTokenKeyAlias))
        deleteAlias(envelope.refreshTokenKeyAlias)
        assertFalse(keystoreContains(envelope.refreshTokenKeyAlias))

        assertIntegrityFailure(SensitivePayloadIntegrityFailure.KEY_UNAVAILABLE) {
            RefreshTokenProtector(context).openExact(envelope, generation = 1)
        }

        assertFalse(
            "Load-only open must not provision a replacement key",
            keystoreContains(envelope.refreshTokenKeyAlias),
        )
    }

    @Test
    fun markerLossWithDurableReferenceFailsBeforeSealAndKeepsExistingAlias() {
        val envelope = seal(generation = 1)
        val alias = envelope.refreshTokenKeyAlias
        deleteMarker(credentialEpochId)
        assertTrue(keystoreContains(alias))
        assertFalse(markerExists(credentialEpochId))
        val successor = validRefreshToken()
        val successorStorage = successor.storageReference()

        assertIntegrityFailure(SensitivePayloadIntegrityFailure.KEY_UNAVAILABLE) {
            RefreshTokenProtector(context).seal(
                credentialEpochId = credentialEpochId,
                deviceId = deviceId,
                generation = 2,
                durableReferenceCount = 1,
                ownedRefreshToken = successor,
            )
        }

        assertTrue(successorStorage.all { it == 0.toByte() })
        assertTrue(keystoreContains(alias))
        assertFalse(markerExists(credentialEpochId))
    }

    @Test
    fun aliasLossWithDurableReferenceFailsWithoutReplacement() {
        val envelope = seal(generation = 1)
        val alias = envelope.refreshTokenKeyAlias
        deleteAlias(alias)
        assertTrue(markerExists(credentialEpochId))
        assertFalse(keystoreContains(alias))
        val successor = validRefreshToken()
        val successorStorage = successor.storageReference()

        assertIntegrityFailure(SensitivePayloadIntegrityFailure.KEY_UNAVAILABLE) {
            RefreshTokenProtector(context).seal(
                credentialEpochId = credentialEpochId,
                deviceId = deviceId,
                generation = 2,
                durableReferenceCount = 1,
                ownedRefreshToken = successor,
            )
        }

        assertTrue(successorStorage.all { it == 0.toByte() })
        assertFalse(
            "A durable reference must prevent replacement-key provisioning",
            keystoreContains(alias),
        )
        assertTrue(markerExists(credentialEpochId))
    }

    @Test
    fun wrongAliasTypeIsRejectedWithoutOverwrite() {
        val alias = refreshTokenAeadAlias(credentialEpochId)
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
            .run {
                init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                    )
                        .setKeySize(256)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build(),
                )
                generateKey()
            }
        val ownedToken = validRefreshToken()
        val ownedStorage = ownedToken.storageReference()

        assertIntegrityFailure(SensitivePayloadIntegrityFailure.KEY_UNAVAILABLE) {
            RefreshTokenProtector(context).seal(
                credentialEpochId = credentialEpochId,
                deviceId = deviceId,
                generation = 1,
                durableReferenceCount = 0,
                ownedRefreshToken = ownedToken,
            )
        }

        assertTrue(ownedStorage.all { it == 0.toByte() })
        assertFalse(markerExists(credentialEpochId))
        val installed = KeyStore.getInstance(ANDROID_KEYSTORE).run {
            load(null)
            requireNotNull(getKey(alias, null))
        }
        assertEquals(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, installed.algorithm)
    }

    @Test
    fun concurrentFirstProvisionSharesOneAuthenticatedEpochKey() {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<RefreshTokenEnvelope> {
                start.await()
                RefreshTokenProtector(context).seal(
                    credentialEpochId = credentialEpochId,
                    deviceId = deviceId,
                    generation = 1,
                    durableReferenceCount = 0,
                    ownedRefreshToken = validRefreshToken(),
                )
            }
            val second = executor.submit<RefreshTokenEnvelope> {
                start.await()
                RefreshTokenProtector(context).seal(
                    credentialEpochId = credentialEpochId,
                    deviceId = deviceId,
                    generation = 2,
                    durableReferenceCount = 0,
                    ownedRefreshToken = validRefreshToken(),
                )
            }
            start.countDown()
            val firstEnvelope = first.get()
            val secondEnvelope = second.get()

            assertEquals(
                firstEnvelope.refreshTokenKeyAlias,
                secondEnvelope.refreshTokenKeyAlias,
            )
            assertTrue(markerExists(credentialEpochId))
            RefreshTokenProtector(context)
                .openExact(firstEnvelope, generation = 1)
                .close()
            RefreshTokenProtector(context)
                .openExact(secondEnvelope, generation = 2)
                .close()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun nonCanonicalTokenIsRejectedBeforeProvisioningAndOwnedBytesAreWiped() {
        val invalid = WipeableSecret.ascii("lar_${"A".repeat(42)}B")
        val storage = invalid.storageReference()
        val expectedAlias = refreshTokenAeadAlias(credentialEpochId)

        val error = assertThrows(IllegalArgumentException::class.java) {
            RefreshTokenProtector(context).seal(
                credentialEpochId = credentialEpochId,
                deviceId = deviceId,
                generation = 1,
                durableReferenceCount = 0,
                ownedRefreshToken = invalid,
            )
        }

        assertTrue(storage.all { it == 0.toByte() })
        assertThrows(IllegalStateException::class.java) { invalid.copyBytes() }
        assertFalse(keystoreContains(expectedAlias))
        assertFalse(error.toString().contains("lar_"))
        assertFalse(error.toString().contains(credentialEpochId))
        assertFalse(error.toString().contains(deviceId))
    }

    @Test
    fun envelopeMapsDirectlyFromUsableAuthStateAndAliasIsEpochDeterministic() {
        val sealed = seal(generation = 4)
        val successor = seal(generation = 5, durableReferenceCount = 1)
        val authState = usableAuthState(sealed, generation = 4)

        val mapped = requireNotNull(RefreshTokenEnvelope.from(authState))

        assertArrayEquals(sealed.refreshTokenCiphertext, mapped.refreshTokenCiphertext)
        assertArrayEquals(sealed.refreshTokenNonce, mapped.refreshTokenNonce)
        assertEquals(sealed.refreshTokenKeyAlias, mapped.refreshTokenKeyAlias)
        assertEquals(sealed.refreshTokenKeyGeneration, mapped.refreshTokenKeyGeneration)
        assertEquals(sealed.refreshTokenAadVersion, mapped.refreshTokenAadVersion)
        assertEquals(sealed.refreshTokenKeyAlias, successor.refreshTokenKeyAlias)
        assertEquals(
            refreshTokenAeadAlias(credentialEpochId),
            refreshTokenAeadAlias(credentialEpochId),
        )
        assertNotEquals(
            refreshTokenAeadAlias(credentialEpochId),
            refreshTokenAeadAlias(alternateCredentialEpochId),
        )
    }

    private fun seal(
        generation: Long,
        durableReferenceCount: Long = 0,
    ): RefreshTokenEnvelope =
        RefreshTokenProtector(context).seal(
            credentialEpochId = credentialEpochId,
            deviceId = deviceId,
            generation = generation,
            durableReferenceCount = durableReferenceCount,
            ownedRefreshToken = validRefreshToken(),
        )

    private fun RefreshTokenProtector.openExact(
        envelope: RefreshTokenEnvelope,
        generation: Long,
    ): WipeableSecret = open(
        credentialEpochId = credentialEpochId,
        deviceId = deviceId,
        generation = generation,
        envelope = envelope,
    )

    private fun RefreshTokenEnvelope.withCiphertextBitFlipped(): RefreshTokenEnvelope {
        val tampered = refreshTokenCiphertext.copyOf()
        tampered[0] = (tampered[0].toInt() xor 1).toByte()
        return copy(refreshTokenCiphertext = tampered)
    }

    private fun RefreshTokenEnvelope.withNonceBitFlipped(): RefreshTokenEnvelope {
        val tampered = refreshTokenNonce.copyOf()
        tampered[0] = (tampered[0].toInt() xor 1).toByte()
        return copy(refreshTokenNonce = tampered)
    }

    private fun assertIntegrityFailure(
        expected: SensitivePayloadIntegrityFailure,
        block: () -> Unit,
    ) {
        val error = assertThrows(SensitivePayloadIntegrityException::class.java, block)
        assertEquals(expected, error.failure)
        assertFalse(error.toString().contains(credentialEpochId))
        assertFalse(error.toString().contains(deviceId))
        assertFalse(error.toString().contains(VALID_REFRESH_TOKEN))
    }

    private fun validRefreshToken(): WipeableSecret = WipeableSecret.copyOf(
        VALID_REFRESH_TOKEN_BYTES,
    )

    private fun WipeableSecret.storageReference(): ByteArray {
        val field = WipeableSecret::class.java.getDeclaredField("storage")
        field.isAccessible = true
        return checkNotNull(field.get(this) as ByteArray?)
    }

    private fun usableAuthState(
        envelope: RefreshTokenEnvelope,
        generation: Long,
    ): SyncAuthStateEntity = SyncAuthStateEntity(
        credentialEpochId = credentialEpochId,
        installationId = UUID.randomUUID().toString(),
        localOwnerId = UUID.randomUUID().toString(),
        deviceId = deviceId,
        personId = UUID.randomUUID().toString(),
        tokenType = "Bearer",
        refreshTokenCiphertext = envelope.refreshTokenCiphertext,
        refreshTokenNonce = envelope.refreshTokenNonce,
        refreshTokenKeyAlias = envelope.refreshTokenKeyAlias,
        refreshTokenKeyGeneration = envelope.refreshTokenKeyGeneration,
        refreshTokenAadVersion = envelope.refreshTokenAadVersion,
        accessExpiresAtUtc = "2026-08-03T01:00:00Z",
        accessExpiresAtEpochMs = 1_785_718_800_000,
        refreshExpiresAtUtc = "2026-08-03T02:00:00Z",
        refreshExpiresAtEpochMs = 1_785_722_400_000,
        familyExpiresAtUtc = "2026-08-03T03:00:00Z",
        familyExpiresAtEpochMs = 1_785_726_000_000,
        generation = generation,
        state = "active",
        bootstrapRequired = false,
        installedAtUtc = "2026-08-03T00:00:00Z",
        updatedAtUtc = "2026-08-03T00:00:00Z",
        failureCode = null,
    )

    private fun deleteKnownAliases() {
        deleteMarker(credentialEpochId)
        deleteMarker(alternateCredentialEpochId)
        deleteAlias(refreshTokenAeadAlias(credentialEpochId))
        deleteAlias(refreshTokenAeadAlias(alternateCredentialEpochId))
    }

    private fun deleteMarker(epoch: String) {
        val marker = context.noBackupFilesDir.resolve(refreshTokenMarkerRelativePath(epoch))
        listOf(marker, marker.resolveSibling(marker.name + ".bak")).forEach { file ->
            if (file.exists()) assertTrue(file.delete())
        }
    }

    private fun markerExists(epoch: String): Boolean {
        val marker = context.noBackupFilesDir.resolve(refreshTokenMarkerRelativePath(epoch))
        return marker.isFile || marker.resolveSibling(marker.name + ".bak").isFile
    }

    private fun deleteAlias(alias: String) {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
            if (containsAlias(alias)) deleteEntry(alias)
        }
    }

    private fun keystoreContains(alias: String): Boolean =
        KeyStore.getInstance(ANDROID_KEYSTORE).run {
            load(null)
            containsAlias(alias)
        }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val VALID_REFRESH_TOKEN =
            "lar_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        private val VALID_REFRESH_TOKEN_BYTES =
            VALID_REFRESH_TOKEN.toByteArray(StandardCharsets.US_ASCII)
    }
}
