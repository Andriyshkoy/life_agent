package ru.andriyshkoy.lifeagent.data.sync.runtime

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import kotlin.math.min
import ru.andriyshkoy.lifeagent.core.id.RandomUuidGenerator
import ru.andriyshkoy.lifeagent.core.id.UuidGenerator
import ru.andriyshkoy.lifeagent.data.local.db.PersistedDurableRequestRef
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedDurableDispatchPort
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedDurableDispatchResult
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedSyncRequestPlanningFacade
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedSyncRequestPlanningOutcome
import ru.andriyshkoy.lifeagent.data.local.db.SyncRequestPersistenceStore
import ru.andriyshkoy.lifeagent.data.local.db.dao.SyncRunnableRequestCandidate
import ru.andriyshkoy.lifeagent.data.sync.wire.M2Endpoint
import ru.andriyshkoy.lifeagent.data.sync.work.SyncWorkExecutionDisposition
import ru.andriyshkoy.lifeagent.data.sync.work.SyncWorkExecutionPort

/** Narrow auth surface used by the bounded sync composition. */
internal interface M2SyncAuthRuntimeBoundary {
    suspend fun recoverInterrupted(): M2AuthRuntimeResult
    suspend fun ensureAccess(): M2AuthRuntimeResult
}

internal class ProductionM2SyncAuthRuntimeBoundary(
    private val runtime: M2AuthRuntime,
) : M2SyncAuthRuntimeBoundary {
    override suspend fun recoverInterrupted(): M2AuthRuntimeResult =
        runtime.recoverInterruptedAuthFlows()

    override suspend fun ensureAccess(): M2AuthRuntimeResult = runtime.ensureAccess()

    override fun toString(): String =
        "ProductionM2SyncAuthRuntimeBoundary(redacted=true)"
}

internal class M2SyncRequestRecoveryResult(
    val recoveredCount: Int,
    val saturated: Boolean,
) {
    init {
        require(recoveredCount >= 0)
        require(!saturated || recoveredCount > 0)
    }

    override fun toString(): String =
        "M2SyncRequestRecoveryResult(count=$recoveredCount,saturated=$saturated,redacted=true)"
}

internal fun interface M2SyncRequestRecoveryBoundary {
    suspend fun recover(now: Instant): M2SyncRequestRecoveryResult
}

internal class ProductionM2SyncRequestRecoveryBoundary(
    private val requests: SyncRequestPersistenceStore,
    private val limit: Int = DEFAULT_RECOVERY_LIMIT,
) : M2SyncRequestRecoveryBoundary {
    init {
        require(limit in 1..1_000)
    }

    override suspend fun recover(now: Instant): M2SyncRequestRecoveryResult {
        val recovered = requests.reconcileExpiredOrExhaustedRequests(
            nowEpochMs = now.toEpochMilli(),
            terminalAtUtc = now.toString(),
            limit = limit,
        )
        return M2SyncRequestRecoveryResult(
            recoveredCount = recovered,
            saturated = recovered == limit,
        )
    }

    override fun toString(): String =
        "ProductionM2SyncRequestRecoveryBoundary(redacted=true)"

    private companion object {
        const val DEFAULT_RECOVERY_LIMIT = 100
    }
}

/** Body-blind planning surface. Request bytes remain in the protected store. */
internal fun interface ProtectedSyncPlanningBoundary {
    suspend fun plan(createdAtUtc: String): ProtectedSyncRequestPlanningOutcome
}

/**
 * Closed result of one protected claim, send and response-reduction boundary.
 * No payload, credential or durable identity crosses this result.
 */
internal enum class ProtectedSyncDispatchDisposition {
    PROGRESSED,
    PULL_CONTINUATION_READY,
    PULL_CYCLE_COMPLETE,
    RETRY_LATER,
    USER_ACTION_REQUIRED,
    NO_PROGRESS,
}

internal fun interface ProtectedDurableSyncDispatchBoundary {
    suspend fun dispatch(candidate: SyncRunnableRequestCandidate):
        ProtectedSyncDispatchDisposition
}

