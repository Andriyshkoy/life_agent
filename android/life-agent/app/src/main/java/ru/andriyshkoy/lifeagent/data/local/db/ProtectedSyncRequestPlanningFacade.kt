package ru.andriyshkoy.lifeagent.data.local.db

import android.content.Context
import androidx.room.withTransaction
import java.time.Instant
import ru.andriyshkoy.lifeagent.core.id.RandomUuidGenerator
import ru.andriyshkoy.lifeagent.core.id.UuidGenerator
import ru.andriyshkoy.lifeagent.data.local.db.dao.SyncRunnableRequestCandidate
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncAuthStateEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncBootstrapSessionEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncHttpRequestEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPushBatchEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPushBatchItemEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncStreamStateEntity
import ru.andriyshkoy.lifeagent.data.security.KeystoreRequestBodyHmacKeyring
import ru.andriyshkoy.lifeagent.data.security.NewDurableRequestPersistence
import ru.andriyshkoy.lifeagent.data.sync.runtime.DurableBootstrapIntentKind
import ru.andriyshkoy.lifeagent.data.sync.runtime.DurableSyncNoRequestReason
import ru.andriyshkoy.lifeagent.data.sync.runtime.DurableSyncPlannerAuth
import ru.andriyshkoy.lifeagent.data.sync.runtime.DurableSyncPlannerStream
import ru.andriyshkoy.lifeagent.data.sync.runtime.DurableSyncRequestKind
import ru.andriyshkoy.lifeagent.data.sync.runtime.DurableSyncRequestPlan
import ru.andriyshkoy.lifeagent.data.sync.runtime.DurableSyncRequestPlanningPolicy
import ru.andriyshkoy.lifeagent.data.sync.runtime.DurableSyncRequestPlanningSnapshot
import ru.andriyshkoy.lifeagent.data.sync.wire.BootstrapRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.M2Endpoint
import ru.andriyshkoy.lifeagent.data.sync.wire.PullRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.PushBatchRequest

internal data class ProtectedSyncRequestConstructionSettings(
    val pageSize: Int,
    val attemptBudget: Int,
    val requestLifetimeMillis: Long,
) {
    init {
        require(pageSize in 1..500)
        require(attemptBudget in 1..64)
        require(requestLifetimeMillis > 0)
    }

    override fun toString(): String =
        "ProtectedSyncRequestConstructionSettings(redacted=true)"
}

/** Body-free authority handed to the narrow canonical push builder. */
internal class ProtectedActionablePushAuthority(
    val credentialEpochId: String,
    val deviceId: String,
    val accessGeneration: Long,
    val createdAtUtc: String,
    val attemptBudget: Int,
    val deadlineAtEpochMs: Long,
) {
    override fun toString(): String =
        "ProtectedActionablePushAuthority(redacted=true)"
}

/**
 * Integration port for canonical outbox materialization. It is invoked only
 * after the Room planner proves that no current durable request can be retained
 * and that actionable outbox work exists.
 */
internal fun interface ProtectedActionablePushConstructionPort {
    suspend fun build(
        authority: ProtectedActionablePushAuthority,
    ): ProtectedActionablePushConstruction
}

internal class ProtectedActionablePushConstruction(
    val request: PushBatchRequest,
    val persistence: NewDurableRequestPersistence,
    val batch: SyncPushBatchEntity,
    items: List<SyncPushBatchItemEntity>,
) {
    val items: List<SyncPushBatchItemEntity> = items.toList()

    override fun toString(): String =
        "ProtectedActionablePushConstruction(itemCount=${items.size},redacted=true)"
}

internal sealed interface ProtectedSyncRequestPlanningOutcome {
    val plan: DurableSyncRequestPlan

    class Retained(
        override val plan: DurableSyncRequestPlan.RetainExisting,
        val candidate: SyncRunnableRequestCandidate,
    ) : ProtectedSyncRequestPlanningOutcome {
        override fun toString(): String =
            "ProtectedSyncRequestPlanningOutcome.Retained(kind=${plan.kind},redacted=true)"
    }

