package ru.andriyshkoy.lifeagent.data.sync.runtime

import android.content.Context
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import ru.andriyshkoy.lifeagent.core.id.RandomUuidGenerator
import ru.andriyshkoy.lifeagent.core.id.UuidGenerator
import ru.andriyshkoy.lifeagent.data.local.db.AccessRecoveryBinding
import ru.andriyshkoy.lifeagent.data.local.db.EnrollmentAttemptBinding
import ru.andriyshkoy.lifeagent.data.local.db.EnrollmentSuccessPersistence
import ru.andriyshkoy.lifeagent.data.local.db.InterruptedAuthRecoveryResult
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabase
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedSyncRequestStore
import ru.andriyshkoy.lifeagent.data.local.db.RefreshAttemptBinding
import ru.andriyshkoy.lifeagent.data.local.db.RefreshSuccessPersistence
import ru.andriyshkoy.lifeagent.data.local.db.SyncAuthPersistenceStore
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncAuthStateEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncAuthTokenFingerprintEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncBootstrapSessionEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncStreamStateEntity
import ru.andriyshkoy.lifeagent.data.security.KeystoreCredentialTokenHmacKeyring
import ru.andriyshkoy.lifeagent.data.security.NewDurableRequestPersistence
import ru.andriyshkoy.lifeagent.data.security.RefreshTokenEnvelope
import ru.andriyshkoy.lifeagent.data.security.RefreshTokenProtector
import ru.andriyshkoy.lifeagent.data.sync.transport.LazyProductionM2HttpsTransportBundle
import ru.andriyshkoy.lifeagent.data.sync.transport.OneShotAuthHttpsNetworkFailure
import ru.andriyshkoy.lifeagent.data.sync.transport.OneShotAuthHttpsOutcome
import ru.andriyshkoy.lifeagent.data.sync.transport.OneShotAuthHttpsProtocolFailure
import ru.andriyshkoy.lifeagent.data.sync.transport.OneShotAuthHttpsRawResponse
import ru.andriyshkoy.lifeagent.data.sync.transport.OneShotAuthHttpsTransport
import ru.andriyshkoy.lifeagent.data.sync.wire.BootstrapRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.DecodedApiError
import ru.andriyshkoy.lifeagent.data.sync.wire.DecodedWireResponse
import ru.andriyshkoy.lifeagent.data.sync.wire.EnrollmentClaimRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.EnrollmentClaimSuccess
import ru.andriyshkoy.lifeagent.data.sync.wire.EnrollmentResponseExpectation
import ru.andriyshkoy.lifeagent.data.sync.wire.RefreshRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.RefreshResponseExpectation
import ru.andriyshkoy.lifeagent.data.sync.wire.RefreshSuccess
import ru.andriyshkoy.lifeagent.data.sync.wire.WipeableSecret
import ru.andriyshkoy.lifeagent.data.sync.wire.WireRequestCodec
import ru.andriyshkoy.lifeagent.data.sync.wire.WireResponseCodec

/**
 * Lazy caller-owned construction point; AppContainer wiring is a later slice.
 * Construction never opens [transports]. The application graph supplies the
 * same instance to protected exact dispatch so both transports share one
 * validated configuration and pinned client after the first real exchange.
 */
internal fun createProductionM2AuthRuntime(
    context: Context,
    database: LifeAgentDatabase,
    accessTokenVault: AccessTokenVault,
    transports: LazyProductionM2HttpsTransportBundle,
    uuidGenerator: UuidGenerator = RandomUuidGenerator,
    clock: Clock = Clock.systemUTC(),
    policy: M2AuthRuntimePolicy = M2AuthRuntimePolicy(),
): M2AuthRuntime {
    val credentials = ProductionM2AuthCredentialBoundary(
        refreshTokenProtector = RefreshTokenProtector(context),
        fingerprintKeyring = KeystoreCredentialTokenHmacKeyring(context),
    )
    return M2AuthRuntime(
        persistence = RoomM2AuthPersistenceBoundary(context, database),
        credentials = credentials,
        exchange = ProductionM2AuthExchangeBoundary(
            LazyProductionM2AuthHttpsTransportProvider(transports),
        ),
        vault = ProductionM2AuthAccessVaultBoundary(accessTokenVault),
        uuidGenerator = uuidGenerator,
        clock = clock,
        policy = policy,
    )
}