internal class ProtectedSyncAttemptPolicy(
    val leaseDurationMillis: Long = DEFAULT_LEASE_DURATION_MILLIS,
) {
    init {
        require(leaseDurationMillis > 0)
    }

    override fun toString(): String = "ProtectedSyncAttemptPolicy(redacted=true)"

    private companion object {
        const val DEFAULT_LEASE_DURATION_MILLIS = 2L * 60L * 1_000L
    }
}

internal class ProtectedSyncAttemptAuthority internal constructor(
    val attemptId: String,
    val attemptedAtEpochMs: Long,
    val leaseExpiresAtEpochMs: Long,
    val updatedAtUtc: String,
) {
    init {
        require(attemptId.isNotBlank())
        require(attemptedAtEpochMs > 0)
        require(leaseExpiresAtEpochMs > attemptedAtEpochMs)
        require(Instant.parse(updatedAtUtc).toEpochMilli() == attemptedAtEpochMs)
    }

    override fun toString(): String =
        "ProtectedSyncAttemptAuthority(redacted=true)"
}

/**
 * Adds bounded attempt scheduling to an already protected opaque dispatch
 * owner. Cancellation and unexpected failures propagate without translation.
 */
internal class ProductionProtectedDurableSyncDispatchBoundary(
    private val delegate: ProtectedDurableDispatchPort,
    private val clock: Clock = Clock.systemUTC(),
    private val uuidGenerator: UuidGenerator = RandomUuidGenerator,
    private val policy: ProtectedSyncAttemptPolicy = ProtectedSyncAttemptPolicy(),
) : ProtectedDurableSyncDispatchBoundary {
    override suspend fun dispatch(
        candidate: SyncRunnableRequestCandidate,
    ): ProtectedSyncDispatchDisposition {
        val attempt = newAttempt(candidate)
            ?: return ProtectedSyncDispatchDisposition.NO_PROGRESS
        return delegate.dispatch(
            candidate = candidate,
            attemptId = attempt.attemptId,
            attemptedAtUtc = attempt.updatedAtUtc,
            leaseExpiresAtEpochMs = attempt.leaseExpiresAtEpochMs,
        ).toSyncDispatchDisposition()
    }

    private fun newAttempt(
        candidate: SyncRunnableRequestCandidate,
    ): ProtectedSyncAttemptAuthority? {
        val attemptedAt = clock.instant()
        val attemptedAtEpochMs = attemptedAt.toEpochMilli()
        if (attemptedAtEpochMs <= 0 || candidate.deadlineAtEpochMs <= attemptedAtEpochMs) {
            return null
        }
        val requestedLeaseEnd = try {
            Math.addExact(attemptedAtEpochMs, policy.leaseDurationMillis)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
        val leaseEnd = min(requestedLeaseEnd, candidate.deadlineAtEpochMs)
        if (leaseEnd <= attemptedAtEpochMs) return null
        return ProtectedSyncAttemptAuthority(
            attemptId = uuidGenerator.next().toString(),
            attemptedAtEpochMs = attemptedAtEpochMs,
            leaseExpiresAtEpochMs = leaseEnd,
            updatedAtUtc = attemptedAt.toString(),
        )
    }

    override fun toString(): String =
        "ProductionProtectedDurableSyncDispatchBoundary(redacted=true)"
}

/**
 * Concrete composition entry point for the existing protected planning,
 * credential, dispatch and bounded-coordinator APIs. The protected dispatch
 * dependency is mandatory; this layer has no unavailable production fallback.
 * The returned port is application-graph scoped and must be retained for the
 * process lifetime so completed startup recovery stays latched.
 */
internal fun createProductionM2SyncWorkExecutionPort(
    authRuntime: M2AuthRuntime,
    planningFacade: ProtectedSyncRequestPlanningFacade,
    requestPersistenceStore: SyncRequestPersistenceStore,
    protectedDispatch: ProtectedDurableDispatchPort,
    clock: Clock = Clock.systemUTC(),
    uuidGenerator: UuidGenerator = RandomUuidGenerator,
    attemptPolicy: ProtectedSyncAttemptPolicy = ProtectedSyncAttemptPolicy(),
): SyncWorkExecutionPort = M2SyncWorkExecutionPort(
    auth = ProductionM2SyncAuthRuntimeBoundary(authRuntime),
    requestRecovery = ProductionM2SyncRequestRecoveryBoundary(requestPersistenceStore),
    planning = ProtectedSyncPlanningBoundary(planningFacade::planAndConstruct),
    dispatch = ProductionProtectedDurableSyncDispatchBoundary(
        delegate = protectedDispatch,
        clock = clock,
        uuidGenerator = uuidGenerator,
        policy = attemptPolicy,
    ),
    clock = clock,
)

/** Production policy around the bounded coordinator's opaque execution port. */
internal class M2SyncWorkExecutionPort(
    auth: M2SyncAuthRuntimeBoundary,
    requestRecovery: M2SyncRequestRecoveryBoundary,
    planning: ProtectedSyncPlanningBoundary,
    dispatch: ProtectedDurableSyncDispatchBoundary,
    clock: Clock = Clock.systemUTC(),
) : SyncWorkExecutionPort {
    private val accessState = M2SyncAccessAuthorityState()
    private val recovery = M2SyncRecoveryPort(
        auth = auth,
        requests = requestRecovery,
        clock = clock,
    )
    private val actionSource = M2SyncCoordinatorActionSource(
        planning = planning,
        accessState = accessState,
        clock = clock,
    )
    private val actionPort = M2SyncCoordinatorActionPort(
        auth = auth,
        accessState = accessState,
        dispatch = dispatch,
    )
    private val coordinator = BoundedSyncCoordinator(
        recovery = recovery,
        actionSource = actionSource,
        actionPort = actionPort,
    )

    override suspend fun runOneBoundedSync(): SyncWorkExecutionDisposition {
        val outcome = coordinator.run()
        return when (outcome.stopReason) {
            SyncCoordinatorStopReason.IDLE -> actionSource.idleDisposition
            SyncCoordinatorStopReason.PULL_CYCLE_COMPLETE ->
                SyncWorkExecutionDisposition.COMPLETE

            SyncCoordinatorStopReason.TRANSITION_LIMIT,
            SyncCoordinatorStopReason.RECOVERY_RETRY_LATER,
            SyncCoordinatorStopReason.ACTION_RETRY_LATER,
            SyncCoordinatorStopReason.NO_PROGRESS,
            -> SyncWorkExecutionDisposition.RETRY

            SyncCoordinatorStopReason.USER_ACTION_REQUIRED,
            SyncCoordinatorStopReason.DUPLICATE_AUTHORITY,
            SyncCoordinatorStopReason.INVALID_ACTION_DISPOSITION,
            -> SyncWorkExecutionDisposition.PERMANENT_FAILURE
        }
    }

    override fun toString(): String = "M2SyncWorkExecutionPort(redacted=true)"
}

/**
 * Auth recovery is repeated until a zero-change pass and then latched, while
 * expired/exhausted request reconciliation runs before every action scan.
 * Saturated bounded recovery yields instead of opening network authority.
 */
private class M2SyncRecoveryPort(
    private val auth: M2SyncAuthRuntimeBoundary,
    private val requests: M2SyncRequestRecoveryBoundary,
    private val clock: Clock,
) : SyncCoordinatorRecoveryPort {
    private var authRecoveryComplete = false

    override suspend fun recoverOne(): SyncCoordinatorRecoveryDisposition {
        if (!authRecoveryComplete) {
            when (val result = auth.recoverInterrupted()) {
                is M2AuthRuntimeResult.RecoveryComplete -> if (result.recoveredCount == 0) {
                    authRecoveryComplete = true
                } else {
                    return SyncCoordinatorRecoveryDisposition.RETRY_LATER
                }

                M2AuthRuntimeResult.Busy,
                M2AuthRuntimeResult.LocalUnavailable,
                -> return SyncCoordinatorRecoveryDisposition.RETRY_LATER

                is M2AuthRuntimeResult.AccessReady,
                is M2AuthRuntimeResult.DurableCredentialsCommitted,
                M2AuthRuntimeResult.ManualReenrollmentRequired,
                M2AuthRuntimeResult.Rejected,
                M2AuthRuntimeResult.Unenrolled,
                -> return SyncCoordinatorRecoveryDisposition.USER_ACTION_REQUIRED
            }
        }
        val requestRecovery = requests.recover(clock.instant())
        return if (requestRecovery.saturated) {
            SyncCoordinatorRecoveryDisposition.RETRY_LATER
        } else {
            SyncCoordinatorRecoveryDisposition.READY
        }
    }
}

private class M2SyncCoordinatorActionSource(
    private val planning: ProtectedSyncPlanningBoundary,
    private val accessState: M2SyncAccessAuthorityState,
    private val clock: Clock,
    private val authorityKeys: OpaqueCoordinatorAuthorityKeys =
        OpaqueCoordinatorAuthorityKeys(),
) : SyncCoordinatorActionSource {
    var idleDisposition: SyncWorkExecutionDisposition = SyncWorkExecutionDisposition.COMPLETE
        private set

    override suspend fun currentActions(): SyncCoordinatorActionSnapshot {
        idleDisposition = SyncWorkExecutionDisposition.COMPLETE
        val createdAtUtc = clock.instant().toString()
        val initial = planning.plan(createdAtUtc)
        val outcome = if (initial is ProtectedSyncRequestPlanningOutcome.Created) {
            resolveCreated(initial, createdAtUtc)
                ?: return noAction(SyncWorkExecutionDisposition.PERMANENT_FAILURE)
        } else {
            initial
        }
        return when (outcome) {
            is ProtectedSyncRequestPlanningOutcome.Retained ->
                retainedAction(outcome)

            is ProtectedSyncRequestPlanningOutcome.NoRequest ->
                noRequest(outcome.plan.reason)

            is ProtectedSyncRequestPlanningOutcome.Created ->
                noAction(SyncWorkExecutionDisposition.PERMANENT_FAILURE)
        }
    }

    private suspend fun resolveCreated(
        created: ProtectedSyncRequestPlanningOutcome.Created,
        createdAtUtc: String,
    ): ProtectedSyncRequestPlanningOutcome.Retained? {
        val followUp = planning.plan(createdAtUtc)
        if (followUp !is ProtectedSyncRequestPlanningOutcome.Retained) return null
        return followUp.takeIf {
            it.plan.kind == created.kind && it.candidate.matches(created.request)
        }
    }

    private fun retainedAction(
        retained: ProtectedSyncRequestPlanningOutcome.Retained,
    ): SyncCoordinatorActionSnapshot {
        val candidate = retained.candidate
        if (candidate.endpointId.toDurableRequestKindOrNull() != retained.plan.kind) {
            return noAction(SyncWorkExecutionDisposition.PERMANENT_FAILURE)
        }
        val action = retained.plan.kind.toCoordinatorAction()
        val currentAccess = accessState.current()
        if (
            action != SyncCoordinatorAction.REVOKE &&
            currentAccess?.credentialEpochId != candidate.credentialEpochId
        ) {
            accessState.clear()
            return SyncCoordinatorActionSnapshot(
                listOf(MissingAccessCoordinatorCandidate),
            )
        }
        return SyncCoordinatorActionSnapshot(
            listOf(
                DurableRequestCoordinatorCandidate(
                    request = candidate,
                    action = action,
                    deduplicationKey = authorityKeys.forRequest(candidate),
                ),
            ),
        )
    }

    private fun noRequest(
        reason: DurableSyncNoRequestReason,
    ): SyncCoordinatorActionSnapshot = when (reason) {
        DurableSyncNoRequestReason.REFRESH_REQUIRED ->
            SyncCoordinatorActionSnapshot(listOf(MissingAccessCoordinatorCandidate))

        DurableSyncNoRequestReason.AUTHORITY_MISSING ->
            noAction(SyncWorkExecutionDisposition.COMPLETE)

        DurableSyncNoRequestReason.OPEN_REQUEST_REQUIRES_RECOVERY ->
            noAction(SyncWorkExecutionDisposition.RETRY)

        DurableSyncNoRequestReason.AUTHORITY_UNUSABLE,
        DurableSyncNoRequestReason.REVOKE_REQUEST_MISSING,
        DurableSyncNoRequestReason.STREAM_AUTHORITY_MISSING,
        DurableSyncNoRequestReason.AUTHORITATIVE_CURSOR_MISSING,
        DurableSyncNoRequestReason.INTEGRITY_HALTED,
        DurableSyncNoRequestReason.INCONSISTENT_BOOTSTRAP_AUTHORITY,
        -> noAction(SyncWorkExecutionDisposition.PERMANENT_FAILURE)
    }

    private fun noAction(
        disposition: SyncWorkExecutionDisposition,
    ): SyncCoordinatorActionSnapshot {
        idleDisposition = disposition
        return SyncCoordinatorActionSnapshot()
    }
}

private class M2SyncCoordinatorActionPort(
    private val auth: M2SyncAuthRuntimeBoundary,
    private val accessState: M2SyncAccessAuthorityState,
    private val dispatch: ProtectedDurableSyncDispatchBoundary,
) : SyncCoordinatorActionPort {
    override suspend fun performOne(
        candidate: SyncCoordinatorActionCandidate,
    ): SyncCoordinatorActionDisposition = when (candidate) {
        MissingAccessCoordinatorCandidate -> ensureAccess()
        is DurableRequestCoordinatorCandidate -> dispatch(candidate)
        else -> SyncCoordinatorActionDisposition.NO_PROGRESS
    }

    private suspend fun dispatch(
        candidate: DurableRequestCoordinatorCandidate,
    ): SyncCoordinatorActionDisposition {
        val result = dispatch.dispatch(candidate.request)
        if (
            result == ProtectedSyncDispatchDisposition.RETRY_LATER ||
            result == ProtectedSyncDispatchDisposition.USER_ACTION_REQUIRED ||
            result == ProtectedSyncDispatchDisposition.NO_PROGRESS
        ) {
            accessState.clear()
        }
        return result.toCoordinatorDisposition()
    }

    private suspend fun ensureAccess(): SyncCoordinatorActionDisposition {
        accessState.clear()
        return when (val result = auth.ensureAccess()) {
            is M2AuthRuntimeResult.AccessReady -> {
                accessState.replace(result.key)
                SyncCoordinatorActionDisposition.PROGRESSED
            }

            M2AuthRuntimeResult.Busy,
            M2AuthRuntimeResult.LocalUnavailable,
            -> SyncCoordinatorActionDisposition.RETRY_LATER

            is M2AuthRuntimeResult.DurableCredentialsCommitted,
            M2AuthRuntimeResult.ManualReenrollmentRequired,
            M2AuthRuntimeResult.Rejected,
            M2AuthRuntimeResult.Unenrolled,
            -> SyncCoordinatorActionDisposition.USER_ACTION_REQUIRED

            is M2AuthRuntimeResult.RecoveryComplete ->
                SyncCoordinatorActionDisposition.NO_PROGRESS
        }
    }
}

/** Body-free cache of the authority that [M2AuthRuntime] last proved usable. */
private class M2SyncAccessAuthorityState {
    private var current: AccessTokenKey? = null

    fun current(): AccessTokenKey? = current

    fun replace(key: AccessTokenKey) {
        current = key
    }

    fun clear() {
        current = null
    }

    override fun toString(): String = "M2SyncAccessAuthorityState(redacted=true)"
}

private object MissingAccessCoordinatorCandidate : SyncCoordinatorActionCandidate(
    action = SyncCoordinatorAction.REFRESH_MISSING_ACCESS_TOKEN,
    deduplicationKey = "missing-access-authority",
)

private class DurableRequestCoordinatorCandidate(
    val request: SyncRunnableRequestCandidate,
    action: SyncCoordinatorAction,
    deduplicationKey: String,
) : SyncCoordinatorActionCandidate(action, deduplicationKey)

/** Process-private salted keys keep durable identities out of coordinator state. */
private class OpaqueCoordinatorAuthorityKeys(
    private val salt: ByteArray = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes),
) {
    fun forRequest(candidate: SyncRunnableRequestCandidate): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        listOf(
            candidate.endpointId,
            candidate.requestIdentity,
            candidate.attemptCount.toString(),
            candidate.accessGenerationUsed.toString(),
        ).forEach { part ->
            digest.update(part.toByteArray(StandardCharsets.UTF_8))
            digest.update(FIELD_SEPARATOR)
        }
        return digest.digest().toHex()
    }

    override fun toString(): String = "OpaqueCoordinatorAuthorityKeys(redacted=true)"

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        this@toHex.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }

    private companion object {
        const val SALT_BYTES = 32
        const val FIELD_SEPARATOR: Byte = 0
        const val HEX = "0123456789abcdef"
    }
}

