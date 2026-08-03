package ru.andriyshkoy.lifeagent.data.sync.runtime

/**
 * Body-free authority projected for durable request planning.
 *
 * The production Room adapter must derive these values in one transaction from
 * the current identity, credential family, stream and bootstrap session. This
 * policy deliberately has no Room or wire dependency: request bodies, cursor
 * values, tokens and request identities cannot enter either its input or result.
 */
internal enum class DurableSyncPlannerAuth {
    MISSING,
    ACTIVE_CURRENT,
    REFRESH_REQUIRED,
    REVOKE_PENDING,
    UNUSABLE,
}

internal enum class DurableSyncPlannerStream {
    MISSING,
    BOOTSTRAP_REQUIRED,
    INCREMENTAL_WITH_CURSOR,
    INCREMENTAL_WITHOUT_CURSOR,
    PULLING_WITH_CURSOR,
    PULLING_WITHOUT_CURSOR,
    INTEGRITY_HALTED,
}

internal enum class DurableSyncRequestKind {
    REVOKE,
    BOOTSTRAP,
    PUSH,
    PULL,
}

/**
 * A body-blind snapshot for a single planning decision.
 *
 * [retainedExistingRequests] contains only non-terminal requests that a future
 * transactional facade has already bound to current authority. It is a set of
 * route classes, never request rows or identifiers. A scheduled retry, active
 * lease or waiting-refresh request that cannot yet be selected is represented
 * by [otherOpenRequestBlocksCreation], preventing duplicate durable intents.
 */
internal class DurableSyncRequestPlanningSnapshot(
    val auth: DurableSyncPlannerAuth,
    val stream: DurableSyncPlannerStream,
    val activeBootstrapSessionPresent: Boolean,
    val actionableOutboxPresent: Boolean,
    retainedExistingRequests: Set<DurableSyncRequestKind> = emptySet(),
    val otherOpenRequestBlocksCreation: Boolean = false,
) {
    val retainedExistingRequests: Set<DurableSyncRequestKind> =
        retainedExistingRequests.toSet()

    override fun toString(): String =
        "DurableSyncRequestPlanningSnapshot(" +
            "auth=$auth,stream=$stream," +
            "retainedRequestKindCount=${retainedExistingRequests.size}," +
            "redacted=true)"
}

internal enum class DurableBootstrapIntentKind {
    START,
    CONTINUE,
}

internal enum class DurableSyncNoRequestReason {
    AUTHORITY_MISSING,
    AUTHORITY_UNUSABLE,
    REFRESH_REQUIRED,
    REVOKE_REQUEST_MISSING,
    STREAM_AUTHORITY_MISSING,
    AUTHORITATIVE_CURSOR_MISSING,
    INTEGRITY_HALTED,
    OPEN_REQUEST_REQUIRES_RECOVERY,
    INCONSISTENT_BOOTSTRAP_AUTHORITY,
}

/** Exactly one redacted planning action, or an explicit fail-closed reason. */
internal sealed interface DurableSyncRequestPlan {
    data class RetainExisting(
        val kind: DurableSyncRequestKind,
    ) : DurableSyncRequestPlan {
        override fun toString(): String =
            "DurableSyncRequestPlan.RetainExisting(kind=$kind,redacted=true)"
    }

    data class CreateBootstrap(
        val intentKind: DurableBootstrapIntentKind,
    ) : DurableSyncRequestPlan {
        override fun toString(): String =
            "DurableSyncRequestPlan.CreateBootstrap(kind=$intentKind,redacted=true)"
    }

    data object CreatePush : DurableSyncRequestPlan {
        override fun toString(): String =
            "DurableSyncRequestPlan.CreatePush(redacted=true)"
    }

    data object CreatePull : DurableSyncRequestPlan {
        override fun toString(): String =
            "DurableSyncRequestPlan.CreatePull(redacted=true)"
    }

    data class NoRequest(
        val reason: DurableSyncNoRequestReason,
    ) : DurableSyncRequestPlan {
        override fun toString(): String =
            "DurableSyncRequestPlan.NoRequest(reason=$reason,redacted=true)"
    }
}

