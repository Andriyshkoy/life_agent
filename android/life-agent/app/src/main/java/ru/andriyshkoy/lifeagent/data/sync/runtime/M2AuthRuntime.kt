package ru.andriyshkoy.lifeagent.data.sync.runtime

import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import ru.andriyshkoy.lifeagent.core.id.RandomUuidGenerator
import ru.andriyshkoy.lifeagent.core.id.UuidGenerator
import ru.andriyshkoy.lifeagent.data.local.db.AccessRecoveryBinding
import ru.andriyshkoy.lifeagent.data.local.db.EnrollmentAttemptBinding
import ru.andriyshkoy.lifeagent.data.local.db.EnrollmentSuccessPersistence
import ru.andriyshkoy.lifeagent.data.local.db.InterruptedAuthRecoveryResult
import ru.andriyshkoy.lifeagent.data.local.db.RefreshAttemptBinding
import ru.andriyshkoy.lifeagent.data.local.db.RefreshSuccessPersistence
import ru.andriyshkoy.lifeagent.data.security.NewDurableRequestPersistence
import ru.andriyshkoy.lifeagent.data.sync.wire.BootstrapRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.EnrollmentClaimSuccess
import ru.andriyshkoy.lifeagent.data.sync.wire.RefreshSuccess
import ru.andriyshkoy.lifeagent.data.sync.wire.WipeableSecret
import ru.andriyshkoy.lifeagent.data.sync.wire.requireEnrollmentCode

internal enum class AuthAccessSource {
    VAULT,
    ENROLLMENT,
    REFRESH,
}

/** Closed, content-free outcomes safe to expose to a coordinator or UI. */
internal sealed interface M2AuthRuntimeResult {
    class AccessReady(
        val key: AccessTokenKey,
        val source: AuthAccessSource,
    ) : M2AuthRuntimeResult {
        override fun toString(): String =
            "M2AuthRuntimeResult.AccessReady(source=$source,redacted=true)"
    }

    class DurableCredentialsCommitted(
        val key: AccessTokenKey,
    ) : M2AuthRuntimeResult {
        override fun toString(): String =
            "M2AuthRuntimeResult.DurableCredentialsCommitted(redacted=true)"
    }

    class RecoveryComplete(
        val recoveredCount: Int,
    ) : M2AuthRuntimeResult {
        init {
            require(recoveredCount >= 0)
        }

        override fun toString(): String =
            "M2AuthRuntimeResult.RecoveryComplete(count=$recoveredCount,redacted=true)"
    }

    data object Busy : M2AuthRuntimeResult {
        override fun toString(): String = "M2AuthRuntimeResult.Busy(redacted=true)"
    }

    data object Unenrolled : M2AuthRuntimeResult {
        override fun toString(): String = "M2AuthRuntimeResult.Unenrolled(redacted=true)"
    }

    data object Rejected : M2AuthRuntimeResult {
        override fun toString(): String = "M2AuthRuntimeResult.Rejected(redacted=true)"
    }

    data object ManualReenrollmentRequired : M2AuthRuntimeResult {
        override fun toString(): String =
            "M2AuthRuntimeResult.ManualReenrollmentRequired(redacted=true)"
    }

    data object LocalUnavailable : M2AuthRuntimeResult {
        override fun toString(): String =
            "M2AuthRuntimeResult.LocalUnavailable(redacted=true)"
    }
}

internal sealed interface EnrollmentExchangeOutcome : AutoCloseable {
    override fun close() = Unit

    class Accepted(
        val response: EnrollmentClaimSuccess,
    ) : EnrollmentExchangeOutcome {
        override fun close() = response.close()
        override fun toString(): String = "EnrollmentExchangeOutcome.Accepted(redacted=true)"
    }

    data object Rejected : EnrollmentExchangeOutcome {
        override fun toString(): String = "EnrollmentExchangeOutcome.Rejected(redacted=true)"
    }

    data object LocalFailure : EnrollmentExchangeOutcome {
        override fun toString(): String = "EnrollmentExchangeOutcome.LocalFailure(redacted=true)"
    }

    data object Ambiguous : EnrollmentExchangeOutcome {
        override fun toString(): String = "EnrollmentExchangeOutcome.Ambiguous(redacted=true)"
    }
}

internal sealed interface RefreshExchangeOutcome : AutoCloseable {
    override fun close() = Unit

