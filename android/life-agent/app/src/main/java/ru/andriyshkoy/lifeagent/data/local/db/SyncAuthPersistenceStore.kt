package ru.andriyshkoy.lifeagent.data.local.db

import androidx.room.withTransaction
import java.time.Instant
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncAuthAttemptEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncAuthStateEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncAuthTokenFingerprintEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncBootstrapSessionEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncHttpRequestEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncStreamStateEntity

data class EnrollmentSuccessPersistence(
    val attemptRequestId: String,
    val authState: SyncAuthStateEntity,
    val accessFingerprint: SyncAuthTokenFingerprintEntity,
    val refreshFingerprint: SyncAuthTokenFingerprintEntity,
    val streamState: SyncStreamStateEntity,
    val bootstrapSession: SyncBootstrapSessionEntity,
)

data class RefreshSuccessPersistence(
    val attemptRequestId: String,
    val credentialEpochId: String,
    val deviceId: String,
    val expectedGeneration: Long,
    val successorGeneration: Long,
    val refreshTokenCiphertext: ByteArray,
    val refreshTokenNonce: ByteArray,
    val refreshTokenKeyAlias: String,
    val refreshTokenKeyGeneration: Int,
    val refreshTokenAadVersion: Int,
    val accessExpiresAtUtc: String,
    val accessExpiresAtEpochMs: Long,
    val refreshExpiresAtUtc: String,
    val refreshExpiresAtEpochMs: Long,
    val familyExpiresAtUtc: String,
    val familyExpiresAtEpochMs: Long,
    val committedAtUtc: String,
    val committedAtEpochMs: Long,
    val accessFingerprint: SyncAuthTokenFingerprintEntity,
    val refreshFingerprint: SyncAuthTokenFingerprintEntity,
)

enum class CredentialRecoveryAction {
    STALE_CALLBACK,
    RETRY_WITH_INSTALLED_GENERATION,
    WAITING_FOR_REFRESH,
    QUARANTINED,
}

/**
 * Owns multi-DAO auth transitions. Network code may prepare and validate wire
 * material, but an auth outcome is not installed until every related Room row
 * can commit together.
 */