    class Created(
        override val plan: DurableSyncRequestPlan,
        val kind: DurableSyncRequestKind,
        val request: PersistedDurableRequestRef,
    ) : ProtectedSyncRequestPlanningOutcome {
        override fun toString(): String =
            "ProtectedSyncRequestPlanningOutcome.Created(kind=$kind,redacted=true)"
    }

    class NoRequest(
        override val plan: DurableSyncRequestPlan.NoRequest,
    ) : ProtectedSyncRequestPlanningOutcome {
        override fun toString(): String =
            "ProtectedSyncRequestPlanningOutcome.NoRequest(reason=${plan.reason},redacted=true)"
    }
}

/**
 * Room-owned planning and construction boundary.
 *
 * Candidate discovery is projection-only. A retained candidate returns before
 * any UUID factory, canonical push builder, body materialization, or Keystore
 * provisioning can run. New request construction remains inside the same Room
 * transaction that produced the body-free authority snapshot.
 */
internal class ProtectedSyncRequestPlanningFacade(
    context: Context,
    private val database: LifeAgentDatabase,
    private val settings: ProtectedSyncRequestConstructionSettings,
    private val actionablePushes: ProtectedActionablePushConstructionPort,
    uuidGenerator: UuidGenerator = RandomUuidGenerator,
    keyring: KeystoreRequestBodyHmacKeyring = KeystoreRequestBodyHmacKeyring(context),
) {
    private val protectedRequests = ProtectedSyncRequestStore(context, database, keyring)
    private val bootstrapIntents = ProductionProtectedBootstrapIntentBoundary(
        database = database,
        protectedRequests = protectedRequests,
        settings = settings,
        uuidGenerator = uuidGenerator,
    )
    private val authDao = database.syncAuthDao()
    private val identityDao = database.identityDao()
    private val outboxDao = database.outboxDao()
    private val replicaDao = database.syncReplicaDao()
    private val transportDao = database.syncTransportDao()

    val protectedBootstrapIntents: ProtectedBootstrapIntentBoundary
        get() = bootstrapIntents

    suspend fun planAndConstruct(
        createdAtUtc: String,
    ): ProtectedSyncRequestPlanningOutcome {
        val nowEpochMs = Instant.parse(createdAtUtc).toEpochMilli()
        return database.withTransaction {
            val candidates = transportDao.findRunnableRequestCandidates(
                nowEpochMs = nowEpochMs,
                limit = MAX_PLANNING_CANDIDATES,
            )
            val auth = authDao.findState()
            val stream = replicaDao.findStreamState()
            val activeSession = replicaDao.findBootstrapSessionWithActiveSlot()
            val authClass = classifyAuth(auth, nowEpochMs)
            val streamClass = classifyStream(auth, stream)
            val retainedKinds = candidates.mapNotNull { candidate ->
                candidate.endpointId.toRequestKindOrNull()
            }.toSet()
            val openRequestCount = transportDao.countOpenRequestRows()
            val enrollmentBlocksCreation = candidates.isEmpty() &&
                openRequestCount == 0L &&
                auth != null &&
                hasConflictingEnrollment(auth)
            val actionableOutboxPresent = candidates.isEmpty() &&
                openRequestCount == 0L &&
                !enrollmentBlocksCreation &&
                authClass == DurableSyncPlannerAuth.ACTIVE_CURRENT &&
                streamClass in setOf(
                    DurableSyncPlannerStream.INCREMENTAL_WITH_CURSOR,
                    DurableSyncPlannerStream.INCREMENTAL_WITHOUT_CURSOR,
                ) &&
                (
                    outboxDao.actionableForBatch(1).isNotEmpty() ||
                        outboxDao.awaitingWireMaterialization(1).isNotEmpty()
                )
            val snapshot = DurableSyncRequestPlanningSnapshot(
                auth = authClass,
                stream = streamClass,
                activeBootstrapSessionPresent = activeSession != null,
                actionableOutboxPresent = actionableOutboxPresent,
                retainedExistingRequests = retainedKinds,
                otherOpenRequestBlocksCreation =
                    openRequestCount > candidates.size.toLong() ||
                    enrollmentBlocksCreation,
            )
            val plan = DurableSyncRequestPlanningPolicy.decide(snapshot)
            when (plan) {
                is DurableSyncRequestPlan.RetainExisting -> {
                    val candidate = checkNotNull(
                        candidates.firstOrNull {
                            it.endpointId.toRequestKindOrNull() == plan.kind
                        },
                    ) {
                        "Retained durable request kind has no body-blind candidate"
                    }
                    ProtectedSyncRequestPlanningOutcome.Retained(plan, candidate)
                }

                is DurableSyncRequestPlan.CreateBootstrap -> if (
                    plan.intentKind == DurableBootstrapIntentKind.CONTINUE &&
                    (
                        activeSession == null ||
                            activeSession.state != "staging" ||
                            activeSession.activeSlot != 1 ||
                            auth == null ||
                            activeSession.credentialEpochId != auth.credentialEpochId ||
                            activeSession.deviceId != auth.deviceId ||
                            activeSession.nextPageCursor == null ||
                            activeSession.nextPageIndex <= 0
                        )
                ) {
                    ProtectedSyncRequestPlanningOutcome.NoRequest(
                        DurableSyncRequestPlan.NoRequest(
                            DurableSyncNoRequestReason.INCONSISTENT_BOOTSTRAP_AUTHORITY,
                        ),
                    )
                } else {
                    createBootstrap(
                        plan = plan,
                        auth = checkNotNull(auth),
                        activeSession = activeSession,
                        createdAtUtc = createdAtUtc,
                    )
                }

                DurableSyncRequestPlan.CreatePull -> createPull(
                    auth = checkNotNull(auth),
                    stream = checkNotNull(stream),
                    createdAtUtc = createdAtUtc,
                )

                DurableSyncRequestPlan.CreatePush -> createPush(
                    auth = checkNotNull(auth),
                    createdAtUtc = createdAtUtc,
                )

                is DurableSyncRequestPlan.NoRequest ->
                    ProtectedSyncRequestPlanningOutcome.NoRequest(plan)
            }
        }
    }

    private suspend fun createBootstrap(
        plan: DurableSyncRequestPlan.CreateBootstrap,
        auth: SyncAuthStateEntity,
        activeSession: SyncBootstrapSessionEntity?,
        createdAtUtc: String,
    ): ProtectedSyncRequestPlanningOutcome {
        val persisted = when (plan.intentKind) {
            DurableBootstrapIntentKind.START -> {
                check(activeSession == null)
                val intent = bootstrapIntents.createInitial(
                    credentialEpochId = auth.credentialEpochId,
                    deviceId = auth.deviceId,
                    createdAtUtc = createdAtUtc,
                )
                try {
                    val identity = SyncRequestPersistenceStore(database)
                        .installOrRetainBootstrapIntent(
                            expectedCredentialEpochId = auth.credentialEpochId,
                            expectedDeviceId = auth.deviceId,
                            proposedIntent = intent,
                            updatedAtUtc = createdAtUtc,
                        )
                    PersistedDurableRequestRef(
                        endpointId = M2Endpoint.SYNC_BOOTSTRAP.endpointId,
                        requestIdentity = identity,
                    )
                } finally {
                    intent.firstRequest.wipeTransientProtectionBuffers()
                }
            }

            DurableBootstrapIntentKind.CONTINUE -> {
                val session = checkNotNull(activeSession)
                check(session.nextPageCursor != null && session.nextPageIndex > 0) {
                    "Active bootstrap session has no continuation cursor"
                }
                val continuation = bootstrapIntents.createContinuation(
                    session = session,
                    createdAtUtc = createdAtUtc,
                )
                try {
                    transportDao.insertRequest(continuation)
                    PersistedDurableRequestRef(
                        endpointId = continuation.endpointId,
                        requestIdentity = continuation.requestIdentity,
                    )
                } finally {
                    continuation.wipeTransientProtectionBuffers()
                }
            }
        }
        return ProtectedSyncRequestPlanningOutcome.Created(
            plan = plan,
            kind = DurableSyncRequestKind.BOOTSTRAP,
            request = persisted,
        )
    }

    private suspend fun createPull(
        auth: SyncAuthStateEntity,
        stream: SyncStreamStateEntity,
        createdAtUtc: String,
    ): ProtectedSyncRequestPlanningOutcome {
        val request = PullRequest(
            requestId = bootstrapIntents.nextRequestIdentity(),
            deviceId = auth.deviceId,
            cursor = checkNotNull(stream.appliedCursor),
            pageSize = settings.pageSize,
        )
        val persisted = protectedRequests.persistPull(
            request = request,
            persistence = bootstrapIntents.persistence(auth, createdAtUtc),
        )
        return ProtectedSyncRequestPlanningOutcome.Created(
            plan = DurableSyncRequestPlan.CreatePull,
            kind = DurableSyncRequestKind.PULL,
            request = persisted,
        )
    }

    private suspend fun createPush(
        auth: SyncAuthStateEntity,
        createdAtUtc: String,
    ): ProtectedSyncRequestPlanningOutcome {
        val authority = bootstrapIntents.authority(auth, createdAtUtc)
        val construction = actionablePushes.build(authority)
        requirePushConstructionBinding(construction, authority)
        val persisted = protectedRequests.persistPush(
            request = construction.request,
            persistence = construction.persistence,
            batch = construction.batch,
            items = construction.items,
        )
        return ProtectedSyncRequestPlanningOutcome.Created(
            plan = DurableSyncRequestPlan.CreatePush,
            kind = DurableSyncRequestKind.PUSH,
            request = persisted,
        )
    }

    private suspend fun classifyAuth(
        auth: SyncAuthStateEntity?,
        nowEpochMs: Long,
    ): DurableSyncPlannerAuth {
        if (auth == null) return DurableSyncPlannerAuth.MISSING
        val identity = identityDao.findIdentity()
        val identityMatches = identity != null &&
            identity.installationId == auth.installationId &&
            identity.localOwnerId == auth.localOwnerId &&
            identity.serverDeviceId == auth.deviceId &&
            identity.serverPersonId == auth.personId
        if (!identityMatches || auth.familyExpiresAtEpochMs <= nowEpochMs) {
            return DurableSyncPlannerAuth.UNUSABLE
        }
        return when (auth.state) {
            "active" -> if (auth.accessExpiresAtEpochMs > nowEpochMs) {
                DurableSyncPlannerAuth.ACTIVE_CURRENT
            } else {
                DurableSyncPlannerAuth.REFRESH_REQUIRED
            }

            "refresh_in_flight" -> DurableSyncPlannerAuth.REFRESH_REQUIRED
            "revoke_pending" -> DurableSyncPlannerAuth.REVOKE_PENDING
            else -> DurableSyncPlannerAuth.UNUSABLE
        }
    }

    private suspend fun hasConflictingEnrollment(auth: SyncAuthStateEntity): Boolean =
        authDao.findAttempts(
            endpointId = M2Endpoint.AUTH_ENROLL.endpointId,
            state = "dispatching",
        ).any { attempt ->
            val credentialMatches = if (attempt.credentialEpochId == null) {
                true
            } else {
                attempt.credentialEpochId == auth.credentialEpochId &&
                    attempt.expectedDeviceId == auth.deviceId &&
                    attempt.expectedGeneration == auth.generation
            }
            attempt.installationId == auth.installationId &&
                attempt.localOwnerId == auth.localOwnerId &&
                credentialMatches
        }

    private fun classifyStream(
        auth: SyncAuthStateEntity?,
        stream: SyncStreamStateEntity?,
    ): DurableSyncPlannerStream {
        if (
            auth == null || stream == null ||
            stream.credentialEpochId != auth.credentialEpochId ||
            stream.deviceId != auth.deviceId ||
            stream.bootstrapRequired != auth.bootstrapRequired
        ) {
            return DurableSyncPlannerStream.MISSING
        }
        if (stream.phase == "integrity_halted" || stream.integrityErrorCode != null) {
            return DurableSyncPlannerStream.INTEGRITY_HALTED
        }
        return when {
            stream.phase == "bootstrap_required" && stream.bootstrapRequired ->
                DurableSyncPlannerStream.BOOTSTRAP_REQUIRED

            stream.phase == "incremental" && !stream.bootstrapRequired ->
                if (stream.appliedCursor == null) {
                    DurableSyncPlannerStream.INCREMENTAL_WITHOUT_CURSOR
                } else {
                    DurableSyncPlannerStream.INCREMENTAL_WITH_CURSOR
                }

            stream.phase == "pulling" && !stream.bootstrapRequired ->
                if (stream.appliedCursor == null) {
                    DurableSyncPlannerStream.PULLING_WITHOUT_CURSOR
                } else {
                    DurableSyncPlannerStream.PULLING_WITH_CURSOR
                }

            else -> DurableSyncPlannerStream.MISSING
        }
    }

    private companion object {
        const val MAX_PLANNING_CANDIDATES = 8
    }
}