    class Accepted(
        val response: RefreshSuccess,
    ) : RefreshExchangeOutcome {
        override fun close() = response.close()
        override fun toString(): String = "RefreshExchangeOutcome.Accepted(redacted=true)"
    }

    data object Rejected : RefreshExchangeOutcome {
        override fun toString(): String = "RefreshExchangeOutcome.Rejected(redacted=true)"
    }

    data object LocalFailure : RefreshExchangeOutcome {
        override fun toString(): String = "RefreshExchangeOutcome.LocalFailure(redacted=true)"
    }

    data object Ambiguous : RefreshExchangeOutcome {
        override fun toString(): String = "RefreshExchangeOutcome.Ambiguous(redacted=true)"
    }
}

/** Content-free readiness result checked before a durable auth attempt. */
internal enum class M2AuthTransportReadiness {
    READY,
    LOCAL_UNAVAILABLE,
}

/** Implementations take ownership of every secret argument on entry. */
internal interface M2AuthExchangeBoundary {
    suspend fun prepareTransport(): M2AuthTransportReadiness

    suspend fun enroll(
        binding: EnrollmentAttemptBinding,
        ownedEnrollmentCode: WipeableSecret,
        replaceActiveDevice: Boolean,
    ): EnrollmentExchangeOutcome

    suspend fun refresh(
        binding: RefreshAttemptBinding,
        ownedRefreshToken: WipeableSecret,
    ): RefreshExchangeOutcome
}

internal interface M2AuthPersistenceBoundary {
    suspend fun beginEnrollment(
        requestId: String,
        createdAt: Instant,
        hmacKeyGeneration: Int,
    ): EnrollmentAttemptBinding?

    suspend fun readAccess(): AccessRecoveryBinding?

    suspend fun beginRefresh(
        requestId: String,
        expected: AccessRecoveryBinding,
        now: Instant,
        hmacKeyGeneration: Int,
    ): RefreshAttemptBinding?

    suspend fun commitEnrollment(installation: PreparedEnrollmentInstallation)
    suspend fun commitRefresh(installation: PreparedRefreshInstallation)
    suspend fun enrollmentRejected(requestId: String, updatedAtUtc: String, failureCode: String)
    suspend fun enrollmentUnknown(requestId: String, updatedAtUtc: String, failureCode: String)
    suspend fun refreshRejected(requestId: String, updatedAtUtc: String, failureCode: String)
    suspend fun refreshUnknown(requestId: String, updatedAtUtc: String, failureCode: String)
    suspend fun recoverInterrupted(updatedAtUtc: String): InterruptedAuthRecoveryResult
}

internal interface M2AuthCredentialBoundary {
    val hmacKeyGeneration: Int

    fun preflightFingerprintKey(durableReferenceCount: Long)
    fun openRefreshToken(binding: RefreshAttemptBinding): WipeableSecret

    fun prepareEnrollment(
        binding: EnrollmentAttemptBinding,
        response: EnrollmentClaimSuccess,
        identifiers: EnrollmentInstallationIdentifiers,
        committedAt: Instant,
        policy: M2AuthRuntimePolicy,
    ): PreparedEnrollmentInstallation

    fun prepareRefresh(
        binding: RefreshAttemptBinding,
        response: RefreshSuccess,
        committedAt: Instant,
    ): PreparedRefreshInstallation
}

internal interface M2AuthAccessVaultBoundary {
    fun contains(key: AccessTokenKey): Boolean
    fun replace(key: AccessTokenKey, ownedAccessToken: WipeableSecret)
    fun revoke(key: AccessTokenKey): Boolean
    fun revokeEpoch(credentialEpochId: String): Int
    fun clear()
}

internal data class M2AuthRuntimePolicy(
    val bootstrapPageSize: Int = 500,
    val bootstrapAttemptBudget: Int = 8,
    val bootstrapRequestLifetimeMillis: Long = 7L * 24L * 60L * 60L * 1_000L,
) {
    init {
        require(bootstrapPageSize in 1..500)
        require(bootstrapAttemptBudget in 1..64)
        require(bootstrapRequestLifetimeMillis > 0)
    }

    override fun toString(): String = "M2AuthRuntimePolicy(redacted=true)"
}

