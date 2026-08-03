package ru.andriyshkoy.lifeagent.data.sync.runtime

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.andriyshkoy.lifeagent.core.id.UuidGenerator
import ru.andriyshkoy.lifeagent.data.local.db.AccessRecoveryBinding
import ru.andriyshkoy.lifeagent.data.local.db.EnrollmentAttemptBinding
import ru.andriyshkoy.lifeagent.data.local.db.EnrollmentSuccessPersistence
import ru.andriyshkoy.lifeagent.data.local.db.InterruptedAuthRecoveryResult
import ru.andriyshkoy.lifeagent.data.local.db.RefreshAttemptBinding
import ru.andriyshkoy.lifeagent.data.local.db.RefreshSuccessPersistence
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncAuthStateEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncAuthTokenFingerprintEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncBootstrapSessionEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncStreamStateEntity
import ru.andriyshkoy.lifeagent.data.security.NewDurableRequestPersistence
import ru.andriyshkoy.lifeagent.data.sync.wire.BootstrapRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.EnrollmentClaimSuccess
import ru.andriyshkoy.lifeagent.data.sync.wire.EphemeralTokenPair
import ru.andriyshkoy.lifeagent.data.sync.wire.RefreshSuccess
import ru.andriyshkoy.lifeagent.data.sync.wire.WipeableSecret

class M2AuthRuntimeTest {
    @Test
    fun zeroRecoveryPreservesCurrentProcessAccessAuthority() = runTest {
        val events = mutableListOf<String>()
        val persistence = FakePersistence(events)
        val vault = FakeVault(events)
        val key = AccessTokenKey(EPOCH_ID, 7)
        val token = WipeableSecret.ascii(ACCESS_TOKEN)
        vault.replace(key, token)

        val result = runtime(
            persistence = persistence,
            credentials = FakeCredentials(),
            exchange = FakeExchange(),
            vault = vault,
        ).recoverInterruptedAuthFlows()

        val recovery = result as M2AuthRuntimeResult.RecoveryComplete
        assertEquals(0, recovery.recoveredCount)
        assertEquals(0, vault.clearCalls)
        assertTrue(vault.contains(key))
        vault.clear()
        assertSecretClosed(token)
    }

    @Test
    fun recoveredInterruptedAuthorityClearsProcessAccessVault() = runTest {
        val events = mutableListOf<String>()
        val persistence = FakePersistence(events).apply {
            interruptedRecovery = InterruptedAuthRecoveryResult(
                recoveredCount = 1,
                currentAuthorityChanged = true,
            )
        }
        val vault = FakeVault(events)
        val key = AccessTokenKey(EPOCH_ID, 7)
        val token = WipeableSecret.ascii(ACCESS_TOKEN)
        vault.replace(key, token)

        val result = runtime(
            persistence = persistence,
            credentials = FakeCredentials(),
            exchange = FakeExchange(),
            vault = vault,
        ).recoverInterruptedAuthFlows()

        val recovery = result as M2AuthRuntimeResult.RecoveryComplete
        assertEquals(1, recovery.recoveredCount)
        assertEquals(1, vault.clearCalls)
        assertFalse(vault.contains(key))
        assertSecretClosed(token)
    }

    @Test
    fun staleOnlyRecoveryCountPreservesExactCurrentProcessAuthority() = runTest {
        val events = mutableListOf<String>()
        val persistence = FakePersistence(events).apply {
            interruptedRecovery = InterruptedAuthRecoveryResult(
                recoveredCount = 1,
                currentAuthorityChanged = false,
            )
        }
        val vault = FakeVault(events)
        val key = AccessTokenKey(EPOCH_ID, 7)
        val token = WipeableSecret.ascii(ACCESS_TOKEN)
        vault.replace(key, token)

        val result = runtime(
            persistence = persistence,
            credentials = FakeCredentials(),
            exchange = FakeExchange(),
            vault = vault,
        ).recoverInterruptedAuthFlows()

        val recovery = result as M2AuthRuntimeResult.RecoveryComplete
        assertEquals(1, recovery.recoveredCount)
        assertEquals(0, vault.clearCalls)
        assertTrue(vault.contains(key))
        vault.clear()
        assertSecretClosed(token)
    }

