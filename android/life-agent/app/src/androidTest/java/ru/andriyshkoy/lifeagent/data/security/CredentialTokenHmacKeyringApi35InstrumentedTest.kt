package ru.andriyshkoy.lifeagent.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.KeyGenerator
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class CredentialTokenHmacKeyringApi35InstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var keyAlias: String
    private lateinit var markerRelativePath: String

    @Before
    fun setUp() {
        val id = UUID.randomUUID().toString()
        keyAlias = "life_agent_test_credential_token_hmac_$id"
        markerRelativePath = "crypto-tests/credential-token-hmac-$id.marker"
    }

    @After
    fun tearDown() {
        deleteMarkerArtifacts()
        deleteKeystoreAlias()
    }

    @Test
    fun distinctKeyIsNonExportableAndSharedPayloadPersistsAcrossReopen() {
        assertNotEquals(
            KeystoreRequestBodyHmacKeyring.DEFAULT_KEY_ALIAS,
            KeystoreCredentialTokenHmacKeyring.DEFAULT_KEY_ALIAS,
        )
        assertNotEquals(
            KeystoreRequestBodyHmacKeyring.DEFAULT_MARKER_RELATIVE_PATH,
            KeystoreCredentialTokenHmacKeyring.DEFAULT_MARKER_RELATIVE_PATH,
        )
        val payload = ByteArray(32) { (it + 41).toByte() }
        val accessToken = token("laa_", payload)
        val refreshToken = token("lar_", payload)
        val retainedAccess = accessToken.copyOf()
        val retainedRefresh = refreshToken.copyOf()
        val first = keyring()
        first.provisionCurrentKey(durableReferenceCount = 0)
        val accessFingerprint = first.fingerprintAccess(accessToken)
        val refreshFingerprint = first.fingerprintRefresh(refreshToken)

        try {
            assertEquals(1, first.currentGeneration)
            assertEquals(32, accessFingerprint.size)
            assertArrayEquals(accessFingerprint, refreshFingerprint)
            assertArrayEquals(retainedAccess, accessToken)
            assertArrayEquals(retainedRefresh, refreshToken)
            val installed = KeyStore.getInstance(ANDROID_KEYSTORE).run {
                load(null)
                getKey(keyAlias, null)
            }
            assertNull("Credential fingerprint HMAC material must not be exportable", installed.encoded)
            assertTrue(markerFile().isFile)

            keyring().verifyAccessExisting(accessToken, accessFingerprint)
            keyring().verifyRefreshExisting(refreshToken, refreshFingerprint)
            val changed = accessFingerprint.copyOf().also {
                it[0] = (it[0].toInt() xor 1).toByte()
            }
            try {
                assertThrows(CredentialTokenFingerprintMismatchException::class.java) {
                    keyring().verifyAccessExisting(accessToken, changed)
                }
            } finally {
                changed.fill(0)
            }

            val rendered = first.toString()
            assertFalse(rendered.contains(keyAlias))
            assertFalse(rendered.contains(markerRelativePath))
            assertFalse(rendered.contains(accessToken.toString(StandardCharsets.US_ASCII)))
        } finally {
            accessFingerprint.fill(0)
            refreshFingerprint.fill(0)
            retainedAccess.fill(0)
            retainedRefresh.fill(0)
            accessToken.fill(0)
            refreshToken.fill(0)
            payload.fill(0)
        }
    }

    @Test
    fun missingAliasVerificationIsLoadOnlyAndNeverRecreatesKey() {
        val payload = ByteArray(32) { (it + 73).toByte() }
        val accessToken = token("laa_", payload)
        val ring = keyring()
        ring.provisionCurrentKey(durableReferenceCount = 0)
        val fingerprint = ring.fingerprintAccess(accessToken)
        deleteKeystoreAlias()

        try {
            assertThrows(CredentialTokenKeyUnavailableException::class.java) {
                keyring().verifyAccessExisting(accessToken, fingerprint)
            }
            assertFalse(keystoreContainsAlias())
            assertTrue("Continuity marker must survive key loss", markerFile().isFile)
            assertThrows(CredentialTokenKeyUnavailableException::class.java) {
                keyring().provisionCurrentKey(durableReferenceCount = 0)
            }
            assertFalse(keystoreContainsAlias())
        } finally {
            fingerprint.fill(0)
            accessToken.fill(0)
            payload.fill(0)
        }
    }

    @Test
    fun markerLossWithDurableReferenceAndAliasReplacementBothFailClosed() {
        val payload = ByteArray(32) { (it + 101).toByte() }
        val refreshToken = token("lar_", payload)
        val ring = keyring()
        ring.provisionCurrentKey(durableReferenceCount = 0)
        val fingerprint = ring.fingerprintRefresh(refreshToken)

        try {
            deleteMarkerArtifacts()
            assertTrue(keystoreContainsAlias())
            assertThrows(CredentialTokenKeyUnavailableException::class.java) {
                keyring().provisionCurrentKey(durableReferenceCount = 1)
            }
            assertFalse(markerFile().exists())
            assertTrue(keystoreContainsAlias())

            // Restore the authentic marker while the original key still exists,
            // then prove that replacing only the alias cannot satisfy it.
            keyring().provisionCurrentKey(durableReferenceCount = 0)
            deleteKeystoreAlias()
            generateHmacAlias()
            assertThrows(CredentialTokenKeyUnavailableException::class.java) {
                keyring().verifyRefreshExisting(refreshToken, fingerprint)
            }
            assertTrue(markerFile().isFile)
            assertTrue(keystoreContainsAlias())
        } finally {
            fingerprint.fill(0)
            refreshToken.fill(0)
            payload.fill(0)
        }
    }

    private fun keyring() = KeystoreCredentialTokenHmacKeyring(
        context = context,
        keyAlias = keyAlias,
        markerRelativePath = markerRelativePath,
    )

    private fun token(prefix: String, payload: ByteArray): ByteArray =
        (prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(payload))
            .toByteArray(StandardCharsets.US_ASCII)

    private fun markerFile() = File(context.noBackupFilesDir, markerRelativePath)

    private fun deleteMarkerArtifacts() {
        listOf("", ".bak", ".new").forEach { suffix ->
            File(markerFile().path + suffix).delete()
        }
    }

    private fun deleteKeystoreAlias() {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
            if (containsAlias(keyAlias)) deleteEntry(keyAlias)
        }
    }

    private fun keystoreContainsAlias(): Boolean =
        KeyStore.getInstance(ANDROID_KEYSTORE).run {
            load(null)
            containsAlias(keyAlias)
        }

    private fun generateHmacAlias() {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                    )
                        .setKeySize(256)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build(),
                )
            }
            .generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
