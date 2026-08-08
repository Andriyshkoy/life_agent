package ru.andriyshkoy.lifeagent.data.export

class NotesExportValidator {
    private val lifeEventValidator = NoteLifeEventContractValidator()

    fun validate(snapshot: NotesExportSnapshot) {
        val result = inspect(snapshot)
        if (result.violations.isNotEmpty()) {
            throw NotesExportValidationException(result.violations)
        }
    }

    internal fun inspect(snapshot: NotesExportSnapshot): NotesExportInspection {
        val violations = mutableListOf<String>()
        val pointers = linkedMapOf<String, String>()

        snapshot.events.forEachIndexed { index, pointer ->
            validateUuid(pointer.eventId, "events[$index].event_id", violations)
            validateUuid(
                pointer.currentRevisionId,
                "events[$index].current_revision_id",
                violations,
            )
            if (pointers.putIfAbsent(pointer.eventId, pointer.currentRevisionId) != null) {
                violations += "duplicate event pointer: ${pointer.eventId}"
            }
        }

        val revisions = snapshot.revisions.mapIndexed { index, revision ->
            lifeEventValidator.inspect(index, revision, violations)
        }
        val revisionsById = linkedMapOf<String, NoteRevisionInspection>()
        val revisionsByEvent = linkedMapOf<String, MutableList<NoteRevisionInspection>>()
        val operationIds = mutableSetOf<String>()
        val captureIds = mutableSetOf<String>()
        var ownerNamespace: Pair<String, String>? = null

        revisions.forEach { revision ->
            if (revisionsById.putIfAbsent(revision.revisionId, revision) != null) {
                violations += "duplicate revision_id: ${revision.revisionId}"
            }
            revisionsByEvent.getOrPut(revision.eventId, ::mutableListOf) += revision
            if (!operationIds.add(revision.operationId)) {
                violations += "duplicate operation_id: ${revision.operationId}"
            }
            if (!captureIds.add(revision.captureId)) {
                violations += "duplicate capture_id: ${revision.captureId}"
            }

            val namespace = revision.installationId to revision.localOwnerId
            if (ownerNamespace == null) {
                ownerNamespace = namespace
            } else if (ownerNamespace != namespace) {
                violations +=
                    "revisions do not share one installation_id/local_owner_id namespace"
            }
        }

        pointers.forEach { (eventId, currentRevisionId) ->
            if (revisionsByEvent[eventId].isNullOrEmpty()) {
                violations += "event has no revisions: $eventId"
            }
            val current = revisionsById[currentRevisionId]
            when {
                current == null -> {
                    violations +=
                        "current_revision_id does not resolve for $eventId: " +
                        currentRevisionId
                }
                current.eventId != eventId -> {
                    violations +=
                        "current_revision_id belongs to another event: " +
                        currentRevisionId
                }
            }
        }

        revisionsByEvent.keys
            .filterNot(pointers::containsKey)
            .forEach { eventId ->
                violations += "orphan revisions for undeclared event: $eventId"
            }

        revisionsById.values.forEach { revision ->
            val uniqueParents = mutableSetOf<String>()
            revision.parentRevisionIds.forEach { parentId ->
                if (!uniqueParents.add(parentId)) {
                    violations +=
                        "${revision.revisionId}: duplicate parent revision: $parentId"
                }
                if (parentId == revision.revisionId) {
                    violations += "${revision.revisionId}: revision cannot parent itself"
                }
                val parent = revisionsById[parentId]
                when {
                    parent == null -> {
                        violations +=
                            "${revision.revisionId}: parent revision does not resolve: " +
                            parentId
                    }
                    parent.eventId != revision.eventId -> {
                        violations +=
                            "${revision.revisionId}: parent belongs to another event: " +
                            parentId
                    }
                    revision.revisionNo <= parent.revisionNo -> {
                        violations +=
                            "${revision.revisionId}: revision_no must increase from parent " +
                            parentId
                    }
                }
            }
        }

        revisionsByEvent.forEach { (eventId, eventRevisions) ->
            val ordered = eventRevisions.sortedWith(
                compareBy<NoteRevisionInspection>(
                    NoteRevisionInspection::revisionNo,
                    NoteRevisionInspection::revisionId,
                ),
            )
            val contiguous = ordered.withIndex().all { (index, revision) ->
                revision.revisionNo == java.math.BigInteger.valueOf(index.toLong() + 1L)
            }
            if (!contiguous) {
                violations +=
                    "event $eventId revision_no sequence is not contiguous"
            }
            ordered.forEachIndexed { index, revision ->
                val expectedParents = if (index == 0) {
                    emptyList()
                } else {
                    listOf(ordered[index - 1].revisionId)
                }
                if (revision.parentRevisionIds != expectedParents) {
                    violations +=
                        "${revision.revisionId}: revision does not supersede " +
                        "its immediate predecessor"
                }
            }
            val currentRevisionId = pointers[eventId]
            if (
                currentRevisionId != null &&
                ordered.isNotEmpty() &&
                currentRevisionId != ordered.last().revisionId
            ) {
                violations +=
                    "current_revision_id does not select the latest revision for $eventId"
            }
        }

        detectCycles(revisionsById, violations)

        return NotesExportInspection(
            revisions = revisions,
            violations = violations,
        )
    }

    private fun detectCycles(
        revisionsById: Map<String, NoteRevisionInspection>,
        violations: MutableList<String>,
    ) {
        val states = mutableMapOf<String, VisitState>()
        var cycleReported = false

        fun visit(revision: NoteRevisionInspection) {
            when (states[revision.revisionId]) {
                VisitState.VISITING -> {
                    if (!cycleReported) {
                        violations += "revision ancestry contains a cycle"
                        cycleReported = true
                    }
                    return
                }
                VisitState.VISITED -> return
                null -> Unit
            }
            states[revision.revisionId] = VisitState.VISITING
            revision.parentRevisionIds.forEach { parentId ->
                val parent = revisionsById[parentId]
                if (parent != null && parent.eventId == revision.eventId) {
                    visit(parent)
                }
            }
            states[revision.revisionId] = VisitState.VISITED
        }

        revisionsById.values.forEach(::visit)
    }

    private fun validateUuid(
        value: String,
        path: String,
        violations: MutableList<String>,
    ) {
        if (!CANONICAL_UUID.matches(value)) {
            violations += "$path must be a lowercase canonical UUID"
        }
    }

    private enum class VisitState {
        VISITING,
        VISITED,
    }

    private companion object {
        val CANONICAL_UUID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )
    }
}

internal data class NotesExportInspection(
    val revisions: List<NoteRevisionInspection>,
    val violations: List<String>,
)

internal data class NoteRevisionInspection(
    val raw: CanonicalNoteRevisionJson,
    val eventId: String,
    val revisionId: String,
    val revisionNo: java.math.BigInteger,
    val captureId: String,
    val operationId: String,
    val installationId: String,
    val localOwnerId: String,
    val recordStatus: String,
    val parentRevisionIds: List<String>,
)