    @Test
    fun enrollmentCommitsRoomBeforeVaultAndRevokesOnlyPredecessorEpoch() = runTest {
        val events = mutableListOf<String>()
        val persistence = FakePersistence(events).apply {
            enrollmentFactory = { requestId -> enrollmentBinding(requestId) }
        }
        val credentials = FakeCredentials()
        val response = enrollmentSuccess()
        val exchange = FakeExchange().apply {
            enrollHandler = { _, code, _ ->
                code.close()
                EnrollmentExchangeOutcome.Accepted(response)
            }
        }
        val vault = FakeVault(events)
        val generated = ArrayDeque(
            listOf(
                UUID.fromString(REQUEST_ID),
                UUID.fromString(NEW_EPOCH_ID),
                UUID.fromString(BOOTSTRAP_ID),
                UUID.fromString(BOOTSTRAP_REQUEST_ID),
            ),
        )
        val enrollmentCode = WipeableSecret.ascii(ENROLLMENT_CODE)

        val result = runtime(
            persistence = persistence,
            credentials = credentials,
            exchange = exchange,
            vault = vault,
            uuidGenerator = UuidGenerator(generated::removeFirst),
        ).enroll(enrollmentCode, replaceActiveDevice = true)

        val ready = result as M2AuthRuntimeResult.AccessReady
        assertEquals(AccessTokenKey(NEW_EPOCH_ID, 1), ready.key)
        assertEquals(AuthAccessSource.ENROLLMENT, ready.source)
        assertTrue(generated.isEmpty())
        assertEquals(1, exchange.enrollCalls)
        assertEquals(1, persistence.committedEnrollments.size)
        assertCommitBeforeVaultReplace(events, "room_commit_enrollment")
        assertEquals(listOf(EPOCH_ID), vault.revokedEpochs)
        assertTrue(vault.contains(AccessTokenKey(NEW_EPOCH_ID, 1)))
        assertSecretClosed(enrollmentCode)
        assertCredentialsClosed(response.credentials)
        assertPreparedEnrollmentMaterialWiped(persistence.committedEnrollments.single())
        vault.clear()
    }

    @Test
    fun ambiguousEnrollmentIsSettledOnceAndNeverAutomaticallyReplayed() = runTest {
        val events = mutableListOf<String>()
        val persistence = FakePersistence(events).apply {
            enrollmentFactory = { requestId -> enrollmentBinding(requestId) }
        }
        val exchange = FakeExchange().apply {
            enrollHandler = { _, code, _ ->
                code.close()
                EnrollmentExchangeOutcome.Ambiguous
            }
        }
        val vault = FakeVault(events)
        val enrollmentCode = WipeableSecret.ascii(ENROLLMENT_CODE)
        val runtime = runtime(persistence, FakeCredentials(), exchange, vault)

        val result = runtime.enroll(enrollmentCode, replaceActiveDevice = true)

        assertSame(M2AuthRuntimeResult.ManualReenrollmentRequired, result)
        assertEquals(1, persistence.beginEnrollmentCalls)
        assertEquals(1, exchange.enrollCalls)
        assertTrue(persistence.committedEnrollments.isEmpty())
        assertEquals(
            listOf(Settlement(REQUEST_ID, "auth_outcome_unknown")),
            persistence.enrollmentUnknowns,
        )
        assertEquals(listOf(EPOCH_ID), vault.revokedEpochs)
        assertSecretClosed(enrollmentCode)
    }

    @Test
    fun ambiguousRefreshIsQuarantinedWithoutAutomaticReplay() = runTest {
        val events = mutableListOf<String>()
        val observed = activeAccess()
        val persistence = FakePersistence(events).apply {
            access = observed
            refreshFactory = { requestId, expected -> refreshBinding(requestId, expected) }
        }
        val credentials = FakeCredentials()
        val exchange = FakeExchange().apply {
            refreshHandler = { _, refreshToken ->
                refreshToken.close()
                RefreshExchangeOutcome.Ambiguous
            }
        }
        val vault = FakeVault(events)

        val result = runtime(persistence, credentials, exchange, vault).ensureAccess()

        assertSame(M2AuthRuntimeResult.ManualReenrollmentRequired, result)
        assertEquals(1, persistence.beginRefreshCalls)
        assertEquals(1, exchange.refreshCalls)
        assertTrue(persistence.committedRefreshes.isEmpty())
        assertEquals(
            listOf(Settlement(REQUEST_ID, "auth_outcome_unknown")),
            persistence.refreshUnknowns,
        )
        assertEquals(listOf(EPOCH_ID), vault.revokedEpochs)
        assertRefreshInputsClosed(persistence, credentials)
    }