internal class EnrollmentInstallationIdentifiers(
    val credentialEpochId: String,
    val bootstrapId: String,
    val bootstrapRequestId: String,
) {
    override fun toString(): String = "EnrollmentInstallationIdentifiers(redacted=true)"
}

internal class PreparedEnrollmentInstallation(
    val persistence: EnrollmentSuccessPersistence,
    val bootstrapRequest: BootstrapRequest,
    val bootstrapPersistence: NewDurableRequestPersistence,
    private var accessToken: WipeableSecret?,
) : AutoCloseable {
    val accessKey = AccessTokenKey(
        credentialEpochId = persistence.authState.credentialEpochId,
        accessGeneration = persistence.authState.generation,
    )

    fun takeAccessToken(): WipeableSecret {
        val owned = checkNotNull(accessToken) { "Enrollment access token is unavailable" }
        accessToken = null
        return owned
    }

    override fun close() {
        accessToken?.close()
        accessToken = null
        persistence.authState.refreshTokenCiphertext?.fill(0)
        persistence.authState.refreshTokenNonce?.fill(0)
        persistence.accessFingerprint.tokenHmac.fill(0)
        persistence.refreshFingerprint.tokenHmac.fill(0)
    }

    override fun toString(): String = "PreparedEnrollmentInstallation(redacted=true)"
}

internal class PreparedRefreshInstallation(
    val persistence: RefreshSuccessPersistence,
    private var accessToken: WipeableSecret?,
) : AutoCloseable {
    val accessKey = AccessTokenKey(
        credentialEpochId = persistence.credentialEpochId,
        accessGeneration = persistence.successorGeneration,
    )

    fun takeAccessToken(): WipeableSecret {
        val owned = checkNotNull(accessToken) { "Refresh access token is unavailable" }
        accessToken = null
        return owned
    }

    override fun close() {
        accessToken?.close()
        accessToken = null
        persistence.refreshTokenCiphertext.fill(0)
        persistence.refreshTokenNonce.fill(0)
        persistence.accessFingerprint.tokenHmac.fill(0)
        persistence.refreshFingerprint.tokenHmac.fill(0)
    }

    override fun toString(): String = "PreparedRefreshInstallation(redacted=true)"
}

/**
 * Non-replayable production auth orchestration.
 *
 * A Room attempt is claimed before one exchange. Any ambiguous exchange is
 * quarantined and requires an explicit enrollment action; this type never
 * automatically replays enrollment or refresh.
 */
