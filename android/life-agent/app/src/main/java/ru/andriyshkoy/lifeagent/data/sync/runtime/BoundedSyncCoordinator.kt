package ru.andriyshkoy.lifeagent.data.sync.runtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One body-free recovery transition performed before any network-capable work.
 *
 * Implementations may repair Room state and inspect Android Keystore metadata,
 * but must not perform HTTP. A process restart therefore reaches durable
 * recovery before it can select revoke, refresh or sync authority.
 */
internal fun interface SyncCoordinatorRecoveryPort {
    suspend fun recoverOne(): SyncCoordinatorRecoveryDisposition
}

internal enum class SyncCoordinatorRecoveryDisposition {
    READY,
    RETRY_LATER,
    USER_ACTION_REQUIRED,
}

/**
 * Ordered action classes understood by the synchronous coordinator.
 *
 * REFRESH_MISSING_ACCESS_TOKEN is deliberately narrower than a generic
 * refresh action: the adapter may expose it only after an exact-generation
 * process-memory token lookup returned no send authority.
 */
internal enum class SyncCoordinatorAction {
    REVOKE,
    REFRESH_MISSING_ACCESS_TOKEN,
    BOOTSTRAP,
    PUSH,
    PULL,
}

/**
 * Opaque authority selected by a production request/auth facade.
 *
 * [deduplicationKey] must be stable while the same authoritative action is
 * pending and must change when a continuation, replacement request or
 * credential generation becomes authoritative. It must not contain tokens,
 * request bodies or health data. The key is never included in diagnostics.
 * A future adapter may subclass this value to retain a body-free request
 * handle understood only by that adapter.
 */
internal abstract class SyncCoordinatorActionCandidate(
    val action: SyncCoordinatorAction,
    internal val deduplicationKey: String,
) {
    init {
        require(deduplicationKey.isNotBlank()) {
            "Coordinator authority key must not be blank"
        }
        require(deduplicationKey.length <= MAX_AUTHORITY_KEY_CHARACTERS) {
            "Coordinator authority key is too large"
        }
    }

    final override fun toString(): String =
        "SyncCoordinatorActionCandidate(action=$action,redacted=true)"

    private companion object {
        const val MAX_AUTHORITY_KEY_CHARACTERS = 256
    }
}

/** A body-free, token-free view of actions that are authoritative now. */
internal class SyncCoordinatorActionSnapshot(
    candidates: List<SyncCoordinatorActionCandidate> = emptyList(),
) {
    val candidates: List<SyncCoordinatorActionCandidate> = candidates.toList()

    init {
        require(this.candidates.map { it.action }.distinct().size == this.candidates.size) {
            "Coordinator snapshot contains duplicate action classes"
        }
    }

    internal fun highestPriority(): SyncCoordinatorActionCandidate? =
        ACTION_PRIORITY.firstNotNullOfOrNull { action ->
            candidates.firstOrNull { candidate -> candidate.action == action }
        }

    override fun toString(): String =
        "SyncCoordinatorActionSnapshot(candidateCount=${candidates.size},redacted=true)"

    private companion object {
        val ACTION_PRIORITY = listOf(
            SyncCoordinatorAction.REVOKE,
            SyncCoordinatorAction.REFRESH_MISSING_ACCESS_TOKEN,
            SyncCoordinatorAction.BOOTSTRAP,
            SyncCoordinatorAction.PUSH,
            SyncCoordinatorAction.PULL,
        )
    }
}

/** Reads current authority without loading a durable body or credential. */
internal fun interface SyncCoordinatorActionSource {
    suspend fun currentActions(): SyncCoordinatorActionSnapshot
}

/**
 * Performs exactly one selected transition and at most one HTTP exchange.
 *
 * PROGRESSED promises that this exact non-pull authority is no longer current.
 * A pull must instead report whether an authoritative continuation is ready
 * or the current pull cycle is complete. If an adapter breaks either promise,
 * the coordinator stops fail-closed instead of starting an unbounded cycle.
 */
internal fun interface SyncCoordinatorActionPort {
    suspend fun performOne(
        candidate: SyncCoordinatorActionCandidate,
    ): SyncCoordinatorActionDisposition
}

internal enum class SyncCoordinatorActionDisposition {
    PROGRESSED,
    PULL_CONTINUATION_READY,
    PULL_CYCLE_COMPLETE,
    RETRY_LATER,
    USER_ACTION_REQUIRED,
    NO_PROGRESS,
}

internal enum class SyncCoordinatorStopReason {
    IDLE,
    TRANSITION_LIMIT,
    RECOVERY_RETRY_LATER,
    ACTION_RETRY_LATER,
    USER_ACTION_REQUIRED,
    NO_PROGRESS,
    DUPLICATE_AUTHORITY,
    PULL_CYCLE_COMPLETE,
    INVALID_ACTION_DISPOSITION,
}