    @Test
    fun restartWithActiveStateAndEmptyVaultRefreshesExactlyOnceToGenerationNPlusOne() =
        runTest {
            val events = mutableListOf<String>()
            val observed = activeAccess(generation = 7)
            val persistence = FakePersistence(events).apply {
                access = observed
                refreshFactory = { requestId, expected -> refreshBinding(requestId, expected) }
            }
            val credentials = FakeCredentials()
            val exchange = FakeExchange().apply {
                refreshHandler = { binding, refreshToken ->
                    refreshToken.close()
                    val response = refreshSuccess(binding, generation = binding.generation + 1)
                    lastRefreshResponse = response
                    RefreshExchangeOutcome.Accepted(response)
                }
            }
            val vault = FakeVault(events)
            val oldKey = AccessTokenKey(EPOCH_ID, 7)
            val newKey = AccessTokenKey(EPOCH_ID, 8)

            val result = runtime(persistence, credentials, exchange, vault).ensureAccess()

            val ready = result as M2AuthRuntimeResult.AccessReady
            assertEquals(newKey, ready.key)
            assertEquals(AuthAccessSource.REFRESH, ready.source)
            assertEquals(1, persistence.readAccessCalls)
            assertEquals(1, persistence.beginRefreshCalls)
            assertEquals(1, exchange.refreshCalls)
            assertEquals(1, persistence.committedRefreshes.size)
            assertEquals(7L, persistence.committedRefreshes.single().persistence.expectedGeneration)
            assertEquals(8L, persistence.committedRefreshes.single().persistence.successorGeneration)
            assertCommitBeforeVaultReplace(events)

            // The predecessor may be revoked before and after exchange, but no
            // generation other than that exact predecessor loses authority.
            assertEquals(setOf(oldKey), vault.revokedKeys.toSet())
            assertTrue(vault.contains(newKey))
            assertFalse(vault.contains(oldKey))
            assertTrue(vault.revokedEpochs.isEmpty())

            assertRefreshInputsClosed(persistence, credentials)
            assertCredentialsClosed(checkNotNull(exchange.lastRefreshResponse).credentials)
            assertPreparedRefreshMaterialWiped(persistence.committedRefreshes.single())

            val installedAccessToken = vault.replacedSecrets.single()
            installedAccessToken.copyBytes()
            vault.clear()
            assertSecretClosed(installedAccessToken)
        }

    @Test
    fun vaultFailureAfterRoomCommitReturnsDurableCommitAndRevokesExactOldAndNewKeys() =
        runTest {
            val events = mutableListOf<String>()
            val observed = activeAccess(generation = 11)
            val persistence = FakePersistence(events).apply {
                access = observed
                refreshFactory = { requestId, expected -> refreshBinding(requestId, expected) }
            }
            val credentials = FakeCredentials()
            val exchange = FakeExchange().apply {
                refreshHandler = { binding, refreshToken ->
                    refreshToken.close()
                    val response = refreshSuccess(binding, generation = binding.generation + 1)
                    lastRefreshResponse = response
                    RefreshExchangeOutcome.Accepted(response)
                }
            }
            val vault = FakeVault(events).apply { failReplace = true }
            val oldKey = AccessTokenKey(EPOCH_ID, 11)
            val newKey = AccessTokenKey(EPOCH_ID, 12)

            val result = runtime(persistence, credentials, exchange, vault).ensureAccess()

            val durable = result as M2AuthRuntimeResult.DurableCredentialsCommitted
            assertEquals(newKey, durable.key)
            assertEquals(1, persistence.committedRefreshes.size)
            assertTrue(persistence.refreshUnknowns.isEmpty())
            assertCommitBeforeVaultReplace(events)
            assertEquals(setOf(oldKey, newKey), vault.revokedKeys.toSet())
            assertTrue(vault.revokedEpochs.isEmpty())
            assertFalse(vault.contains(oldKey))
            assertFalse(vault.contains(newKey))

            assertRefreshInputsClosed(persistence, credentials)
            assertCredentialsClosed(checkNotNull(exchange.lastRefreshResponse).credentials)
            assertPreparedRefreshMaterialWiped(persistence.committedRefreshes.single())
            assertSecretClosed(vault.replacedSecrets.single())
        }

