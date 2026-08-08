package ru.andriyshkoy.lifeagent.wellbeing.data

import androidx.room.withTransaction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.andriyshkoy.lifeagent.core.id.RandomUuidGenerator
import ru.andriyshkoy.lifeagent.core.id.UuidGenerator
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabase
import ru.andriyshkoy.lifeagent.data.local.db.LocalIdentityStore
import ru.andriyshkoy.lifeagent.data.local.db.dao.CurrentWellbeingCatalogRow
import ru.andriyshkoy.lifeagent.data.local.db.dao.WellbeingCatalogVersionContextRow
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalCatalogHeadEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalCatalogItemEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalCatalogVersionEntity
import ru.andriyshkoy.lifeagent.data.local.serialization.CanonicalWellbeingCatalogCodec
import ru.andriyshkoy.lifeagent.wellbeing.domain.ArchiveWellbeingDimensionCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.CorruptWellbeingCatalogException
import ru.andriyshkoy.lifeagent.wellbeing.domain.CreateWellbeingDimensionCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.InvalidWellbeingCatalogException
import ru.andriyshkoy.lifeagent.wellbeing.domain.StaleWellbeingCatalogVersionException
import ru.andriyshkoy.lifeagent.wellbeing.domain.UpdateWellbeingDimensionCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingCatalogExportSnapshot
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingCatalogHeadSnapshot
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingCatalogIdentityCollisionException
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingCatalogItemSnapshot
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingCatalogRepository
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingCatalogVersionSnapshot
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingDimension
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingDimensionNotFoundException
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingOption
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingOptionDraft
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingPolicy

