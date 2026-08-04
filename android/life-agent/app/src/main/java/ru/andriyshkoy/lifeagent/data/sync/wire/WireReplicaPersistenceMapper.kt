package ru.andriyshkoy.lifeagent.data.sync.wire

import ru.andriyshkoy.lifeagent.data.local.db.ReplicaChangePersistence
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPageReceiptEntity

/**
 * Persistence projection of one already-validated replica page.
 *
 * The mapper is deliberately stateless. The caller supplies only the durable
 * page index and the local time at which the HTTP attempt completed; all wire
 * identities and cursor facts are derived from the decoded response itself.
 */
internal data class WireReplicaPagePersistence(
    val receipt: SyncPageReceiptEntity,
    val changes: List<ReplicaChangePersistence>,
) {
    override fun toString(): String =
        "WireReplicaPagePersistence(changeCount=${changes.size},redacted=true)"
}

internal object WireReplicaPersistenceMapper {
    /**
     * Maps only strict fresh-page responses. Cross-page state is intentionally
     * absent: Room reducers remain authoritative for replay, cursor history and
     * replica topology.
     */
    fun map(
        response: FreshReplicaPage,
        pageIndex: Int,
        terminalAtUtc: String,
    ): WireReplicaPagePersistence {
        require(pageIndex >= 0) { "pageIndex must be non-negative" }
        val localTerminalAtUtc = requireCanonicalLocalInstant(terminalAtUtc)
        return when (response) {
            is FreshBootstrapPage -> mapBootstrap(
                page = response.page,
                pageIndex = pageIndex,
                terminalAtUtc = localTerminalAtUtc,
            )

            is FreshPullPage -> mapPull(
                page = response.page,
                pageIndex = pageIndex,
                terminalAtUtc = localTerminalAtUtc,
            )
        }
    }

    private fun mapBootstrap(
        page: BootstrapPageSuccess,
        pageIndex: Int,
        terminalAtUtc: String,
    ): WireReplicaPagePersistence {
        val changes = page.changes.map(::mapChange)
        return WireReplicaPagePersistence(
            receipt = SyncPageReceiptEntity(
                pageId = page.pageId,
                endpointId = M2Endpoint.SYNC_BOOTSTRAP.endpointId,
                requestIdentity = page.requestId,
                bootstrapId = page.bootstrapId,
                pageIndex = pageIndex,
                snapshotId = page.snapshotId,
                fromCursor = page.fromPageCursor,
                nextCursor = page.nextPageCursor,
                incrementalCursor = page.incrementalCursor,
                pageSha256 = page.pageSha256,
                changeCount = changes.size,
                completeOrHasMore = page.complete,
                state = "staged",
                firstServerSequence = changes.firstOrNull()?.serverSequence,
                lastServerSequence = changes.lastOrNull()?.serverSequence,
                receivedAtUtc = terminalAtUtc,
                appliedAtUtc = null,
            ),
            changes = changes,
        )
    }

    private fun mapPull(
        page: PullPageSuccess,
        pageIndex: Int,
        terminalAtUtc: String,
    ): WireReplicaPagePersistence {
        val changes = page.changes.map(::mapChange)
        return WireReplicaPagePersistence(
            receipt = SyncPageReceiptEntity(
                pageId = page.pageId,
                endpointId = M2Endpoint.SYNC_PULL.endpointId,
                requestIdentity = page.requestId,
                bootstrapId = null,
                pageIndex = pageIndex,
                snapshotId = null,
                fromCursor = page.fromCursor,
                nextCursor = page.nextCursor,
                incrementalCursor = null,
                pageSha256 = page.pageSha256,
                changeCount = changes.size,
                completeOrHasMore = page.hasMore,
                state = "applied",
                firstServerSequence = changes.firstOrNull()?.serverSequence,
                lastServerSequence = changes.lastOrNull()?.serverSequence,
                receivedAtUtc = terminalAtUtc,
                appliedAtUtc = terminalAtUtc,
            ),
            changes = changes,
        )
    }

    private fun mapChange(change: ServerChangeWire): ReplicaChangePersistence {
        val committedAtUtc = requireCanonicalServerInstant(
            change.event.document
                .requireObject("server")
                .requireString("received_at"),
        )
        if (change.event.receivedAt != committedAtUtc) schemaFailure()
        val root = jsonObjectOf(
            "server_sequence" to change.serverSequence.asJson(),
            "change_kind" to "event_revision_committed".asJson(),
            "result_code" to change.resultCode.wireName.asJson(),
            "operation_id" to change.operationId.asJson(),
            "capture_id" to change.captureId.asJson(),
            "event_id" to change.eventId.asJson(),
            "revision_id" to change.revisionId.asJson(),
            "current_revision_id" to change.currentRevisionId.asJson(),
            "operation_content_sha256" to change.operationContentSha256.asJson(),
            "capture" to change.capture.document,
            "event" to change.event.document,
        )
        return ReplicaChangePersistence(
            serverSequence = change.serverSequence,
            operationId = change.operationId,
            operationContentSha256 = change.operationContentSha256,
            captureId = change.captureId,
            eventId = change.eventId,
            revisionId = change.revisionId,
            currentRevisionId = change.currentRevisionId,
            resultCode = change.resultCode.wireName,
            committedAtUtc = committedAtUtc,
            changeJcs = StrictJson.canonicalBytes(root),
        )
    }
}