/** Production request-construction adapter consumed by protected reducers. */
internal class ProductionProtectedBootstrapIntentBoundary(
    private val database: LifeAgentDatabase,
    private val protectedRequests: ProtectedSyncRequestStore,
    private val settings: ProtectedSyncRequestConstructionSettings,
    private val uuidGenerator: UuidGenerator = RandomUuidGenerator,
) : ProtectedBootstrapIntentBoundary {
    private val authDao = database.syncAuthDao()
    private val replicaDao = database.syncReplicaDao()

    override suspend fun createContinuation(
        session: SyncBootstrapSessionEntity,
        createdAtUtc: String,
    ): SyncHttpRequestEntity {
        check(database.inTransaction())
        val auth = requireCurrentAuth(session.credentialEpochId, session.deviceId)
        return protectedRequests.prepareBootstrapContinuationInCurrentTransaction(
            session = session,
            request = BootstrapRequest(
                requestId = nextRequestIdentity(),
                bootstrapId = session.bootstrapId,
                deviceId = session.deviceId,
                pageSize = settings.pageSize,
                pageCursor = checkNotNull(session.nextPageCursor),
            ),
            persistence = persistence(auth, createdAtUtc),
        )
    }

    override suspend fun createInitial(
        credentialEpochId: String,
        deviceId: String,
        createdAtUtc: String,
    ): BootstrapIntentPersistence {
        check(database.inTransaction())
        val auth = requireCurrentAuth(credentialEpochId, deviceId)
        val bootstrapId = uuidGenerator.next().toString()
        check(replicaDao.findBootstrapSession(bootstrapId) == null) {
            "Generated bootstrap identity is already persisted"
        }
        val session = SyncBootstrapSessionEntity(
            bootstrapId = bootstrapId,
            credentialEpochId = credentialEpochId,
            deviceId = deviceId,
            state = "staging",
            activeSlot = 1,
            snapshotId = null,
            nextPageCursor = null,
            candidateIncrementalCursor = null,
            nextPageIndex = 0,
            lastStagedServerSequence = null,
            stagedPageCount = 0,
            stagedBodyBytes = 0,
            createdAtUtc = createdAtUtc,
            updatedAtUtc = createdAtUtc,
        )
        return protectedRequests.prepareInitialBootstrapIntentInCurrentTransaction(
            session = session,
            request = BootstrapRequest(
                requestId = nextRequestIdentity(),
                bootstrapId = bootstrapId,
                deviceId = deviceId,
                pageSize = settings.pageSize,
                pageCursor = null,
            ),
            persistence = persistence(auth, createdAtUtc),
        )
    }

    override suspend fun verifyExistingCandidates(
        session: SyncBootstrapSessionEntity,
        failedAtUtc: String,
    ): Boolean = protectedRequests.verifyExistingBootstrapCandidates(session, failedAtUtc)

    internal fun nextRequestIdentity(): String = uuidGenerator.next().toString()

    internal fun persistence(
        auth: SyncAuthStateEntity,
        createdAtUtc: String,
    ): NewDurableRequestPersistence {
        val createdAtEpochMs = Instant.parse(createdAtUtc).toEpochMilli()
        return NewDurableRequestPersistence(
            localCredentialEpochId = auth.credentialEpochId,
            accessGenerationUsed = auth.generation,
            attemptBudget = settings.attemptBudget,
            deadlineAtEpochMs = Math.addExact(
                createdAtEpochMs,
                settings.requestLifetimeMillis,
            ),
            createdAtUtc = createdAtUtc,
        )
    }

    internal fun authority(
        auth: SyncAuthStateEntity,
        createdAtUtc: String,
    ): ProtectedActionablePushAuthority {
        val persistence = persistence(auth, createdAtUtc)
        return ProtectedActionablePushAuthority(
            credentialEpochId = persistence.localCredentialEpochId,
            deviceId = auth.deviceId,
            accessGeneration = persistence.accessGenerationUsed,
            createdAtUtc = persistence.createdAtUtc,
            attemptBudget = persistence.attemptBudget,
            deadlineAtEpochMs = persistence.deadlineAtEpochMs,
        )
    }

    private suspend fun requireCurrentAuth(
        credentialEpochId: String,
        deviceId: String,
    ): SyncAuthStateEntity {
        val auth = checkNotNull(authDao.findState()) {
            "Protected bootstrap construction requires current credentials"
        }
        check(
            auth.credentialEpochId == credentialEpochId &&
                auth.deviceId == deviceId &&
                auth.state in setOf("active", "refresh_in_flight"),
        ) {
            "Protected bootstrap construction belongs to stale credentials"
        }
        return auth
    }
}

