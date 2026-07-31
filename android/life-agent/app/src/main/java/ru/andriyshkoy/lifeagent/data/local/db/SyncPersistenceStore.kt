package ru.andriyshkoy.lifeagent.data.local.db

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalCaptureEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalEventHeadEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalEventRevisionEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalInstallationEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalLifeEventEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalOwnerEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalRevisionParentEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncBootstrapSessionEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncHttpRequestEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPageReceiptEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncServerChangeEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncStreamStateEntity

/**
 * A verified HTTP response and every durable state transition derived from it
 * must commit together. Callers perform transport/schema verification before
 * constructing these values; this store owns the crash boundary.
 */
data class TerminalHttpResponsePersistence(
    val endpointId: String,
    val requestIdentity: String,
    val expectedAttemptId: String,
    val httpStatus: Int,
    val exactResponseBody: ByteArray,
    val responseSha256: String,
    val terminalAtUtc: String,
    val terminalErrorCode: String?,
)

sealed interface PushResultPersistence {
    val ordinal: Int
}

data class PushAckPersistence(
    override val ordinal: Int,
    val change: SyncServerChangeEntity,
    val detailsJcs: ByteArray? = null,
) : PushResultPersistence

data class PushErrorPersistence(
    override val ordinal: Int,
    val operationId: String?,
    val operationContentSha256: String?,
    val errorCode: String,
    val retryable: Boolean,
    val detailsJcs: ByteArray?,
) : PushResultPersistence