class SyncAuthPersistenceStore(
    private val database: LifeAgentDatabase,
) {
    private val authDao = database.syncAuthDao()
    private val identityDao = database.identityDao()
    private val replicaDao = database.syncReplicaDao()
    private val transportDao = database.syncTransportDao()
    private val requestStore = SyncRequestPersistenceStore(database)

    suspend fun beginEnrollment(attempt: SyncAuthAttemptEntity) {
        require(attempt.endpointId == AUTH_ENROLL)
        require(attempt.state == DISPATCHING)
        require(attempt.lastErrorCode == null)
        requireExpectedTupleCoherence(attempt)
        database.withTransaction {
            val identity = checkNotNull(identityDao.findIdentity()) {
                "Enrollment requires a current local identity"
            }
            check(
                identity.installationId == attempt.installationId &&
                    identity.localOwnerId == attempt.localOwnerId,
            ) {
                "Enrollment attempt does not bind the current local identity"
            }
            check(
                authDao.findAttempts(AUTH_ENROLL, DISPATCHING).isEmpty(),
            ) {
                "Another enrollment attempt is already dispatching"
            }
            requireExpectedCurrentFamily(attempt, authDao.findState())
            authDao.insertAttempt(attempt)
        }
    }

    /**
     * Commits only the non-request enrollment projections. The caller must
     * already own the outer protected-request transaction and insert its
     * authenticated bootstrap row before that transaction can commit.
     */
    internal suspend fun commitEnrollmentSuccessState(
        bundle: EnrollmentSuccessPersistence,
    ) {
        check(database.inTransaction()) {
            "Enrollment success state requires the protected request transaction"
        }
        validateEnrollmentSuccessBundle(bundle)
        database.withTransaction {
            val attempt = checkNotNull(authDao.findAttempt(bundle.attemptRequestId)) {
                "Enrollment attempt is missing"
            }
            check(attempt.endpointId == AUTH_ENROLL && attempt.state == DISPATCHING) {
                "Enrollment response does not bind a dispatching attempt"
            }
            requireExpectedTupleCoherence(attempt)
            val identity = checkNotNull(identityDao.findIdentity()) {
                "Enrollment response has no current local identity"
            }
            check(
                identity.installationId == attempt.installationId &&
                    identity.localOwnerId == attempt.localOwnerId &&
                    bundle.authState.installationId == attempt.installationId &&
                    bundle.authState.localOwnerId == attempt.localOwnerId,
            ) {
                "Enrollment response does not bind the current local identity"
            }

            val predecessor = authDao.findState()
            requireExpectedCurrentFamily(attempt, predecessor)
            val predecessorStream = replicaDao.findStreamState()
            if (predecessor == null) {
                check(predecessorStream == null) {
                    "Initial enrollment found an orphan sync stream"
                }
                check(replicaDao.findBootstrapSessionWithActiveSlot() == null) {
                    "Initial enrollment found an orphan bootstrap shadow"
                }
            } else {
                check(
                    predecessor.installationId == attempt.installationId &&
                        predecessor.localOwnerId == attempt.localOwnerId &&
                        predecessor.deviceId == bundle.authState.deviceId &&
                        predecessor.personId == bundle.authState.personId &&
                        predecessor.credentialEpochId !=
                        bundle.authState.credentialEpochId,
                ) {
                    "Replacement enrollment must preserve identity and install a new epoch"
                }
                val oldStream = checkNotNull(predecessorStream) {
                    "Replacement enrollment has no predecessor sync stream"
                }
                check(
                    oldStream.credentialEpochId == predecessor.credentialEpochId &&
                        oldStream.deviceId == predecessor.deviceId,
                ) {
                    "Replacement enrollment predecessor stream drifted"
                }
            }
            check(
                identity.serverDeviceId == null ||
                    identity.serverDeviceId == bundle.authState.deviceId,
            ) {
                "Installation is already bound to another device"
            }
            check(
                identity.serverPersonId == null ||
                    identity.serverPersonId == bundle.authState.personId,
            ) {
                "Owner is already bound to another person"
            }

            identityDao.bindCurrentServerIdentity(
                installationId = attempt.installationId,
                localOwnerId = attempt.localOwnerId,
                deviceId = bundle.authState.deviceId,
                personId = bundle.authState.personId,
            )

            if (predecessor != null) {
                releaseOpenPushBatches(
                    credentialEpochId = predecessor.credentialEpochId,
                    deviceId = predecessor.deviceId,
                )
                transportDao.invalidateSupersededSyncRequests(
                    credentialEpochId = predecessor.credentialEpochId,
                    deviceId = predecessor.deviceId,
                    retainedBootstrapRequestIdentity = null,
                    terminalAtUtc = bundle.authState.updatedAtUtc,
                )
                val activeSession = replicaDao.findBootstrapSessionWithActiveSlot()
                if (activeSession != null) {
                    check(
                        activeSession.credentialEpochId ==
                            predecessor.credentialEpochId &&
                            activeSession.deviceId == predecessor.deviceId,
                    ) {
                        "Replacement enrollment cannot supersede another family"
                    }
                    check(
                        replicaDao.supersedeActiveBootstrapSession(
                            bootstrapId = activeSession.bootstrapId,
                            credentialEpochId = predecessor.credentialEpochId,
                            deviceId = predecessor.deviceId,
                            updatedAtUtc = bundle.authState.updatedAtUtc,
                        ) == 1,
                    ) {
                        "Predecessor bootstrap shadow lost its CAS"
                    }
                }
                check(
                    replicaDao.deleteExactStream(
                        credentialEpochId = predecessor.credentialEpochId,
                        deviceId = predecessor.deviceId,
                    ) == 1,
                ) {
                    "Predecessor sync stream lost its CAS"
                }
                check(
                    authDao.deleteExactFamily(
                        credentialEpochId = predecessor.credentialEpochId,
                        deviceId = predecessor.deviceId,
                        generation = predecessor.generation,
                    ) == 1,
                ) {
                    "Predecessor credential family lost its CAS"
                }
            }

            authDao.installEnrollment(
                state = bundle.authState,
                accessFingerprint = bundle.accessFingerprint,
                refreshFingerprint = bundle.refreshFingerprint,
            )
            replicaDao.insertStreamState(bundle.streamState)
            replicaDao.insertBootstrapSession(bundle.bootstrapSession)
            check(
                authDao.compareAndSetAttemptState(
                    requestId = attempt.requestId,
                    expectedState = DISPATCHING,
                    newState = COMPLETED,
                    updatedAtUtc = bundle.authState.updatedAtUtc,
                    lastErrorCode = null,
                ) == 1,
            ) {
                "Enrollment attempt lost its completion CAS"
            }
        }
    }

    suspend fun commitEnrollmentOutcomeUnknown(
        requestId: String,
        updatedAtUtc: String,
        failureCode: String = "auth_outcome_unknown",
    ) {
        require(failureCode.isNotBlank())
        database.withTransaction {
            quarantineEnrollmentAttempt(
                requestId = requestId,
                updatedAtUtc = updatedAtUtc,
                failureCode = failureCode,
            )
        }
    }

    suspend fun commitEnrollmentTerminalFailure(
        requestId: String,
        updatedAtUtc: String,
        failureCode: String,
    ) {
        require(failureCode.isNotBlank())
        database.withTransaction {
            val attempt = checkNotNull(authDao.findAttempt(requestId)) {
                "Enrollment attempt is missing"
            }
            check(attempt.endpointId == AUTH_ENROLL && attempt.state == DISPATCHING)
            requireExpectedTupleCoherence(attempt)
            requireExpectedCurrentFamily(attempt, authDao.findState())
            check(
                authDao.compareAndSetAttemptState(
                    requestId = requestId,
                    expectedState = DISPATCHING,
                    newState = FAILED,
                    updatedAtUtc = updatedAtUtc,
                    lastErrorCode = failureCode,
                ) == 1,
            )
        }
    }

    suspend fun commitRefreshSuccess(success: RefreshSuccessPersistence) {
        validateRefreshSuccess(success)
        database.withTransaction {
            val attempt = checkNotNull(authDao.findAttempt(success.attemptRequestId)) {
                "Refresh attempt is missing"
            }
            check(
                attempt.endpointId == AUTH_REFRESH &&
                    attempt.state == DISPATCHING &&
                    attempt.credentialEpochId == success.credentialEpochId &&
                    attempt.expectedDeviceId == success.deviceId &&
                    attempt.expectedGeneration == success.expectedGeneration,
            ) {
                "Refresh response does not bind the dispatching attempt"
            }
            val current = checkNotNull(authDao.findState()) {
                "Refresh credential family is missing"
            }
            val identity = checkNotNull(identityDao.findIdentity()) {
                "Refresh has no current local identity"
            }
            check(
                current.credentialEpochId == success.credentialEpochId &&
                    current.deviceId == success.deviceId &&
                    current.generation == success.expectedGeneration &&
                    current.state == REFRESH_IN_FLIGHT &&
                    current.installationId == identity.installationId &&
                    current.localOwnerId == identity.localOwnerId &&
                    attempt.installationId == identity.installationId &&
                    attempt.localOwnerId == identity.localOwnerId &&
                    current.familyExpiresAtUtc == success.familyExpiresAtUtc &&
                    current.familyExpiresAtEpochMs ==
                    success.familyExpiresAtEpochMs,
            ) {
                "Refresh response lost its current-family binding"
            }
            authDao.insertTokenFingerprint(success.accessFingerprint)
            authDao.insertTokenFingerprint(success.refreshFingerprint)
            check(
                authDao.compareAndInstallRefreshSuccess(
                    credentialEpochId = success.credentialEpochId,
                    deviceId = success.deviceId,
                    expectedGeneration = success.expectedGeneration,
                    nextGeneration = success.successorGeneration,
                    refreshTokenCiphertext = success.refreshTokenCiphertext,
                    refreshTokenNonce = success.refreshTokenNonce,
                    refreshTokenKeyAlias = success.refreshTokenKeyAlias,
                    refreshTokenKeyGeneration =
                        success.refreshTokenKeyGeneration,
                    refreshTokenAadVersion = success.refreshTokenAadVersion,
                    accessExpiresAtUtc = success.accessExpiresAtUtc,
                    accessExpiresAtEpochMs = success.accessExpiresAtEpochMs,
                    refreshExpiresAtUtc = success.refreshExpiresAtUtc,
                    refreshExpiresAtEpochMs = success.refreshExpiresAtEpochMs,
                    familyExpiresAtUtc = success.familyExpiresAtUtc,
                    familyExpiresAtEpochMs = success.familyExpiresAtEpochMs,
                    updatedAtUtc = success.committedAtUtc,
                ) == 1,
            ) {
                "Refresh successor lost its credential-family CAS"
            }
            transportDao.releaseExactWaitingRefreshRequests(
                credentialEpochId = success.credentialEpochId,
                deviceId = success.deviceId,
                failedAccessGeneration = success.expectedGeneration,
                successorGeneration = success.successorGeneration,
                nextAttemptAtEpochMs = success.committedAtEpochMs,
                updatedAtUtc = success.committedAtUtc,
            )
            transportDao.failExactWaitingRefreshRequests(
                credentialEpochId = success.credentialEpochId,
                deviceId = success.deviceId,
                failedAccessGeneration = success.expectedGeneration,
                terminalAtUtc = success.committedAtUtc,
                failureCode = "credential_recovery_expired",
            )
            check(
                authDao.compareAndSetAttemptState(
                    requestId = success.attemptRequestId,
                    expectedState = DISPATCHING,
                    newState = COMPLETED,
                    updatedAtUtc = success.committedAtUtc,
                    lastErrorCode = null,
                ) == 1,
            ) {
                "Refresh attempt lost its completion CAS"
            }
        }
    }

    suspend fun commitRefreshOutcomeUnknown(
        requestId: String,
        updatedAtUtc: String,
        failureCode: String,
    ) {
        require(failureCode.isNotBlank())
        database.withTransaction {
            quarantineRefreshAttempt(
                requestId = requestId,
                updatedAtUtc = updatedAtUtc,
                failureCode = failureCode,
                attemptTerminalState = OUTCOME_UNKNOWN,
            )
        }
    }

    suspend fun commitRefreshTerminalFailure(
        requestId: String,
        updatedAtUtc: String,
        failureCode: String,
    ) {
        require(failureCode.isNotBlank())
        database.withTransaction {
            quarantineRefreshAttempt(
                requestId = requestId,
                updatedAtUtc = updatedAtUtc,
                failureCode = failureCode,
                attemptTerminalState = FAILED,
            )
        }
    }

    suspend fun handleTrustedSyncUnauthorized(
        endpointId: String,
        requestIdentity: String,
        expectedAttemptId: String,
        failedAccessGeneration: Long,
        nowEpochMs: Long,
        nextAttemptAtEpochMs: Long,
        updatedAtUtc: String,
    ): CredentialRecoveryAction =
        database.withTransaction {
            handleTrustedSyncUnauthorizedInCurrentTransaction(
                endpointId = endpointId,
                requestIdentity = requestIdentity,
                expectedAttemptId = expectedAttemptId,
                failedAccessGeneration = failedAccessGeneration,
                nowEpochMs = nowEpochMs,
                nextAttemptAtEpochMs = nextAttemptAtEpochMs,
                updatedAtUtc = updatedAtUtc,
            )
        }

    internal suspend fun handleTrustedSyncUnauthorizedInCurrentTransaction(
        endpointId: String,
        requestIdentity: String,
        expectedAttemptId: String,
        failedAccessGeneration: Long,
        nowEpochMs: Long,
        nextAttemptAtEpochMs: Long,
        updatedAtUtc: String,
    ): CredentialRecoveryAction {
        check(database.inTransaction()) {
            "Unauthorized response reduction requires an outer transaction"
        }
        val request = checkNotNull(
            transportDao.findRequest(endpointId, requestIdentity),
        ) {
            "Unauthorized sync request is missing"
        }
        val storedGeneration = request.accessGenerationUsed
        if (
            request.state != SENDING ||
            request.activeAttemptId != expectedAttemptId ||
            (
                storedGeneration != null &&
                    storedGeneration > 0 &&
                    storedGeneration != failedAccessGeneration
                )
        ) {
            return CredentialRecoveryAction.STALE_CALLBACK
        }
        val current = authDao.findState()
        val identity = identityDao.findIdentity()
        if (
            current != null &&
            (
                identity == null ||
                    current.credentialEpochId != request.credentialEpochId ||
                    current.deviceId != request.deviceId ||
                    identity.installationId != current.installationId ||
                    identity.localOwnerId != current.localOwnerId
                )
        ) {
            return CredentialRecoveryAction.STALE_CALLBACK
        }
        if (!requestStore.preflightFreshResponseRequestMetadata(request, updatedAtUtc)) {
            return CredentialRecoveryAction.QUARANTINED
        }
        if (current == null) {
            check(
                markCredentialFailureTerminalInCurrentTransaction(
                    endpointId = endpointId,
                    requestIdentity = requestIdentity,
                    credentialEpochId = request.credentialEpochId,
                    failedAccessGeneration = failedAccessGeneration,
                    expectedAttemptId = expectedAttemptId,
                    terminalAtUtc = updatedAtUtc,
                    failureCode = "credential_family_missing",
                ) == 1,
            )
            return CredentialRecoveryAction.QUARANTINED
        }

            val requestCanRecover =
                request.attemptCount < request.attemptBudget &&
                    nowEpochMs < request.deadlineAtEpochMs &&
                    nextAttemptAtEpochMs < request.deadlineAtEpochMs
            val familyCanRefresh =
                current.state in setOf(ACTIVE, REFRESH_IN_FLIGHT) &&
                    current.refreshExpiresAtEpochMs > nowEpochMs &&
                    current.familyExpiresAtEpochMs > nowEpochMs
            val installedAccessCanDispatch =
                current.state == ACTIVE &&
                    current.accessExpiresAtEpochMs > nowEpochMs &&
                    current.familyExpiresAtEpochMs > nowEpochMs
            if (
                !requestCanRecover ||
                (
                    current.generation == failedAccessGeneration &&
                        !familyCanRefresh
                    ) ||
                (
                    current.generation > failedAccessGeneration &&
                        !installedAccessCanDispatch
                    )
            ) {
                check(
                    markCredentialFailureTerminalInCurrentTransaction(
                        endpointId = endpointId,
                        requestIdentity = requestIdentity,
                        credentialEpochId = request.credentialEpochId,
                        failedAccessGeneration = failedAccessGeneration,
                        expectedAttemptId = expectedAttemptId,
                        terminalAtUtc = updatedAtUtc,
                        failureCode = "credential_recovery_ineligible",
                    ) == 1,
                )
                if (current.state in setOf(ACTIVE, REFRESH_IN_FLIGHT)) {
                    check(
                        authDao.quarantine(
                            credentialEpochId = current.credentialEpochId,
                            generation = current.generation,
                            expectedState = current.state,
                            newState = QUARANTINED,
                            updatedAtUtc = updatedAtUtc,
                            failureCode = "credential_recovery_ineligible",
                        ) == 1,
                    )
                }
                return CredentialRecoveryAction.QUARANTINED
            }

            return when {
                request.originalRetryCount == 1 -> {
                    check(
                        markCredentialRecoveryExhaustedInCurrentTransaction(
                            endpointId = endpointId,
                            requestIdentity = requestIdentity,
                            credentialEpochId = request.credentialEpochId,
                            failedAccessGeneration = failedAccessGeneration,
                            expectedAttemptId = expectedAttemptId,
                            terminalAtUtc = updatedAtUtc,
                        ) == 1,
                    )
                    if (current.state in setOf(ACTIVE, REFRESH_IN_FLIGHT)) {
                        check(
                            authDao.quarantine(
                                credentialEpochId = current.credentialEpochId,
                                generation = current.generation,
                                expectedState = current.state,
                                newState = QUARANTINED,
                                updatedAtUtc = updatedAtUtc,
                                failureCode = "credential_recovery_exhausted",
                            ) == 1,
                        )
                    }
                    CredentialRecoveryAction.QUARANTINED
                }

                current.generation > failedAccessGeneration -> {
                    check(
                        transportDao.scheduleExactRetryWithInstalledGeneration(
                            endpointId = endpointId,
                            requestIdentity = requestIdentity,
                            credentialEpochId = request.credentialEpochId,
                            failedAccessGeneration = failedAccessGeneration,
                            installedGeneration = current.generation,
                            expectedAttemptId = expectedAttemptId,
                            nowEpochMs = nowEpochMs,
                            nextAttemptAtEpochMs = nextAttemptAtEpochMs,
                            updatedAtUtc = updatedAtUtc,
                        ) == 1,
                    ) {
                        "Stale-generation request lost its exact-retry CAS"
                    }
                    CredentialRecoveryAction.RETRY_WITH_INSTALLED_GENERATION
                }

                current.generation < failedAccessGeneration -> {
                    check(
                        markCredentialFailureTerminalInCurrentTransaction(
                            endpointId = endpointId,
                            requestIdentity = requestIdentity,
                            credentialEpochId = request.credentialEpochId,
                            failedAccessGeneration = failedAccessGeneration,
                            expectedAttemptId = expectedAttemptId,
                            terminalAtUtc = updatedAtUtc,
                            failureCode = "future_credential_generation",
                        ) == 1,
                    )
                    if (current.state in setOf(ACTIVE, REFRESH_IN_FLIGHT)) {
                        check(
                            authDao.quarantine(
                                credentialEpochId = current.credentialEpochId,
                                generation = current.generation,
                                expectedState = current.state,
                                newState = QUARANTINED,
                                updatedAtUtc = updatedAtUtc,
                                failureCode = "future_credential_generation",
                            ) == 1,
                        )
                    }
                    CredentialRecoveryAction.QUARANTINED
                }

                else -> {
                    check(
                        transportDao.waitForCredentialRefresh(
                            endpointId = endpointId,
                            requestIdentity = requestIdentity,
                            credentialEpochId = request.credentialEpochId,
                            failedAccessGeneration = failedAccessGeneration,
                            expectedAttemptId = expectedAttemptId,
                            nowEpochMs = nowEpochMs,
                            updatedAtUtc = updatedAtUtc,
                        ) == 1,
                    ) {
                        "Current-generation request lost its refresh-wait CAS"
                    }
                    CredentialRecoveryAction.WAITING_FOR_REFRESH
                }
            }
        }

    private suspend fun markCredentialFailureTerminalInCurrentTransaction(
        endpointId: String,
        requestIdentity: String,
        credentialEpochId: String,
        failedAccessGeneration: Long,
        expectedAttemptId: String,
        terminalAtUtc: String,
        failureCode: String,
    ): Int {
        check(database.inTransaction()) {
            "Credential failure terminalization requires an outer transaction"
        }
        val terminalized = transportDao.markCredentialFailureTerminalRow(
            endpointId = endpointId,
            requestIdentity = requestIdentity,
            credentialEpochId = credentialEpochId,
            failedAccessGeneration = failedAccessGeneration,
            expectedAttemptId = expectedAttemptId,
            terminalAtUtc = terminalAtUtc,
            failureCode = failureCode,
        )
        if (terminalized == 1 && endpointId == SYNC_PUSH) {
            transportDao.releaseTerminalPushBatch(requestIdentity)
        }
        return terminalized
    }

    private suspend fun markCredentialRecoveryExhaustedInCurrentTransaction(
        endpointId: String,
        requestIdentity: String,
        credentialEpochId: String,
        failedAccessGeneration: Long,
        expectedAttemptId: String,
        terminalAtUtc: String,
    ): Int {
        check(database.inTransaction()) {
            "Credential recovery terminalization requires an outer transaction"
        }
        val terminalized = transportDao.markCredentialRecoveryExhaustedRow(
            endpointId = endpointId,
            requestIdentity = requestIdentity,
            credentialEpochId = credentialEpochId,
            failedAccessGeneration = failedAccessGeneration,
            expectedAttemptId = expectedAttemptId,
            terminalAtUtc = terminalAtUtc,
        )
        if (terminalized == 1 && endpointId == SYNC_PUSH) {
            transportDao.releaseTerminalPushBatch(requestIdentity)
        }
        return terminalized
    }

    /**
     * A process death after a non-replayable refresh was dispatched is always
     * ambiguous. It is quarantined before any request can become runnable.
     */
    suspend fun recoverInterruptedAuthFlows(updatedAtUtc: String): Int =
        database.withTransaction {
            var recovered = 0
            val integrityRecovered = SyncRequestPersistenceStore(database)
                .recoverInvalidRequestMetadata(
                    terminalAtUtc = updatedAtUtc,
                    limit = MAX_INTEGRITY_RECOVERY_ROWS,
                )
            recovered += integrityRecovered
            if (integrityRecovered == MAX_INTEGRITY_RECOVERY_ROWS) {
                // Keep recovery bounded. The next invocation continues the
                // integrity queue before any full-row auth-recovery scan.
                return@withTransaction recovered
            }
            authDao.findAttempts(AUTH_ENROLL, DISPATCHING).forEach { attempt ->
                val current = authDao.findState()
                val exactPredecessor =
                    if (attempt.credentialEpochId == null) {
                        current == null
                    } else {
                        current?.credentialEpochId == attempt.credentialEpochId &&
                            current.deviceId == attempt.expectedDeviceId &&
                            current.generation == attempt.expectedGeneration
                    }
                if (exactPredecessor) {
                    quarantineEnrollmentAttempt(
                        requestId = attempt.requestId,
                        updatedAtUtc = updatedAtUtc,
                        failureCode = "enrollment_interrupted",
                    )
                } else {
                    authDao.compareAndSetAttemptState(
                        requestId = attempt.requestId,
                        expectedState = DISPATCHING,
                        newState = OUTCOME_UNKNOWN,
                        updatedAtUtc = updatedAtUtc,
                        lastErrorCode = "enrollment_interrupted_stale",
                    )
                }
                recovered += 1
            }
            authDao.findAttempts(AUTH_REFRESH, DISPATCHING).forEach { attempt ->
                val current = authDao.findState()
                val identity = identityDao.findIdentity()
                if (
                    current != null &&
                    identity != null &&
                    current.credentialEpochId == attempt.credentialEpochId &&
                    current.deviceId == attempt.expectedDeviceId &&
                    current.generation == attempt.expectedGeneration &&
                    current.state == REFRESH_IN_FLIGHT &&
                    current.installationId == identity.installationId &&
                    current.localOwnerId == identity.localOwnerId &&
                    attempt.installationId == identity.installationId &&
                    attempt.localOwnerId == identity.localOwnerId
                ) {
                    quarantineRefreshAttempt(
                        requestId = attempt.requestId,
                        updatedAtUtc = updatedAtUtc,
                        failureCode = "refresh_interrupted",
                        attemptTerminalState = OUTCOME_UNKNOWN,
                    )
                } else {
                    authDao.compareAndSetAttemptState(
                        requestId = attempt.requestId,
                        expectedState = DISPATCHING,
                        newState = OUTCOME_UNKNOWN,
                        updatedAtUtc = updatedAtUtc,
                        lastErrorCode = "refresh_interrupted_stale",
                    )
                }
                recovered += 1
            }
            val orphan = authDao.findState()
            if (orphan?.state == REFRESH_IN_FLIGHT) {
                check(
                    authDao.quarantine(
                        credentialEpochId = orphan.credentialEpochId,
                        generation = orphan.generation,
                        expectedState = REFRESH_IN_FLIGHT,
                        newState = QUARANTINED,
                        updatedAtUtc = updatedAtUtc,
                        failureCode = "orphan_refresh_in_flight",
                    ) == 1,
                )
                transportDao.failExactWaitingRefreshRequests(
                    credentialEpochId = orphan.credentialEpochId,
                    deviceId = orphan.deviceId,
                    failedAccessGeneration = orphan.generation,
                    terminalAtUtc = updatedAtUtc,
                    failureCode = "orphan_refresh_in_flight",
                )
                recovered += 1
            }
            transportDao.findWaitingRefreshRequests()
                .groupBy {
                    Triple(
                        it.credentialEpochId,
                        it.deviceId,
                        checkNotNull(it.accessGenerationUsed),
                    )
                }
                .forEach { (binding, _) ->
                    val (epoch, device, generation) = binding
                    val installed = authDao.findState()
                    if (
                        installed?.credentialEpochId == epoch &&
                        installed.deviceId == device &&
                        installed.generation == generation &&
                        installed.state in setOf(ACTIVE, REFRESH_IN_FLIGHT)
                    ) {
                        check(
                            authDao.quarantine(
                                credentialEpochId = epoch,
                                generation = generation,
                                expectedState = installed.state,
                                newState = QUARANTINED,
                                updatedAtUtc = updatedAtUtc,
                                failureCode = "orphan_waiting_refresh",
                            ) == 1,
                        )
                    }
                    transportDao.failExactWaitingRefreshRequests(
                        credentialEpochId = epoch,
                        deviceId = device,
                        failedAccessGeneration = generation,
                        terminalAtUtc = updatedAtUtc,
                        failureCode = "orphan_waiting_refresh",
                    )
                    recovered += 1
                }
            recovered
        }

    private suspend fun quarantineEnrollmentAttempt(
        requestId: String,
        updatedAtUtc: String,
        failureCode: String,
    ) {
        val attempt = checkNotNull(authDao.findAttempt(requestId)) {
            "Enrollment attempt is missing"
        }
        check(attempt.endpointId == AUTH_ENROLL && attempt.state == DISPATCHING) {
            "Enrollment outcome does not bind a dispatching attempt"
        }
        requireExpectedTupleCoherence(attempt)
        val predecessor = authDao.findState()
        requireExpectedCurrentFamily(attempt, predecessor)
        if (predecessor != null) {
            check(
                authDao.quarantine(
                    credentialEpochId = predecessor.credentialEpochId,
                    generation = predecessor.generation,
                    expectedState = predecessor.state,
                    newState = QUARANTINED,
                    updatedAtUtc = updatedAtUtc,
                    failureCode = failureCode,
                ) == 1,
            ) {
                "Ambiguous replacement enrollment lost its family CAS"
            }
        }
        check(
            authDao.compareAndSetAttemptState(
                requestId = requestId,
                expectedState = DISPATCHING,
                newState = OUTCOME_UNKNOWN,
                updatedAtUtc = updatedAtUtc,
                lastErrorCode = failureCode,
            ) == 1,
        ) {
            "Enrollment attempt lost its outcome-unknown CAS"
        }
    }

    /**
     * Returns false for a callback whose lease was already taken over.
     * A byte-identical terminal replay remains successful and idempotent.
     */
    suspend fun commitRevokeTerminal(
        response: TerminalHttpResponsePersistence,
    ): Boolean {
        require(response.endpointId == AUTH_REVOKE)
        require(
            (response.httpStatus == 200 && response.terminalErrorCode == null) ||
                (
                    response.httpStatus == 401 &&
                    response.terminalErrorCode == "credential_unavailable"
                    ),
        )
        return database.withTransaction {
            commitRevokeTerminalInCurrentTransaction(response)
        }
    }

    internal suspend fun commitRevokeTerminalInCurrentTransaction(
        response: TerminalHttpResponsePersistence,
    ): Boolean {
        check(database.inTransaction()) {
            "Terminal revoke reduction requires an outer transaction"
        }
        require(response.endpointId == AUTH_REVOKE)
        require(
            (response.httpStatus == 200 && response.terminalErrorCode == null) ||
                (
                    response.httpStatus == 401 &&
                        response.terminalErrorCode == "credential_unavailable"
                    ),
        )
        val request = checkNotNull(
            transportDao.findRequest(response.endpointId, response.requestIdentity),
        ) {
            "Revoke request is missing"
        }
        if (
            request.state != TERMINAL &&
            (
                request.state != SENDING ||
                    request.activeAttemptId != response.expectedAttemptId
                )
        ) {
            return false
        }
        val familyBefore = authDao.findState()
        if (
            request.state != TERMINAL &&
            (
                familyBefore == null ||
                    familyBefore.credentialEpochId != request.credentialEpochId ||
                    familyBefore.deviceId != request.deviceId ||
                    familyBefore.state != REVOKE_PENDING ||
                    (
                        request.accessGenerationUsed != null &&
                            request.accessGenerationUsed > 0 &&
                            request.accessGenerationUsed != familyBefore.generation
                        )
                )
        ) {
            return false
        }
        if (
            request.state != TERMINAL &&
            !requestStore.preflightFreshResponseRequestMetadata(
                request,
                response.terminalAtUtc,
            )
        ) {
            return false
        }
        val generation = checkNotNull(request.accessGenerationUsed) {
            "Revoke request lost its credential generation"
        }
        val installed = transportDao.storeTerminalResponse(
            endpointId = response.endpointId,
            requestIdentity = response.requestIdentity,
            expectedAttemptId = response.expectedAttemptId,
            httpStatus = response.httpStatus,
            exactResponseBody = response.exactResponseBody,
            responseSha256 = response.responseSha256,
            terminalAtUtc = response.terminalAtUtc,
            terminalErrorCode = response.terminalErrorCode,
        )
        if (installed == 0) {
            val retained = checkNotNull(
                transportDao.findRequest(
                    response.endpointId,
                    response.requestIdentity,
                ),
            )
            if (
                retained.state != TERMINAL &&
                (
                    retained.state != SENDING ||
                        retained.activeAttemptId != response.expectedAttemptId
                    )
            ) {
                return false
            }
            requireExactTerminalResponse(retained, response)
        }
        val revoked = authDao.markExactFamilyRevoked(
            credentialEpochId = request.credentialEpochId,
            deviceId = request.deviceId,
            generation = generation,
            updatedAtUtc = response.terminalAtUtc,
        )
        val exactFamilyBefore =
            familyBefore != null &&
                familyBefore.credentialEpochId == request.credentialEpochId &&
                familyBefore.deviceId == request.deviceId &&
                familyBefore.generation == generation
        when {
            exactFamilyBefore && familyBefore.state == REVOKE_PENDING ->
                check(revoked == 1) {
                    "Terminal revoke lost its exact-family CAS"
                }

            exactFamilyBefore && familyBefore.state == REVOKED &&
                installed == 0 ->
                check(revoked == 0)

            !exactFamilyBefore -> check(revoked == 0) {
                "Late revoke modified a replacement credential family"
            }

            else -> error("Terminal revoke found an invalid exact-family state")
        }
        return true
    }

    suspend fun quarantineRevokeIntegrity(
        requestIdentity: String,
        expectedKeyAlias: String,
        expectedKeyGeneration: Int,
        expectedAadVersion: Int,
        expectedAttemptId: String,
        updatedAtUtc: String,
        failureCode: String,
    ): Boolean =
        database.withTransaction {
            val request = checkNotNull(
                transportDao.findRequest(AUTH_REVOKE, requestIdentity),
            ) {
                "Revoke request is missing"
            }
            val generation = checkNotNull(request.accessGenerationUsed)
            require(
                failureCode in setOf(
                    "sealed_body_key_unavailable",
                    "sealed_body_authentication_failed",
                    "sealed_body_hmac_mismatch",
                    "sealed_body_metadata_invalid",
                ),
            )
            if (
                transportDao.quarantineSealedRevokeRequestRow(
                    requestIdentity = requestIdentity,
                    expectedKeyAlias = expectedKeyAlias,
                    expectedKeyGeneration = expectedKeyGeneration,
                    expectedAadVersion = expectedAadVersion,
                    expectedAttemptId = expectedAttemptId,
                    quarantinedAtUtc = updatedAtUtc,
                    failureCode = failureCode,
                ) == 0
            ) {
                return@withTransaction false
            }
            val current = authDao.findState()
            if (
                current?.credentialEpochId == request.credentialEpochId &&
                current.deviceId == request.deviceId &&
                current.generation == generation &&
                current.state == REVOKE_PENDING
            ) {
                check(
                    authDao.quarantine(
                        credentialEpochId = current.credentialEpochId,
                        generation = current.generation,
                        expectedState = REVOKE_PENDING,
                        newState = INTEGRITY_FAILURE,
                        updatedAtUtc = updatedAtUtc,
                        failureCode = failureCode,
                    ) == 1,
                )
            }
            true
        }

    internal suspend fun haltVerifiedRevokeAfterRollback(
        credentialEpochId: String,
        accessGenerationUsed: Long,
        errorCode: String,
        updatedAtUtc: String,
    ) {
        check(!database.inTransaction()) {
            "Verified revoke halt must run only after the response transaction rolled back"
        }
        require(accessGenerationUsed > 0)
        require(errorCode.isNotBlank())
        try {
            database.withTransaction {
                val current = authDao.findState()
                if (
                    current?.credentialEpochId == credentialEpochId &&
                    current.generation == accessGenerationUsed &&
                    current.state == REVOKE_PENDING
                ) {
                    check(
                        authDao.quarantine(
                            credentialEpochId = current.credentialEpochId,
                            generation = current.generation,
                            expectedState = REVOKE_PENDING,
                            newState = INTEGRITY_FAILURE,
                            updatedAtUtc = updatedAtUtc,
                            failureCode = errorCode,
                        ) == 1,
                    ) {
                        "Verified revoke halt lost its exact current-family CAS"
                    }
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            // Preserve the original response-integrity failure for the caller.
        }
    }

    private suspend fun quarantineRefreshAttempt(
        requestId: String,
        updatedAtUtc: String,
        failureCode: String,
        attemptTerminalState: String,
    ) {
        require(attemptTerminalState in setOf(FAILED, OUTCOME_UNKNOWN))
        val attempt = checkNotNull(authDao.findAttempt(requestId)) {
            "Refresh attempt is missing"
        }
        check(attempt.endpointId == AUTH_REFRESH && attempt.state == DISPATCHING)
        val epoch = checkNotNull(attempt.credentialEpochId)
        val device = checkNotNull(attempt.expectedDeviceId)
        val generation = checkNotNull(attempt.expectedGeneration)
        val current = checkNotNull(authDao.findState()) {
            "Refresh credential family is missing"
        }
        val identity = checkNotNull(identityDao.findIdentity()) {
            "Refresh outcome has no current local identity"
        }
        check(
            current.credentialEpochId == epoch &&
                current.deviceId == device &&
                current.generation == generation &&
                current.state == REFRESH_IN_FLIGHT &&
                current.installationId == identity.installationId &&
                current.localOwnerId == identity.localOwnerId &&
                attempt.installationId == identity.installationId &&
                attempt.localOwnerId == identity.localOwnerId,
        ) {
            "Refresh outcome lost its credential-family binding"
        }
        check(
            authDao.quarantine(
                credentialEpochId = epoch,
                generation = generation,
                expectedState = REFRESH_IN_FLIGHT,
                newState = QUARANTINED,
                updatedAtUtc = updatedAtUtc,
                failureCode = failureCode,
            ) == 1,
        )
        transportDao.failExactWaitingRefreshRequests(
            credentialEpochId = epoch,
            deviceId = device,
            failedAccessGeneration = generation,
            terminalAtUtc = updatedAtUtc,
            failureCode = failureCode,
        )
        check(
            authDao.compareAndSetAttemptState(
                requestId = requestId,
                expectedState = DISPATCHING,
                newState = attemptTerminalState,
                updatedAtUtc = updatedAtUtc,
                lastErrorCode = failureCode,
            ) == 1,
        )
    }

    private suspend fun releaseOpenPushBatches(
        credentialEpochId: String,
        deviceId: String,
    ) {
        transportDao.findOpenPushBatchIds(
            credentialEpochId = credentialEpochId,
            deviceId = deviceId,
        ).forEach { batchId ->
            val expected = transportDao.findBatchItems(batchId).size
            check(expected > 0)
            check(transportDao.releasePushBatchForBootstrap(batchId) == expected) {
                "Open push batch could not release every exact member"
            }
        }
    }

    private fun validateEnrollmentSuccessBundle(
        bundle: EnrollmentSuccessPersistence,
    ) {
        val auth = bundle.authState
        val stream = bundle.streamState
        val session = bundle.bootstrapSession
        require(CANONICAL_UUID_PATTERN.matches(auth.credentialEpochId)) {
            "Enrollment credential epoch must be a canonical UUID"
        }
        require(auth.state == ACTIVE)
        require(auth.generation == 1L)
        require(auth.bootstrapRequired)
        require(auth.failureCode == null)
        require(
            bundle.accessFingerprint.credentialEpochId == auth.credentialEpochId &&
                bundle.refreshFingerprint.credentialEpochId == auth.credentialEpochId &&
                bundle.accessFingerprint.generation == auth.generation &&
                bundle.refreshFingerprint.generation == auth.generation &&
                bundle.accessFingerprint.tokenKind == "access" &&
                bundle.refreshFingerprint.tokenKind == "refresh",
        )
        require(
            stream.credentialEpochId == auth.credentialEpochId &&
                stream.deviceId == auth.deviceId &&
                stream.phase == BOOTSTRAP_REQUIRED &&
                stream.bootstrapRequired &&
                stream.appliedCursor == null &&
                stream.integrityErrorCode == null,
        )
        require(
            session.credentialEpochId == auth.credentialEpochId &&
                session.deviceId == auth.deviceId &&
                session.state == STAGING &&
                session.activeSlot == 1 &&
                session.snapshotId == null &&
                session.nextPageCursor == null &&
                session.candidateIncrementalCursor == null &&
                session.nextPageIndex == 0 &&
                session.lastStagedServerSequence == null &&
                session.stagedPageCount == 0 &&
                session.stagedBodyBytes == 0L,
        )
    }

    private fun validateRefreshSuccess(success: RefreshSuccessPersistence) {
        require(success.successorGeneration == success.expectedGeneration + 1)
        require(success.expectedGeneration > 0)
        require(success.refreshTokenCiphertext.isNotEmpty())
        require(success.refreshTokenNonce.isNotEmpty())
        require(success.refreshTokenKeyAlias.isNotBlank())
        require(success.refreshTokenKeyGeneration > 0)
        require(success.refreshTokenAadVersion > 0)
        require(success.accessExpiresAtEpochMs > 0)
        require(success.refreshExpiresAtEpochMs > success.accessExpiresAtEpochMs)
        require(success.familyExpiresAtEpochMs >= success.refreshExpiresAtEpochMs)
        require(
            Instant.parse(success.committedAtUtc).toEpochMilli() ==
                success.committedAtEpochMs,
        )
        require(
            Instant.parse(success.accessExpiresAtUtc).toEpochMilli() ==
                success.accessExpiresAtEpochMs,
        )
        require(
            Instant.parse(success.refreshExpiresAtUtc).toEpochMilli() ==
                success.refreshExpiresAtEpochMs,
        )
        require(
            Instant.parse(success.familyExpiresAtUtc).toEpochMilli() ==
                success.familyExpiresAtEpochMs,
        )
        require(
            success.accessFingerprint.credentialEpochId ==
                success.credentialEpochId &&
                success.refreshFingerprint.credentialEpochId ==
                success.credentialEpochId &&
                success.accessFingerprint.generation ==
                success.successorGeneration &&
                success.refreshFingerprint.generation ==
                success.successorGeneration &&
                success.accessFingerprint.tokenKind == "access" &&
                success.refreshFingerprint.tokenKind == "refresh",
        )
    }

    private fun requireExpectedTupleCoherence(attempt: SyncAuthAttemptEntity) {
        val expected = listOf(
            attempt.credentialEpochId,
            attempt.expectedDeviceId,
            attempt.expectedGeneration,
        )
        require(expected.all { it == null } || expected.all { it != null }) {
            "Enrollment predecessor tuple must be complete or absent"
        }
    }

    private fun requireExpectedCurrentFamily(
        attempt: SyncAuthAttemptEntity,
        current: SyncAuthStateEntity?,
    ) {
        if (attempt.credentialEpochId == null) {
            check(current == null) {
                "Initial enrollment cannot replace an installed family"
            }
            return
        }
        val installed = checkNotNull(current) {
            "Replacement enrollment predecessor is missing"
        }
        check(
            installed.state in ENROLLMENT_REPLACEABLE_STATES &&
                installed.credentialEpochId == attempt.credentialEpochId &&
                installed.deviceId == attempt.expectedDeviceId &&
                installed.generation == attempt.expectedGeneration &&
                installed.installationId == attempt.installationId &&
                installed.localOwnerId == attempt.localOwnerId,
        ) {
            "Replacement enrollment predecessor tuple is stale"
        }
    }

    private fun requireExactTerminalResponse(
        retained: SyncHttpRequestEntity,
        response: TerminalHttpResponsePersistence,
    ) {
        check(
            retained.state == TERMINAL &&
                retained.terminalHttpStatus == response.httpStatus &&
                retained.exactResponseBody?.contentEquals(
                    response.exactResponseBody,
                ) == true &&
                retained.responseSha256 == response.responseSha256 &&
                retained.terminalErrorCode == response.terminalErrorCode,
        ) {
            "Terminal revoke response replay drifted"
        }
    }

    private companion object {
        const val AUTH_ENROLL = "auth_enroll"
        const val AUTH_REFRESH = "auth_refresh"
        const val AUTH_REVOKE = "auth_revoke"
        const val SYNC_PUSH = "sync_push"
        const val ACTIVE = "active"
        const val REFRESH_IN_FLIGHT = "refresh_in_flight"
        const val REVOKE_PENDING = "revoke_pending"
        const val REVOKED = "revoked"
        const val QUARANTINED = "quarantined"
        const val INTEGRITY_FAILURE = "integrity_failure"
        const val DISPATCHING = "dispatching"
        const val COMPLETED = "completed"
        const val FAILED = "failed"
        const val OUTCOME_UNKNOWN = "outcome_unknown"
        const val READY = "ready"
        const val SENDING = "sending"
        const val TERMINAL = "terminal"
        const val BOOTSTRAP_REQUIRED = "bootstrap_required"
        const val STAGING = "staging"
        const val MAX_INTEGRITY_RECOVERY_ROWS = 1_000
        val ENROLLMENT_REPLACEABLE_STATES = setOf(
            ACTIVE,
            QUARANTINED,
            "expired",
            REVOKED,
            INTEGRITY_FAILURE,
        )
        val CANONICAL_UUID_PATTERN = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        )
    }
}
