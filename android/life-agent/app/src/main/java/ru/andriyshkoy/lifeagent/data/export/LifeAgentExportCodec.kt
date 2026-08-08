package ru.andriyshkoy.lifeagent.data.export

interface LifeAgentExportCodec {
    fun encode(snapshot: LifeAgentExportSnapshot): ByteArray

    fun decode(bytes: ByteArray): LifeAgentExportSnapshot

    fun canonicalize(bytes: ByteArray): ByteArray = encode(decode(bytes))
}

class CanonicalLifeAgentExportCodec(
    private val validator: LifeAgentExportValidator = LifeAgentExportValidator(),
) : LifeAgentExportCodec {
    override fun encode(snapshot: LifeAgentExportSnapshot): ByteArray {
        val normalized = normalize(snapshot)
        val document = CanonicalJsonObject(
            mapOf(
                "format" to CanonicalJsonString(LIFE_AGENT_EXPORT_FORMAT),
                "format_version" to CanonicalJsonString(LIFE_AGENT_EXPORT_FORMAT_VERSION),
                "catalogs" to encodeCatalogs(normalized.catalogs),
                "events" to CanonicalJsonArray(normalized.events.map(::encodeEventPointer)),
                "revisions" to CanonicalJsonArray(
                    normalized.revisions.map(CanonicalLifeEventJson::document),
                ),
            ),
        )
        return CanonicalJson.encode(document)
    }

    override fun decode(bytes: ByteArray): LifeAgentExportSnapshot {
        if (bytes.size > MAX_EXPORT_BYTES) {
            throw LifeAgentExportFormatException(
                "Life Agent export exceeds the $MAX_EXPORT_BYTES byte safety limit",
            )
        }
        val document = CanonicalJson.parse(bytes) as? CanonicalJsonObject
            ?: throw LifeAgentExportFormatException("Life Agent export must be a JSON object")
        document.requireExactFields(ROOT_FIELDS, "export")
        val format = document.requireString("format", "export")
        if (format != LIFE_AGENT_EXPORT_FORMAT) {
            throw LifeAgentExportFormatException("unsupported export format '$format'")
        }
        val formatVersion = document.requireString("format_version", "export")
        if (formatVersion != LIFE_AGENT_EXPORT_FORMAT_VERSION) {
            throw LifeAgentExportFormatException(
                "unsupported export format_version '$formatVersion'",
            )
        }
        val snapshot = LifeAgentExportSnapshot(
            catalogs = decodeCatalogs(document.requireObject("catalogs", "export")),
            events = document.requireArray("events", "export").elements.mapIndexed { index, value ->
                val path = "events[$index]"
                val pointer = value as? CanonicalJsonObject
                    ?: throw LifeAgentExportFormatException("$path must be an object")
                pointer.requireExactFields(EVENT_POINTER_FIELDS, path)
                EventPointerExportSnapshot(
                    eventId = pointer.requireString("event_id", path),
                    currentRevisionId = pointer.requireString("current_revision_id", path),
                )
            },
            revisions = document.requireArray("revisions", "export").elements
                .mapIndexed { index, value ->
                    val revision = value as? CanonicalJsonObject
                        ?: throw LifeAgentExportFormatException(
                            "revisions[$index] must be an object",
                        )
                    CanonicalLifeEventJson.fromDocument(revision)
                },
        )
        return normalize(snapshot)
    }

    private fun encodeCatalogs(snapshot: CatalogExportSnapshot): CanonicalJsonObject =
        CanonicalJsonObject(
            mapOf(
                "items" to CanonicalJsonArray(snapshot.items.map { item ->
                    CanonicalJsonObject(
                        mapOf(
                            "catalog_item_id" to CanonicalJsonString(item.catalogItemId),
                            "local_owner_id" to CanonicalJsonString(item.localOwnerId),
                            "catalog_kind" to CanonicalJsonString(item.catalogKind),
                            "created_at" to CanonicalJsonString(item.createdAt),
                        ),
                    )
                }),
                "versions" to CanonicalJsonArray(snapshot.versions.map { version ->
                    CanonicalJsonObject(
                        mapOf(
                            "catalog_version_id" to
                                CanonicalJsonString(version.catalogVersionId),
                            "catalog_item_id" to CanonicalJsonString(version.catalogItemId),
                            "version_no" to CanonicalJsonInteger(
                                version.versionNo.toBigInteger(),
                            ),
                            "schema_version" to CanonicalJsonString(version.schemaVersion),
                            "payload" to version.payload.document,
                            "content_sha256" to CanonicalJsonString(version.contentSha256),
                            "created_at" to CanonicalJsonString(version.createdAt),
                        ),
                    )
                }),
                "heads" to CanonicalJsonArray(snapshot.heads.map { head ->
                    CanonicalJsonObject(
                        mapOf(
                            "catalog_item_id" to CanonicalJsonString(head.catalogItemId),
                            "current_version_id" to CanonicalJsonString(head.currentVersionId),
                            "updated_at" to CanonicalJsonString(head.updatedAt),
                        ),
                    )
                }),
            ),
        )

    private fun encodeEventPointer(pointer: EventPointerExportSnapshot): CanonicalJsonObject =
        CanonicalJsonObject(
            mapOf(
                "event_id" to CanonicalJsonString(pointer.eventId),
                "current_revision_id" to CanonicalJsonString(pointer.currentRevisionId),
            ),
        )

    private fun decodeCatalogs(document: CanonicalJsonObject): CatalogExportSnapshot {
        document.requireExactFields(CATALOG_FIELDS, "catalogs")
        val items = document.requireArray("items", "catalogs").elements
            .mapIndexed { index, value ->
                val path = "catalogs.items[$index]"
                val item = value as? CanonicalJsonObject
                    ?: throw LifeAgentExportFormatException("$path must be an object")
                item.requireExactFields(CATALOG_ITEM_FIELDS, path)
                CatalogItemExportSnapshot(
                    catalogItemId = item.requireString("catalog_item_id", path),
                    localOwnerId = item.requireString("local_owner_id", path),
                    catalogKind = item.requireString("catalog_kind", path),
                    createdAt = item.requireString("created_at", path),
                )
            }
        val versions = document.requireArray("versions", "catalogs").elements
            .mapIndexed { index, value ->
                val path = "catalogs.versions[$index]"
                val version = value as? CanonicalJsonObject
                    ?: throw LifeAgentExportFormatException("$path must be an object")
                version.requireExactFields(CATALOG_VERSION_FIELDS, path)
                CatalogVersionExportSnapshot(
                    catalogVersionId = version.requireString("catalog_version_id", path),
                    catalogItemId = version.requireString("catalog_item_id", path),
                    versionNo = version.requireInt("version_no", path),
                    schemaVersion = version.requireString("schema_version", path),
                    payload = CanonicalCatalogPayloadJson.fromDocument(
                        version.requireObject("payload", path),
                    ),
                    contentSha256 = version.requireString("content_sha256", path),
                    createdAt = version.requireString("created_at", path),
                )
            }
        val heads = document.requireArray("heads", "catalogs").elements
            .mapIndexed { index, value ->
                val path = "catalogs.heads[$index]"
                val head = value as? CanonicalJsonObject
                    ?: throw LifeAgentExportFormatException("$path must be an object")
                head.requireExactFields(CATALOG_HEAD_FIELDS, path)
                CatalogHeadExportSnapshot(
                    catalogItemId = head.requireString("catalog_item_id", path),
                    currentVersionId = head.requireString("current_version_id", path),
                    updatedAt = head.requireString("updated_at", path),
                )
            }
        return CatalogExportSnapshot(items, versions, heads)
    }

    private fun normalize(snapshot: LifeAgentExportSnapshot): LifeAgentExportSnapshot {
        val inspection = validator.inspect(snapshot)
        if (inspection.violations.isNotEmpty()) {
            throw LifeAgentExportValidationException(inspection.violations)
        }
        return LifeAgentExportSnapshot(
            catalogs = CatalogExportSnapshot(
                items = snapshot.catalogs.items.sortedBy(CatalogItemExportSnapshot::catalogItemId),
                versions = snapshot.catalogs.versions.sortedWith(
                    compareBy<CatalogVersionExportSnapshot>(
                        CatalogVersionExportSnapshot::catalogItemId,
                        CatalogVersionExportSnapshot::versionNo,
                        CatalogVersionExportSnapshot::catalogVersionId,
                    ),
                ),
                heads = snapshot.catalogs.heads.sortedBy(CatalogHeadExportSnapshot::catalogItemId),
            ),
            events = snapshot.events.sortedBy(EventPointerExportSnapshot::eventId),
            revisions = inspection.revisions.sortedWith(
                compareBy<LifeEventRevisionInspection>(
                    LifeEventRevisionInspection::eventId,
                    LifeEventRevisionInspection::revisionNo,
                    LifeEventRevisionInspection::revisionId,
                ),
            ).map(LifeEventRevisionInspection::raw),
        )
    }

    private fun CanonicalJsonObject.requireExactFields(expected: Set<String>, path: String) {
        val missing = expected - properties.keys
        val unexpected = properties.keys - expected
        if (missing.isNotEmpty() || unexpected.isNotEmpty()) {
            throw LifeAgentExportFormatException(
                buildString {
                    append("$path has an invalid field set")
                    if (missing.isNotEmpty()) append("; missing=${missing.sorted()}")
                    if (unexpected.isNotEmpty()) append("; unexpected=${unexpected.sorted()}")
                },
            )
        }
    }

    private fun CanonicalJsonObject.requireString(name: String, path: String): String =
        (properties[name] as? CanonicalJsonString)?.value
            ?: throw LifeAgentExportFormatException("$path.$name must be a string")

    private fun CanonicalJsonObject.requireObject(name: String, path: String): CanonicalJsonObject =
        properties[name] as? CanonicalJsonObject
            ?: throw LifeAgentExportFormatException("$path.$name must be an object")

    private fun CanonicalJsonObject.requireArray(name: String, path: String): CanonicalJsonArray =
        properties[name] as? CanonicalJsonArray
            ?: throw LifeAgentExportFormatException("$path.$name must be an array")

    private fun CanonicalJsonObject.requireInt(name: String, path: String): Int {
        val value = (properties[name] as? CanonicalJsonInteger)?.value
            ?: throw LifeAgentExportFormatException("$path.$name must be an integer")
        return value.toLocalIntExactOrNull()
            ?: throw LifeAgentExportFormatException(
                "$path.$name exceeds the local integer range",
            )
    }

    private companion object {
        const val MAX_EXPORT_BYTES = 64 * 1024 * 1024
        val ROOT_FIELDS = setOf("format", "format_version", "catalogs", "events", "revisions")
        val CATALOG_FIELDS = setOf("items", "versions", "heads")
        val CATALOG_ITEM_FIELDS = setOf(
            "catalog_item_id", "local_owner_id", "catalog_kind", "created_at",
        )
        val CATALOG_VERSION_FIELDS = setOf(
            "catalog_version_id", "catalog_item_id", "version_no", "schema_version",
            "payload", "content_sha256", "created_at",
        )
        val CATALOG_HEAD_FIELDS = setOf("catalog_item_id", "current_version_id", "updated_at")
        val EVENT_POINTER_FIELDS = setOf("event_id", "current_revision_id")
    }
}
