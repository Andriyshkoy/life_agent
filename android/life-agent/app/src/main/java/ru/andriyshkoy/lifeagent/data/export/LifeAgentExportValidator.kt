package ru.andriyshkoy.lifeagent.data.export

import java.math.BigInteger
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.OffsetDateTime
import java.util.Locale

class LifeAgentExportValidator {
    private val lifeEventValidator = LifeEventContractValidator()

    fun validate(snapshot: LifeAgentExportSnapshot) {
        val inspection = inspect(snapshot)
        if (inspection.violations.isNotEmpty()) {
            throw LifeAgentExportValidationException(inspection.violations)
        }
    }

    internal fun inspect(snapshot: LifeAgentExportSnapshot): LifeAgentExportInspection {
        val violations = mutableListOf<String>()
        val catalog = inspectCatalog(snapshot.catalogs, violations)
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
        val revisionsById = linkedMapOf<String, LifeEventRevisionInspection>()
        val revisionsByEvent = linkedMapOf<String, MutableList<LifeEventRevisionInspection>>()
        val operationIds = mutableSetOf<String>()
        val captureIds = mutableSetOf<String>()
        var eventNamespace: Pair<String, String>? = null
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
            if (eventNamespace == null) {
                eventNamespace = namespace
            } else if (eventNamespace != namespace) {
                violations += "revisions do not share one installation_id/local_owner_id namespace"
            }
            if (revision.kind == "wellbeing") {
                validateWellbeingCatalogSnapshots(revision, catalog, violations)
            }
        }

        val catalogOwners = catalog.items.map(CatalogItemInspection::localOwnerId).toSet()
        if (catalogOwners.size > 1) {
            violations += "catalog items do not share one local_owner_id namespace"
        }
        eventNamespace?.second?.let { localOwnerId ->
            if (catalogOwners.isNotEmpty() && catalogOwners != setOf(localOwnerId)) {
                violations += "catalogs and events do not share one local_owner_id namespace"
            }
        }

        pointers.forEach { (eventId, currentRevisionId) ->
            if (revisionsByEvent[eventId].isNullOrEmpty()) {
                violations += "event has no revisions: $eventId"
            }
            val current = revisionsById[currentRevisionId]
            when {
                current == null -> violations +=
                    "current_revision_id does not resolve for $eventId: $currentRevisionId"
                current.eventId != eventId -> violations +=
                    "current_revision_id belongs to another event: $currentRevisionId"
            }
        }
        revisionsByEvent.keys.filterNot(pointers::containsKey).forEach { eventId ->
            violations += "orphan revisions for undeclared event: $eventId"
        }
        revisionsById.values.forEach { revision ->
            val uniqueParents = mutableSetOf<String>()
            revision.parentRevisionIds.forEach { parentId ->
                if (!uniqueParents.add(parentId)) {
                    violations += "${revision.revisionId}: duplicate parent revision: $parentId"
                }
                if (parentId == revision.revisionId) {
                    violations += "${revision.revisionId}: revision cannot parent itself"
                }
                val parent = revisionsById[parentId]
                when {
                    parent == null -> violations +=
                        "${revision.revisionId}: parent revision does not resolve: $parentId"
                    parent.eventId != revision.eventId -> violations +=
                        "${revision.revisionId}: parent belongs to another event: $parentId"
                    revision.revisionNo <= parent.revisionNo -> violations +=
                        "${revision.revisionId}: revision_no must increase from parent $parentId"
                }
            }
        }
        revisionsByEvent.forEach { (eventId, eventRevisions) ->
            if (eventRevisions.map(LifeEventRevisionInspection::kind).toSet().size != 1) {
                violations += "event $eventId contains revisions of different kinds"
            }
            val ordered = eventRevisions.sortedWith(
                compareBy<LifeEventRevisionInspection>(
                    LifeEventRevisionInspection::revisionNo,
                    LifeEventRevisionInspection::revisionId,
                ),
            )
            if (!ordered.withIndex().all { (index, revision) ->
                    revision.revisionNo == BigInteger.valueOf(index.toLong() + 1L)
                }
            ) {
                violations += "event $eventId revision_no sequence is not contiguous"
            }
            ordered.forEachIndexed { index, revision ->
                val expectedParents = if (index == 0) emptyList() else {
                    listOf(ordered[index - 1].revisionId)
                }
                if (revision.parentRevisionIds != expectedParents) {
                    violations +=
                        "${revision.revisionId}: revision does not supersede its immediate predecessor"
                }
            }
            if (
                ordered.isNotEmpty() &&
                pointers[eventId] != null &&
                pointers[eventId] != ordered.last().revisionId
            ) {
                violations += "current_revision_id does not select the latest revision for $eventId"
            }
        }
        detectCycles(revisionsById, violations)
        validateGlobalIds(
            catalog = catalog,
            eventIds = pointers.keys,
            revisions = revisions,
            captureIds = captureIds,
            operationIds = operationIds,
            violations = violations,
        )