/**
 * Pure ordering policy used by the synchronous M2 coordinator.
 *
 * Existing durable authority always wins, in revoke -> bootstrap -> push ->
 * pull order. New work is planned only with current active credentials and no
 * unresolved open request. Bootstrap blocks incremental work. In incremental
 * mode actionable outbox work precedes pull; an in-progress pull is continued
 * before starting a new push batch.
 */
internal object DurableSyncRequestPlanningPolicy {
    private val retainedPriority = listOf(
        DurableSyncRequestKind.REVOKE,
        DurableSyncRequestKind.BOOTSTRAP,
        DurableSyncRequestKind.PUSH,
        DurableSyncRequestKind.PULL,
    )

    fun decide(snapshot: DurableSyncRequestPlanningSnapshot): DurableSyncRequestPlan {
        retainedPriority.firstOrNull(snapshot.retainedExistingRequests::contains)?.let {
            return DurableSyncRequestPlan.RetainExisting(it)
        }
        if (snapshot.otherOpenRequestBlocksCreation) {
            return noRequest(DurableSyncNoRequestReason.OPEN_REQUEST_REQUIRES_RECOVERY)
        }
        when (snapshot.auth) {
            DurableSyncPlannerAuth.MISSING ->
                return noRequest(DurableSyncNoRequestReason.AUTHORITY_MISSING)

            DurableSyncPlannerAuth.REFRESH_REQUIRED ->
                return noRequest(DurableSyncNoRequestReason.REFRESH_REQUIRED)

            DurableSyncPlannerAuth.REVOKE_PENDING ->
                return noRequest(DurableSyncNoRequestReason.REVOKE_REQUEST_MISSING)

            DurableSyncPlannerAuth.UNUSABLE ->
                return noRequest(DurableSyncNoRequestReason.AUTHORITY_UNUSABLE)

            DurableSyncPlannerAuth.ACTIVE_CURRENT -> Unit
        }

        if (
            snapshot.activeBootstrapSessionPresent &&
            snapshot.stream != DurableSyncPlannerStream.BOOTSTRAP_REQUIRED
        ) {
            return noRequest(DurableSyncNoRequestReason.INCONSISTENT_BOOTSTRAP_AUTHORITY)
        }

        return when (snapshot.stream) {
            DurableSyncPlannerStream.MISSING ->
                noRequest(DurableSyncNoRequestReason.STREAM_AUTHORITY_MISSING)

            DurableSyncPlannerStream.BOOTSTRAP_REQUIRED ->
                DurableSyncRequestPlan.CreateBootstrap(
                    intentKind = if (snapshot.activeBootstrapSessionPresent) {
                        DurableBootstrapIntentKind.CONTINUE
                    } else {
                        DurableBootstrapIntentKind.START
                    },
                )

            DurableSyncPlannerStream.INCREMENTAL_WITH_CURSOR ->
                if (snapshot.actionableOutboxPresent) {
                    DurableSyncRequestPlan.CreatePush
                } else {
                    DurableSyncRequestPlan.CreatePull
                }

            DurableSyncPlannerStream.INCREMENTAL_WITHOUT_CURSOR ->
                if (snapshot.actionableOutboxPresent) {
                    DurableSyncRequestPlan.CreatePush
                } else {
                    noRequest(DurableSyncNoRequestReason.AUTHORITATIVE_CURSOR_MISSING)
                }

            DurableSyncPlannerStream.PULLING_WITH_CURSOR ->
                DurableSyncRequestPlan.CreatePull

            DurableSyncPlannerStream.PULLING_WITHOUT_CURSOR ->
                noRequest(DurableSyncNoRequestReason.AUTHORITATIVE_CURSOR_MISSING)

            DurableSyncPlannerStream.INTEGRITY_HALTED ->
                noRequest(DurableSyncNoRequestReason.INTEGRITY_HALTED)
        }
    }

    private fun noRequest(reason: DurableSyncNoRequestReason) =
        DurableSyncRequestPlan.NoRequest(reason)
}