internal class M2AuthRuntime internal constructor(
    private val persistence: M2AuthPersistenceBoundary,
    private val credentials: M2AuthCredentialBoundary,
    private val exchange: M2AuthExchangeBoundary,
    private val vault: M2AuthAccessVaultBoundary,
    private val uuidGenerator: UuidGenerator = RandomUuidGenerator,
    private val clock: Clock = Clock.systemUTC(),
    private val policy: M2AuthRuntimePolicy = M2AuthRuntimePolicy(),
) {
    private val operationMutex = Mutex()

    suspend fun recoverInterruptedAuthFlows(): M2AuthRuntimeResult {
        if (!operationMutex.tryLock()) return M2AuthRuntimeResult.Busy
        return try {
            val recovery = persistence.recoverInterrupted(clock.instant().toString())
            if (recovery.currentAuthorityChanged) {
                vault.clear()
            }
            M2AuthRuntimeResult.RecoveryComplete(recovery.recoveredCount)
        } finally {
            operationMutex.unlock()
        }
    }

    suspend fun enroll(
        ownedEnrollmentCode: WipeableSecret,
        replaceActiveDevice: Boolean,
    ): M2AuthRuntimeResult {
        var retainedCode: WipeableSecret? = ownedEnrollmentCode
        if (!operationMutex.tryLock()) {
            retainedCode?.close()
            return M2AuthRuntimeResult.Busy
        }
        return try {
            try {
                requireEnrollmentCode(checkNotNull(retainedCode))
            } catch (_: Exception) {
                return M2AuthRuntimeResult.Rejected
            }
            val readiness = try {
                exchange.prepareTransport()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return M2AuthRuntimeResult.LocalUnavailable
            }
            if (readiness == M2AuthTransportReadiness.LOCAL_UNAVAILABLE) {
                return M2AuthRuntimeResult.LocalUnavailable
            }
            val requestId = uuidGenerator.next().toString()
            val startedAt = clock.instant()
            val binding = try {
                persistence.beginEnrollment(
                    requestId = requestId,
                    createdAt = startedAt,
                    hmacKeyGeneration = credentials.hmacKeyGeneration,
                )
            } catch (error: CancellationException) {
                settleEnrollmentUnknown(requestId, "auth_start_cancelled")
                throw error
            } catch (_: Exception) {
                return M2AuthRuntimeResult.LocalUnavailable
            } ?: return M2AuthRuntimeResult.Busy

            try {
                credentials.preflightFingerprintKey(
                    binding.credentialFingerprintReferenceCount,
                )
            } catch (_: Exception) {
                settleEnrollmentRejected(requestId, "auth_local_key_unavailable")
                return M2AuthRuntimeResult.LocalUnavailable
            }

            val codeForExchange = checkNotNull(retainedCode)
            retainedCode = null
            val outcome = try {
                exchange.enroll(
                    binding = binding,
                    ownedEnrollmentCode = codeForExchange,
                    replaceActiveDevice = replaceActiveDevice,
                )
            } catch (error: CancellationException) {
                settleEnrollmentUnknown(requestId, "auth_exchange_cancelled")
                binding.predecessorCredentialEpochId?.let(vault::revokeEpoch)
                throw error
            } catch (_: Exception) {
                settleEnrollmentUnknown(requestId, "auth_exchange_failed")
                binding.predecessorCredentialEpochId?.let(vault::revokeEpoch)
                return M2AuthRuntimeResult.ManualReenrollmentRequired
            } finally {
                codeForExchange.close()
            }
            outcome.use {
                when (it) {
                    is EnrollmentExchangeOutcome.Accepted ->
                        withContext(NonCancellable) {
                            installEnrollment(binding, it.response)
                        }

                    EnrollmentExchangeOutcome.Rejected,
                    EnrollmentExchangeOutcome.LocalFailure,
                    -> {
                        settleEnrollmentRejected(requestId, "auth_enrollment_rejected")
                        M2AuthRuntimeResult.Rejected
                    }

                    EnrollmentExchangeOutcome.Ambiguous -> {
                        settleEnrollmentUnknown(requestId, "auth_outcome_unknown")
                        binding.predecessorCredentialEpochId?.let(vault::revokeEpoch)
                        M2AuthRuntimeResult.ManualReenrollmentRequired
                    }
                }
            }
        } finally {
            retainedCode?.close()
            operationMutex.unlock()
        }
    }

    suspend fun ensureAccess(): M2AuthRuntimeResult {
        if (!operationMutex.tryLock()) return M2AuthRuntimeResult.Busy
        return try {
            val observed = try {
                persistence.readAccess()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return M2AuthRuntimeResult.LocalUnavailable
            } ?: return M2AuthRuntimeResult.Unenrolled
            val now = clock.instant()
            val currentKey = AccessTokenKey(
                credentialEpochId = observed.credentialEpochId,
                accessGeneration = observed.generation,
            )
            if (
                observed.state != ACTIVE ||
                observed.refreshExpiresAtEpochMs <= now.toEpochMilli() ||
                observed.familyExpiresAtEpochMs <= now.toEpochMilli()
            ) {
                vault.revokeEpoch(observed.credentialEpochId)
                return M2AuthRuntimeResult.ManualReenrollmentRequired
            }
            if (
                observed.accessExpiresAtEpochMs > now.toEpochMilli() &&
                vault.contains(currentKey)
            ) {
                return M2AuthRuntimeResult.AccessReady(
                    key = currentKey,
                    source = AuthAccessSource.VAULT,
                )
            }
            refresh(observed, now, currentKey)
        } finally {
            operationMutex.unlock()
        }
    }

    override fun toString(): String = "M2AuthRuntime(redacted=true)"

    private suspend fun refresh(
        observed: AccessRecoveryBinding,
        now: Instant,
        predecessorKey: AccessTokenKey,
    ): M2AuthRuntimeResult {
        val readiness = try {
            exchange.prepareTransport()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return M2AuthRuntimeResult.LocalUnavailable
        }
        if (readiness == M2AuthTransportReadiness.LOCAL_UNAVAILABLE) {
            return M2AuthRuntimeResult.LocalUnavailable
        }
        vault.revoke(predecessorKey)
        val requestId = uuidGenerator.next().toString()
        val binding = try {
            persistence.beginRefresh(
                requestId = requestId,
                expected = observed,
                now = now,
                hmacKeyGeneration = credentials.hmacKeyGeneration,
            )
        } catch (error: CancellationException) {
            settleRefreshUnknown(requestId, "auth_start_cancelled")
            vault.revokeEpoch(observed.credentialEpochId)
            throw error
        } catch (_: Exception) {
            return M2AuthRuntimeResult.LocalUnavailable
        } ?: return M2AuthRuntimeResult.Busy

        return binding.use {
            try {
                credentials.preflightFingerprintKey(
                    binding.credentialFingerprintReferenceCount,
                )
            } catch (_: Exception) {
                settleRefreshRejected(requestId, "auth_local_key_unavailable")
                vault.revokeEpoch(binding.credentialEpochId)
                return M2AuthRuntimeResult.ManualReenrollmentRequired
            }
            val refreshToken = try {
                credentials.openRefreshToken(binding)
            } catch (_: Exception) {
                settleRefreshRejected(requestId, "auth_refresh_envelope_invalid")
                vault.revokeEpoch(binding.credentialEpochId)
                return M2AuthRuntimeResult.ManualReenrollmentRequired
            }
            val outcome = try {
                exchange.refresh(
                    binding = binding,
                    ownedRefreshToken = refreshToken,
                )
            } catch (error: CancellationException) {
                settleRefreshUnknown(requestId, "auth_exchange_cancelled")
                vault.revokeEpoch(binding.credentialEpochId)
                throw error
            } catch (_: Exception) {
                settleRefreshUnknown(requestId, "auth_exchange_failed")
                vault.revokeEpoch(binding.credentialEpochId)
                return M2AuthRuntimeResult.ManualReenrollmentRequired
            } finally {
                refreshToken.close()
            }
            outcome.use {
                when (it) {
                    is RefreshExchangeOutcome.Accepted ->
                        withContext(NonCancellable) {
                            installRefresh(binding, it.response, predecessorKey)
                        }

                    RefreshExchangeOutcome.Rejected,
                    RefreshExchangeOutcome.LocalFailure,
                    -> {
                        settleRefreshRejected(requestId, "auth_refresh_rejected")
                        vault.revokeEpoch(binding.credentialEpochId)
                        M2AuthRuntimeResult.ManualReenrollmentRequired
                    }

                    RefreshExchangeOutcome.Ambiguous -> {
                        settleRefreshUnknown(requestId, "auth_outcome_unknown")
                        vault.revokeEpoch(binding.credentialEpochId)
                        M2AuthRuntimeResult.ManualReenrollmentRequired
                    }
                }
            }
        }
    }

    private suspend fun installEnrollment(
        binding: EnrollmentAttemptBinding,
        response: EnrollmentClaimSuccess,
    ): M2AuthRuntimeResult {
        val committedAt = clock.instant()
        val identifiers = nextEnrollmentIdentifiers(binding)
        var prepared: PreparedEnrollmentInstallation? = null
        var roomCommitted = false
        return try {
            prepared = credentials.prepareEnrollment(
                binding = binding,
                response = response,
                identifiers = identifiers,
                committedAt = committedAt,
                policy = policy,
            )
            persistence.commitEnrollment(checkNotNull(prepared))
            roomCommitted = true
            installEnrollmentAccess(checkNotNull(prepared), binding)
        } catch (_: Exception) {
            if (!roomCommitted) {
                settleEnrollmentUnknown(binding.requestId, "auth_install_failed")
                binding.predecessorCredentialEpochId?.let(vault::revokeEpoch)
                M2AuthRuntimeResult.ManualReenrollmentRequired
            } else {
                prepared?.let { vault.revoke(it.accessKey) }
                binding.predecessorCredentialEpochId?.let(vault::revokeEpoch)
                M2AuthRuntimeResult.DurableCredentialsCommitted(
                    checkNotNull(prepared).accessKey,
                )
            }
        } finally {
            prepared?.close()
        }
    }

    private fun installEnrollmentAccess(
        prepared: PreparedEnrollmentInstallation,
        binding: EnrollmentAttemptBinding,
    ): M2AuthRuntimeResult {
        val accessToken = prepared.takeAccessToken()
        return try {
            vault.replace(prepared.accessKey, accessToken)
            binding.predecessorCredentialEpochId?.let(vault::revokeEpoch)
            M2AuthRuntimeResult.AccessReady(
                key = prepared.accessKey,
                source = AuthAccessSource.ENROLLMENT,
            )
        } catch (_: Exception) {
            accessToken.close()
            vault.revoke(prepared.accessKey)
            binding.predecessorCredentialEpochId?.let(vault::revokeEpoch)
            M2AuthRuntimeResult.DurableCredentialsCommitted(prepared.accessKey)
        }
    }

    private suspend fun installRefresh(
        binding: RefreshAttemptBinding,
        response: RefreshSuccess,
        predecessorKey: AccessTokenKey,
    ): M2AuthRuntimeResult {
        var prepared: PreparedRefreshInstallation? = null
        var roomCommitted = false
        return try {
            prepared = credentials.prepareRefresh(
                binding = binding,
                response = response,
                committedAt = clock.instant(),
            )
            persistence.commitRefresh(checkNotNull(prepared))
            roomCommitted = true
            installRefreshAccess(checkNotNull(prepared), predecessorKey)
        } catch (_: Exception) {
            if (!roomCommitted) {
                settleRefreshUnknown(binding.requestId, "auth_install_failed")
                vault.revokeEpoch(binding.credentialEpochId)
                M2AuthRuntimeResult.ManualReenrollmentRequired
            } else {
                prepared?.let { vault.revoke(it.accessKey) }
                vault.revoke(predecessorKey)
                M2AuthRuntimeResult.DurableCredentialsCommitted(
                    checkNotNull(prepared).accessKey,
                )
            }
        } finally {
            prepared?.close()
        }
    }

    private fun installRefreshAccess(
        prepared: PreparedRefreshInstallation,
        predecessorKey: AccessTokenKey,
    ): M2AuthRuntimeResult {
        val accessToken = prepared.takeAccessToken()
        return try {
            vault.replace(prepared.accessKey, accessToken)
            vault.revoke(predecessorKey)
            M2AuthRuntimeResult.AccessReady(
                key = prepared.accessKey,
                source = AuthAccessSource.REFRESH,
            )
        } catch (_: Exception) {
            accessToken.close()
            vault.revoke(prepared.accessKey)
            vault.revoke(predecessorKey)
            M2AuthRuntimeResult.DurableCredentialsCommitted(prepared.accessKey)
        }
    }

    private fun nextEnrollmentIdentifiers(
        binding: EnrollmentAttemptBinding,
    ): EnrollmentInstallationIdentifiers {
        val epoch = uuidGenerator.next().toString()
        val bootstrap = uuidGenerator.next().toString()
        val request = uuidGenerator.next().toString()
        check(
            epoch != binding.predecessorCredentialEpochId &&
                setOf(epoch, bootstrap, request, binding.requestId).size == 4,
        ) {
            "Generated enrollment authority is not unique"
        }
        return EnrollmentInstallationIdentifiers(
            credentialEpochId = epoch,
            bootstrapId = bootstrap,
            bootstrapRequestId = request,
        )
    }

    private suspend fun settleEnrollmentRejected(requestId: String, failureCode: String) {
        withContext(NonCancellable) {
            runCatching {
                persistence.enrollmentRejected(
                    requestId,
                    clock.instant().toString(),
                    failureCode,
                )
            }
        }
    }

    private suspend fun settleEnrollmentUnknown(requestId: String, failureCode: String) {
        withContext(NonCancellable) {
            runCatching {
                persistence.enrollmentUnknown(
                    requestId,
                    clock.instant().toString(),
                    failureCode,
                )
            }
        }
    }

    private suspend fun settleRefreshRejected(requestId: String, failureCode: String) {
        withContext(NonCancellable) {
            runCatching {
                persistence.refreshRejected(
                    requestId,
                    clock.instant().toString(),
                    failureCode,
                )
            }
        }
    }

    private suspend fun settleRefreshUnknown(requestId: String, failureCode: String) {
        withContext(NonCancellable) {
            runCatching {
                persistence.refreshUnknown(
                    requestId,
                    clock.instant().toString(),
                    failureCode,
                )
            }
        }
    }

    private companion object {
        const val ACTIVE = "active"
    }
}