        return LifeAgentExportInspection(
            catalog = catalog,
            revisions = revisions,
            violations = violations.distinct(),
        )
    }

    private fun inspectCatalog(
        snapshot: CatalogExportSnapshot,
        violations: MutableList<String>,
    ): CatalogInspection {
        val itemsById = linkedMapOf<String, CatalogItemInspection>()
        val items = snapshot.items.mapIndexed { index, item ->
            val path = "catalogs.items[$index]"
            validateUuid(item.catalogItemId, "$path.catalog_item_id", violations)
            validateUuid(item.localOwnerId, "$path.local_owner_id", violations)
            if (item.catalogKind != WELLBEING_CATALOG_KIND) {
                violations += "$path.catalog_kind must equal '$WELLBEING_CATALOG_KIND'"
            }
            validateInstant(item.createdAt, "$path.created_at", violations)
            CatalogItemInspection(item, item.catalogItemId, item.localOwnerId).also { inspected ->
                if (itemsById.putIfAbsent(item.catalogItemId, inspected) != null) {
                    violations += "duplicate catalog_item_id: ${item.catalogItemId}"
                }
            }
        }

        val versionsById = linkedMapOf<String, CatalogVersionInspection>()
        val versionsByItem = linkedMapOf<String, MutableList<CatalogVersionInspection>>()
        val optionOwners = mutableMapOf<String, String>()
        val optionVersionContent = mutableMapOf<Pair<String, BigInteger>, OptionImmutableContent>()
        val versions = snapshot.versions.mapIndexed { index, version ->
            val path = "catalogs.versions[$index]"
            validateUuid(version.catalogVersionId, "$path.catalog_version_id", violations)
            validateUuid(version.catalogItemId, "$path.catalog_item_id", violations)
            if (version.versionNo < 1) {
                violations += "$path.version_no must be at least 1"
            }
            if (version.schemaVersion != WELLBEING_CATALOG_SCHEMA_VERSION) {
                violations += "$path.schema_version must equal '$WELLBEING_CATALOG_SCHEMA_VERSION'"
            }
            validateInstant(version.createdAt, "$path.created_at", violations)
            val payload = inspectCatalogPayload(version.payload, path, violations)
            val expectedDigest = sha256(version.payload.toByteArray())
            if (!SHA256.matches(version.contentSha256)) {
                violations += "$path.content_sha256 must be a lowercase SHA-256 digest"
            } else if (version.contentSha256 != expectedDigest) {
                violations += "$path.content_sha256 does not match canonical catalog payload"
            }
            if (version.catalogItemId !in itemsById) {
                violations += "$path belongs to an unknown catalog item"
            }
            payload.options.forEach { option ->
                val priorOwner = optionOwners.putIfAbsent(option.optionId, version.catalogItemId)
                if (priorOwner != null && priorOwner != version.catalogItemId) {
                    violations += "option_id ${option.optionId} belongs to more than one dimension"
                }
                val key = option.optionId to option.optionVersion
                val content = OptionImmutableContent(
                    catalogItemId = version.catalogItemId,
                    active = option.active,
                    label = option.label,
                    sortOrder = option.sortOrder,
                )
                val priorContent = optionVersionContent.putIfAbsent(key, content)
                if (priorContent != null && priorContent != content) {
                    violations += "option ${option.optionId} version ${option.optionVersion} has conflicting content"
                }
            }
            CatalogVersionInspection(version, payload).also { inspected ->
                if (versionsById.putIfAbsent(version.catalogVersionId, inspected) != null) {
                    violations += "duplicate catalog_version_id: ${version.catalogVersionId}"
                }
                versionsByItem.getOrPut(version.catalogItemId, ::mutableListOf) += inspected
            }
        }

        versionsByItem.forEach { (itemId, itemVersions) ->
            val ordered = itemVersions.sortedWith(
                compareBy<CatalogVersionInspection>(
                    { it.raw.versionNo },
                    { it.raw.catalogVersionId },
                ),
            )
            if (!ordered.withIndex().all { (index, version) ->
                    version.raw.versionNo == index + 1
                }
            ) {
                violations += "catalog item $itemId version_no sequence is not contiguous"
            }
            val latestOptionVersion = mutableMapOf<String, BigInteger>()
            ordered.forEach { version ->
                version.payload.options.forEach { option ->
                    val prior = latestOptionVersion[option.optionId]
                    if (prior != null && option.optionVersion < prior) {
                        violations += "option ${option.optionId} version decreases across catalog history"
                    }
                    latestOptionVersion[option.optionId] = option.optionVersion
                }
            }
        }

        val headsByItem = linkedMapOf<String, CatalogHeadExportSnapshot>()
        snapshot.heads.forEachIndexed { index, head ->
            val path = "catalogs.heads[$index]"
            validateUuid(head.catalogItemId, "$path.catalog_item_id", violations)
            validateUuid(head.currentVersionId, "$path.current_version_id", violations)
            validateInstant(head.updatedAt, "$path.updated_at", violations)
            if (headsByItem.putIfAbsent(head.catalogItemId, head) != null) {
                violations += "duplicate catalog head: ${head.catalogItemId}"
            }
            val selected = versionsById[head.currentVersionId]
            when {
                selected == null -> violations += "$path.current_version_id does not resolve"
                selected.raw.catalogItemId != head.catalogItemId -> violations +=
                    "$path.current_version_id belongs to another catalog item"
            }
            val latest = versionsByItem[head.catalogItemId]?.maxWithOrNull(
                compareBy<CatalogVersionInspection>(
                    { it.raw.versionNo },
                    { it.raw.catalogVersionId },
                ),
            )
            if (latest != null && head.currentVersionId != latest.raw.catalogVersionId) {
                violations += "$path.current_version_id does not select the latest version"
            }
        }
        itemsById.keys.forEach { itemId ->
            if (versionsByItem[itemId].isNullOrEmpty()) {
                violations += "catalog item has no versions: $itemId"
            }
            if (itemId !in headsByItem) {
                violations += "catalog item has no head: $itemId"
            }
        }
        (versionsByItem.keys + headsByItem.keys).filterNot(itemsById::containsKey).forEach { itemId ->
            violations += "catalog history references an unknown item: $itemId"
        }
        return CatalogInspection(
            items = items,
            versions = versions,
            versionsByItemAndNumber = versions.associateBy {
                it.raw.catalogItemId to it.raw.versionNo
            },
            optionIds = optionOwners.keys,
        )
    }

    private fun inspectCatalogPayload(
        raw: CanonicalCatalogPayloadJson,
        versionPath: String,
        violations: MutableList<String>,
    ): CatalogPayloadInspection {
        val path = "$versionPath.payload"
        val document = raw.document
        document.requireExactFields(CATALOG_PAYLOAD_FIELDS, path, violations)
        val active = document.requireBoolean("active", path, violations)
        val label = document.requireString("label", path, violations)
        validateLabel(label, "$path.label", violations)
        val sortOrder = document.requireInteger("sort_order", path, violations)
        validateLocalInteger(sortOrder, "$path.sort_order", violations)
        val values = document.requireArray("options", path, violations)
        if (values.elements.isEmpty()) {
            violations += "$path.options must contain at least one option"
        }
        if (values.elements.size > MAX_OPTIONS) {
            violations += "$path.options contains more than $MAX_OPTIONS options"
        }
        val seenIds = mutableSetOf<String>()
        val activeLabels = mutableSetOf<String>()
        val options = values.elements.mapIndexedNotNull { index, value ->
            val optionPath = "$path.options[$index]"
            val option = value as? CanonicalJsonObject
            if (option == null) {
                violations += "$optionPath must be an object"
                return@mapIndexedNotNull null
            }
            option.requireExactFields(CATALOG_OPTION_FIELDS, optionPath, violations)
            val optionActive = option.requireBoolean("active", optionPath, violations)
            val optionLabel = option.requireString("label", optionPath, violations)
            validateLabel(optionLabel, "$optionPath.label", violations)
            val optionId = option.requireString("option_id", optionPath, violations)
            validateUuid(optionId, "$optionPath.option_id", violations)
            if (!seenIds.add(optionId)) {
                violations += "$path.options repeats option_id $optionId"
            }
            val optionVersion = option.requirePositiveInteger(
                "option_version",
                optionPath,
                violations,
            )
            validateLocalInteger(optionVersion, "$optionPath.option_version", violations)
            val optionSortOrder = option.requireInteger("sort_order", optionPath, violations)
            validateLocalInteger(optionSortOrder, "$optionPath.sort_order", violations)
            if (optionActive) {
                val folded = optionLabel.lowercase(Locale.ROOT)
                if (!activeLabels.add(folded)) {
                    violations += "$path.options repeats an active label"
                }
            }
            CatalogOptionInspection(
                optionId = optionId,
                optionVersion = optionVersion,
                active = optionActive,
                label = optionLabel,
                sortOrder = optionSortOrder,
            )
        }
        val optionOrder = options.map { it.sortOrder to it.optionId }
        val canonicalOptionOrder = options.sortedWith(
            compareBy<CatalogOptionInspection>(
                CatalogOptionInspection::sortOrder,
                CatalogOptionInspection::optionId,
            ),
        ).map { it.sortOrder to it.optionId }
        if (optionOrder != canonicalOptionOrder) {
            violations += "$path.options is not in canonical sort_order/option_id order"
        }
        if (active && options.none(CatalogOptionInspection::active)) {
            violations += "$path active dimension must contain an active option"
        }
        return CatalogPayloadInspection(raw, active, label, sortOrder, options)
    }

    private fun validateWellbeingCatalogSnapshots(
        revision: LifeEventRevisionInspection,
        catalog: CatalogInspection,
        violations: MutableList<String>,
    ) {
        revision.wellbeingValues.forEachIndexed { index, value ->
            val path = "revision ${revision.revisionId} payload.values[$index]"
            val versionNo = value.dimensionVersion.toLocalIntExactOrNull()
            val version: CatalogVersionInspection? = versionNo?.let { number ->
                catalog.versionsByItemAndNumber[value.dimensionId to number]
            }
            if (version == null) {
                violations += "$path dimension snapshot does not resolve"
                return@forEachIndexed
            }
            if (value.dimensionLabel != version.payload.label) {
                violations += "$path dimension label snapshot differs from catalog"
            }
            val option = version.payload.options.firstOrNull { candidate ->
                candidate.optionId == value.optionId &&
                    candidate.optionVersion == value.optionVersion
            }
            if (option == null) {
                violations += "$path option snapshot is not a member of the dimension version"
            } else if (
                value.optionLabel != option.label ||
                value.optionSortOrder != option.sortOrder
            ) {
                violations += "$path option snapshot differs from catalog"
            }
        }
    }

    private fun validateGlobalIds(
        catalog: CatalogInspection,
        eventIds: Collection<String>,
        revisions: List<LifeEventRevisionInspection>,
        captureIds: Set<String>,
        operationIds: Set<String>,
        violations: MutableList<String>,
    ) {
        val namespaces = sortedMapOf(
            "catalog_item_id" to catalog.items.map(CatalogItemInspection::catalogItemId).toSet(),
            "catalog_version_id" to catalog.versions.map { it.raw.catalogVersionId }.toSet(),
            "option_id" to catalog.optionIds.toSet(),
            "event_id" to eventIds.toSet(),
            "revision_id" to revisions.map(LifeEventRevisionInspection::revisionId).toSet(),
            "capture_id" to captureIds,
            "operation_id" to operationIds,
        )
        val names = namespaces.keys.toList()
        names.forEachIndexed { index, left ->
            names.drop(index + 1).forEach { right ->
                if (namespaces.getValue(left).intersect(namespaces.getValue(right)).isNotEmpty()) {
                    violations += "global IDs collide between $left and $right"
                }
            }
        }
    }

    private fun detectCycles(
        revisionsById: Map<String, LifeEventRevisionInspection>,
        violations: MutableList<String>,
    ) {
        val states = mutableMapOf<String, VisitState>()
        var reported = false
        fun visit(revision: LifeEventRevisionInspection) {
            when (states[revision.revisionId]) {
                VisitState.VISITING -> {
                    if (!reported) violations += "revision ancestry contains a cycle"
                    reported = true
                    return
                }
                VisitState.VISITED -> return
                null -> Unit
            }
            states[revision.revisionId] = VisitState.VISITING
            revision.parentRevisionIds.mapNotNull(revisionsById::get).forEach(::visit)
            states[revision.revisionId] = VisitState.VISITED
        }
        revisionsById.values.forEach(::visit)
    }

    private fun validateLabel(value: String, path: String, violations: MutableList<String>) {
        if (value.isBlank()) violations += "$path must contain a visible character"
        if (value != value.trim()) violations += "$path must be normalized"
        if (value.codePointCount(0, value.length) > MAX_LABEL_CODE_POINTS) {
            violations += "$path exceeds $MAX_LABEL_CODE_POINTS Unicode code points"
        }
    }

    private fun validateLocalInteger(
        value: BigInteger,
        path: String,
        violations: MutableList<String>,
    ) {
        if (value < MIN_INT || value > MAX_INT) violations += "$path exceeds the local integer range"
    }

    private fun validateUuid(value: String, path: String, violations: MutableList<String>) {
        if (!CANONICAL_UUID.matches(value)) violations += "$path must be a lowercase canonical UUID"
    }

    private fun validateInstant(value: String, path: String, violations: MutableList<String>) {
        try {
            OffsetDateTime.parse(value)
        } catch (_: DateTimeException) {
            violations += "$path must be an RFC 3339 date-time"
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun CanonicalJsonObject.requireExactFields(
        expected: Set<String>,
        path: String,
        violations: MutableList<String>,
    ) {
        val missing = expected - properties.keys
        val unexpected = properties.keys - expected
        if (missing.isNotEmpty()) violations += "$path is missing fields: ${missing.sorted()}"
        if (unexpected.isNotEmpty()) violations += "$path has unknown fields: ${unexpected.sorted()}"
    }

    private fun CanonicalJsonObject.requireString(
        name: String,
        path: String,
        violations: MutableList<String>,
    ): String = (properties[name] as? CanonicalJsonString)?.value ?: run {
        violations += "$path.$name must be a string"
        INVALID_STRING
    }

    private fun CanonicalJsonObject.requireBoolean(
        name: String,
        path: String,
        violations: MutableList<String>,
    ): Boolean = (properties[name] as? CanonicalJsonBoolean)?.value ?: run {
        violations += "$path.$name must be a boolean"
        false
    }

    private fun CanonicalJsonObject.requireArray(
        name: String,
        path: String,
        violations: MutableList<String>,
    ): CanonicalJsonArray = properties[name] as? CanonicalJsonArray ?: run {
        violations += "$path.$name must be an array"
        CanonicalJsonArray(emptyList())
    }

    private fun CanonicalJsonObject.requireInteger(
        name: String,
        path: String,
        violations: MutableList<String>,
    ): BigInteger = (properties[name] as? CanonicalJsonInteger)?.value ?: run {
        violations += "$path.$name must be an integer"
        BigInteger.ZERO
    }

    private fun CanonicalJsonObject.requirePositiveInteger(
        name: String,
        path: String,
        violations: MutableList<String>,
    ): BigInteger = requireInteger(name, path, violations).also { value ->
        if (value < BigInteger.ONE) violations += "$path.$name must be at least 1"
    }

    private enum class VisitState { VISITING, VISITED }

    private companion object {
        const val INVALID_STRING = "<invalid>"
        const val MAX_LABEL_CODE_POINTS = 64
        const val MAX_OPTIONS = 64
        val MIN_INT = BigInteger.valueOf(Int.MIN_VALUE.toLong())
        val MAX_INT = BigInteger.valueOf(Int.MAX_VALUE.toLong())
        val SHA256 = Regex("^[a-f0-9]{64}$")
        val CANONICAL_UUID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )
        val CATALOG_PAYLOAD_FIELDS = setOf("active", "label", "options", "sort_order")
        val CATALOG_OPTION_FIELDS = setOf(
            "active",
            "label",
            "option_id",
            "option_version",
            "sort_order",
        )
    }
}