private fun SyncRunnableRequestCandidate.matches(
    request: PersistedDurableRequestRef,
): Boolean = endpointId == request.endpointId && requestIdentity == request.requestIdentity

private fun DurableSyncRequestKind.toCoordinatorAction(): SyncCoordinatorAction = when (this) {
    DurableSyncRequestKind.REVOKE -> SyncCoordinatorAction.REVOKE
    DurableSyncRequestKind.BOOTSTRAP -> SyncCoordinatorAction.BOOTSTRAP
    DurableSyncRequestKind.PUSH -> SyncCoordinatorAction.PUSH
    DurableSyncRequestKind.PULL -> SyncCoordinatorAction.PULL
}

private fun ProtectedSyncDispatchDisposition.toCoordinatorDisposition():
    SyncCoordinatorActionDisposition = when (this) {
    ProtectedSyncDispatchDisposition.PROGRESSED -> SyncCoordinatorActionDisposition.PROGRESSED
    ProtectedSyncDispatchDisposition.PULL_CONTINUATION_READY ->
        SyncCoordinatorActionDisposition.PULL_CONTINUATION_READY
    ProtectedSyncDispatchDisposition.PULL_CYCLE_COMPLETE ->
        SyncCoordinatorActionDisposition.PULL_CYCLE_COMPLETE
    ProtectedSyncDispatchDisposition.RETRY_LATER ->
        SyncCoordinatorActionDisposition.RETRY_LATER
    ProtectedSyncDispatchDisposition.USER_ACTION_REQUIRED ->
        SyncCoordinatorActionDisposition.USER_ACTION_REQUIRED
    ProtectedSyncDispatchDisposition.NO_PROGRESS ->
        SyncCoordinatorActionDisposition.NO_PROGRESS
}