    @Test
    fun cancellationSettlesUnknownThenPropagatesWithoutReplay() = runTest {
        val events = mutableListOf<String>()
        val observed = activeAccess()
        val persistence = FakePersistence(events).apply {
            access = observed
            refreshFactory = { requestId, expected -> refreshBinding(requestId, expected) }
        }
        val credentials = FakeCredentials()
        val exchange = FakeExchange().apply {
            refreshHandler = { _, refreshToken ->
                refreshToken.close()
                events += "exchange_cancelled"
                throw CancellationException("cancelled at test boundary")
            }
        }
        val vault = FakeVault(events)

        val failure = runCatching {
            runtime(persistence, credentials, exchange, vault).ensureAccess()
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(1, exchange.refreshCalls)
        assertEquals(1, persistence.beginRefreshCalls)
        assertTrue(persistence.committedRefreshes.isEmpty())
        assertEquals(
            listOf(Settlement(REQUEST_ID, "auth_exchange_cancelled")),
            persistence.refreshUnknowns,
        )
        assertTrue(events.indexOf("exchange_cancelled") < events.indexOf("refresh_unknown"))
        assertEquals(listOf(EPOCH_ID), vault.revokedEpochs)
        assertRefreshInputsClosed(persistence, credentials)
    }

    @Test
    fun diagnosticsRemainRedactedAcrossRuntimeValues() {
        val events = mutableListOf<String>()
        val persistence = FakePersistence(events)
        val credentials = FakeCredentials()
        val exchange = FakeExchange()
        val vault = FakeVault(events)
        val observed = activeAccess()
        val enrollmentBinding = enrollmentBinding(REQUEST_ID)
        val refreshBinding = refreshBinding(REQUEST_ID, observed)
        val markerSecret = WipeableSecret.ascii("marker-secret-value")
        val enrollmentResponse = enrollmentSuccess()
        val refreshResponse = refreshSuccess(refreshBinding, generation = 8)
        val enrollmentOutcome = EnrollmentExchangeOutcome.Accepted(enrollmentResponse)
        val refreshOutcome = RefreshExchangeOutcome.Accepted(refreshResponse)
        val prepared = credentials.prepareRefresh(refreshBinding, refreshResponse, NOW)

        try {
            val diagnosticLines = listOf(
                runtime(persistence, credentials, exchange, vault),
                AccessTokenKey(EPOCH_ID, 7),
                M2AuthRuntimeResult.AccessReady(
                    AccessTokenKey(EPOCH_ID, 7),
                    AuthAccessSource.REFRESH,
                ),
                M2AuthRuntimeResult.DurableCredentialsCommitted(AccessTokenKey(EPOCH_ID, 8)),
                InterruptedAuthRecoveryResult(
                    recoveredCount = 1,
                    currentAuthorityChanged = false,
                ),
                M2AuthRuntimePolicy(),
                EnrollmentInstallationIdentifiers(EPOCH_ID, BOOTSTRAP_ID, BOOTSTRAP_REQUEST_ID),
                enrollmentBinding,
                observed,
                refreshBinding,
                markerSecret,
                enrollmentResponse,
                refreshResponse,
                enrollmentOutcome,
                refreshOutcome,
                prepared,
            ).map(Any::toString)
            val diagnostics = diagnosticLines.joinToString(separator = "\n")

            assertTrue(diagnosticLines.all { "redacted=true" in it })
            assertFalse(diagnostics.contains(EPOCH_ID))
            assertFalse(diagnostics.contains(DEVICE_ID))
            assertFalse(diagnostics.contains(PERSON_ID))
            assertFalse(diagnostics.contains("marker-secret-value"))
            assertFalse(diagnostics.contains(ACCESS_TOKEN))
            assertFalse(diagnostics.contains(REFRESH_TOKEN))
        } finally {
            markerSecret.close()
            enrollmentOutcome.close()
            refreshOutcome.close()
            prepared.close()
            refreshBinding.close()
        }

        assertSecretClosed(markerSecret)
        assertCredentialsClosed(enrollmentResponse.credentials)
        assertCredentialsClosed(refreshResponse.credentials)
        assertPreparedRefreshMaterialWiped(prepared)
        assertThrows(IllegalStateException::class.java) {
            refreshBinding.copyRefreshTokenCiphertext()
        }
    }

    private fun runtime(
        persistence: FakePersistence,
        credentials: FakeCredentials,
        exchange: FakeExchange,
        vault: FakeVault,
        uuidGenerator: UuidGenerator = UuidGenerator { UUID.fromString(REQUEST_ID) },
    ): M2AuthRuntime = M2AuthRuntime(
        persistence = persistence,
        credentials = credentials,
        exchange = exchange,
        vault = vault,
        uuidGenerator = uuidGenerator,
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
    )

    private class FakePersistence(
        private val events: MutableList<String>,
    ) : M2AuthPersistenceBoundary {
        var access: AccessRecoveryBinding? = null
        var enrollmentFactory: ((String) -> EnrollmentAttemptBinding?)? = null
        var refreshFactory:
            ((String, AccessRecoveryBinding) -> RefreshAttemptBinding?)? = null

        var beginEnrollmentCalls = 0
        var readAccessCalls = 0
        var beginRefreshCalls = 0
        var interruptedRecovery = InterruptedAuthRecoveryResult(
            recoveredCount = 0,
            currentAuthorityChanged = false,
        )
        var lastRefreshBinding: RefreshAttemptBinding? = null
        val committedEnrollments = mutableListOf<PreparedEnrollmentInstallation>()
        val committedRefreshes = mutableListOf<PreparedRefreshInstallation>()
        val enrollmentUnknowns = mutableListOf<Settlement>()
        val refreshUnknowns = mutableListOf<Settlement>()

        override suspend fun beginEnrollment(
            requestId: String,
            createdAt: Instant,
            hmacKeyGeneration: Int,
        ): EnrollmentAttemptBinding? {
            beginEnrollmentCalls += 1
            return enrollmentFactory?.invoke(requestId)
        }

        override suspend fun readAccess(): AccessRecoveryBinding? {
            readAccessCalls += 1
            return access
        }

        override suspend fun beginRefresh(
            requestId: String,
            expected: AccessRecoveryBinding,
            now: Instant,
            hmacKeyGeneration: Int,
        ): RefreshAttemptBinding? {
            beginRefreshCalls += 1
            return refreshFactory?.invoke(requestId, expected).also {
                lastRefreshBinding = it
            }
        }

        override suspend fun commitEnrollment(installation: PreparedEnrollmentInstallation) {
            events += "room_commit_enrollment"
            committedEnrollments += installation
        }

        override suspend fun commitRefresh(installation: PreparedRefreshInstallation) {
            events += "room_commit_refresh"
            committedRefreshes += installation
        }

        override suspend fun enrollmentRejected(
            requestId: String,
            updatedAtUtc: String,
            failureCode: String,
        ) = Unit

        override suspend fun enrollmentUnknown(
            requestId: String,
            updatedAtUtc: String,
            failureCode: String,
        ) {
            events += "enrollment_unknown"
            enrollmentUnknowns += Settlement(requestId, failureCode)
        }

        override suspend fun refreshRejected(
            requestId: String,
            updatedAtUtc: String,
            failureCode: String,
        ) = Unit

        override suspend fun refreshUnknown(
            requestId: String,
            updatedAtUtc: String,
            failureCode: String,
        ) {
            events += "refresh_unknown"
            refreshUnknowns += Settlement(requestId, failureCode)
        }

        override suspend fun recoverInterrupted(
            updatedAtUtc: String,
        ): InterruptedAuthRecoveryResult = interruptedRecovery
    }

    private class FakeCredentials : M2AuthCredentialBoundary {
        override val hmacKeyGeneration: Int = 3
        var lastOpenedRefreshToken: WipeableSecret? = null

        override fun preflightFingerprintKey(durableReferenceCount: Long) = Unit

        override fun openRefreshToken(binding: RefreshAttemptBinding): WipeableSecret =
            WipeableSecret.ascii(REFRESH_TOKEN).also {
                lastOpenedRefreshToken = it
            }

        override fun prepareEnrollment(
            binding: EnrollmentAttemptBinding,
            response: EnrollmentClaimSuccess,
            identifiers: EnrollmentInstallationIdentifiers,
            committedAt: Instant,
            policy: M2AuthRuntimePolicy,
        ): PreparedEnrollmentInstallation {
            val generation = response.credentials.generation
            val accessToken = response.credentials.useAccessToken(WipeableSecret::copyOf)
            val accessExpires = NOW.plusSeconds(600)
            val refreshExpires = NOW.plusSeconds(3_600)
            val familyExpires = NOW.plusSeconds(7_200)
            return PreparedEnrollmentInstallation(
                persistence = EnrollmentSuccessPersistence(
                    attemptRequestId = binding.requestId,
                    authState = SyncAuthStateEntity(
                        credentialEpochId = identifiers.credentialEpochId,
                        installationId = binding.installationId,
                        localOwnerId = binding.localOwnerId,
                        deviceId = response.deviceId,
                        personId = response.personId,
                        tokenType = "Bearer",
                        refreshTokenCiphertext = byteArrayOf(11, 12, 13, 14),
                        refreshTokenNonce = byteArrayOf(15, 16, 17),
                        refreshTokenKeyAlias = "test-enrollment-refresh-key",
                        refreshTokenKeyGeneration = 1,
                        refreshTokenAadVersion = 1,
                        accessExpiresAtUtc = accessExpires.toString(),
                        accessExpiresAtEpochMs = accessExpires.toEpochMilli(),
                        refreshExpiresAtUtc = refreshExpires.toString(),
                        refreshExpiresAtEpochMs = refreshExpires.toEpochMilli(),
                        familyExpiresAtUtc = familyExpires.toString(),
                        familyExpiresAtEpochMs = familyExpires.toEpochMilli(),
                        generation = generation,
                        state = "active",
                        bootstrapRequired = true,
                        installedAtUtc = committedAt.toString(),
                        updatedAtUtc = committedAt.toString(),
                        failureCode = null,
                    ),
                    accessFingerprint = fingerprint(
                        epoch = identifiers.credentialEpochId,
                        generation = generation,
                        kind = "access",
                        marker = 31,
                    ),
                    refreshFingerprint = fingerprint(
                        epoch = identifiers.credentialEpochId,
                        generation = generation,
                        kind = "refresh",
                        marker = 32,
                    ),
                    streamState = SyncStreamStateEntity(
                        credentialEpochId = identifiers.credentialEpochId,
                        deviceId = response.deviceId,
                        phase = "bootstrap_required",
                        bootstrapRequired = true,
                        appliedCursor = null,
                        highWatermarkHint = null,
                        integrityErrorCode = null,
                        updatedAtUtc = committedAt.toString(),
                        replicaLineageId = identifiers.bootstrapId,
                    ),
                    bootstrapSession = SyncBootstrapSessionEntity(
                        bootstrapId = identifiers.bootstrapId,
                        credentialEpochId = identifiers.credentialEpochId,
                        deviceId = response.deviceId,
                        state = "staging",
                        activeSlot = 1,
                        snapshotId = null,
                        nextPageCursor = null,
                        candidateIncrementalCursor = null,
                        nextPageIndex = 0,
                        lastStagedServerSequence = null,
                        createdAtUtc = committedAt.toString(),
                        updatedAtUtc = committedAt.toString(),
                    ),
                ),
                bootstrapRequest = BootstrapRequest(
                    requestId = identifiers.bootstrapRequestId,
                    bootstrapId = identifiers.bootstrapId,
                    deviceId = response.deviceId,
                    pageSize = policy.bootstrapPageSize,
                    pageCursor = null,
                ),
                bootstrapPersistence = NewDurableRequestPersistence(
                    localCredentialEpochId = identifiers.credentialEpochId,
                    accessGenerationUsed = generation,
                    attemptBudget = policy.bootstrapAttemptBudget,
                    deadlineAtEpochMs = committedAt.toEpochMilli() +
                        policy.bootstrapRequestLifetimeMillis,
                    createdAtUtc = committedAt.toString(),
                ),
                accessToken = accessToken,
            )
        }

        override fun prepareRefresh(
            binding: RefreshAttemptBinding,
            response: RefreshSuccess,
            committedAt: Instant,
        ): PreparedRefreshInstallation {
            val successor = response.credentials.generation
            val accessToken = response.credentials.useAccessToken(WipeableSecret::copyOf)
            return PreparedRefreshInstallation(
                persistence = RefreshSuccessPersistence(
                    attemptRequestId = binding.requestId,
                    credentialEpochId = binding.credentialEpochId,
                    deviceId = binding.deviceId,
                    expectedGeneration = binding.generation,
                    successorGeneration = successor,
                    refreshTokenCiphertext = byteArrayOf(21, 22, 23, 24),
                    refreshTokenNonce = byteArrayOf(31, 32, 33),
                    refreshTokenKeyAlias = "test-refresh-key",
                    refreshTokenKeyGeneration = 2,
                    refreshTokenAadVersion = 1,
                    accessExpiresAtUtc = NOW.plusSeconds(600).toString(),
                    accessExpiresAtEpochMs = NOW.plusSeconds(600).toEpochMilli(),
                    refreshExpiresAtUtc = NOW.plusSeconds(3_600).toString(),
                    refreshExpiresAtEpochMs = NOW.plusSeconds(3_600).toEpochMilli(),
                    familyExpiresAtUtc = NOW.plusSeconds(7_200).toString(),
                    familyExpiresAtEpochMs = NOW.plusSeconds(7_200).toEpochMilli(),
                    committedAtUtc = committedAt.toString(),
                    committedAtEpochMs = committedAt.toEpochMilli(),
                    accessFingerprint = fingerprint(
                        generation = successor,
                        kind = "access",
                        marker = 41,
                    ),
                    refreshFingerprint = fingerprint(
                        generation = successor,
                        kind = "refresh",
                        marker = 42,
                    ),
                ),
                accessToken = accessToken,
            )
        }
    }

    private class FakeExchange : M2AuthExchangeBoundary {
        var enrollCalls = 0
        var refreshCalls = 0
        var lastRefreshResponse: RefreshSuccess? = null
        var enrollHandler:
            suspend (EnrollmentAttemptBinding, WipeableSecret, Boolean) ->
                EnrollmentExchangeOutcome = { _, code, _ ->
                    code.close()
                    EnrollmentExchangeOutcome.LocalFailure
                }
        var refreshHandler:
            suspend (RefreshAttemptBinding, WipeableSecret) -> RefreshExchangeOutcome =
            { _, refreshToken ->
                refreshToken.close()
                RefreshExchangeOutcome.LocalFailure
            }

        override suspend fun enroll(
            binding: EnrollmentAttemptBinding,
            ownedEnrollmentCode: WipeableSecret,
            replaceActiveDevice: Boolean,
        ): EnrollmentExchangeOutcome {
            enrollCalls += 1
            return enrollHandler(binding, ownedEnrollmentCode, replaceActiveDevice)
        }

        override suspend fun refresh(
            binding: RefreshAttemptBinding,
            ownedRefreshToken: WipeableSecret,
        ): RefreshExchangeOutcome {
            refreshCalls += 1
            return refreshHandler(binding, ownedRefreshToken)
        }
    }

    private class FakeVault(
        private val events: MutableList<String>,
    ) : M2AuthAccessVaultBoundary {
        private val stored = linkedMapOf<AccessTokenKey, WipeableSecret>()
        val replacedSecrets = mutableListOf<WipeableSecret>()
        val revokedKeys = mutableListOf<AccessTokenKey>()
        val revokedEpochs = mutableListOf<String>()
        var failReplace = false
        var clearCalls = 0

        override fun contains(key: AccessTokenKey): Boolean = stored.containsKey(key)

        override fun replace(key: AccessTokenKey, ownedAccessToken: WipeableSecret) {
            events += "vault_replace"
            replacedSecrets += ownedAccessToken
            if (failReplace) {
                ownedAccessToken.close()
                error("synthetic vault write failure")
            }
            stored.remove(key)?.close()
            stored[key] = ownedAccessToken
        }

        override fun revoke(key: AccessTokenKey): Boolean {
            revokedKeys += key
            return stored.remove(key)?.let {
                it.close()
                true
            } ?: false
        }

        override fun revokeEpoch(credentialEpochId: String): Int {
            revokedEpochs += credentialEpochId
            val matching = stored.keys.filter { it.credentialEpochId == credentialEpochId }
            matching.forEach(::revoke)
            return matching.size
        }

        override fun clear() {
            clearCalls += 1
            stored.values.forEach(WipeableSecret::close)
            stored.clear()
        }
    }

    private data class Settlement(
        val requestId: String,
        val failureCode: String,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-03T00:00:00Z")
        const val REQUEST_ID = "10000000-0000-4000-8000-000000000010"
        const val EPOCH_ID = "10000000-0000-4000-8000-000000000001"
        const val NEW_EPOCH_ID = "10000000-0000-4000-8000-000000000011"
        const val INSTALLATION_ID = "10000000-0000-4000-8000-000000000002"
        const val OWNER_ID = "10000000-0000-4000-8000-000000000003"
        const val DEVICE_ID = "10000000-0000-4000-8000-000000000004"
        const val PERSON_ID = "10000000-0000-4000-8000-000000000005"
        const val BOOTSTRAP_ID = "10000000-0000-4000-8000-000000000006"
        const val BOOTSTRAP_REQUEST_ID = "10000000-0000-4000-8000-000000000007"
        const val ENROLLMENT_CODE = "ABCD-EFGH-JKLM-NPQR-STUV-WXYZ-2345"
        const val ACCESS_TOKEN = "laa_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val REFRESH_TOKEN = "lar_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA"

        fun enrollmentBinding(requestId: String): EnrollmentAttemptBinding =
            EnrollmentAttemptBinding(
                requestId = requestId,
                installationId = INSTALLATION_ID,
                localOwnerId = OWNER_ID,
                predecessorCredentialEpochId = EPOCH_ID,
                predecessorDeviceId = DEVICE_ID,
                predecessorGeneration = 7,
                expectedStableDeviceId = DEVICE_ID,
                expectedStablePersonId = PERSON_ID,
                credentialFingerprintReferenceCount = 2,
            )

        fun activeAccess(generation: Long = 7): AccessRecoveryBinding =
            AccessRecoveryBinding(
                credentialEpochId = EPOCH_ID,
                installationId = INSTALLATION_ID,
                localOwnerId = OWNER_ID,
                deviceId = DEVICE_ID,
                personId = PERSON_ID,
                generation = generation,
                state = "active",
                accessExpiresAtEpochMs = NOW.plusSeconds(600).toEpochMilli(),
                refreshExpiresAtEpochMs = NOW.plusSeconds(3_600).toEpochMilli(),
                familyExpiresAtUtc = NOW.plusSeconds(7_200).toString(),
                familyExpiresAtEpochMs = NOW.plusSeconds(7_200).toEpochMilli(),
                bootstrapRequired = false,
            )

        fun refreshBinding(
            requestId: String,
            observed: AccessRecoveryBinding,
        ): RefreshAttemptBinding = RefreshAttemptBinding(
            requestId = requestId,
            credentialEpochId = observed.credentialEpochId,
            installationId = observed.installationId,
            localOwnerId = observed.localOwnerId,
            deviceId = observed.deviceId,
            personId = observed.personId,
            generation = observed.generation,
            accessExpiresAtEpochMs = observed.accessExpiresAtEpochMs,
            refreshExpiresAtEpochMs = observed.refreshExpiresAtEpochMs,
            familyExpiresAtUtc = observed.familyExpiresAtUtc,
            familyExpiresAtEpochMs = observed.familyExpiresAtEpochMs,
            bootstrapRequired = observed.bootstrapRequired,
            durableRefreshTokenReferenceCount = 1,
            credentialFingerprintReferenceCount = 2,
            refreshTokenCiphertext = byteArrayOf(1, 2, 3, 4),
            refreshTokenNonce = byteArrayOf(5, 6, 7),
            refreshTokenKeyAlias = "test-refresh-key",
            refreshTokenKeyGeneration = 2,
            refreshTokenAadVersion = 1,
        )

        fun refreshSuccess(
            binding: RefreshAttemptBinding,
            generation: Long,
        ): RefreshSuccess = RefreshSuccess(
            requestId = binding.requestId,
            deviceId = binding.deviceId,
            credentials = tokenPair(generation),
            serverTime = NOW.toString(),
        )

        fun enrollmentSuccess(): EnrollmentClaimSuccess = EnrollmentClaimSuccess(
            requestId = REQUEST_ID,
            installationId = INSTALLATION_ID,
            localOwnerId = OWNER_ID,
            deviceId = DEVICE_ID,
            personId = PERSON_ID,
            credentials = tokenPair(generation = 1),
            bootstrapRequired = true,
            serverTime = NOW.toString(),
        )

        fun tokenPair(generation: Long): EphemeralTokenPair = EphemeralTokenPair(
            accessToken = ACCESS_TOKEN.toByteArray(Charsets.US_ASCII),
            refreshToken = REFRESH_TOKEN.toByteArray(Charsets.US_ASCII),
            accessExpiresAt = NOW.plusSeconds(600).toString(),
            refreshExpiresAt = NOW.plusSeconds(3_600).toString(),
            familyExpiresAt = NOW.plusSeconds(7_200).toString(),
            generation = generation,
        )

        fun fingerprint(
            epoch: String = EPOCH_ID,
            generation: Long,
            kind: String,
            marker: Int,
        ): SyncAuthTokenFingerprintEntity = SyncAuthTokenFingerprintEntity(
            credentialEpochId = epoch,
            generation = generation,
            tokenKind = kind,
            tokenHmac = ByteArray(32) { marker.toByte() },
            hmacKeyGeneration = 3,
            createdAtUtc = NOW.toString(),
        )

        fun assertCommitBeforeVaultReplace(
            events: List<String>,
            commitEvent: String = "room_commit_refresh",
        ) {
            val commitIndex = events.indexOf(commitEvent)
            val replaceIndex = events.indexOf("vault_replace")
            assertTrue("Room commit was not observed", commitIndex >= 0)
            assertTrue("Vault replace was not observed", replaceIndex >= 0)
            assertTrue("Vault replace happened before Room commit", commitIndex < replaceIndex)
        }

        fun assertRefreshInputsClosed(
            persistence: FakePersistence,
            credentials: FakeCredentials,
        ) {
            assertThrows(IllegalStateException::class.java) {
                checkNotNull(persistence.lastRefreshBinding).copyRefreshTokenCiphertext()
            }
            assertSecretClosed(checkNotNull(credentials.lastOpenedRefreshToken))
        }

        fun assertPreparedRefreshMaterialWiped(prepared: PreparedRefreshInstallation) {
            val durable = prepared.persistence
            assertTrue(durable.refreshTokenCiphertext.all { it == 0.toByte() })
            assertTrue(durable.refreshTokenNonce.all { it == 0.toByte() })
            assertTrue(durable.accessFingerprint.tokenHmac.all { it == 0.toByte() })
            assertTrue(durable.refreshFingerprint.tokenHmac.all { it == 0.toByte() })
        }

        fun assertPreparedEnrollmentMaterialWiped(
            prepared: PreparedEnrollmentInstallation,
        ) {
            val durable = prepared.persistence
            assertTrue(
                checkNotNull(durable.authState.refreshTokenCiphertext)
                    .all { it == 0.toByte() },
            )
            assertTrue(
                checkNotNull(durable.authState.refreshTokenNonce)
                    .all { it == 0.toByte() },
            )
            assertTrue(durable.accessFingerprint.tokenHmac.all { it == 0.toByte() })
            assertTrue(durable.refreshFingerprint.tokenHmac.all { it == 0.toByte() })
        }

        fun assertSecretClosed(secret: WipeableSecret) {
            assertThrows(IllegalStateException::class.java) { secret.copyBytes() }
        }

        fun assertCredentialsClosed(credentials: EphemeralTokenPair) {
            assertThrows(IllegalStateException::class.java) { credentials.copyAccessToken() }
            assertThrows(IllegalStateException::class.java) { credentials.copyRefreshToken() }
        }
    }
}