/** Opens the shared pinned auth transport only for an actual auth exchange. */
internal fun interface M2AuthHttpsTransportProvider {
    fun open(): OneShotAuthHttpsTransport
}

internal class LazyProductionM2AuthHttpsTransportProvider(
    private val transports: LazyProductionM2HttpsTransportBundle,
) : M2AuthHttpsTransportProvider {
    override fun open(): OneShotAuthHttpsTransport = transports.open().auth

    override fun toString(): String =
        "LazyProductionM2AuthHttpsTransportProvider(redacted=true)"
}

internal class RoomM2AuthPersistenceBoundary(
    context: Context,
    database: LifeAgentDatabase,
) : M2AuthPersistenceBoundary {
    private val auth = SyncAuthPersistenceStore(database)
    private val protectedRequests = ProtectedSyncRequestStore(context, database)

    override suspend fun beginEnrollment(
        requestId: String,
        createdAt: Instant,
        hmacKeyGeneration: Int,
    ): EnrollmentAttemptBinding? = auth.beginEnrollmentAttempt(
        requestId = requestId,
        createdAt = createdAt,
        hmacKeyGeneration = hmacKeyGeneration,
    )

    override suspend fun readAccess(): AccessRecoveryBinding? =
        auth.readAccessRecoveryBinding()

    override suspend fun beginRefresh(
        requestId: String,
        expected: AccessRecoveryBinding,
        now: Instant,
        hmacKeyGeneration: Int,
    ): RefreshAttemptBinding? = auth.beginRefreshAttempt(
        requestId = requestId,
        expected = expected,
        now = now,
        hmacKeyGeneration = hmacKeyGeneration,
    )

    override suspend fun commitEnrollment(
        installation: PreparedEnrollmentInstallation,
    ) {
        protectedRequests.commitEnrollmentSuccess(
            bundle = installation.persistence,
            bootstrapRequest = installation.bootstrapRequest,
            persistence = installation.bootstrapPersistence,
        )
    }

    override suspend fun commitRefresh(installation: PreparedRefreshInstallation) {
        auth.commitRefreshSuccess(installation.persistence)
    }

    override suspend fun enrollmentRejected(
        requestId: String,
        updatedAtUtc: String,
        failureCode: String,
    ) {
        auth.commitEnrollmentTerminalFailure(requestId, updatedAtUtc, failureCode)
    }

    override suspend fun enrollmentUnknown(
        requestId: String,
        updatedAtUtc: String,
        failureCode: String,
    ) {
        auth.commitEnrollmentOutcomeUnknown(requestId, updatedAtUtc, failureCode)
    }

    override suspend fun refreshRejected(
        requestId: String,
        updatedAtUtc: String,
        failureCode: String,
    ) {
        auth.commitRefreshTerminalFailure(requestId, updatedAtUtc, failureCode)
    }

    override suspend fun refreshUnknown(
        requestId: String,
        updatedAtUtc: String,
        failureCode: String,
    ) {
        auth.commitRefreshOutcomeUnknown(requestId, updatedAtUtc, failureCode)
    }

    override suspend fun recoverInterrupted(
        updatedAtUtc: String,
    ): InterruptedAuthRecoveryResult =
        auth.recoverInterruptedAuthFlows(updatedAtUtc)

    override fun toString(): String = "RoomM2AuthPersistenceBoundary(redacted=true)"
}