private fun ProtectedDurableDispatchResult.toSyncDispatchDisposition():
    ProtectedSyncDispatchDisposition = when (this) {
    ProtectedDurableDispatchResult.PROGRESSED ->
        ProtectedSyncDispatchDisposition.PROGRESSED
    ProtectedDurableDispatchResult.PULL_CONTINUATION_READY ->
        ProtectedSyncDispatchDisposition.PULL_CONTINUATION_READY
    ProtectedDurableDispatchResult.PULL_CYCLE_COMPLETE ->
        ProtectedSyncDispatchDisposition.PULL_CYCLE_COMPLETE
    ProtectedDurableDispatchResult.RETRY_LATER ->
        ProtectedSyncDispatchDisposition.RETRY_LATER
    ProtectedDurableDispatchResult.USER_ACTION_REQUIRED ->
        ProtectedSyncDispatchDisposition.USER_ACTION_REQUIRED
    ProtectedDurableDispatchResult.NO_PROGRESS ->
        ProtectedSyncDispatchDisposition.NO_PROGRESS
}

private fun String.toDurableRequestKindOrNull(): DurableSyncRequestKind? = when (this) {
    M2Endpoint.AUTH_REVOKE.endpointId -> DurableSyncRequestKind.REVOKE
    M2Endpoint.SYNC_BOOTSTRAP.endpointId -> DurableSyncRequestKind.BOOTSTRAP
    M2Endpoint.SYNC_PUSH.endpointId -> DurableSyncRequestKind.PUSH
    M2Endpoint.SYNC_PULL.endpointId -> DurableSyncRequestKind.PULL
    else -> null
}