private fun requirePushConstructionBinding(
    construction: ProtectedActionablePushConstruction,
    authority: ProtectedActionablePushAuthority,
) {
    val operations = construction.request.operations
    val items = construction.items
    require(
        construction.request.deviceId == authority.deviceId &&
            construction.request.batchId == construction.batch.batchId &&
            construction.batch.endpointId == M2Endpoint.SYNC_PUSH.endpointId &&
            construction.batch.requestIdentity == construction.request.batchId &&
            construction.batch.createdAtUtc == authority.createdAtUtc &&
            items.size in 1..100 &&
            operations.size == items.size &&
            construction.batch.operationCount == items.size &&
            items.map { it.ordinal } == items.indices.toList() &&
            items.all { it.batchId == construction.batch.batchId } &&
            items.zipWithNext().all { (previous, next) ->
                previous.localSequence < next.localSequence
            } &&
            items.map { it.operationId }.distinct().size == items.size &&
            construction.persistence.localCredentialEpochId == authority.credentialEpochId &&
            construction.persistence.accessGenerationUsed == authority.accessGeneration &&
            construction.persistence.createdAtUtc == authority.createdAtUtc &&
            construction.persistence.attemptBudget == authority.attemptBudget &&
            construction.persistence.deadlineAtEpochMs == authority.deadlineAtEpochMs,
    ) {
        "Canonical push construction drifted from current planning authority"
    }
    operations.zip(items).forEach { (operation, item) ->
        require(
            operation.ordinal == item.ordinal &&
                operation.clientSequence == item.localSequence &&
                operation.operationId == item.operationId &&
                operation.operationContentSha256 == item.wireOperationContentSha256,
        ) {
            "Canonical push operation drifted from its persistence row"
        }
    }
}

private fun String.toRequestKindOrNull(): DurableSyncRequestKind? = when (this) {
    M2Endpoint.AUTH_REVOKE.endpointId -> DurableSyncRequestKind.REVOKE
    M2Endpoint.SYNC_BOOTSTRAP.endpointId -> DurableSyncRequestKind.BOOTSTRAP
    M2Endpoint.SYNC_PUSH.endpointId -> DurableSyncRequestKind.PUSH
    M2Endpoint.SYNC_PULL.endpointId -> DurableSyncRequestKind.PULL
    else -> null
}