internal class ProductionM2AuthExchangeBoundary(
    private val transportProvider: M2AuthHttpsTransportProvider,
) : M2AuthExchangeBoundary {
    private val transport: OneShotAuthHttpsTransport by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
        transportProvider::open,
    )

    override suspend fun prepareTransport(): M2AuthTransportReadiness = try {
        transport
        M2AuthTransportReadiness.READY
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        M2AuthTransportReadiness.LOCAL_UNAVAILABLE
    }

    override suspend fun enroll(
        binding: EnrollmentAttemptBinding,
        ownedEnrollmentCode: WipeableSecret,
        replaceActiveDevice: Boolean,
    ): EnrollmentExchangeOutcome {
        val request = EnrollmentClaimRequest(
            requestId = binding.requestId,
            enrollmentCode = ownedEnrollmentCode,
            installationId = binding.installationId,
            localOwnerId = binding.localOwnerId,
            replaceActiveDevice = replaceActiveDevice,
        )
        return request.use {
            val materialized = try {
                WireRequestCodec.materialize(request)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return EnrollmentExchangeOutcome.LocalFailure
            }
            materialized.use {
                val outcome = execute(materialized)
                    ?: return EnrollmentExchangeOutcome.Ambiguous
                outcome.use {
                    when (outcome) {
                        is OneShotAuthHttpsRawResponse -> decodeEnrollment(
                            raw = outcome,
                            expectation = EnrollmentResponseExpectation(
                                request = request,
                                expectedStableDeviceId = binding.expectedStableDeviceId,
                                expectedStablePersonId = binding.expectedStablePersonId,
                            ),
                        )

                        is OneShotAuthHttpsNetworkFailure,
                        is OneShotAuthHttpsProtocolFailure,
                        -> EnrollmentExchangeOutcome.Ambiguous
                    }
                }
            }
        }
    }

    override suspend fun refresh(
        binding: RefreshAttemptBinding,
        ownedRefreshToken: WipeableSecret,
    ): RefreshExchangeOutcome {
        val request = RefreshRequest(
            requestId = binding.requestId,
            deviceId = binding.deviceId,
            generation = binding.generation,
            refreshToken = ownedRefreshToken,
        )
        return request.use {
            val materialized = try {
                WireRequestCodec.materialize(request)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return RefreshExchangeOutcome.LocalFailure
            }
            materialized.use {
                val outcome = execute(materialized)
                    ?: return RefreshExchangeOutcome.Ambiguous
                outcome.use {
                    when (outcome) {
                        is OneShotAuthHttpsRawResponse -> decodeRefresh(
                            raw = outcome,
                            expectation = RefreshResponseExpectation(
                                request = request,
                                expectedFamilyExpiresAt = binding.familyExpiresAtUtc,
                                previouslyIssuedTokenSha256 = emptySet(),
                            ),
                        )

                        is OneShotAuthHttpsNetworkFailure,
                        is OneShotAuthHttpsProtocolFailure,
                        -> RefreshExchangeOutcome.Ambiguous
                    }
                }
            }
        }
    }

    private suspend fun execute(
        materialized: ru.andriyshkoy.lifeagent.data.sync.wire.MaterializedWireRequest,
    ): OneShotAuthHttpsOutcome? = try {
        transport.execute(materialized)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun decodeEnrollment(
        raw: OneShotAuthHttpsRawResponse,
        expectation: EnrollmentResponseExpectation,
    ): EnrollmentExchangeOutcome {
        val decoded = decode(raw, expectation)
            ?: return EnrollmentExchangeOutcome.Ambiguous
        return when (decoded) {
            is EnrollmentClaimSuccess -> EnrollmentExchangeOutcome.Accepted(decoded)
            is DecodedApiError -> EnrollmentExchangeOutcome.Rejected
            else -> {
                closeUnexpected(decoded)
                EnrollmentExchangeOutcome.Ambiguous
            }
        }
    }

    private fun decodeRefresh(
        raw: OneShotAuthHttpsRawResponse,
        expectation: RefreshResponseExpectation,
    ): RefreshExchangeOutcome {
        val decoded = decode(raw, expectation)
            ?: return RefreshExchangeOutcome.Ambiguous
        return when (decoded) {
            is RefreshSuccess -> RefreshExchangeOutcome.Accepted(decoded)
            is DecodedApiError -> RefreshExchangeOutcome.Rejected
            else -> {
                closeUnexpected(decoded)
                RefreshExchangeOutcome.Ambiguous
            }
        }
    }

    private fun decode(
        raw: OneShotAuthHttpsRawResponse,
        expectation: ru.andriyshkoy.lifeagent.data.sync.wire.ResponseExpectation,
    ): DecodedWireResponse? = try {
        raw.consumeBody { body ->
            WireResponseCodec.decode(
                httpStatus = raw.httpStatus,
                body = body,
                expectation = expectation,
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun closeUnexpected(decoded: DecodedWireResponse) {
        (decoded as? AutoCloseable)?.close()
    }

    override fun toString(): String = "ProductionM2AuthExchangeBoundary(redacted=true)"
}

internal class ProductionM2AuthCredentialBoundary(
    private val refreshTokenProtector: RefreshTokenProtector,
    private val fingerprintKeyring: KeystoreCredentialTokenHmacKeyring,
) : M2AuthCredentialBoundary {
    override val hmacKeyGeneration: Int
        get() = fingerprintKeyring.currentGeneration

    override fun preflightFingerprintKey(durableReferenceCount: Long) {
        fingerprintKeyring.provisionCurrentKey(durableReferenceCount)
    }

    override fun openRefreshToken(binding: RefreshAttemptBinding): WipeableSecret {
        val ciphertext = binding.copyRefreshTokenCiphertext()
        val nonce = binding.copyRefreshTokenNonce()
        return try {
            refreshTokenProtector.open(
                credentialEpochId = binding.credentialEpochId,
                deviceId = binding.deviceId,
                generation = binding.generation,
                envelope = RefreshTokenEnvelope(
                    refreshTokenCiphertext = ciphertext,
                    refreshTokenNonce = nonce,
                    refreshTokenKeyAlias = binding.refreshTokenKeyAlias,
                    refreshTokenKeyGeneration = binding.refreshTokenKeyGeneration,
                    refreshTokenAadVersion = binding.refreshTokenAadVersion,
                ),
            )
        } finally {
            ciphertext.fill(0)
            nonce.fill(0)
        }
    }

    override fun prepareEnrollment(
        binding: EnrollmentAttemptBinding,
        response: EnrollmentClaimSuccess,
        identifiers: EnrollmentInstallationIdentifiers,
        committedAt: Instant,
        policy: M2AuthRuntimePolicy,
    ): PreparedEnrollmentInstallation {
        check(
            response.requestId == binding.requestId &&
                response.installationId == binding.installationId &&
                response.localOwnerId == binding.localOwnerId &&
                response.bootstrapRequired,
        ) {
            "Enrollment response lost its dispatch binding"
        }
        val generation = response.credentials.generation
        check(generation == 1L)
        var accessToken: WipeableSecret? = null
        var envelope: RefreshTokenEnvelope? = null
        var accessFingerprint: ByteArray? = null
        var refreshFingerprint: ByteArray? = null
        try {
            accessToken = response.credentials.useAccessToken(WipeableSecret::copyOf)
            accessFingerprint = response.credentials.useAccessToken(
                fingerprintKeyring::fingerprintAccess,
            )
            refreshFingerprint = response.credentials.useRefreshToken(
                fingerprintKeyring::fingerprintRefresh,
            )
            val ownedRefresh = response.credentials.useRefreshToken(WipeableSecret::copyOf)
            envelope = refreshTokenProtector.seal(
                credentialEpochId = identifiers.credentialEpochId,
                deviceId = response.deviceId,
                generation = generation,
                durableReferenceCount = 0,
                ownedRefreshToken = ownedRefresh,
            )
            val committedAtUtc = committedAt.toString()
            val accessExpires = Instant.parse(response.credentials.accessExpiresAt)
            val refreshExpires = Instant.parse(response.credentials.refreshExpiresAt)
            val familyExpires = Instant.parse(response.credentials.familyExpiresAt)
            val auth = SyncAuthStateEntity(
                credentialEpochId = identifiers.credentialEpochId,
                installationId = binding.installationId,
                localOwnerId = binding.localOwnerId,
                deviceId = response.deviceId,
                personId = response.personId,
                tokenType = "Bearer",
                refreshTokenCiphertext = envelope.refreshTokenCiphertext,
                refreshTokenNonce = envelope.refreshTokenNonce,
                refreshTokenKeyAlias = envelope.refreshTokenKeyAlias,
                refreshTokenKeyGeneration = envelope.refreshTokenKeyGeneration,
                refreshTokenAadVersion = envelope.refreshTokenAadVersion,
                accessExpiresAtUtc = accessExpires.toString(),
                accessExpiresAtEpochMs = accessExpires.toEpochMilli(),
                refreshExpiresAtUtc = refreshExpires.toString(),
                refreshExpiresAtEpochMs = refreshExpires.toEpochMilli(),
                familyExpiresAtUtc = familyExpires.toString(),
                familyExpiresAtEpochMs = familyExpires.toEpochMilli(),
                generation = generation,
                state = "active",
                bootstrapRequired = true,
                installedAtUtc = committedAtUtc,
                updatedAtUtc = committedAtUtc,
                failureCode = null,
            )
            val bundle = EnrollmentSuccessPersistence(
                attemptRequestId = binding.requestId,
                authState = auth,
                accessFingerprint = fingerprint(
                    epoch = identifiers.credentialEpochId,
                    generation = generation,
                    kind = "access",
                    hmac = checkNotNull(accessFingerprint),
                    createdAtUtc = committedAtUtc,
                ),
                refreshFingerprint = fingerprint(
                    epoch = identifiers.credentialEpochId,
                    generation = generation,
                    kind = "refresh",
                    hmac = checkNotNull(refreshFingerprint),
                    createdAtUtc = committedAtUtc,
                ),
                streamState = SyncStreamStateEntity(
                    credentialEpochId = identifiers.credentialEpochId,
                    deviceId = response.deviceId,
                    phase = "bootstrap_required",
                    bootstrapRequired = true,
                    appliedCursor = null,
                    lastAppliedServerSequence = 0,
                    highWatermarkHint = null,
                    integrityErrorCode = null,
                    updatedAtUtc = committedAtUtc,
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
                    stagedPageCount = 0,
                    stagedBodyBytes = 0,
                    createdAtUtc = committedAtUtc,
                    updatedAtUtc = committedAtUtc,
                ),
            )
            val installation = PreparedEnrollmentInstallation(
                persistence = bundle,
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
                    deadlineAtEpochMs = Math.addExact(
                        committedAt.toEpochMilli(),
                        policy.bootstrapRequestLifetimeMillis,
                    ),
                    createdAtUtc = committedAtUtc,
                ),
                accessToken = checkNotNull(accessToken),
            )
            accessToken = null
            envelope = null
            accessFingerprint = null
            refreshFingerprint = null
            return installation
        } finally {
            accessToken?.close()
            envelope?.wipe()
            accessFingerprint?.fill(0)
            refreshFingerprint?.fill(0)
        }
    }

    override fun prepareRefresh(
        binding: RefreshAttemptBinding,
        response: RefreshSuccess,
        committedAt: Instant,
    ): PreparedRefreshInstallation {
        val successorGeneration = Math.addExact(binding.generation, 1L)
        check(
            response.requestId == binding.requestId &&
                response.deviceId == binding.deviceId &&
                response.credentials.generation == successorGeneration &&
                response.credentials.familyExpiresAt == binding.familyExpiresAtUtc,
        ) {
            "Refresh response lost its dispatch binding"
        }
        var accessToken: WipeableSecret? = null
        var envelope: RefreshTokenEnvelope? = null
        var accessFingerprint: ByteArray? = null
        var refreshFingerprint: ByteArray? = null
        try {
            accessToken = response.credentials.useAccessToken(WipeableSecret::copyOf)
            accessFingerprint = response.credentials.useAccessToken(
                fingerprintKeyring::fingerprintAccess,
            )
            refreshFingerprint = response.credentials.useRefreshToken(
                fingerprintKeyring::fingerprintRefresh,
            )
            val ownedRefresh = response.credentials.useRefreshToken(WipeableSecret::copyOf)
            envelope = refreshTokenProtector.seal(
                credentialEpochId = binding.credentialEpochId,
                deviceId = binding.deviceId,
                generation = successorGeneration,
                durableReferenceCount = binding.durableRefreshTokenReferenceCount,
                ownedRefreshToken = ownedRefresh,
            )
            val committedAtUtc = committedAt.toString()
            val accessExpires = Instant.parse(response.credentials.accessExpiresAt)
            val refreshExpires = Instant.parse(response.credentials.refreshExpiresAt)
            val familyExpires = Instant.parse(response.credentials.familyExpiresAt)
            val installation = PreparedRefreshInstallation(
                persistence = RefreshSuccessPersistence(
                    attemptRequestId = binding.requestId,
                    credentialEpochId = binding.credentialEpochId,
                    deviceId = binding.deviceId,
                    expectedGeneration = binding.generation,
                    successorGeneration = successorGeneration,
                    refreshTokenCiphertext = envelope.refreshTokenCiphertext,
                    refreshTokenNonce = envelope.refreshTokenNonce,
                    refreshTokenKeyAlias = envelope.refreshTokenKeyAlias,
                    refreshTokenKeyGeneration = envelope.refreshTokenKeyGeneration,
                    refreshTokenAadVersion = envelope.refreshTokenAadVersion,
                    accessExpiresAtUtc = accessExpires.toString(),
                    accessExpiresAtEpochMs = accessExpires.toEpochMilli(),
                    refreshExpiresAtUtc = refreshExpires.toString(),
                    refreshExpiresAtEpochMs = refreshExpires.toEpochMilli(),
                    familyExpiresAtUtc = familyExpires.toString(),
                    familyExpiresAtEpochMs = familyExpires.toEpochMilli(),
                    committedAtUtc = committedAtUtc,
                    committedAtEpochMs = committedAt.toEpochMilli(),
                    accessFingerprint = fingerprint(
                        epoch = binding.credentialEpochId,
                        generation = successorGeneration,
                        kind = "access",
                        hmac = checkNotNull(accessFingerprint),
                        createdAtUtc = committedAtUtc,
                    ),
                    refreshFingerprint = fingerprint(
                        epoch = binding.credentialEpochId,
                        generation = successorGeneration,
                        kind = "refresh",
                        hmac = checkNotNull(refreshFingerprint),
                        createdAtUtc = committedAtUtc,
                    ),
                ),
                accessToken = checkNotNull(accessToken),
            )
            accessToken = null
            envelope = null
            accessFingerprint = null
            refreshFingerprint = null
            return installation
        } finally {
            accessToken?.close()
            envelope?.wipe()
            accessFingerprint?.fill(0)
            refreshFingerprint?.fill(0)
        }
    }

    private fun fingerprint(
        epoch: String,
        generation: Long,
        kind: String,
        hmac: ByteArray,
        createdAtUtc: String,
    ) = SyncAuthTokenFingerprintEntity(
        credentialEpochId = epoch,
        generation = generation,
        tokenKind = kind,
        tokenHmac = hmac,
        hmacKeyGeneration = hmacKeyGeneration,
        createdAtUtc = createdAtUtc,
    )

    private fun RefreshTokenEnvelope.wipe() {
        refreshTokenCiphertext.fill(0)
        refreshTokenNonce.fill(0)
    }

    override fun toString(): String = "ProductionM2AuthCredentialBoundary(redacted=true)"
}

internal class ProductionM2AuthAccessVaultBoundary(
    private val vault: AccessTokenVault,
) : M2AuthAccessVaultBoundary {
    override fun contains(key: AccessTokenKey): Boolean =
        vault.claim(key)?.use { true } ?: false

    override fun replace(key: AccessTokenKey, ownedAccessToken: WipeableSecret) {
        vault.replace(key, ownedAccessToken)
    }

    override fun revoke(key: AccessTokenKey): Boolean = vault.revoke(key)

    override fun revokeEpoch(credentialEpochId: String): Int =
        vault.revokeEpoch(credentialEpochId)

    override fun clear() = vault.clear()

    override fun toString(): String = "ProductionM2AuthAccessVaultBoundary(redacted=true)"
}