internal data class LifeAgentExportInspection(
    val catalog: CatalogInspection,
    val revisions: List<LifeEventRevisionInspection>,
    val violations: List<String>,
)

internal data class LifeEventRevisionInspection(
    val raw: CanonicalLifeEventJson,
    val eventId: String,
    val revisionId: String,
    val revisionNo: BigInteger,
    val captureId: String,
    val operationId: String,
    val installationId: String,
    val localOwnerId: String,
    val kind: String,
    val recordStatus: String,
    val parentRevisionIds: List<String>,
    val wellbeingValues: List<WellbeingValueInspection>,
)

internal data class WellbeingValueInspection(
    val dimensionId: String,
    val dimensionVersion: BigInteger,
    val dimensionLabel: String,
    val optionId: String,
    val optionVersion: BigInteger,
    val optionLabel: String,
    val optionSortOrder: BigInteger,
)

internal data class CatalogInspection(
    val items: List<CatalogItemInspection>,
    val versions: List<CatalogVersionInspection>,
    val versionsByItemAndNumber: Map<Pair<String, Int>, CatalogVersionInspection>,
    val optionIds: Collection<String>,
)

internal data class CatalogItemInspection(
    val raw: CatalogItemExportSnapshot,
    val catalogItemId: String,
    val localOwnerId: String,
)

internal data class CatalogVersionInspection(
    val raw: CatalogVersionExportSnapshot,
    val payload: CatalogPayloadInspection,
)

internal data class CatalogPayloadInspection(
    val raw: CanonicalCatalogPayloadJson,
    val active: Boolean,
    val label: String,
    val sortOrder: BigInteger,
    val options: List<CatalogOptionInspection>,
)

internal data class CatalogOptionInspection(
    val optionId: String,
    val optionVersion: BigInteger,
    val active: Boolean,
    val label: String,
    val sortOrder: BigInteger,
)

private data class OptionImmutableContent(
    val catalogItemId: String,
    val active: Boolean,
    val label: String,
    val sortOrder: BigInteger,
)