class SyncPersistenceStore(
    private val database: LifeAgentDatabase,
) {
    private val authDao = database.syncAuthDao()
    private val mutationDao = database.noteMutationDao()
    private val outboxDao = database.outboxDao()
    private val replicaDao = database.syncReplicaDao()
    private val transportDao = database.syncTransportDao()
    private val replicaCodec = ReplicaChangeCodec()

    suspend fun commitPushResponse(
        response: TerminalHttpResponsePersistence,
        results: List<PushResultPersistence>,
    ) {
        require(response.endpointId == PUSH_ENDPOINT)
        try {
            database.withTransaction {
                val request = requireReplicaValue(
                    transportDao.findRequest(
                        endpointId = response.endpointId,
                        requestIdentity = response.requestIdentity,
                    ),
                    "push_request_missing",
                    "Push response has no durable request",
                )
                val stream = requireReplicaValue(
                    replicaDao.findStreamState(),
                    "sync_stream_missing",
                    "Push response has no active sync stream",
                )
                requireReplica(
                    request.credentialEpochId == stream.credentialEpochId &&
                        request.deviceId == stream.deviceId,
                    "sync_request_binding_drift",
                    "Push request no longer belongs to the active sync stream",
                )
                if (isStaleAttemptCallback(request, response)) {
                    // A newer worker owns the request. The late response may
                    // neither install its bytes nor reduce its parsed results.
                    return@withTransaction
                }
                val batch = requireReplicaValue(
                    transportDao.findBatch(response.requestIdentity),
                    "push_batch_missing",
                    "Push batch is missing",
                )
                requireReplica(
                    batch.batchId == response.requestIdentity &&
                        batch.endpointId == response.endpointId &&
                        batch.requestIdentity == response.requestIdentity &&
                        request.idempotencyKey == batch.batchId &&
                        batch.operationCount in 1..100,
                    "push_response_drift",
                    "Push response does not bind to its durable batch identity",
                )
                val items = transportDao.findBatchItems(batch.batchId)
                requireReplica(
                    items.size == batch.operationCount &&
                        results.map { it.ordinal } == items.indices.toList() &&
                        items.zipWithNext().all { (previous, next) ->
                            previous.localSequence < next.localSequence
                        },
                    "push_response_drift",
                    "Push response must contain one result in physical ordinal order",
                )
                items.forEach { item ->
                    val outbox = requireReplicaValue(
                        mutationDao.findOutbox(item.operationId),
                        "push_batch_membership_drift",
                        "Push batch item has no durable outbox operation",
                    )
                    requireReplica(
                        outbox.localSequence == item.localSequence &&
                            outbox.wireOperationContentSha256 ==
                            item.wireOperationContentSha256,
                        "push_batch_membership_drift",
                        "Push batch item differs from its immutable outbox operation",
                    )
                }
                val installedResponse = installOrVerifyTerminalResponse(response)
                if (installedResponse) {
                    requireReplica(
                        stream.integrityErrorCode == null,
                        "sync_integrity_already_halted",
                        "Push sync is already halted",
                    )
                }

                results.zip(items).forEach { (result, item) ->
                    requireReplica(
                        result.ordinal == item.ordinal,
                        "push_response_drift",
                        "Push result moved from its physical batch ordinal",
                    )
                    if (!installedResponse) {
                        verifyPushResultReplay(
                            response = response,
                            batchId = batch.batchId,
                            itemOperationId = item.operationId,
                            itemWireSha256 = item.wireOperationContentSha256,
                            result = result,
                        )
                    } else {
                        when (result) {
                            is PushAckPersistence -> commitAck(
                                response = response,
                                batchId = batch.batchId,
                                itemOperationId = item.operationId,
                                itemWireSha256 = item.wireOperationContentSha256,
                                result = result,
                            )

                            is PushErrorPersistence -> commitError(
                                response = response,
                                batchId = batch.batchId,
                                itemOperationId = item.operationId,
                                itemWireSha256 = item.wireOperationContentSha256,
                                result = result,
                            )
                        }
                    }
                }
                val terminalIntegrityErrorCode = results
                    .asSequence()
                    .filterIsInstance<PushErrorPersistence>()
                    .map { it.errorCode }
                    .firstOrNull(TERMINAL_INTEGRITY_ITEM_ERROR_CODES::contains)
                if (terminalIntegrityErrorCode != null) {
                    if (installedResponse) {
                        requireReplica(
                            replicaDao.markIntegrityHalted(
                                credentialEpochId = request.credentialEpochId,
                                deviceId = request.deviceId,
                                errorCode = terminalIntegrityErrorCode,
                                updatedAtUtc = response.terminalAtUtc,
                            ) == 1,
                            terminalIntegrityErrorCode,
                            "Terminal push item could not halt the sync stream",
                        )
                    } else {
                        val retainedStream = requireReplicaValue(
                            replicaDao.findStreamState(),
                            "sync_stream_missing",
                            "Terminal push replay has no retained sync stream",
                        )
                        requireReplica(
                            retainedStream.credentialEpochId ==
                                request.credentialEpochId &&
                                retainedStream.deviceId == request.deviceId &&
                                retainedStream.phase == "integrity_halted" &&
                                retainedStream.integrityErrorCode ==
                                terminalIntegrityErrorCode,
                            terminalIntegrityErrorCode,
                            "Terminal push replay lost its durable stream halt",
                        )
                    }
                }
            }
        } catch (error: ReplicaIntegrityException) {
            haltPushAfterRollback(
                response = response,
                error = error,
            )
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val integrityError = ReplicaIntegrityException(
                errorCode = "push_reduction_failed",
                message = "Verified push response could not reduce atomically",
                cause = error,
            )
            haltPushAfterRollback(
                response = response,
                error = integrityError,
            )
            throw integrityError
        }
    }

    suspend fun commitBootstrapPage(
        response: TerminalHttpResponsePersistence,
        receipt: SyncPageReceiptEntity,
        changes: List<ReplicaChangePersistence>,
    ) {
        require(receipt.completeOrHasMore) {
            "A non-final bootstrap page requires a durable next-request intent"
        }
        val bootstrapId = receipt.bootstrapId
        var streamBinding: SyncStreamStateEntity? = null
        try {
            database.withTransaction {
                requireReplica(
                    response.endpointId == BOOTSTRAP_ENDPOINT &&
                        receipt.endpointId == response.endpointId &&
                        receipt.requestIdentity == response.requestIdentity &&
                        receipt.receivedAtUtc == response.terminalAtUtc,
                    "bootstrap_receipt_drift",
                    "Bootstrap receipt does not bind to its terminal response",
                )
                val durableBootstrapId = requireReplicaValue(
                    bootstrapId,
                    "bootstrap_receipt_drift",
                    "Bootstrap receipt has no bootstrap identity",
                )
                val session = requireReplicaValue(
                    replicaDao.findBootstrapSession(durableBootstrapId),
                    "bootstrap_session_missing",
                    "Bootstrap staging session is missing",
                )
                val stream = requireReplicaValue(
                    replicaDao.findStreamState(),
                    "sync_stream_missing",
                    "Sync stream state is missing",
                )
                val request = verifyRequestAndStreamBinding(response, stream)
                requireReplica(
                    session.credentialEpochId == stream.credentialEpochId &&
                        session.deviceId == stream.deviceId,
                    "bootstrap_session_binding_drift",
                    "Bootstrap session no longer belongs to the active stream",
                )
                if (isStaleAttemptCallback(request, response)) {
                    return@withTransaction
                }
                if (
                    request.state == "terminal" &&
                    session.state in setOf("staging", "complete")
                ) {
                    streamBinding = stream
                }
                verifyBootstrapRequestBinding(
                    request = request,
                    session = session,
                    expectedPageCursor = receipt.fromCursor,
                )

                val installedResponse = installOrVerifyTerminalResponse(response)
                if (installedResponse) {
                    requireReplica(
                        session.state == "staging" &&
                            session.activeSlot == 1 &&
                            replicaDao.findBootstrapSessionWithActiveSlot()?.bootstrapId ==
                            durableBootstrapId,
                        "bootstrap_session_binding_drift",
                        "New bootstrap page does not belong to the active shadow",
                    )
                    requireReplica(
                        stream.integrityErrorCode == null,
                        "sync_integrity_already_halted",
                        "Bootstrap sync is already halted",
                    )
                    streamBinding = stream
                }

                validateBootstrapPageShape(receipt, changes)
                val decodedPage = changes.map(replicaCodec::decode)
                decodedPage.forEach { change ->
                    verifyServerReceiptOverlap(change)
                }
                if (!installedResponse) {
                    val existingReceipt = verifyPageReceiptReplay(receipt)
                    verifyStagedPageReplay(durableBootstrapId, receipt.pageId, changes)
                    val retainedTerminalAtUtc = requireReplicaValue(
                        request.terminalAtUtc,
                        "bootstrap_receipt_drift",
                        "Bootstrap replay lost its first terminal timestamp",
                    )
                    requireReplica(
                        existingReceipt.receivedAtUtc == retainedTerminalAtUtc,
                        "bootstrap_receipt_drift",
                        "Bootstrap receipt lost its first terminal timestamp",
                    )
                    when (session.state) {
                        "staging" -> requireReplica(
                            session.activeSlot == 1 &&
                                replicaDao
                                    .findBootstrapSessionWithActiveSlot()
                                    ?.bootstrapId == durableBootstrapId &&
                                existingReceipt.state == "staged" &&
                                existingReceipt.appliedAtUtc == null,
                            "bootstrap_receipt_drift",
                            "Staging bootstrap replay has a non-staged receipt",
                        )

                        "complete" -> {
                            requireReplica(
                                session.activeSlot == null &&
                                    existingReceipt.state == "applied" &&
                                    existingReceipt.appliedAtUtc ==
                                    session.updatedAtUtc,
                                "bootstrap_receipt_drift",
                                "Completed bootstrap replay has a non-applied receipt",
                            )
                            verifyMaterializedReplay(decodedPage)
                        }

                        else -> throw ReplicaIntegrityException(
                            errorCode = "bootstrap_session_binding_drift",
                            message = "Bootstrap page replay has no durable active state",
                        )
                    }
                    return@withTransaction
                }
                validateBootstrapSessionContinuation(
                    session = session,
                    receipt = receipt,
                    changes = changes,
                )
                requireReplica(
                    replicaDao.findPageReceipt(receipt.pageId) == null &&
                        replicaDao.findPageReceiptByRequest(
                            receipt.endpointId,
                            receipt.requestIdentity,
                        ) == null,
                    "bootstrap_page_collision",
                    "Bootstrap page or request identity is already claimed",
                )

                val priorChanges = replicaDao.findStagedChanges(durableBootstrapId)
                    .map(ReplicaChangePersistence::from)
                validateTopology(
                    (priorChanges + changes).map(replicaCodec::decode),
                    ReplicaTopologyState(),
                )
                try {
                    replicaDao.stageBootstrapPage(
                        receipt = receipt,
                        changes = changes.map {
                            it.asStaged(
                                bootstrapId = durableBootstrapId,
                                pageId = receipt.pageId,
                            )
                        },
                        responseBodyBytes = response.exactResponseBody.size.toLong(),
                    )
                } catch (error: ReplicaIntegrityException) {
                    throw error
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    throw ReplicaIntegrityException(
                        errorCode = "bootstrap_shadow_drift",
                        message = "Bootstrap page could not advance the shadow atomically",
                        cause = error,
                    )
                }

                if (receipt.completeOrHasMore) {
                    promoteBootstrapSnapshot(
                        bootstrapId = durableBootstrapId,
                        response = response,
                        stream = stream,
                    )
                }
            }
        } catch (error: ReplicaIntegrityException) {
            haltStreamAfterRollback(
                stream = streamBinding,
                error = error,
                updatedAtUtc = response.terminalAtUtc,
            )
            throw error
        }
    }

    /**
     * Applies a verified incremental page and advances the durable cursor in
     * the same Room transaction. A committed replay only verifies receipts.
     */
    suspend fun commitPullPage(
        response: TerminalHttpResponsePersistence,
        receipt: SyncPageReceiptEntity,
        changes: List<ReplicaChangePersistence>,
    ) {
        var streamBinding: SyncStreamStateEntity? = null
        try {
            database.withTransaction {
                requireReplica(
                    response.endpointId == PULL_ENDPOINT &&
                        receipt.endpointId == response.endpointId &&
                        receipt.requestIdentity == response.requestIdentity &&
                        receipt.receivedAtUtc == response.terminalAtUtc &&
                        receipt.appliedAtUtc == response.terminalAtUtc,
                    "pull_receipt_drift",
                    "Pull receipt does not bind to its terminal response",
                )
                val stream = requireReplicaValue(
                    replicaDao.findStreamState(),
                    "sync_stream_missing",
                    "Sync stream state is missing",
                )
                val request = verifyRequestAndStreamBinding(response, stream)
                if (isStaleAttemptCallback(request, response)) {
                    return@withTransaction
                }
                streamBinding = stream
                validatePullPageShape(receipt, changes)
                val decodedPage = changes.map(replicaCodec::decode)
                decodedPage.forEach { change ->
                    verifyServerReceiptOverlap(change)
                }

                val installedResponse = installOrVerifyTerminalResponse(response)
                if (!installedResponse) {
                    val existingReceipt = verifyPageReceiptReplay(receipt)
                    val retainedTerminalAtUtc = requireReplicaValue(
                        request.terminalAtUtc,
                        "pull_receipt_drift",
                        "Pull replay lost its first terminal timestamp",
                    )
                    requireReplica(
                        existingReceipt.state == "applied" &&
                            existingReceipt.receivedAtUtc == retainedTerminalAtUtc &&
                            existingReceipt.appliedAtUtc == retainedTerminalAtUtc,
                        "pull_receipt_drift",
                        "Pull replay has a non-applied durable receipt",
                    )
                    verifyMaterializedReplay(decodedPage)
                    return@withTransaction
                }
                requireReplica(
                    stream.integrityErrorCode == null,
                    "sync_integrity_already_halted",
                    "Incremental sync is already halted",
                )
                requireReplica(
                    !stream.bootstrapRequired &&
                        stream.phase in setOf("incremental", "pulling"),
                    "bootstrap_required",
                    "Pull cannot bypass an explicit bootstrap requirement",
                )
                requireReplica(
                    replicaDao.findBootstrapSessionWithActiveSlot() == null,
                    "pull_during_bootstrap",
                    "Pull cannot apply while bootstrap staging is active",
                )
                requireReplica(
                    receipt.fromCursor == stream.appliedCursor &&
                        (
                            changes.isEmpty() ||
                                changes.first().serverSequence >
                                stream.lastAppliedServerSequence
                            ),
                    "pull_cursor_invalid",
                    "Pull page does not continue the committed stream position",
                )
                requireReplica(
                    replicaDao.findPageReceipt(receipt.pageId) == null &&
                        replicaDao.findPageReceiptByRequest(
                            receipt.endpointId,
                            receipt.requestIdentity,
                        ) == null,
                    "pull_page_collision",
                    "Pull page or request identity is already claimed",
                )

                val topology = buildAppliedTopology(stream.lastAppliedServerSequence)
                verifyExistingPullCoverage(
                    exclusiveServerSequence = stream.lastAppliedServerSequence,
                    incoming = decodedPage,
                )
                validateTopology(decodedPage, topology)
                replicaDao.insertPageReceipt(receipt)
                decodedPage.forEach { change ->
                    materializeReplicaChange(
                        change = change,
                        endpointId = response.endpointId,
                        requestIdentity = response.requestIdentity,
                        verifiedAtUtc = response.terminalAtUtc,
                    )
                }
                val nextLastSequence =
                    changes.lastOrNull()?.serverSequence
                        ?: stream.lastAppliedServerSequence
                requireReplica(
                    replicaDao.compareAndAdvanceCursor(
                        credentialEpochId = stream.credentialEpochId,
                        deviceId = stream.deviceId,
                        expectedCursor = stream.appliedCursor,
                        expectedServerSequence = stream.lastAppliedServerSequence,
                        nextCursor = requireReplicaValue(
                            receipt.nextCursor,
                            "pull_cursor_invalid",
                            "Pull receipt has no next cursor",
                        ),
                        lastServerSequence = nextLastSequence,
                        nextPhase = if (receipt.completeOrHasMore) "pulling" else "incremental",
                        updatedAtUtc = response.terminalAtUtc,
                    ) == 1,
                    "pull_cursor_cas_failed",
                    "Pull data and cursor could not advance together",
                )
            }
        } catch (error: ReplicaIntegrityException) {
            haltStreamAfterRollback(
                stream = streamBinding,
                error = error,
                updatedAtUtc = response.terminalAtUtc,
            )
            throw error
        }
    }

    /**
     * A valid cursor_expired response abandons only the uncommitted bootstrap
     * shadow. The previously committed replica and cursor remain visible.
     */
    suspend fun commitBootstrapCursorExpired(
        response: TerminalHttpResponsePersistence,
        bootstrapId: String,
        replacementIntent: BootstrapIntentPersistence,
    ) {
        require(response.endpointId == BOOTSTRAP_ENDPOINT)
        require(response.terminalErrorCode == CURSOR_EXPIRED)
        validateBootstrapIntent(replacementIntent)
        var streamBinding: SyncStreamStateEntity? = null
        try {
            database.withTransaction {
                val request = requireReplicaValue(
                    transportDao.findRequest(response.endpointId, response.requestIdentity),
                    "bootstrap_request_missing",
                    "Expired bootstrap request is missing",
                )
                if (isStaleAttemptCallback(request, response)) {
                    return@withTransaction
                }
                val session = requireReplicaValue(
                    replicaDao.findBootstrapSession(bootstrapId),
                    "bootstrap_session_missing",
                    "Expired bootstrap session is missing",
                )
                verifyBootstrapRequestBinding(
                    request = request,
                    session = session,
                    expectedPageCursor = session.nextPageCursor,
                )
                val installed = installOrVerifyTerminalResponse(response)
                if (!installed) {
                    val retainedTerminalAtUtc = requireReplicaValue(
                        request.terminalAtUtc,
                        "bootstrap_expiry_replay_drift",
                        "Expired bootstrap replay lost its first terminal timestamp",
                    )
                    verifyBootstrapExpiryReplay(
                        response = response,
                        session = session,
                        retainedTerminalAtUtc = retainedTerminalAtUtc,
                    )
                    return@withTransaction
                }
                requireReplica(
                    session.state == "staging" &&
                        session.activeSlot == 1 &&
                        replicaDao.findBootstrapSessionWithActiveSlot()?.bootstrapId ==
                        bootstrapId,
                    "bootstrap_expiry_binding_drift",
                    "Expired response does not belong to the active bootstrap shadow",
                )
                val stream = requireReplicaValue(
                    replicaDao.findStreamState(),
                    "sync_stream_missing",
                    "Sync stream state is missing",
                )
                requireReplica(
                    stream.credentialEpochId == session.credentialEpochId &&
                        stream.deviceId == session.deviceId,
                    "sync_request_binding_drift",
                    "Expired bootstrap no longer belongs to the active sync stream",
                )
                streamBinding = stream
                val stagedChangeCount =
                    replicaDao.countStagedBootstrapChanges(bootstrapId)
                requireReplica(
                    replicaDao.deleteStagedBootstrapChanges(bootstrapId) ==
                        stagedChangeCount,
                    "bootstrap_expiry_staging_drift",
                    "Expired bootstrap did not discard every staged change",
                )
                requireReplica(
                    replicaDao.deleteStagedBootstrapReceipts(bootstrapId) ==
                        session.stagedPageCount,
                    "bootstrap_expiry_staging_drift",
                    "Expired bootstrap did not discard every staged page",
                )
                requireReplica(
                    replicaDao.countStagedBootstrapChanges(bootstrapId) == 0,
                    "bootstrap_expiry_staging_drift",
                    "Expired bootstrap retained staged changes",
                )
                val expiryReceipt = bootstrapExpiryReceipt(response, session)
                requireReplica(
                    replicaDao.findPageReceipt(expiryReceipt.pageId) == null &&
                        replicaDao.findPageReceiptByRequest(
                            expiryReceipt.endpointId,
                            expiryReceipt.requestIdentity,
                        ) == null,
                    "bootstrap_expiry_receipt_collision",
                    "Expired bootstrap receipt identity is already claimed",
                )
                replicaDao.insertPageReceipt(expiryReceipt)
                requireReplica(
                    replicaDao.markBootstrapExpired(
                        bootstrapId = bootstrapId,
                        credentialEpochId = request.credentialEpochId,
                        deviceId = request.deviceId,
                        updatedAtUtc = response.terminalAtUtc,
                    ) == 1,
                    "bootstrap_expiry_binding_drift",
                    "Expired bootstrap shadow could not retain its terminal binding",
                )
                requireReplica(
                    replicaDao.requireBootstrap(
                        credentialEpochId = request.credentialEpochId,
                        deviceId = request.deviceId,
                        updatedAtUtc = response.terminalAtUtc,
                    ) == 1,
                    "sync_stream_missing",
                    "Expired bootstrap could not restore bootstrap-required phase",
                )
                requireReplica(
                    authDao.setBootstrapRequired(
                        credentialEpochId = request.credentialEpochId,
                        deviceId = request.deviceId,
                        bootstrapRequired = true,
                        updatedAtUtc = response.terminalAtUtc,
                    ) == 1,
                    "auth_stream_binding_drift",
                    "Expired bootstrap could not gate its credential family",
                )
                requireReplica(
                    replacementIntent.session.credentialEpochId ==
                        request.credentialEpochId &&
                        replacementIntent.session.deviceId == request.deviceId,
                    "bootstrap_replacement_binding_drift",
                    "Replacement bootstrap intent belongs to another stream",
                )
                val retainedRequestIdentity =
                    SyncRequestPersistenceStore(database)
                        .installOrRetainBootstrapIntent(
                            expectedCredentialEpochId =
                                request.credentialEpochId,
                            expectedDeviceId = request.deviceId,
                            proposedIntent = replacementIntent,
                            updatedAtUtc = response.terminalAtUtc,
                        )
                releaseOpenPushBatchesForBootstrap(
                    credentialEpochId = request.credentialEpochId,
                    deviceId = request.deviceId,
                )
                transportDao.invalidateSupersededSyncRequests(
                    credentialEpochId = request.credentialEpochId,
                    deviceId = request.deviceId,
                    retainedBootstrapRequestIdentity =
                        retainedRequestIdentity,
                    terminalAtUtc = response.terminalAtUtc,
                )
            }
        } catch (error: ReplicaIntegrityException) {
            haltStreamAfterRollback(
                stream = streamBinding,
                error = error,
                updatedAtUtc = response.terminalAtUtc,
            )
            throw error
        }
    }

    /**
     * cursor_invalid is a protocol-integrity failure, never an automatic
     * bootstrap recovery signal.
     */
    suspend fun commitCursorInvalid(response: TerminalHttpResponsePersistence) {
        require(response.endpointId == BOOTSTRAP_ENDPOINT || response.endpointId == PULL_ENDPOINT)
        require(response.terminalErrorCode == CURSOR_INVALID)
        var streamBinding: SyncStreamStateEntity? = null
        try {
            database.withTransaction {
                val request = requireReplicaValue(
                    transportDao.findRequest(response.endpointId, response.requestIdentity),
                    "sync_request_missing",
                    "Cursor-invalid request is missing",
                )
                if (request.state == "terminal") {
                    val stream = replicaDao.findStreamState()
                    val activeStream = stream?.takeIf {
                        request.credentialEpochId == it.credentialEpochId &&
                            request.deviceId == it.deviceId
                    }
                    streamBinding = activeStream
                    val activeBinding =
                        if (
                            response.endpointId == BOOTSTRAP_ENDPOINT &&
                            activeStream != null
                        ) {
                            val requestBody = bootstrapRequestBody(request)
                            val bootstrapId = bootstrapRequestString(
                                requestBody,
                                "bootstrap_id",
                            )
                            val session = replicaDao.findBootstrapSession(bootstrapId)
                            if (
                                session != null &&
                                session.state == "staging" &&
                                session.activeSlot == 1 &&
                                replicaDao
                                    .findBootstrapSessionWithActiveSlot()
                                    ?.bootstrapId == bootstrapId
                            ) {
                                streamBinding = activeStream
                                verifyBootstrapRequestBinding(
                                    request = request,
                                    session = session,
                                    expectedPageCursor = session.nextPageCursor,
                                )
                                activeStream
                            } else {
                                streamBinding = null
                                null
                            }
                        } else {
                            activeStream
                        }
                    requireReplica(
                        !installOrVerifyTerminalResponse(response),
                        "terminal_response_drift",
                        "Cursor-invalid replay unexpectedly replaced its receipt",
                    )
                    if (activeBinding != null) {
                        requireReplica(
                            activeBinding.phase == "integrity_halted" &&
                                activeBinding.integrityErrorCode == CURSOR_INVALID,
                            CURSOR_INVALID,
                            "Cursor-invalid replay lost its durable stream halt",
                        )
                    }
                    return@withTransaction
                }
                if (isStaleAttemptCallback(request, response)) {
                    return@withTransaction
                }
                val stream = replicaDao.findStreamState()
                if (response.endpointId == BOOTSTRAP_ENDPOINT) {
                    val tentativeStream = stream?.takeIf {
                        request.credentialEpochId == it.credentialEpochId &&
                            request.deviceId == it.deviceId
                    }
                    streamBinding = tentativeStream
                    if (tentativeStream == null) {
                        streamBinding = null
                        requireReplica(
                            transportDao.markSupersededBootstrapRequest(
                                requestIdentity = request.requestIdentity,
                                credentialEpochId = request.credentialEpochId,
                                deviceId = request.deviceId,
                                expectedAttemptId = response.expectedAttemptId,
                                terminalAtUtc = response.terminalAtUtc,
                            ) == 1,
                            "bootstrap_supersession_drift",
                            "Historical bootstrap request lost its terminal CAS",
                        )
                        return@withTransaction
                    }
                    val requestBody = bootstrapRequestBody(request)
                    val bootstrapId = bootstrapRequestString(
                        requestBody,
                        "bootstrap_id",
                    )
                    val session = replicaDao.findBootstrapSession(bootstrapId)
                    if (
                        session == null ||
                        session.state != "staging" ||
                        session.activeSlot != 1 ||
                        replicaDao
                            .findBootstrapSessionWithActiveSlot()
                            ?.bootstrapId != bootstrapId
                    ) {
                        streamBinding = null
                        requireReplica(
                            transportDao.markSupersededBootstrapRequest(
                                requestIdentity = request.requestIdentity,
                                credentialEpochId = request.credentialEpochId,
                                deviceId = request.deviceId,
                                expectedAttemptId = response.expectedAttemptId,
                                terminalAtUtc = response.terminalAtUtc,
                            ) == 1,
                            "bootstrap_supersession_drift",
                            "Superseded bootstrap request lost its terminal CAS",
                        )
                        return@withTransaction
                    }
                    verifyBootstrapRequestBinding(
                        request = request,
                        session = session,
                        expectedPageCursor = session.nextPageCursor,
                    )
                }
                val activeStream = requireReplicaValue(
                    stream,
                    "sync_stream_missing",
                    "Cursor-invalid response has no active sync stream",
                )
                requireReplica(
                    request.credentialEpochId == activeStream.credentialEpochId &&
                        request.deviceId == activeStream.deviceId,
                    "sync_request_binding_drift",
                    "Cursor-invalid request no longer belongs to the active stream",
                )
                streamBinding = activeStream
                if (!installOrVerifyTerminalResponse(response)) {
                    requireReplica(
                        activeStream.phase == "integrity_halted" &&
                            activeStream.integrityErrorCode == CURSOR_INVALID,
                        CURSOR_INVALID,
                        "Cursor-invalid replay lost its durable stream halt",
                    )
                    return@withTransaction
                }
                requireReplica(
                    replicaDao.markIntegrityHalted(
                        credentialEpochId = request.credentialEpochId,
                        deviceId = request.deviceId,
                        errorCode = CURSOR_INVALID,
                        updatedAtUtc = response.terminalAtUtc,
                    ) == 1,
                    "sync_stream_missing",
                    "Cursor-invalid response could not halt the sync stream",
                )
            }
        } catch (error: ReplicaIntegrityException) {
            haltStreamAfterRollback(
                stream = streamBinding,
                error = error,
                updatedAtUtc = response.terminalAtUtc,
            )
            throw error
        }
    }

    private suspend fun verifyRequestAndStreamBinding(
        response: TerminalHttpResponsePersistence,
        stream: SyncStreamStateEntity,
    ): SyncHttpRequestEntity {
        val request = requireReplicaValue(
            transportDao.findRequest(response.endpointId, response.requestIdentity),
            "sync_request_missing",
            "Durable sync request is missing",
        )
        requireReplica(
            request.credentialEpochId == stream.credentialEpochId &&
                request.deviceId == stream.deviceId,
            "sync_request_binding_drift",
            "Durable request no longer belongs to the active sync stream",
        )
        return request
    }

    private fun isStaleAttemptCallback(
        request: SyncHttpRequestEntity,
        response: TerminalHttpResponsePersistence,
    ): Boolean =
        request.state != "terminal" &&
            (
                request.state != "sending" ||
                    request.activeAttemptId != response.expectedAttemptId
                )

    private fun verifyBootstrapRequestBinding(
        request: SyncHttpRequestEntity,
        session: SyncBootstrapSessionEntity,
        expectedPageCursor: String?,
    ) {
        val requestBody = bootstrapRequestBody(request)
        requireReplica(
            request.endpointId == BOOTSTRAP_ENDPOINT &&
                request.credentialEpochId == session.credentialEpochId &&
                request.deviceId == session.deviceId &&
                bootstrapRequestString(requestBody, "protocol_version") == "1.0.0" &&
                bootstrapRequestString(requestBody, "message_type") ==
                "bootstrap_request" &&
                bootstrapRequestString(requestBody, "request_id") ==
                request.requestIdentity &&
                bootstrapRequestString(requestBody, "bootstrap_id") ==
                session.bootstrapId &&
                bootstrapRequestString(requestBody, "device_id") ==
                session.deviceId &&
                bootstrapRequestNullableString(requestBody, "page_cursor") ==
                expectedPageCursor,
            "bootstrap_request_binding_drift",
            "Bootstrap request does not bind to the supplied shadow position",
        )
    }

    private fun bootstrapRequestBody(
        request: SyncHttpRequestEntity,
    ): JsonObject {
        val rawBody = requireReplicaValue(
            request.rawRequestBody,
            "bootstrap_request_binding_drift",
            "Bootstrap request body is unavailable",
        )
        val root = try {
            Json.parseToJsonElement(rawBody.decodeToString()) as? JsonObject
        } catch (error: Exception) {
            throw ReplicaIntegrityException(
                errorCode = "bootstrap_request_binding_drift",
                message = "Bootstrap request body cannot prove its session binding",
                cause = error,
            )
        }
        val requestBody = requireReplicaValue(
            root,
            "bootstrap_request_binding_drift",
            "Bootstrap request body is not an object",
        )
        return requestBody
    }

    private fun bootstrapRequestString(
        request: JsonObject,
        name: String,
    ): String {
        val value = request[name] as? JsonPrimitive
        requireReplica(
            value != null && value.isString,
            "bootstrap_request_binding_drift",
            "Bootstrap request $name is not a string",
        )
        return checkNotNull(value).content
    }

    private fun bootstrapRequestNullableString(
        request: JsonObject,
        name: String,
    ): String? = when (val value = request[name]) {
        JsonNull -> null
        is JsonPrimitive -> {
            requireReplica(
                value.isString,
                "bootstrap_request_binding_drift",
                "Bootstrap request $name is not a string or null",
            )
            value.content
        }

        else -> throw ReplicaIntegrityException(
            errorCode = "bootstrap_request_binding_drift",
            message = "Bootstrap request $name is missing or malformed",
        )
    }

    private fun bootstrapExpiryReceipt(
        response: TerminalHttpResponsePersistence,
        session: SyncBootstrapSessionEntity,
        retainedTerminalAtUtc: String = response.terminalAtUtc,
    ) = SyncPageReceiptEntity(
        pageId = response.requestIdentity,
        endpointId = response.endpointId,
        requestIdentity = response.requestIdentity,
        bootstrapId = session.bootstrapId,
        pageIndex = session.nextPageIndex,
        snapshotId = session.snapshotId,
        fromCursor = session.nextPageCursor,
        nextCursor = null,
        incrementalCursor = session.candidateIncrementalCursor,
        pageSha256 = response.responseSha256,
        changeCount = 0,
        completeOrHasMore = false,
        state = "expired",
        firstServerSequence = null,
        lastServerSequence = null,
        receivedAtUtc = retainedTerminalAtUtc,
        appliedAtUtc = retainedTerminalAtUtc,
    )

    private suspend fun verifyBootstrapExpiryReplay(
        response: TerminalHttpResponsePersistence,
        session: SyncBootstrapSessionEntity,
        retainedTerminalAtUtc: String,
    ) {
        requireReplica(
            session.state == "expired" &&
                session.activeSlot == null &&
                session.updatedAtUtc == retainedTerminalAtUtc &&
                replicaDao.countStagedBootstrapChanges(session.bootstrapId) == 0 &&
                replicaDao.countStagedBootstrapReceipts(session.bootstrapId) == 0,
            "bootstrap_expiry_replay_drift",
            "Expired bootstrap replay found a divergent retained shadow",
        )
        val expected = bootstrapExpiryReceipt(
            response = response,
            session = session,
            retainedTerminalAtUtc = retainedTerminalAtUtc,
        )
        requireReplica(
            replicaDao.findPageReceipt(expected.pageId) == expected &&
                replicaDao.findPageReceiptByRequest(
                    expected.endpointId,
                    expected.requestIdentity,
                ) == expected,
            "bootstrap_expiry_replay_drift",
            "Expired bootstrap replay differs from its retained receipt",
        )
    }

    private fun validateBootstrapPageShape(
        receipt: SyncPageReceiptEntity,
        changes: List<ReplicaChangePersistence>,
    ) {
        requireReplica(
            receipt.state == "staged" &&
                receipt.appliedAtUtc == null &&
                receipt.pageIndex >= 0 &&
                receipt.snapshotId != null &&
                receipt.incrementalCursor != null,
            "bootstrap_receipt_invalid",
            "Bootstrap receipt has an invalid durable state",
        )
        if (receipt.completeOrHasMore) {
            requireReplica(
                receipt.nextCursor == null,
                "bootstrap_cursor_invalid",
                "A complete bootstrap page must not expose a continuation cursor",
            )
        } else {
            requireReplica(
                receipt.nextCursor != null && changes.isNotEmpty(),
                "bootstrap_cursor_invalid",
                "An incomplete bootstrap page needs changes and a continuation cursor",
            )
        }
        if (changes.isEmpty()) {
            requireReplica(
                receipt.completeOrHasMore && receipt.nextCursor == null,
                "bootstrap_empty_page_invalid",
                "Only the final bootstrap page may be empty",
            )
        }
        verifyReceiptProjection(receipt, changes)
    }

    private fun validateBootstrapSessionContinuation(
        session: SyncBootstrapSessionEntity,
        receipt: SyncPageReceiptEntity,
        changes: List<ReplicaChangePersistence>,
    ) {
        requireReplica(
            session.state == "staging" &&
                session.activeSlot == 1 &&
                session.nextPageIndex == receipt.pageIndex &&
                session.nextPageCursor == receipt.fromCursor &&
                (session.snapshotId == null || session.snapshotId == receipt.snapshotId) &&
                (
                    session.candidateIncrementalCursor == null ||
                        session.candidateIncrementalCursor == receipt.incrementalCursor
                    ),
            "bootstrap_shadow_drift",
            "Bootstrap page does not continue the active shadow session",
        )
        val firstSequence = changes.firstOrNull()?.serverSequence
        requireReplica(
            firstSequence == null ||
                session.lastStagedServerSequence == null ||
                firstSequence > session.lastStagedServerSequence,
            "replica_topology_invalid",
            "Bootstrap page overlaps its already staged sequence prefix",
        )
    }

    private fun validatePullPageShape(
        receipt: SyncPageReceiptEntity,
        changes: List<ReplicaChangePersistence>,
    ) {
        requireReplica(
            receipt.bootstrapId == null &&
                receipt.snapshotId == null &&
                receipt.incrementalCursor == null &&
                receipt.state == "applied" &&
                receipt.appliedAtUtc != null &&
                receipt.pageIndex >= 0 &&
                receipt.nextCursor != null,
            "pull_receipt_invalid",
            "Pull receipt has an invalid durable state",
        )
        if (receipt.completeOrHasMore) {
            requireReplica(
                changes.isNotEmpty(),
                "pull_empty_page_invalid",
                "A pull page with has_more must contain changes",
            )
        }
        if (changes.isEmpty()) {
            requireReplica(
                !receipt.completeOrHasMore &&
                    receipt.nextCursor == receipt.fromCursor,
                "pull_empty_page_invalid",
                "An empty final pull page must preserve its cursor",
            )
        } else {
            requireReplica(
                receipt.nextCursor != receipt.fromCursor,
                "pull_cursor_invalid",
                "A non-empty pull page must advance its cursor",
            )
        }
        verifyReceiptProjection(receipt, changes)
    }

    private fun verifyReceiptProjection(
        receipt: SyncPageReceiptEntity,
        changes: List<ReplicaChangePersistence>,
    ) {
        requireReplica(
            receipt.changeCount == changes.size,
            "page_receipt_projection_drift",
            "Page receipt change count differs from the verified page",
        )
        requireReplica(
            changes.zipWithNext().all { (previous, next) ->
                previous.serverSequence < next.serverSequence
            },
            "replica_topology_invalid",
            "Page server sequences are not strictly increasing",
        )
        if (changes.isEmpty()) {
            requireReplica(
                receipt.firstServerSequence == null &&
                    receipt.lastServerSequence == null,
                "page_receipt_projection_drift",
                "Empty page has non-empty sequence bounds",
            )
        } else {
            requireReplica(
                receipt.firstServerSequence == changes.first().serverSequence &&
                    receipt.lastServerSequence == changes.last().serverSequence,
                "page_receipt_projection_drift",
                "Page sequence bounds differ from the verified changes",
            )
        }
    }

    private suspend fun verifyPageReceiptReplay(
        incoming: SyncPageReceiptEntity,
    ): SyncPageReceiptEntity {
        val byPage = replicaDao.findPageReceipt(incoming.pageId)
        val byRequest = replicaDao.findPageReceiptByRequest(
            incoming.endpointId,
            incoming.requestIdentity,
        )
        val existing = requireReplicaValue(
            byPage ?: byRequest,
            "page_receipt_missing",
            "Terminal page replay has no durable page receipt",
        )
        requireReplica(
            byPage == null || byRequest == null || byPage.pageId == byRequest.pageId,
            "page_receipt_collision",
            "Page and request identities resolve to different receipts",
        )
        requireReplica(
            pageReceiptImmutableProjection(existing) ==
                pageReceiptImmutableProjection(incoming),
            "page_receipt_drift",
            "Terminal page replay differs from the durable page receipt",
        )
        return existing
    }

    private fun pageReceiptImmutableProjection(
        receipt: SyncPageReceiptEntity,
    ): List<Any?> = listOf(
        receipt.pageId,
        receipt.endpointId,
        receipt.requestIdentity,
        receipt.bootstrapId,
        receipt.pageIndex,
        receipt.snapshotId,
        receipt.fromCursor,
        receipt.nextCursor,
        receipt.incrementalCursor,
        receipt.pageSha256,
        receipt.changeCount,
        receipt.completeOrHasMore,
        receipt.firstServerSequence,
        receipt.lastServerSequence,
    )

    private suspend fun verifyStagedPageReplay(
        bootstrapId: String,
        pageId: String,
        incoming: List<ReplicaChangePersistence>,
    ) {
        val existing = replicaDao.findStagedPageChanges(bootstrapId, pageId)
            .map(ReplicaChangePersistence::from)
        requireReplica(
            replicaChangesEqual(existing, incoming),
            "bootstrap_page_drift",
            "Bootstrap replay differs from the retained staged page",
        )
    }

    private suspend fun verifyMaterializedReplay(
        changes: List<DecodedReplicaChange>,
    ) {
        changes.forEach { change ->
            verifyServerReceiptOverlap(change)
            verifyLocalMaterialization(change)
        }
    }

    private suspend fun promoteBootstrapSnapshot(
        bootstrapId: String,
        response: TerminalHttpResponsePersistence,
        stream: SyncStreamStateEntity,
    ) {
        val stagedRows = replicaDao.findStagedChanges(bootstrapId)
        val staged = stagedRows
            .map(ReplicaChangePersistence::from)
            .map(replicaCodec::decode)
        val topology = ReplicaTopologyState()
        validateTopology(staged, topology)
        verifyExistingSnapshotCoverage(staged)

        stagedRows.zip(staged).forEach { (row, change) ->
            val pageReceipt = requireReplicaValue(
                replicaDao.findPageReceipt(row.pageId),
                "page_receipt_missing",
                "Staged bootstrap change lost its page receipt",
            )
            materializeReplicaChange(
                change = change,
                endpointId = pageReceipt.endpointId,
                requestIdentity = pageReceipt.requestIdentity,
                verifiedAtUtc = pageReceipt.receivedAtUtc,
            )
        }

        val session = requireReplicaValue(
            replicaDao.findBootstrapSession(bootstrapId),
            "bootstrap_session_missing",
            "Bootstrap session disappeared before promotion",
        )
        val incrementalCursor = requireReplicaValue(
            session.candidateIncrementalCursor,
            "bootstrap_cursor_invalid",
            "Final bootstrap page has no incremental cursor",
        )
        requireReplica(
            session.nextPageCursor == null &&
                session.lastStagedServerSequence ==
                staged.lastOrNull()?.input?.serverSequence,
            "bootstrap_shadow_drift",
            "Bootstrap shadow metadata differs from its staged changes",
        )
        requireReplica(
            replicaDao.markBootstrapReceiptsApplied(
                bootstrapId = bootstrapId,
                appliedAtUtc = response.terminalAtUtc,
            ) == session.stagedPageCount,
            "bootstrap_receipt_drift",
            "Not every bootstrap receipt advanced to applied",
        )
        requireReplica(
            replicaDao.promoteBootstrapCursor(
                credentialEpochId = stream.credentialEpochId,
                deviceId = stream.deviceId,
                incrementalCursor = incrementalCursor,
                lastServerSequence = topology.lastServerSequence,
                updatedAtUtc = response.terminalAtUtc,
            ) == 1,
            "bootstrap_cursor_cas_failed",
            "Bootstrap cursor could not replace the active replica cursor",
        )
        requireReplica(
            authDao.setBootstrapRequired(
                credentialEpochId = stream.credentialEpochId,
                deviceId = stream.deviceId,
                bootstrapRequired = false,
                updatedAtUtc = response.terminalAtUtc,
            ) == 1,
            "auth_stream_binding_drift",
            "Bootstrap promotion could not release the credential-family gate",
        )
        requireReplica(
            replicaDao.markBootstrapComplete(
                bootstrapId = bootstrapId,
                incrementalCursor = incrementalCursor,
                updatedAtUtc = response.terminalAtUtc,
            ) == 1,
            "bootstrap_promotion_failed",
            "Bootstrap shadow did not complete atomically",
        )
    }

    private suspend fun verifyExistingSnapshotCoverage(
        staged: List<DecodedReplicaChange>,
    ) {
        val stagedByOperation = staged.associateBy { it.input.operationId }
        val snapshotLastServerSequence =
            staged.lastOrNull()?.input?.serverSequence ?: 0L
        replicaDao.findServerChangesThrough(snapshotLastServerSequence)
            .forEach { existing ->
                val delivered = requireReplicaValue(
                    stagedByOperation[existing.operationId],
                    "bootstrap_snapshot_drift",
                    "Bootstrap snapshot omitted an existing terminal server change",
                )
                verifyServerChangeStable(
                    existing = existing,
                    incoming = delivered.input,
                )
            }
    }

    private suspend fun buildAppliedTopology(
        inclusiveServerSequence: Long,
    ): ReplicaTopologyState {
        val topology = ReplicaTopologyState()
        val applied = replicaDao.findServerChangesThrough(inclusiveServerSequence)
        applied.forEach { change ->
            val revision = requireReplicaValue(
                replicaDao.findRevision(change.revisionId),
                "replica_history_corrupt",
                "Applied server receipt lost its revision",
            )
            val event = requireReplicaValue(
                replicaDao.findEvent(change.eventId),
                "replica_history_corrupt",
                "Applied server receipt lost its event",
            )
            val parents = replicaDao.findRevisionParents(change.revisionId)
            requireReplica(
                parents.size <= 1,
                "replica_history_corrupt",
                "Applied linear revision has multiple parents",
            )
            topology.accept(
                ReplicaTopologyRecord(
                    serverSequence = change.serverSequence,
                    operationId = change.operationId,
                    operationContentSha256 = change.operationContentSha256,
                    captureId = change.captureId,
                    eventId = change.eventId,
                    revisionId = change.revisionId,
                    revisionNo = revision.revisionNo,
                    parentRevisionId = parents.singleOrNull()?.parentRevisionId,
                    eventKind = event.kind,
                    resultCode = change.resultCode,
                    currentRevisionId = change.currentRevisionId,
                ),
            )
        }
        requireReplica(
            inclusiveServerSequence == 0L ||
                topology.lastServerSequence == inclusiveServerSequence,
            "replica_history_corrupt",
            "Applied cursor sequence has no matching terminal server change",
        )
        return topology
    }

    private suspend fun verifyExistingPullCoverage(
        exclusiveServerSequence: Long,
        incoming: List<DecodedReplicaChange>,
    ) {
        val inclusiveServerSequence =
            incoming.lastOrNull()?.input?.serverSequence ?: return
        val incomingBySequence = incoming.associateBy { it.input.serverSequence }
        replicaDao.findServerChangesInRange(
            exclusiveServerSequence = exclusiveServerSequence,
            inclusiveServerSequence = inclusiveServerSequence,
        ).forEach { existing ->
            val delivered = requireReplicaValue(
                incomingBySequence[existing.serverSequence],
                "pull_page_drift",
                "Pull page skipped an already retained terminal server change",
            )
            verifyServerChangeStable(
                existing = existing,
                incoming = delivered.input,
            )
        }
    }

    private fun validateTopology(
        changes: List<DecodedReplicaChange>,
        topology: ReplicaTopologyState,
    ) {
        changes.forEach(topology::accept)
    }

    private suspend fun materializeReplicaChange(
        change: DecodedReplicaChange,
        endpointId: String,
        requestIdentity: String,
        verifiedAtUtc: String,
    ) {
        try {
            insertOrVerifyInstallation(change.installation)
            insertOrVerifyOwner(change.owner)
            insertOrVerifyCapture(change.capture)
            insertOrVerifyEvent(change.event)
            insertOrVerifyRevision(change.revision)
            change.parents.forEach { parent ->
                insertOrVerifyParent(parent)
            }
            insertOrVerifyServerChange(
                change.serverChange(
                    endpointId = endpointId,
                    requestIdentity = requestIdentity,
                    verifiedAtUtc = verifiedAtUtc,
                ),
            )
            installReplicaHead(change)
        } catch (error: ReplicaIntegrityException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw ReplicaIntegrityException(
                errorCode = "replica_materialization_failed",
                message = "Replica change could not materialize atomically",
                cause = error,
            )
        }
    }

    private suspend fun insertOrVerifyInstallation(
        incoming: LocalInstallationEntity,
    ) {
        val byIdentity = replicaDao.findInstallation(incoming.installationId)
        val byDevice = requireReplicaValue(
            incoming.serverDeviceId,
            "historical_device_provenance_drift",
            "Committed historical installation has no submitting device",
        ).let { replicaDao.findInstallationByDevice(it) }
        requireReplica(
            byIdentity == null ||
                byDevice == null ||
                byIdentity.installationId == byDevice.installationId,
            "historical_device_provenance_drift",
            "Submitting device is already bound to another installation",
        )
        val existing = byIdentity ?: byDevice
        if (existing == null) {
            replicaDao.insertInstallation(incoming)
            return
        }
        requireReplica(
            existing.installationId == incoming.installationId &&
                existing.serverDeviceId == incoming.serverDeviceId,
            "historical_device_provenance_drift",
            "Historical installation was rebound to another submitting device",
        )
    }

    private suspend fun insertOrVerifyOwner(incoming: LocalOwnerEntity) {
        val byIdentity = replicaDao.findOwner(incoming.localOwnerId)
        val byInstallation =
            replicaDao.findOwnerByInstallation(incoming.installationId)
        requireReplica(
            byIdentity == null ||
                byInstallation == null ||
                byIdentity.localOwnerId == byInstallation.localOwnerId,
            "historical_owner_provenance_drift",
            "Historical installation is already bound to another owner",
        )
        val existing = byIdentity ?: byInstallation
        if (existing == null) {
            replicaDao.insertOwner(incoming)
            return
        }
        requireReplica(
            existing.localOwnerId == incoming.localOwnerId &&
                existing.installationId == incoming.installationId,
            "historical_owner_provenance_drift",
            "Historical owner was rebound to another installation",
        )
    }

    private suspend fun insertOrVerifyCapture(incoming: LocalCaptureEntity) {
        val byIdentity = replicaDao.findCapture(incoming.captureId)
        val byOperation = replicaDao.findCaptureByOperation(incoming.operationId)
        requireReplica(
            byIdentity == null ||
                byOperation == null ||
                byIdentity.captureId == byOperation.captureId,
            "capture_id_collision",
            "Capture and operation identities resolve to different rows",
        )
        var existing = byIdentity ?: byOperation
        if (existing == null) {
            replicaDao.insertCapture(incoming)
            return
        }
        if (
            existing.persistenceState == "local_pending" &&
            incoming.persistenceState == "authenticated_ingress"
        ) {
            requireReplica(
                mutationDao.promoteCaptureToAuthenticatedIngress(
                    captureId = existing.captureId,
                    operationId = existing.operationId,
                    localOwnerId = existing.localOwnerId,
                    installationId = existing.installationId,
                ) == 1,
                "capture_reconciliation_failed",
                "Local pending capture could not reconcile with server ingress",
            )
            existing = requireReplicaValue(
                replicaDao.findCapture(incoming.captureId),
                "capture_reconciliation_failed",
                "Reconciled capture disappeared",
            )
        }
        requireReplica(
            capturesEqual(existing, incoming),
            "capture_id_collision",
            "Capture insert-or-verify detected divergent immutable content",
        )
    }

    private suspend fun insertOrVerifyEvent(incoming: LocalLifeEventEntity) {
        val existing = replicaDao.findEvent(incoming.eventId)
        if (existing == null) {
            replicaDao.insertEvent(incoming)
            return
        }
        val root = requireReplicaValue(
            replicaDao.findEventRoot(incoming.eventId),
            "event_id_collision",
            "Existing event has no durable root revision",
        )
        val rootCapture = requireReplicaValue(
            replicaDao.findCapture(root.captureId),
            "event_id_collision",
            "Existing event root has no durable capture",
        )
        requireReplica(
            existing.localOwnerId == rootCapture.localOwnerId &&
                existing.kind == incoming.kind,
            "event_id_collision",
            "Event insert-or-verify detected divergent root ownership or kind",
        )
    }

    private suspend fun insertOrVerifyRevision(incoming: LocalEventRevisionEntity) {
        if (incoming.revisionNo == 1) {
            val existingRoot = replicaDao.findEventRoot(incoming.eventId)
            requireReplica(
                existingRoot == null ||
                    existingRoot.revisionId == incoming.revisionId,
                "event_id_collision",
                "A second root reused an existing event identity",
            )
        }
        val byIdentity = replicaDao.findRevision(incoming.revisionId)
        val byOperation = replicaDao.findRevisionByOperation(incoming.operationId)
        val bySequence = requireReplicaValue(
            incoming.serverSequence,
            "revision_reconciliation_failed",
            "Committed revision has no server sequence",
        ).let { replicaDao.findRevisionByServerSequence(it) }
        val collisions = listOfNotNull(byIdentity, byOperation, bySequence)
            .distinctBy { it.revisionId }
        requireReplica(
            collisions.size <= 1,
            "revision_id_collision",
            "Revision, operation, and server sequence resolve to different rows",
        )
        var existing = collisions.singleOrNull()
        if (existing == null) {
            replicaDao.insertRevision(incoming)
            return
        }
        if (
            existing.serverReceivedAt == null &&
            existing.serverSequence == null
        ) {
            requireReplica(
                mutationDao.attachServerMetadata(
                    eventId = incoming.eventId,
                    revisionId = incoming.revisionId,
                    captureId = incoming.captureId,
                    operationId = incoming.operationId,
                    serverReceivedAt = requireReplicaValue(
                        incoming.serverReceivedAt,
                        "revision_reconciliation_failed",
                        "Incoming committed revision has no received time",
                    ),
                    serverSequence = requireReplicaValue(
                        incoming.serverSequence,
                        "revision_reconciliation_failed",
                        "Incoming committed revision has no server sequence",
                    ),
                ) == 1,
                "revision_reconciliation_failed",
                "Local pending revision could not reconcile with the server",
            )
            existing = requireReplicaValue(
                replicaDao.findRevision(incoming.revisionId),
                "revision_reconciliation_failed",
                "Reconciled revision disappeared",
            )
        }
        requireReplica(
            revisionsEqual(existing, incoming),
            "revision_id_collision",
            "Revision insert-or-verify detected divergent immutable content",
        )
    }

    private suspend fun insertOrVerifyParent(
        incoming: LocalRevisionParentEntity,
    ) {
        val existing = replicaDao.findRevisionParents(incoming.childRevisionId)
        if (existing.isEmpty()) {
            replicaDao.insertParent(incoming)
            return
        }
        requireReplica(
            existing == listOf(incoming),
            "replica_topology_invalid",
            "Revision parent insert-or-verify detected lineage drift",
        )
    }

    private suspend fun installReplicaHead(change: DecodedReplicaChange) {
        val existing = replicaDao.findEventHead(change.input.eventId)
        if (existing == null) {
            requireReplica(
                change.input.resultCode == "applied" &&
                    change.input.currentRevisionId == change.input.revisionId,
                "replica_cas_drift",
                "A new event cannot begin with a conflict",
            )
            replicaDao.insertEventHead(
                LocalEventHeadEntity(
                    eventId = change.input.eventId,
                    currentRevisionId = change.input.currentRevisionId,
                    serverCurrentRevisionId = change.input.currentRevisionId,
                    serverObservedSequence = change.input.serverSequence,
                    updatedAtUtc = change.input.committedAtUtc,
                ),
            )
            val inserted = requireReplicaValue(
                replicaDao.findEventHead(change.input.eventId),
                "replica_head_drift",
                "Inserted server head disappeared",
            )
            verifyRetainedHeadProjection(
                head = inserted,
                errorCode = "replica_head_drift",
                label = "Inserted replica head",
            )
            return
        }
        val observedSequence = existing.serverObservedSequence
        if (
            observedSequence != null &&
            observedSequence >= change.input.serverSequence
        ) {
            verifyRetainedHeadProjection(
                head = existing,
                errorCode = "replica_head_drift",
                label = "Retained replica head",
            )
            val equalSequenceHeadMatches =
                observedSequence != change.input.serverSequence ||
                    existing.serverCurrentRevisionId ==
                    change.input.currentRevisionId
            requireReplica(
                existing.serverCurrentRevisionId != null &&
                    equalSequenceHeadMatches,
                "replica_head_drift",
                "Retained server head contradicts the replica change",
            )
            // Equal redelivery and a receipt behind a newer observed head are
            // exact no-ops. In particular, do not move a local pending head's
            // updated_at_utc back to the server commit time.
            return
        }
        val changed = replicaDao.installObservedServerHead(
            eventId = change.input.eventId,
            serverCurrentRevisionId = change.input.currentRevisionId,
            serverObservedSequence = change.input.serverSequence,
            updatedAtUtc = change.input.committedAtUtc,
        )
        val installed = requireReplicaValue(
            replicaDao.findEventHead(change.input.eventId),
            "replica_head_drift",
            "Installed server head disappeared",
        )
        requireReplica(
            changed == 1,
            "replica_head_drift",
            "Server head insert-or-verify failed",
        )
        verifyRetainedHeadProjection(
            head = installed,
            errorCode = "replica_head_drift",
            label = "Installed replica head",
        )
        requireReplica(
            installed.serverObservedSequence == change.input.serverSequence &&
                installed.serverCurrentRevisionId == change.input.currentRevisionId,
            "replica_head_drift",
            "Installed server head differs from the replica change",
        )
    }

    private suspend fun verifyRetainedHeadProjection(
        head: LocalEventHeadEntity,
        errorCode: String,
        label: String,
    ): Long {
        val observedSequence = requireReplicaValue(
            head.serverObservedSequence,
            errorCode,
            "$label has no observed server sequence",
        )
        val serverCurrentRevisionId = requireReplicaValue(
            head.serverCurrentRevisionId,
            errorCode,
            "$label has no current server revision",
        )
        val observedChange = requireReplicaValue(
            replicaDao.findServerChangeBySequence(observedSequence),
            errorCode,
            "$label does not resolve to a retained server receipt",
        )
        requireReplica(
            observedChange.eventId == head.eventId &&
                observedChange.currentRevisionId == serverCurrentRevisionId,
            errorCode,
            "$label differs from its retained server receipt",
        )
        val latestEventChange = requireReplicaValue(
            replicaDao.findLatestServerChangeForEvent(head.eventId),
            errorCode,
            "$label event has no retained server history",
        )
        requireReplica(
            latestEventChange.serverSequence == observedSequence &&
                latestEventChange.currentRevisionId == serverCurrentRevisionId,
            errorCode,
            "$label is behind its retained event history",
        )
        requireReplica(
            head.currentRevisionId == serverCurrentRevisionId ||
                replicaDao.hasNonAckedOutboxRevision(
                    eventId = head.eventId,
                    revisionId = head.currentRevisionId,
                ),
            errorCode,
            "$label has an unbacked local current revision",
        )
        return observedSequence
    }

    private suspend fun verifyServerReceiptOverlap(
        change: DecodedReplicaChange,
    ) {
        verifyServerReceiptOverlap(change.input)
    }

    private suspend fun verifyServerReceiptOverlap(
        incoming: ReplicaChangePersistence,
    ) {
        val collisions = listOfNotNull(
            replicaDao.findServerChange(incoming.operationId),
            replicaDao.findServerChangeBySequence(incoming.serverSequence),
            replicaDao.findServerChangeByCapture(incoming.captureId),
            replicaDao.findServerChangeByRevision(incoming.revisionId),
        ).distinctBy { it.serverSequence }
        collisions.forEach { existing ->
            verifyServerChangeStable(existing, incoming)
        }
    }

    private fun verifyServerChangeStable(
        existing: SyncServerChangeEntity,
        incoming: ReplicaChangePersistence,
    ) {
        requireReplica(
            existing.serverSequence == incoming.serverSequence &&
                existing.operationId == incoming.operationId &&
                existing.operationContentSha256 == incoming.operationContentSha256 &&
                existing.resultCode == incoming.resultCode &&
                existing.captureId == incoming.captureId &&
                existing.eventId == incoming.eventId &&
                existing.revisionId == incoming.revisionId &&
                existing.currentRevisionId == incoming.currentRevisionId &&
                existing.committedAtUtc == incoming.committedAtUtc,
            "terminal_receipt_drift",
            "Server redelivery differs from the retained terminal receipt",
        )
    }

    private suspend fun verifyLocalMaterialization(change: DecodedReplicaChange) {
        val installation = requireReplicaValue(
            replicaDao.findInstallation(change.installation.installationId),
            "replica_materialization_missing",
            "Materialized installation is missing",
        )
        requireReplica(
            installation.serverDeviceId == change.installation.serverDeviceId,
            "historical_device_provenance_drift",
            "Materialized installation device provenance drifted",
        )
        val owner = requireReplicaValue(
            replicaDao.findOwner(change.owner.localOwnerId),
            "replica_materialization_missing",
            "Materialized owner is missing",
        )
        requireReplica(
            owner.installationId == change.owner.installationId,
            "historical_owner_provenance_drift",
            "Materialized owner provenance drifted",
        )
        val event = requireReplicaValue(
            replicaDao.findEvent(change.input.eventId),
            "replica_materialization_missing",
            "Materialized event is missing",
        )
        val eventRoot = requireReplicaValue(
            replicaDao.findEventRoot(change.input.eventId),
            "replica_materialization_missing",
            "Materialized event root is missing",
        )
        val eventRootCapture = requireReplicaValue(
            replicaDao.findCapture(eventRoot.captureId),
            "replica_materialization_missing",
            "Materialized event root capture is missing",
        )
        requireReplica(
            event.localOwnerId == eventRootCapture.localOwnerId &&
                event.kind == change.event.kind,
            "event_id_collision",
            "Materialized event differs from its replay",
        )
        requireReplica(
            capturesEqual(
                requireReplicaValue(
                    replicaDao.findCapture(change.input.captureId),
                    "replica_materialization_missing",
                    "Materialized capture is missing",
                ),
                change.capture,
            ),
            "capture_id_collision",
            "Materialized capture differs from its replay",
        )
        requireReplica(
            revisionsEqual(
                requireReplicaValue(
                    replicaDao.findRevision(change.input.revisionId),
                    "replica_materialization_missing",
                    "Materialized revision is missing",
                ),
                change.revision,
            ),
            "revision_id_collision",
            "Materialized revision differs from its replay",
        )
        requireReplica(
            replicaDao.findRevisionParents(change.input.revisionId) == change.parents,
            "replica_topology_invalid",
            "Materialized revision parent differs from its replay",
        )
        val head = requireReplicaValue(
            replicaDao.findEventHead(change.input.eventId),
            "replica_materialization_missing",
            "Materialized event head is missing",
        )
        val observedSequence = verifyRetainedHeadProjection(
            head = head,
            errorCode = "replica_head_drift",
            label = "Materialized event head",
        )
        val equalSequenceHeadMatches =
            observedSequence != change.input.serverSequence ||
                head.serverCurrentRevisionId == change.input.currentRevisionId
        requireReplica(
            head.serverCurrentRevisionId != null &&
                observedSequence >= change.input.serverSequence &&
                equalSequenceHeadMatches,
            "replica_head_drift",
            "Materialized event head contradicts its replay",
        )
    }

    private fun replicaChangesEqual(
        first: List<ReplicaChangePersistence>,
        second: List<ReplicaChangePersistence>,
    ): Boolean =
        first.size == second.size &&
            first.zip(second).all { (left, right) ->
                left.copy(changeJcs = EMPTY_BYTES) ==
                    right.copy(changeJcs = EMPTY_BYTES) &&
                    left.changeJcs.contentEquals(right.changeJcs)
            }

    private fun capturesEqual(
        first: LocalCaptureEntity,
        second: LocalCaptureEntity,
    ): Boolean =
        first.copy(contentJcs = EMPTY_BYTES) ==
            second.copy(contentJcs = EMPTY_BYTES) &&
            first.contentJcs.contentEquals(second.contentJcs)

    private fun revisionsEqual(
        first: LocalEventRevisionEntity,
        second: LocalEventRevisionEntity,
    ): Boolean =
        first.copy(
            payloadJcs = EMPTY_BYTES,
            evidenceJcs = EMPTY_BYTES,
            qualityFlagsJcs = EMPTY_BYTES,
        ) == second.copy(
            payloadJcs = EMPTY_BYTES,
            evidenceJcs = EMPTY_BYTES,
            qualityFlagsJcs = EMPTY_BYTES,
        ) &&
            first.payloadJcs.contentEquals(second.payloadJcs) &&
            first.evidenceJcs.contentEquals(second.evidenceJcs) &&
            first.qualityFlagsJcs.contentEquals(second.qualityFlagsJcs)

    private fun requireReplica(
        condition: Boolean,
        errorCode: String,
        message: String,
    ) {
        if (!condition) {
            throw ReplicaIntegrityException(errorCode, message)
        }
    }

    private fun <T : Any> requireReplicaValue(
        value: T?,
        errorCode: String,
        message: String,
    ): T = value ?: throw ReplicaIntegrityException(errorCode, message)

    private suspend fun haltStreamAfterRollback(
        stream: SyncStreamStateEntity?,
        error: ReplicaIntegrityException,
        updatedAtUtc: String,
    ) {
        val binding = stream ?: return
        try {
            database.withTransaction {
                replicaDao.markIntegrityHalted(
                    credentialEpochId = binding.credentialEpochId,
                    deviceId = binding.deviceId,
                    errorCode = error.errorCode,
                    updatedAtUtc = updatedAtUtc,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Preserve the original integrity failure for the caller.
        }
    }

    private suspend fun haltPushAfterRollback(
        response: TerminalHttpResponsePersistence,
        error: ReplicaIntegrityException,
    ) {
        val request = try {
            transportDao.findRequest(
                endpointId = response.endpointId,
                requestIdentity = response.requestIdentity,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return
        val stream = try {
            replicaDao.findStreamState()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }?.takeIf {
            it.credentialEpochId == request.credentialEpochId &&
                it.deviceId == request.deviceId
        }
        haltStreamAfterRollback(
            stream = stream,
            error = error,
            updatedAtUtc = response.terminalAtUtc,
        )
    }

    private suspend fun installOrVerifyTerminalResponse(
        response: TerminalHttpResponsePersistence,
    ): Boolean {
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
        if (installed == 1) {
            return true
        }
        val existing = checkNotNull(
            transportDao.findRequest(response.endpointId, response.requestIdentity),
        ) {
            "Terminal response request is missing"
        }
        requireReplica(
            existing.state == "terminal" &&
                existing.terminalHttpStatus == response.httpStatus &&
                existing.exactResponseBody?.contentEquals(response.exactResponseBody) == true &&
                existing.responseSha256 == response.responseSha256 &&
                existing.terminalErrorCode == response.terminalErrorCode,
            "terminal_response_drift",
            "Terminal response replay differs from the stored response",
        )
        return false
    }

    private suspend fun verifyPushResultReplay(
        response: TerminalHttpResponsePersistence,
        batchId: String,
        itemOperationId: String,
        itemWireSha256: String,
        result: PushResultPersistence,
    ) {
        when (result) {
            is PushAckPersistence -> verifyAckReplay(
                response = response,
                itemOperationId = itemOperationId,
                itemWireSha256 = itemWireSha256,
                result = result,
            )

            is PushErrorPersistence -> verifyErrorReplay(
                batchId = batchId,
                itemOperationId = itemOperationId,
                itemWireSha256 = itemWireSha256,
                result = result,
                allowSupersededMissingParent = true,
            )
        }
    }

    private fun validateAckBinding(
        response: TerminalHttpResponsePersistence,
        itemOperationId: String,
        itemWireSha256: String,
        result: PushAckPersistence,
    ) {
        val change = result.change
        requireReplica(
            change.resultCode == "applied" || change.resultCode == "conflict",
            "push_ack_status_drift",
            "Push ACK has an unsupported terminal result",
        )
        requireReplica(
            (
                change.resultCode == "applied" &&
                    change.currentRevisionId == change.revisionId
                ) ||
                (
                    change.resultCode == "conflict" &&
                        change.currentRevisionId != change.revisionId
                    ),
            "push_ack_status_drift",
            "Push ACK result contradicts its current revision",
        )
        requireReplica(
            change.operationId == itemOperationId &&
                change.operationContentSha256 == itemWireSha256,
            "push_ack_digest_drift",
            "Push ACK does not match its durable batch operation",
        )
        requireReplica(
            change.firstEndpointId == response.endpointId &&
                change.firstRequestIdentity == response.requestIdentity,
            "push_ack_receipt_drift",
            "Push ACK receipt provenance does not match this response",
        )
        requireReplica(
            result.detailsJcs == null,
            "push_ack_status_drift",
            "Push ACK cannot contain an error-details projection",
        )
    }

    private fun validateErrorBinding(
        itemOperationId: String,
        itemWireSha256: String,
        result: PushErrorPersistence,
    ) {
        requireReplica(
            result.operationId == null || result.operationId == itemOperationId,
            "push_error_operation_drift",
            "Push item error has an invalid operation identity projection",
        )
        requireReplica(
            result.operationContentSha256 == null ||
                result.operationContentSha256 == itemWireSha256,
            "push_error_digest_drift",
            "Push item error has an invalid operation digest projection",
        )
        requireReplica(
            result.retryable == (result.errorCode == "missing_parent"),
            "push_error_retryability_drift",
            "Only missing_parent may be persisted as retryable",
        )
        requireReplica(
            result.detailsJcs != null,
            "push_error_details_drift",
            "Push item error must retain its field-errors projection",
        )
        if (
            result.errorCode == "missing_parent" ||
            result.errorCode == "invalid_parent"
        ) {
            requireReplica(
                result.operationId == itemOperationId &&
                    result.operationContentSha256 == itemWireSha256,
                "push_dependency_receipt_drift",
                "Dependency result must retain operation and digest",
            )
        }
        if (result.errorCode == "invalid_parent") {
            requireReplica(
                result.detailsJcs?.contentEquals(EMPTY_FIELD_ERRORS_JCS) == true,
                "push_invalid_parent_receipt_drift",
                "invalid_parent must retain empty field errors",
            )
        }
    }

    private suspend fun verifyAckReplay(
        response: TerminalHttpResponsePersistence,
        itemOperationId: String,
        itemWireSha256: String,
        result: PushAckPersistence,
    ) {
        validateAckBinding(
            response = response,
            itemOperationId = itemOperationId,
            itemWireSha256 = itemWireSha256,
            result = result,
        )
        val change = result.change
        val receipt = requireReplicaValue(
            replicaDao.findServerChange(itemOperationId),
            "push_ack_status_drift",
            "Retained push ACK has no terminal server receipt",
        )
        requireReplica(
            serverReceiptProjectionEquals(receipt, change),
            "push_ack_receipt_drift",
            "Parsed push ACK differs from the retained terminal server receipt",
        )

        val outbox = requireReplicaValue(
            mutationDao.findOutbox(itemOperationId),
            "push_ack_outbox_missing",
            "Retained push ACK has no outbox operation",
        )
        val expectedState = if (change.resultCode == "applied") "acked" else "conflict"
        requireReplica(
            outbox.operationId == itemOperationId &&
                outbox.wireOperationContentSha256 == itemWireSha256 &&
                outbox.captureId == change.captureId &&
                outbox.eventId == change.eventId &&
                outbox.revisionId == change.revisionId &&
                outbox.state == expectedState &&
                outbox.activeBatchId == null &&
                outbox.lastResultBatchId != null &&
                outbox.lastResultCode == change.resultCode &&
                outbox.lastResultRetryable == false &&
                outbox.lastResultCurrentRevisionId == change.currentRevisionId &&
                nullableBytesEqual(outbox.lastResultDetailsJcs, result.detailsJcs) &&
                outbox.serverSequence == change.serverSequence &&
                outbox.ackedAtUtc == change.committedAtUtc &&
                outbox.lastErrorCode == null,
            "push_ack_outbox_drift",
            "Parsed push ACK differs from the retained outbox result",
        )

        val capture = requireReplicaValue(
            replicaDao.findCapture(change.captureId),
            "push_ack_materialization_missing",
            "Retained push ACK has no capture",
        )
        requireReplica(
            capture.operationId == change.operationId &&
                capture.persistenceState == "authenticated_ingress",
            "push_ack_materialization_drift",
            "Retained ACK capture provenance differs from the terminal receipt",
        )
        val revision = requireReplicaValue(
            replicaDao.findRevision(change.revisionId),
            "push_ack_materialization_missing",
            "Retained push ACK has no immutable revision",
        )
        requireReplica(
            revision.eventId == change.eventId &&
                revision.captureId == change.captureId &&
                revision.operationId == change.operationId &&
                revision.serverReceivedAt == change.committedAtUtc &&
                revision.serverSequence == change.serverSequence,
            "push_ack_materialization_drift",
            "Retained ACK revision metadata differs from the terminal receipt",
        )
        val pointer = requireReplicaValue(
            replicaDao.findEventHead(change.eventId),
            "push_ack_head_missing",
            "Retained push ACK has no event head",
        )
        val observedSequence = verifyRetainedHeadProjection(
            head = pointer,
            errorCode = "push_ack_head_drift",
            label = "Retained push ACK head",
        )
        requireReplica(
            observedSequence >= change.serverSequence &&
                (
                    observedSequence != change.serverSequence ||
                        pointer.serverCurrentRevisionId == change.currentRevisionId
                    ),
            "push_ack_head_drift",
            "Retained server head contradicts the terminal ACK",
        )
    }

    private suspend fun verifyErrorReplay(
        batchId: String,
        itemOperationId: String,
        itemWireSha256: String,
        result: PushErrorPersistence,
        allowSupersededMissingParent: Boolean,
    ) {
        validateErrorBinding(
            itemOperationId = itemOperationId,
            itemWireSha256 = itemWireSha256,
            result = result,
        )
        val outbox = requireReplicaValue(
            mutationDao.findOutbox(itemOperationId),
            "push_error_outbox_missing",
            "Retained push item error has no outbox operation",
        )
        requireReplica(
            outbox.operationId == itemOperationId &&
                outbox.wireOperationContentSha256 == itemWireSha256,
            "push_error_outbox_drift",
            "Push item error does not match the immutable outbox operation",
        )

        val sameBatch = outbox.lastResultBatchId == batchId
        val retainedErrorProjectionMatches =
            outbox.lastResultCode == result.errorCode &&
                outbox.lastResultRetryable == result.retryable &&
                outbox.lastResultCurrentRevisionId == null &&
                nullableBytesEqual(outbox.lastResultDetailsJcs, result.detailsJcs) &&
                outbox.serverSequence == null &&
                outbox.ackedAtUtc == null &&
                outbox.lastErrorCode == result.errorCode
        val retryMovedToNewBatch =
            sameBatch &&
                retainedErrorProjectionMatches &&
                result.errorCode == "missing_parent" &&
                outbox.state == "batched" &&
                outbox.activeBatchId != null &&
                outbox.activeBatchId != batchId
        val sameBatchTerminalState =
            sameBatch &&
                retainedErrorProjectionMatches &&
                outbox.activeBatchId == null &&
                (
                    (result.retryable && outbox.state == "waiting_parent") ||
                        (!result.retryable && outbox.state == "failed")
                    )
        val movedInvalidParent =
            !sameBatch &&
                retainedErrorProjectionMatches &&
                result.errorCode == "invalid_parent" &&
                outbox.lastResultBatchId != null &&
                (
                    (outbox.state == "failed" && outbox.activeBatchId == null) ||
                        (
                            outbox.state == "batched" &&
                                outbox.activeBatchId == batchId
                            )
                    )
        val retainedAck = replicaDao.findServerChange(itemOperationId)
        val coherentNewerResult = when (outbox.state) {
            "acked", "conflict" ->
                outbox.activeBatchId == null &&
                    outbox.lastResultBatchId != null &&
                    outbox.lastResultRetryable == false &&
                    outbox.lastResultCode in setOf("applied", "conflict") &&
                    outbox.lastResultCurrentRevisionId != null &&
                    outbox.serverSequence != null &&
                    outbox.ackedAtUtc != null &&
                    outbox.lastErrorCode == null &&
                    retainedAck != null &&
                    retainedAck.operationId == outbox.operationId &&
                    retainedAck.operationContentSha256 ==
                    outbox.wireOperationContentSha256 &&
                    retainedAck.resultCode == outbox.lastResultCode &&
                    retainedAck.captureId == outbox.captureId &&
                    retainedAck.eventId == outbox.eventId &&
                    retainedAck.revisionId == outbox.revisionId &&
                    retainedAck.currentRevisionId ==
                    outbox.lastResultCurrentRevisionId &&
                    retainedAck.serverSequence == outbox.serverSequence &&
                    retainedAck.committedAtUtc == outbox.ackedAtUtc

            "failed" ->
                outbox.activeBatchId == null &&
                    outbox.lastResultBatchId != null &&
                    outbox.lastResultCode != null &&
                    outbox.lastResultRetryable == false &&
                    outbox.lastResultCurrentRevisionId == null &&
                    outbox.serverSequence == null &&
                    outbox.ackedAtUtc == null &&
                    outbox.lastErrorCode == outbox.lastResultCode &&
                    retainedAck == null

            "waiting_parent" ->
                outbox.activeBatchId == null &&
                    outbox.lastResultBatchId != null &&
                    outbox.lastResultCode == "missing_parent" &&
                    outbox.lastResultRetryable == true &&
                    outbox.lastResultCurrentRevisionId == null &&
                    outbox.serverSequence == null &&
                    outbox.ackedAtUtc == null &&
                    outbox.lastErrorCode == "missing_parent" &&
                    retainedAck == null

            "batched" ->
                outbox.activeBatchId != null &&
                    outbox.lastResultBatchId != null &&
                    outbox.lastResultCode == "missing_parent" &&
                    outbox.lastResultRetryable == true &&
                    outbox.lastResultCurrentRevisionId == null &&
                    outbox.serverSequence == null &&
                    outbox.ackedAtUtc == null &&
                    outbox.lastErrorCode == "missing_parent" &&
                    retainedAck == null

            else -> false
        }
        val supersededMissingParent =
            allowSupersededMissingParent &&
                !sameBatch &&
                result.errorCode == "missing_parent" &&
                outbox.lastResultBatchId != null &&
                coherentNewerResult
        requireReplica(
            sameBatchTerminalState ||
                retryMovedToNewBatch ||
                movedInvalidParent ||
                supersededMissingParent,
            "push_error_status_drift",
            "Retained outbox status contradicts the parsed push item error",
        )
        requireReplica(
            supersededMissingParent || retainedAck == null,
            "push_error_status_drift",
            "Push item error conflicts with a retained ACK receipt",
        )
    }

    private suspend fun commitAck(
        response: TerminalHttpResponsePersistence,
        batchId: String,
        itemOperationId: String,
        itemWireSha256: String,
        result: PushAckPersistence,
    ) {
        val change = result.change
        validateAckBinding(
            response = response,
            itemOperationId = itemOperationId,
            itemWireSha256 = itemWireSha256,
            result = result,
        )

        val outbox = requireReplicaValue(
            mutationDao.findOutbox(itemOperationId),
            "push_ack_outbox_missing",
            "ACK outbox operation is missing",
        )
        requireReplica(
            outbox.captureId == change.captureId &&
                outbox.eventId == change.eventId &&
                outbox.revisionId == change.revisionId,
            "push_ack_outbox_drift",
            "ACK identity does not match the durable outbox operation",
        )
        requireReplica(
            mutationDao.promoteCaptureToAuthenticatedIngress(
                captureId = outbox.captureId,
                operationId = outbox.operationId,
                localOwnerId = outbox.localOwnerId,
                installationId = outbox.installationId,
            ) == 1,
            "push_ack_materialization_drift",
            "ACK capture provenance does not match the outbox",
        )
        requireReplica(
            mutationDao.attachServerMetadata(
                eventId = change.eventId,
                revisionId = change.revisionId,
                captureId = change.captureId,
                operationId = change.operationId,
                serverReceivedAt = change.committedAtUtc,
                serverSequence = change.serverSequence,
            ) == 1,
            "push_ack_materialization_drift",
            "ACK server metadata conflicts with the immutable revision",
        )
        insertOrVerifyServerChange(change)

        val outboxState = if (change.resultCode == "applied") "acked" else "conflict"
        val recorded = outboxDao.recordResult(
            operationId = change.operationId,
            wireContentSha256 = change.operationContentSha256,
            state = outboxState,
            batchId = batchId,
            resultCode = change.resultCode,
            retryable = false,
            currentRevisionId = change.currentRevisionId,
            detailsJcs = result.detailsJcs,
            serverSequence = change.serverSequence,
            ackedAtUtc = change.committedAtUtc,
            errorCode = null,
        )
        if (recorded == 0) {
            verifyAckReplay(
                response = response,
                itemOperationId = itemOperationId,
                itemWireSha256 = itemWireSha256,
                result = result,
            )
        }
        installNewerRemoteHead(change)
    }

    private suspend fun commitError(
        response: TerminalHttpResponsePersistence,
        batchId: String,
        itemOperationId: String,
        itemWireSha256: String,
        result: PushErrorPersistence,
    ) {
        validateErrorBinding(
            itemOperationId = itemOperationId,
            itemWireSha256 = itemWireSha256,
            result = result,
        )
        if (result.errorCode == "missing_parent") {
            val outbox = requireReplicaValue(
                mutationDao.findOutbox(itemOperationId),
                "push_error_outbox_missing",
                "Missing-parent result has no outbox operation",
            )
            requireReplica(
                outbox.baseRevisionId != null,
                "missing_parent_root_invalid",
                "A root operation cannot have a missing parent",
            )
        }
        val state = if (result.retryable) "waiting_parent" else "failed"
        val recorded = outboxDao.recordErrorByBatchOrdinal(
            batchId = batchId,
            ordinal = result.ordinal,
            state = state,
            resultCode = result.errorCode,
            retryable = result.retryable,
            detailsJcs = result.detailsJcs,
            errorCode = result.errorCode,
        )
        if (recorded == 0) {
            verifyErrorReplay(
                batchId = batchId,
                itemOperationId = itemOperationId,
                itemWireSha256 = itemWireSha256,
                result = result,
                allowSupersededMissingParent = false,
            )
        }
    }

    private suspend fun insertOrVerifyServerChange(change: SyncServerChangeEntity) {
        val collisions = listOfNotNull(
            replicaDao.findServerChange(change.operationId),
            replicaDao.findServerChangeBySequence(change.serverSequence),
            replicaDao.findServerChangeByCapture(change.captureId),
            replicaDao.findServerChangeByRevision(change.revisionId),
        ).distinctBy { it.serverSequence to it.operationId }
        if (collisions.isEmpty()) {
            replicaDao.insertServerChange(change)
            return
        }
        collisions.forEach { existing ->
            requireReplica(
                serverReceiptProjectionEquals(existing, change),
                "terminal_receipt_drift",
                "Server change replay violates insert-or-verify",
            )
        }
    }

    private fun serverReceiptProjectionEquals(
        existing: SyncServerChangeEntity,
        incoming: SyncServerChangeEntity,
    ): Boolean =
        existing.serverSequence == incoming.serverSequence &&
            existing.operationId == incoming.operationId &&
            existing.operationContentSha256 == incoming.operationContentSha256 &&
            existing.resultCode == incoming.resultCode &&
            existing.captureId == incoming.captureId &&
            existing.eventId == incoming.eventId &&
            existing.revisionId == incoming.revisionId &&
            existing.currentRevisionId == incoming.currentRevisionId &&
            existing.committedAtUtc == incoming.committedAtUtc

    private suspend fun installNewerRemoteHead(change: SyncServerChangeEntity) {
        mutationDao.recordNewerRemoteHead(
            eventId = change.eventId,
            serverCurrentRevisionId = change.currentRevisionId,
            serverObservedSequence = change.serverSequence,
            updatedAtUtc = change.committedAtUtc,
        )
        val pointer = requireReplicaValue(
            replicaDao.findEventHead(change.eventId),
            "push_ack_head_missing",
            "ACK event head is missing",
        )
        val observedSequence = verifyRetainedHeadProjection(
            head = pointer,
            errorCode = "push_ack_head_drift",
            label = "ACK event head",
        )
        requireReplica(
            observedSequence >= change.serverSequence &&
                (
                    observedSequence != change.serverSequence ||
                        pointer.serverCurrentRevisionId == change.currentRevisionId
                    ),
            "push_ack_head_drift",
            "Remote head replay conflicts at the observed server sequence",
        )
    }

    private suspend fun releaseOpenPushBatchesForBootstrap(
        credentialEpochId: String,
        deviceId: String,
    ) {
        transportDao.findOpenPushBatchIds(
            credentialEpochId = credentialEpochId,
            deviceId = deviceId,
        ).forEach { batchId ->
            val expected = transportDao.findBatchItems(batchId).size
            requireReplica(
                expected > 0 &&
                    transportDao.releasePushBatchForBootstrap(batchId) ==
                    expected,
                "push_batch_membership_drift",
                "Bootstrap recovery could not release an open push batch",
            )
        }
    }

    private fun nullableBytesEqual(
        first: ByteArray?,
        second: ByteArray?,
    ): Boolean = when {
        first == null -> second == null
        second == null -> false
        else -> first.contentEquals(second)
    }

    private companion object {
        const val PUSH_ENDPOINT = "sync_push"
        const val BOOTSTRAP_ENDPOINT = "sync_bootstrap"
        const val PULL_ENDPOINT = "sync_pull"
        const val CURSOR_EXPIRED = "cursor_expired"
        const val CURSOR_INVALID = "cursor_invalid"
        val TERMINAL_INTEGRITY_ITEM_ERROR_CODES = setOf(
            "schema_invalid",
            "operation_hash_mismatch",
            "operation_id_collision",
            "client_sequence_collision",
            "capture_id_collision",
            "revision_id_collision",
            "event_id_collision",
            "invalid_parent",
            "ownership_violation",
        )
        val EMPTY_BYTES = ByteArray(0)
        val EMPTY_FIELD_ERRORS_JCS = "[]".encodeToByteArray()
    }
}
