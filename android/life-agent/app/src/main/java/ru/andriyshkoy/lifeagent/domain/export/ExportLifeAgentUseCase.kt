package ru.andriyshkoy.lifeagent.domain.export

import ru.andriyshkoy.lifeagent.data.export.CanonicalCatalogPayloadJson
import ru.andriyshkoy.lifeagent.data.export.CanonicalLifeEventJson
import ru.andriyshkoy.lifeagent.data.export.CatalogExportSnapshot
import ru.andriyshkoy.lifeagent.data.export.CatalogHeadExportSnapshot
import ru.andriyshkoy.lifeagent.data.export.CatalogItemExportSnapshot
import ru.andriyshkoy.lifeagent.data.export.CatalogVersionExportSnapshot
import ru.andriyshkoy.lifeagent.data.export.EventPointerExportSnapshot
import ru.andriyshkoy.lifeagent.data.export.LifeAgentExportCodec
import ru.andriyshkoy.lifeagent.data.export.LifeAgentExportSnapshot
import ru.andriyshkoy.lifeagent.notes.domain.NotesRepository
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingCatalogRepository
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingRepository

fun interface LifeAgentExportSnapshotSource {
    suspend fun loadLifeAgentExportSnapshot(): LifeAgentExportSnapshot
}

class ExportLifeAgentUseCase(
    private val source: LifeAgentExportSnapshotSource,
    private val codec: LifeAgentExportCodec,
) {
    suspend operator fun invoke(): ByteArray =
        codec.encode(source.loadLifeAgentExportSnapshot())
}

/** Repository-only composition boundary for a complete local export. */
class RepositoriesLifeAgentExportSnapshotSource(
    private val notesRepository: NotesRepository,
    private val wellbeingRepository: WellbeingRepository,
    private val wellbeingCatalogRepository: WellbeingCatalogRepository,
) : LifeAgentExportSnapshotSource {
    override suspend fun loadLifeAgentExportSnapshot(): LifeAgentExportSnapshot {
        val notes = notesRepository.exportSnapshot()
        val wellbeing = wellbeingRepository.exportSnapshot()
        val catalog = wellbeingCatalogRepository.exportSnapshot()
        return LifeAgentExportSnapshot(
            catalogs = CatalogExportSnapshot(
                items = catalog.items.map { item ->
                    CatalogItemExportSnapshot(
                        catalogItemId = item.catalogItemId.toString(),
                        localOwnerId = item.localOwnerId.toString(),
                        catalogKind = item.catalogKind,
                        createdAt = item.createdAt.toString(),
                    )
                },
                versions = catalog.versions.map { version ->
                    CatalogVersionExportSnapshot(
                        catalogVersionId = version.catalogVersionId.toString(),
                        catalogItemId = version.catalogItemId.toString(),
                        versionNo = version.version,
                        schemaVersion = version.schemaVersion,
                        payload = CanonicalCatalogPayloadJson.fromJson(
                            version.canonicalPayloadJson.toByteArray(Charsets.UTF_8),
                        ),
                        contentSha256 = version.contentSha256,
                        createdAt = version.createdAt.toString(),
                    )
                },
                heads = catalog.heads.map { head ->
                    CatalogHeadExportSnapshot(
                        catalogItemId = head.catalogItemId.toString(),
                        currentVersionId = head.currentVersionId.toString(),
                        updatedAt = head.updatedAt.toString(),
                    )
                },
            ),
            events = buildList {
                notes.events.forEach { pointer ->
                    add(
                        EventPointerExportSnapshot(
                            eventId = pointer.eventId.toString(),
                            currentRevisionId = pointer.currentRevisionId.toString(),
                        ),
                    )
                }
                wellbeing.events.forEach { pointer ->
                    add(
                        EventPointerExportSnapshot(
                            eventId = pointer.eventId.toString(),
                            currentRevisionId = pointer.currentRevisionId.toString(),
                        ),
                    )
                }
            },
            revisions = buildList {
                notes.revisions.forEach { revision ->
                    add(
                        CanonicalLifeEventJson.fromJson(
                            revision.canonicalJson.toByteArray(Charsets.UTF_8),
                        ),
                    )
                }
                wellbeing.revisions.forEach { revision ->
                    add(
                        CanonicalLifeEventJson.fromJson(
                            revision.canonicalJson.toByteArray(Charsets.UTF_8),
                        ),
                    )
                }
            },
        )
    }
}