class RoomWellbeingCatalogRepository(
    private val database: LifeAgentDatabase,
    uuidGenerator: UuidGenerator = RandomUuidGenerator,
    private val codec: CanonicalWellbeingCatalogCodec = CanonicalWellbeingCatalogCodec(),
) : WellbeingCatalogRepository {
    private val identityStore = LocalIdentityStore(database, uuidGenerator)
    private val dao = database.wellbeingCatalogDao()

    override suspend fun ensureSeeded(createdAt: Instant) {
        database.withTransaction {
            if (dao.countDimensions() > 0) return@withTransaction
            val identity = identityStore.ensureIdentityInCurrentTransaction(createdAt)
            defaultDimensions().forEach { seed ->
                val encoding = codec.encodePayload(
                    label = seed.label,
                    sortOrder = seed.sortOrder,
                    active = true,
                    options = seed.options,
                )
                dao.insertItem(
                    LocalCatalogItemEntity(
                        catalogItemId = seed.dimensionId.toString(),
                        localOwnerId = identity.localOwnerId,
                        catalogKind = CATALOG_KIND,
                        createdAtUtc = createdAt.toString(),
                    ),
                )
                dao.insertVersion(
                    LocalCatalogVersionEntity(
                        catalogVersionId = seed.catalogVersionId.toString(),
                        catalogItemId = seed.dimensionId.toString(),
                        versionNo = 1,
                        schemaVersion = CanonicalWellbeingCatalogCodec.SCHEMA_VERSION,
                        payloadJcs = encoding.bytes,
                        contentSha256 = encoding.sha256,
                        createdAtUtc = createdAt.toString(),
                    ),
                )
                dao.insertHead(
                    LocalCatalogHeadEntity(
                        catalogItemId = seed.dimensionId.toString(),
                        currentVersionId = seed.catalogVersionId.toString(),
                        updatedAtUtc = createdAt.toString(),
                    ),
                )
            }
        }
    }

    override fun observeDimensions(includeArchived: Boolean): Flow<List<WellbeingDimension>> =
        dao.observeCurrent().map { rows ->
            rows.map(::decodeCurrent)
                .filter { includeArchived || it.active }
                .map { dimension ->
                    if (includeArchived) dimension else {
                        dimension.copy(options = dimension.options.filter(WellbeingOption::active))
                    }
                }
                .sortedWith(compareBy(WellbeingDimension::sortOrder, WellbeingDimension::dimensionId))
        }

    override suspend fun getDimension(dimensionId: UUID): WellbeingDimension? =
        dao.findCurrent(dimensionId.toString())?.let(::decodeCurrent)

    override suspend fun create(
        command: CreateWellbeingDimensionCommand,
    ): WellbeingDimension {
        requireDistinctCommandIds(
            dimensionId = command.dimensionId,
            catalogVersionId = command.catalogVersionId,
            optionIds = command.options.map(WellbeingOptionDraft::optionId),
        )
        val label = WellbeingPolicy.normalizeCatalogLabel(command.label, "Dimension label")
        WellbeingPolicy.validateOptionDrafts(command.options, dimensionActive = true)
        val options = command.options.toVersionOneOptions()
        val encoding = codec.encodePayload(label, command.sortOrder, true, options)
        return database.withTransaction {
            requireOptionOwnership(
                dimensionId = command.dimensionId,
                optionIds = options.map(WellbeingOption::optionId).toSet(),
            )
            dao.findCurrent(command.dimensionId.toString())?.let { existing ->
                if (
                    existing.version.catalogVersionId == command.catalogVersionId.toString() &&
                    existing.version.contentSha256 == encoding.sha256
                ) {
                    return@withTransaction decodeCurrent(existing)
                }
                throw WellbeingCatalogIdentityCollisionException(
                    "Wellbeing dimension ID was already used",
                )
            }
            if (dao.itemExists(command.dimensionId.toString())) {
                throw WellbeingCatalogIdentityCollisionException("Catalog item ID was already used")
            }
            if (dao.versionExists(command.catalogVersionId.toString())) {
                throw WellbeingCatalogIdentityCollisionException("Catalog version ID was already used")
            }
            val identity = identityStore.ensureIdentityInCurrentTransaction(command.createdAt)
            dao.insertItem(
                LocalCatalogItemEntity(
                    catalogItemId = command.dimensionId.toString(),
                    localOwnerId = identity.localOwnerId,
                    catalogKind = CATALOG_KIND,
                    createdAtUtc = command.createdAt.toString(),
                ),
            )
            dao.insertVersion(
                LocalCatalogVersionEntity(
                    catalogVersionId = command.catalogVersionId.toString(),
                    catalogItemId = command.dimensionId.toString(),
                    versionNo = 1,
                    schemaVersion = CanonicalWellbeingCatalogCodec.SCHEMA_VERSION,
                    payloadJcs = encoding.bytes,
                    contentSha256 = encoding.sha256,
                    createdAtUtc = command.createdAt.toString(),
                ),
            )
            dao.insertHead(
                LocalCatalogHeadEntity(
                    catalogItemId = command.dimensionId.toString(),
                    currentVersionId = command.catalogVersionId.toString(),
                    updatedAtUtc = command.createdAt.toString(),
                ),
            )
            checkNotNull(dao.findCurrent(command.dimensionId.toString())).let(::decodeCurrent)
        }
    }

    override suspend fun update(
        command: UpdateWellbeingDimensionCommand,
    ): WellbeingDimension {
        requireDistinctCommandIds(
            dimensionId = command.dimensionId,
            catalogVersionId = command.catalogVersionId,
            optionIds = command.options.map(WellbeingOptionDraft::optionId),
        )
        val label = WellbeingPolicy.normalizeCatalogLabel(command.label, "Dimension label")
        WellbeingPolicy.validateOptionDrafts(command.options, dimensionActive = command.active)
        return database.withTransaction {
            val baseRow = dao.findVersion(
                command.dimensionId.toString(),
                command.expectedCurrentVersionId.toString(),
            ) ?: throw WellbeingDimensionNotFoundException(command.dimensionId)
            val base = decodeVersion(baseRow)
            val current = dao.findCurrent(command.dimensionId.toString())
                ?: throw WellbeingDimensionNotFoundException(command.dimensionId)
            val options = evolveOptions(base.options, command.options)
            requireOptionOwnership(
                dimensionId = command.dimensionId,
                optionIds = options.map(WellbeingOption::optionId).toSet(),
            )
            val encoding = codec.encodePayload(
                label = label,
                sortOrder = command.sortOrder,
                active = command.active,
                options = options,
            )

            if (current.head.currentVersionId != command.expectedCurrentVersionId.toString()) {
                if (
                    current.head.currentVersionId == command.catalogVersionId.toString() &&
                    current.version.contentSha256 == encoding.sha256
                ) {
                    return@withTransaction decodeCurrent(current)
                }
                throw StaleWellbeingCatalogVersionException(
                    command.dimensionId,
                    command.expectedCurrentVersionId,
                    UUID.fromString(current.head.currentVersionId),
                )
            }
            if (dao.versionExists(command.catalogVersionId.toString())) {
                throw WellbeingCatalogIdentityCollisionException("Catalog version ID was already used")
            }
            dao.insertVersion(
                LocalCatalogVersionEntity(
                    catalogVersionId = command.catalogVersionId.toString(),
                    catalogItemId = command.dimensionId.toString(),
                    versionNo = base.version + 1,
                    schemaVersion = CanonicalWellbeingCatalogCodec.SCHEMA_VERSION,
                    payloadJcs = encoding.bytes,
                    contentSha256 = encoding.sha256,
                    createdAtUtc = command.createdAt.toString(),
                ),
            )
            moveHead(
                dimensionId = command.dimensionId,
                expectedVersionId = command.expectedCurrentVersionId,
                newVersionId = command.catalogVersionId,
                updatedAt = command.createdAt,
            )
            checkNotNull(dao.findCurrent(command.dimensionId.toString())).let(::decodeCurrent)
        }
    }

    override suspend fun archive(
        command: ArchiveWellbeingDimensionCommand,
    ): WellbeingDimension {
        val currentDimension = getDimension(command.dimensionId)
            ?: throw WellbeingDimensionNotFoundException(command.dimensionId)
        val update = UpdateWellbeingDimensionCommand(
            dimensionId = command.dimensionId,
            catalogVersionId = command.catalogVersionId,
            expectedCurrentVersionId = command.expectedCurrentVersionId,
            label = currentDimension.label,
            sortOrder = currentDimension.sortOrder,
            active = false,
            options = currentDimension.options.map { option ->
                WellbeingOptionDraft(
                    optionId = option.optionId,
                    label = option.label,
                    sortOrder = option.sortOrder,
                    active = option.active,
                )
            },
            createdAt = command.archivedAt,
        )
        return update(update)
    }

    override suspend fun exportSnapshot(): WellbeingCatalogExportSnapshot =
        database.withTransaction {
            val items = dao.findAllItems().map { item ->
                WellbeingCatalogItemSnapshot(
                    catalogItemId = UUID.fromString(item.catalogItemId),
                    localOwnerId = UUID.fromString(item.localOwnerId),
                    catalogKind = item.catalogKind,
                    createdAt = Instant.parse(item.createdAtUtc),
                )
            }
            val versions = dao.findAllVersions().map { version ->
                requireValidVersion(version)
                WellbeingCatalogVersionSnapshot(
                    catalogVersionId = UUID.fromString(version.catalogVersionId),
                    catalogItemId = UUID.fromString(version.catalogItemId),
                    version = version.versionNo,
                    schemaVersion = version.schemaVersion,
                    canonicalPayloadJson = version.payloadJcs.toString(StandardCharsets.UTF_8),
                    contentSha256 = version.contentSha256,
                    createdAt = Instant.parse(version.createdAtUtc),
                )
            }
            val heads = dao.findAllHeads().map { head ->
                WellbeingCatalogHeadSnapshot(
                    catalogItemId = UUID.fromString(head.catalogItemId),
                    currentVersionId = UUID.fromString(head.currentVersionId),
                    updatedAt = Instant.parse(head.updatedAtUtc),
                )
            }
            WellbeingCatalogExportSnapshot(items = items, versions = versions, heads = heads)
        }

    private suspend fun moveHead(
        dimensionId: UUID,
        expectedVersionId: UUID,
        newVersionId: UUID,
        updatedAt: Instant,
    ) {
        val changed = dao.compareAndSetHead(
            dimensionId = dimensionId.toString(),
            expectedVersionId = expectedVersionId.toString(),
            newVersionId = newVersionId.toString(),
            updatedAtUtc = updatedAt.toString(),
        )
        if (changed != 1) {
            val actual = dao.findCurrent(dimensionId.toString())
                ?.head
                ?.currentVersionId
                ?.let(UUID::fromString)
                ?: throw WellbeingDimensionNotFoundException(dimensionId)
            throw StaleWellbeingCatalogVersionException(dimensionId, expectedVersionId, actual)
        }
    }

    private fun decodeCurrent(row: CurrentWellbeingCatalogRow): WellbeingDimension {
        if (row.head.catalogItemId != row.item.catalogItemId ||
            row.head.currentVersionId != row.version.catalogVersionId
        ) {
            throw CorruptWellbeingCatalogException("Wellbeing catalog head is inconsistent")
        }
        return decode(
            item = row.item,
            version = row.version,
        )
    }

    private fun decodeVersion(row: WellbeingCatalogVersionContextRow): WellbeingDimension =
        decode(row.item, row.version)

    private fun decode(
        item: LocalCatalogItemEntity,
        version: LocalCatalogVersionEntity,
    ): WellbeingDimension {
        if (item.catalogKind != CATALOG_KIND || version.catalogItemId != item.catalogItemId) {
            throw CorruptWellbeingCatalogException("Wellbeing catalog row is inconsistent")
        }
        return try {
            requireValidVersion(version)
        } catch (error: IllegalArgumentException) {
            throw CorruptWellbeingCatalogException("Stored wellbeing catalog is invalid", error)
        }
    }

    private fun requireValidVersion(version: LocalCatalogVersionEntity): WellbeingDimension {
        if (version.schemaVersion != CanonicalWellbeingCatalogCodec.SCHEMA_VERSION) {
            throw IllegalArgumentException("Unknown wellbeing catalog schema version")
        }
        val dimension = codec.decodeDimension(
            dimensionId = UUID.fromString(version.catalogItemId),
            catalogVersionId = UUID.fromString(version.catalogVersionId),
            version = version.versionNo,
            payloadJcs = version.payloadJcs,
        )
        val canonical = codec.encodePayload(
            label = dimension.label,
            sortOrder = dimension.sortOrder,
            active = dimension.active,
            options = dimension.options,
        )
        if (canonical.sha256 != version.contentSha256) {
            canonical.bytes.fill(0)
            throw IllegalArgumentException("Wellbeing catalog digest does not match payload")
        }
        canonical.bytes.fill(0)
        return dimension
    }

    private fun evolveOptions(
        current: List<WellbeingOption>,
        drafts: List<WellbeingOptionDraft>,
    ): List<WellbeingOption> {
        val currentById = current.associateBy(WellbeingOption::optionId)
        val draftIds = drafts.map(WellbeingOptionDraft::optionId).toSet()
        val omitted = currentById.keys - draftIds
        if (omitted.isNotEmpty()) {
            throw InvalidWellbeingCatalogException(
                "Existing wellbeing options must be archived instead of removed",
            )
        }
        return drafts.map { draft ->
            val label = WellbeingPolicy.normalizeCatalogLabel(draft.label, "Option label")
            val previous = currentById[draft.optionId]
            if (previous == null) {
                WellbeingOption(
                    optionId = draft.optionId,
                    version = 1,
                    label = label,
                    sortOrder = draft.sortOrder,
                    active = draft.active,
                )
            } else {
                val changed = previous.label != label ||
                    previous.sortOrder != draft.sortOrder ||
                    previous.active != draft.active
                previous.copy(
                    version = if (changed) previous.version + 1 else previous.version,
                    label = label,
                    sortOrder = draft.sortOrder,
                    active = draft.active,
                )
            }
        }.sortedWith(compareBy(WellbeingOption::sortOrder, WellbeingOption::optionId))
    }

    /**
     * Option UUIDs identify one dimension for their full append-only history, not only the head.
     * Keeping this check in the catalog transaction makes locally valid state export-valid too.
     */
    private suspend fun requireOptionOwnership(
        dimensionId: UUID,
        optionIds: Set<UUID>,
    ) {
        if (optionIds.isEmpty()) return
        dao.findAllVersionContexts().forEach { row ->
            if (row.item.catalogItemId == dimensionId.toString()) return@forEach
            val foreign = decodeVersion(row).options.firstOrNull { it.optionId in optionIds }
            if (foreign != null) {
                throw WellbeingCatalogIdentityCollisionException(
                    "Wellbeing option ID is already owned by another dimension",
                )
            }
        }
    }

    private fun requireDistinctCommandIds(
        dimensionId: UUID,
        catalogVersionId: UUID,
        optionIds: List<UUID>,
    ) {
        val ids = listOf(dimensionId, catalogVersionId) + optionIds
        if (ids.toSet().size != ids.size) {
            throw WellbeingCatalogIdentityCollisionException(
                "Wellbeing catalog command IDs must be mutually distinct",
            )
        }
    }

    private fun List<WellbeingOptionDraft>.toVersionOneOptions(): List<WellbeingOption> =
        map { draft ->
            WellbeingOption(
                optionId = draft.optionId,
                version = 1,
                label = WellbeingPolicy.normalizeCatalogLabel(draft.label, "Option label"),
                sortOrder = draft.sortOrder,
                active = draft.active,
            )
        }.sortedWith(compareBy(WellbeingOption::sortOrder, WellbeingOption::optionId))

    private data class SeedDimension(
        val dimensionId: UUID,
        val catalogVersionId: UUID,
        val label: String,
        val sortOrder: Int,
        val options: List<WellbeingOption>,
    )

    private fun defaultDimensions(): List<SeedDimension> = listOf(
        seedDimension(
            key = "overall",
            label = "Общее самочувствие",
            sortOrder = 10,
            labels = listOf(
                "Очень плохое",
                "Плохое",
                "Нормальное",
                "Хорошее",
                "Отличное",
            ),
        ),
        seedDimension(
            key = "mood",
            label = "Настроение",
            sortOrder = 20,
            labels = listOf(
                "Очень неприятное",
                "Неприятное",
                "Нейтральное",
                "Приятное",
                "Очень приятное",
            ),
        ),
        seedDimension(
            key = "energy",
            label = "Энергия",
            sortOrder = 30,
            labels = listOf(
                "Совсем нет сил",
                "Мало энергии",
                "Средне",
                "Много энергии",
                "Очень много энергии",
            ),
        ),
        seedDimension(
            key = "stress",
            label = "Стресс",
            sortOrder = 40,
            labels = listOf("Нет", "Низкий", "Умеренный", "Высокий", "Очень высокий"),
        ),
    )

    private fun seedDimension(
        key: String,
        label: String,
        sortOrder: Int,
        labels: List<String>,
    ): SeedDimension = SeedDimension(
        dimensionId = seedUuid("dimension/$key"),
        catalogVersionId = seedUuid("dimension/$key/version/1"),
        label = label,
        sortOrder = sortOrder,
        options = labels.mapIndexed { index, optionLabel ->
            WellbeingOption(
                optionId = seedUuid("dimension/$key/option/$index"),
                version = 1,
                label = optionLabel,
                sortOrder = (index + 1) * 10,
                active = true,
            )
        },
    )

    private fun seedUuid(key: String): UUID = UUID.nameUUIDFromBytes(
        "$SEED_NAMESPACE/$key".toByteArray(StandardCharsets.UTF_8),
    )

    private companion object {
        const val CATALOG_KIND = "wellbeing_dimension"
        const val SEED_NAMESPACE = "life-agent/wellbeing-catalog/v1"
    }
}