/** Content-free result suitable for scheduling decisions and diagnostics. */
internal class SyncCoordinatorRunOutcome internal constructor(
    val transitionCount: Int,
    val stopReason: SyncCoordinatorStopReason,
) {
    init {
        require(transitionCount in 1..BoundedSyncCoordinator.MAX_TRANSITIONS_PER_RUN)
    }

    override fun toString(): String =
        "SyncCoordinatorRunOutcome(" +
            "transitionCount=$transitionCount,stopReason=$stopReason,redacted=true)"
}

/**
 * Synchronous bounded coordinator core.
 *
 * Recovery is transition one. At most three subsequent transitions can run,
 * and every transition completes before the next authority scan begins. The
 * default mutex is process-wide, so independently constructed workers cannot
 * overlap HTTP-capable transitions. Cancellation and other failures are not
 * converted into retry outcomes; they propagate after the mutex is released.
 */
internal class BoundedSyncCoordinator(
    private val recovery: SyncCoordinatorRecoveryPort,
    private val actionSource: SyncCoordinatorActionSource,
    private val actionPort: SyncCoordinatorActionPort,
    private val executionMutex: Mutex = ProcessSyncCoordinatorExecution.mutex,
) {
    suspend fun run(): SyncCoordinatorRunOutcome = executionMutex.withLock {
        runWithExclusiveProcessAuthority()
    }

    private suspend fun runWithExclusiveProcessAuthority(): SyncCoordinatorRunOutcome {
        var transitionCount = 1
        when (recovery.recoverOne()) {
            SyncCoordinatorRecoveryDisposition.READY -> Unit
            SyncCoordinatorRecoveryDisposition.RETRY_LATER ->
                return outcome(
                    transitionCount,
                    SyncCoordinatorStopReason.RECOVERY_RETRY_LATER,
                )

            SyncCoordinatorRecoveryDisposition.USER_ACTION_REQUIRED ->
                return outcome(
                    transitionCount,
                    SyncCoordinatorStopReason.USER_ACTION_REQUIRED,
                )
        }

        val performedAuthorities = mutableSetOf<PerformedAuthority>()
        while (transitionCount < MAX_TRANSITIONS_PER_RUN) {
            val candidate = actionSource.currentActions().highestPriority()
                ?: return outcome(transitionCount, SyncCoordinatorStopReason.IDLE)
            val authority = PerformedAuthority(
                action = candidate.action,
                deduplicationKey = candidate.deduplicationKey,
            )
            if (!performedAuthorities.add(authority)) {
                return outcome(
                    transitionCount,
                    SyncCoordinatorStopReason.DUPLICATE_AUTHORITY,
                )
            }

            transitionCount += 1
            when (actionPort.performOne(candidate)) {
                SyncCoordinatorActionDisposition.PROGRESSED ->
                    if (candidate.action == SyncCoordinatorAction.PULL) {
                        return outcome(
                            transitionCount,
                            SyncCoordinatorStopReason.INVALID_ACTION_DISPOSITION,
                        )
                    }

                SyncCoordinatorActionDisposition.PULL_CONTINUATION_READY ->
                    if (candidate.action != SyncCoordinatorAction.PULL) {
                        return outcome(
                            transitionCount,
                            SyncCoordinatorStopReason.INVALID_ACTION_DISPOSITION,
                        )
                    }

                SyncCoordinatorActionDisposition.PULL_CYCLE_COMPLETE ->
                    return outcome(
                        transitionCount,
                        if (candidate.action == SyncCoordinatorAction.PULL) {
                            SyncCoordinatorStopReason.PULL_CYCLE_COMPLETE
                        } else {
                            SyncCoordinatorStopReason.INVALID_ACTION_DISPOSITION
                        },
                    )

                SyncCoordinatorActionDisposition.RETRY_LATER ->
                    return outcome(
                        transitionCount,
                        SyncCoordinatorStopReason.ACTION_RETRY_LATER,
                    )

                SyncCoordinatorActionDisposition.USER_ACTION_REQUIRED ->
                    return outcome(
                        transitionCount,
                        SyncCoordinatorStopReason.USER_ACTION_REQUIRED,
                    )

                SyncCoordinatorActionDisposition.NO_PROGRESS ->
                    return outcome(
                        transitionCount,
                        SyncCoordinatorStopReason.NO_PROGRESS,
                    )
            }
        }
        return outcome(transitionCount, SyncCoordinatorStopReason.TRANSITION_LIMIT)
    }

    private fun outcome(
        transitionCount: Int,
        reason: SyncCoordinatorStopReason,
    ) = SyncCoordinatorRunOutcome(transitionCount, reason)

    private data class PerformedAuthority(
        val action: SyncCoordinatorAction,
        val deduplicationKey: String,
    )

    internal companion object {
        const val MAX_TRANSITIONS_PER_RUN = 4
    }
}

private object ProcessSyncCoordinatorExecution {
    val mutex = Mutex()
}
