package ru.andriyshkoy.lifeagent.wellbeing.data

import androidx.room.withTransaction
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.andriyshkoy.lifeagent.core.id.MutationIds
import ru.andriyshkoy.lifeagent.core.id.RandomUuidGenerator
import ru.andriyshkoy.lifeagent.core.id.UuidGenerator
import ru.andriyshkoy.lifeagent.core.time.ResolvedPointTime
import ru.andriyshkoy.lifeagent.core.time.TemporalPrecision
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabase
import ru.andriyshkoy.lifeagent.data.local.db.LocalIdentityStore
import ru.andriyshkoy.lifeagent.data.local.db.dao.CurrentRevisionRow
import ru.andriyshkoy.lifeagent.data.local.db.dao.LocalIdentityRow
import ru.andriyshkoy.lifeagent.data.local.db.dao.RevisionContextRow
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalCaptureEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalEventHeadEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalEventRevisionEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalLifeEventEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalRevisionParentEntity
import ru.andriyshkoy.lifeagent.data.local.serialization.CanonicalWellbeingCodec
import ru.andriyshkoy.lifeagent.data.local.serialization.CanonicalWellbeingCatalogCodec
import ru.andriyshkoy.lifeagent.data.local.serialization.WellbeingRevisionEncoding
import ru.andriyshkoy.lifeagent.wellbeing.domain.CanonicalWellbeingRevisionSnapshot
import ru.andriyshkoy.lifeagent.wellbeing.domain.CorrectWellbeingCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.CorruptLocalWellbeingException
import ru.andriyshkoy.lifeagent.wellbeing.domain.CreateWellbeingCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.RetractWellbeingCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.RetractedWellbeingCorrectionException
import ru.andriyshkoy.lifeagent.wellbeing.domain.StaleWellbeingRevisionException
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingEventPointer
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingExportSnapshot
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingIdempotencyConflictException
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingIdentityCollisionException
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingMutationDisposition
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingMutationOutcome
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingMutationReceipt
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingNotFoundException
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingPayload
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingPolicy
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingRecordStatus
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingRepository
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingSnapshot
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingSummary
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingValueSnapshot

class RoomWellbeingRepository(
    private val database: LifeAgentDatabase,
    private val collectorVersion: String,
    uuidGenerator: UuidGenerator = RandomUuidGenerator,
    private val codec: CanonicalWellbeingCodec = CanonicalWellbeingCodec(),
    private val catalogCodec: CanonicalWellbeingCatalogCodec = CanonicalWellbeingCatalogCodec(),
) : WellbeingRepository {
    private val identityStore = LocalIdentityStore(database, uuidGenerator)
    private val mutationDao = database.lifeEventMutationDao()
    private val queryDao = database.wellbeingQueryDao()
    private val catalogDao = database.wellbeingCatalogDao()

    init {
        require(collectorVersion.isNotBlank()) { "Collector version must not be blank" }
    }

    override suspend fun create(command: CreateWellbeingCommand): WellbeingMutationOutcome {
        val payload = WellbeingPolicy.normalizePayload(command.values, command.comment)
        val fingerprint = codec.commandFingerprint(command)
        return database.withTransaction {
            replay(command.ids.operationId, fingerprint)?.let {
                return@withTransaction WellbeingMutationOutcome.Persisted(it)
            }
            validateCurrentSelections(payload.values)
            ensureUnusedMutationIds(command.ids, eventMustBeUnused = true)
            val identity = ensureIdentity(command.recordedAt.toInstant())
            val encoding = codec.encodeRevision(
                ids = command.ids,
                revisionNo = 1,
                payload = payload,
                status = WellbeingRecordStatus.ACTIVE,
                effectiveTime = command.effectiveTime,
                recordedAt = command.recordedAt,
                correctionReason = null,
                parentRevisionId = null,
            )
            val revision = revision(
                ids = command.ids,
                revisionNo = 1,
                encoding = encoding,
                commandFingerprint = fingerprint,
                status = WellbeingRecordStatus.ACTIVE,
                effectiveTime = command.effectiveTime,
                recordedAt = command.recordedAt,
                correctionReason = null,
            )
            mutationDao.insertCapture(capture(command.ids, identity, payload, command.recordedAt))
            mutationDao.insertEvent(
                LocalLifeEventEntity(
                    eventId = command.ids.eventId.toString(),
                    localOwnerId = identity.localOwnerId,
                    kind = WELLBEING_KIND,
                    createdAtUtc = command.recordedAt.toInstant().toString(),
                ),
            )
            mutationDao.insertRevision(revision)
            mutationDao.insertHead(
                LocalEventHeadEntity(
                    eventId = command.ids.eventId.toString(),
                    currentRevisionId = command.ids.revisionId.toString(),
                    updatedAtUtc = command.recordedAt.toInstant().toString(),
                ),
            )
            WellbeingMutationOutcome.Persisted(
                receipt(revision, WellbeingMutationDisposition.COMMITTED),
            )
        }
    }

    override suspend fun correct(command: CorrectWellbeingCommand): WellbeingMutationOutcome {
        val payload = WellbeingPolicy.normalizePayload(command.values, command.comment)
        val fingerprint = codec.commandFingerprint(command)
        return database.withTransaction {
            replay(command.ids.operationId, fingerprint)?.let {
                return@withTransaction WellbeingMutationOutcome.Persisted(it)
            }
            ensureUnusedMutationIds(command.ids, eventMustBeUnused = false)
            val current = requireCurrent(command.ids.eventId)
            requireExpectedCurrent(
                command.ids.eventId,
                command.expectedCurrentRevisionId,
                UUID.fromString(current.headRevisionId),
            )
            if (WellbeingRecordStatus.fromStorage(current.revision.recordStatus) ==
                WellbeingRecordStatus.RETRACTED
            ) {
                throw RetractedWellbeingCorrectionException(command.ids.eventId)
            }
            validateCorrectionSelections(
                previous = decodePayload(current.revision).values,
                proposed = payload.values,
            )
            val identity = requireIdentity()
            val revisionNo = current.revision.revisionNo + 1
            val encoding = codec.encodeRevision(
                ids = command.ids,
                revisionNo = revisionNo,
                payload = payload,
                status = WellbeingRecordStatus.ACTIVE,
                effectiveTime = command.effectiveTime,
                recordedAt = command.recordedAt,
                correctionReason = command.reason,
                parentRevisionId = current.revision.revisionId,
            )
            val revision = revision(
                ids = command.ids,
                revisionNo = revisionNo,
                encoding = encoding,
                commandFingerprint = fingerprint,
                status = WellbeingRecordStatus.ACTIVE,
                effectiveTime = command.effectiveTime,
                recordedAt = command.recordedAt,
                correctionReason = command.reason,
            )
            mutationDao.insertCapture(capture(command.ids, identity, payload, command.recordedAt))
            mutationDao.insertRevision(revision)
            mutationDao.insertParent(
                LocalRevisionParentEntity(
                    eventId = command.ids.eventId.toString(),
                    childRevisionId = command.ids.revisionId.toString(),
                    parentRevisionId = current.revision.revisionId,
                    relation = PARENT_SUPERSEDES,
                ),
            )
            moveHead(command.ids, command.expectedCurrentRevisionId, command.recordedAt.toInstant())
            WellbeingMutationOutcome.Persisted(
                receipt(revision, WellbeingMutationDisposition.COMMITTED),
            )
        }
    }

    override suspend fun retract(command: RetractWellbeingCommand): WellbeingMutationOutcome {
        val fingerprint = codec.commandFingerprint(command)
        return database.withTransaction {
            replay(command.ids.operationId, fingerprint)?.let {
                return@withTransaction WellbeingMutationOutcome.Persisted(it)
            }
            val current = requireCurrent(command.ids.eventId)
            if (WellbeingRecordStatus.fromStorage(current.revision.recordStatus) ==
                WellbeingRecordStatus.RETRACTED
            ) {
                return@withTransaction WellbeingMutationOutcome.AlreadyRetracted(
                    current.toSnapshot(codec),
                )
            }
            requireExpectedCurrent(
                command.ids.eventId,
                command.expectedCurrentRevisionId,
                UUID.fromString(current.headRevisionId),
            )
            ensureUnusedMutationIds(command.ids, eventMustBeUnused = false)
            val identity = requireIdentity()
            val effectiveTime = current.revision.toResolvedPointTime()
            val payload = decodePayload(current.revision)
            val revisionNo = current.revision.revisionNo + 1
            val encoding = codec.encodeRevision(
                ids = command.ids,
                revisionNo = revisionNo,
                payload = payload,
                status = WellbeingRecordStatus.RETRACTED,
                effectiveTime = effectiveTime,
                recordedAt = command.recordedAt,
                correctionReason = command.reason,
                parentRevisionId = current.revision.revisionId,
            )
            val revision = revision(
                ids = command.ids,
                revisionNo = revisionNo,
                encoding = encoding,
                commandFingerprint = fingerprint,
                status = WellbeingRecordStatus.RETRACTED,
                effectiveTime = effectiveTime,
                recordedAt = command.recordedAt,
                correctionReason = command.reason,
            )
            mutationDao.insertCapture(capture(command.ids, identity, payload, command.recordedAt))
            mutationDao.insertRevision(revision)
            mutationDao.insertParent(
                LocalRevisionParentEntity(
                    eventId = command.ids.eventId.toString(),
                    childRevisionId = command.ids.revisionId.toString(),
                    parentRevisionId = current.revision.revisionId,
                    relation = PARENT_SUPERSEDES,
                ),
            )
            moveHead(command.ids, command.expectedCurrentRevisionId, command.recordedAt.toInstant())
            WellbeingMutationOutcome.Persisted(
                receipt(revision, WellbeingMutationDisposition.COMMITTED),
            )
        }
    }

    override fun observeLastCommitted(): Flow<WellbeingSummary?> =
        queryDao.observeLastCommitted().map { row ->
            row?.toSnapshot(codec)?.let { snapshot ->
                WellbeingSummary(
                    eventId = snapshot.eventId,
                    revisionId = snapshot.revisionId,
                    operationId = snapshot.operationId,
                    payload = snapshot.payload,
                    status = snapshot.status,
                    effectiveTime = snapshot.effectiveTime,
                    recordedAt = snapshot.recordedAt,
                )
            }
        }

    override suspend fun getByEventId(eventId: UUID): WellbeingSnapshot? =
        queryDao.findCurrent(eventId.toString())?.toSnapshot(codec)

    override suspend fun findByOperationId(operationId: UUID): WellbeingMutationReceipt? =
        queryDao.findByOperationId(operationId.toString())
            ?.toReceipt(codec, WellbeingMutationDisposition.COMMITTED)

    override suspend fun exportSnapshot(): WellbeingExportSnapshot = database.withTransaction {
        val eventIds = queryDao.findAllEventIds()
        val pointers = queryDao.findEventPointers().map { pointer ->
            WellbeingEventPointer(
                eventId = UUID.fromString(pointer.eventId),
                currentRevisionId = UUID.fromString(pointer.currentRevisionId),
            )
        }
        if (pointers.map { it.eventId.toString() } != eventIds) {
            throw CorruptLocalWellbeingException(
                "Cannot snapshot wellbeing because local event heads are incomplete",
            )
        }
        val revisions = queryDao.findAllRevisionContexts().map { row ->
            val canonical = codec.encodeCanonicalEvent(
                row = row,
                parents = queryDao.findParents(row.revision.revisionId),
            )
            CanonicalWellbeingRevisionSnapshot(
                eventId = UUID.fromString(row.revision.eventId),
                revisionId = UUID.fromString(row.revision.revisionId),
                revisionNo = row.revision.revisionNo,
                status = WellbeingRecordStatus.fromStorage(row.revision.recordStatus),
                canonicalJson = canonical.utf8,
                contentSha256 = row.revision.contentSha256,
            )
        }
        WellbeingExportSnapshot(events = pointers, revisions = revisions)
    }

    private suspend fun replay(
        operationId: UUID,
        expectedFingerprint: String,
    ): WellbeingMutationReceipt? {
        val row = queryDao.findByOperationId(operationId.toString()) ?: return null
        if (row.revision.commandFingerprintSha256 != expectedFingerprint) {
            throw WellbeingIdempotencyConflictException(operationId)
        }
        return row.toReceipt(codec, WellbeingMutationDisposition.REPLAYED)
    }

    private suspend fun ensureIdentity(createdAt: Instant): LocalIdentityRow =
        identityStore.ensureIdentityInCurrentTransaction(createdAt)

    private suspend fun requireIdentity(): LocalIdentityRow = identityStore.requireIdentity()

    private suspend fun ensureUnusedMutationIds(ids: MutationIds, eventMustBeUnused: Boolean) {
        if (mutationDao.operationExists(ids.operationId.toString())) {
            throw WellbeingIdentityCollisionException("Operation ID was already used")
        }
        if (mutationDao.captureExists(ids.captureId.toString())) {
            throw WellbeingIdentityCollisionException("Capture ID was already used")
        }
        if (mutationDao.revisionExists(ids.revisionId.toString())) {
            throw WellbeingIdentityCollisionException("Revision ID was already used")
        }
        if (eventMustBeUnused && mutationDao.eventExists(ids.eventId.toString())) {
            throw WellbeingIdentityCollisionException("Event ID was already used")
        }
    }

    private suspend fun validateCorrectionSelections(
        previous: List<WellbeingValueSnapshot>,
        proposed: List<WellbeingValueSnapshot>,
    ) {
        val previousSnapshots = previous.toSet()
        proposed.forEach { value ->
            if (value !in previousSnapshots) validateCurrentSelection(value)
        }
    }

    private suspend fun validateCurrentSelections(values: List<WellbeingValueSnapshot>) {
        values.forEach { value -> validateCurrentSelection(value) }
    }

    private suspend fun validateCurrentSelection(value: WellbeingValueSnapshot) {
        val row = catalogDao.findCurrent(value.dimensionId.toString())
            ?: throw ru.andriyshkoy.lifeagent.wellbeing.domain.InvalidWellbeingException(
                "Selected wellbeing dimension is unavailable",
            )
        if (
            row.item.catalogKind != WELLBEING_CATALOG_KIND ||
            row.version.schemaVersion != CanonicalWellbeingCatalogCodec.SCHEMA_VERSION
        ) {
            throw CorruptLocalWellbeingException("Stored wellbeing catalog is invalid")
        }
        val dimension = try {
            catalogCodec.decodeDimension(
                dimensionId = UUID.fromString(row.item.catalogItemId),
                catalogVersionId = UUID.fromString(row.version.catalogVersionId),
                version = row.version.versionNo,
                payloadJcs = row.version.payloadJcs,
            )
        } catch (error: IllegalArgumentException) {
            throw CorruptLocalWellbeingException("Stored wellbeing catalog is invalid", error)
        }
        val canonicalCatalog = catalogCodec.encodePayload(
            label = dimension.label,
            sortOrder = dimension.sortOrder,
            active = dimension.active,
            options = dimension.options,
        )
        val digestMatches = canonicalCatalog.sha256 == row.version.contentSha256
        canonicalCatalog.bytes.fill(0)
        if (!digestMatches) {
            throw CorruptLocalWellbeingException("Stored wellbeing catalog digest is invalid")
        }
        val option = dimension.options.firstOrNull { it.optionId == value.optionId }
        val exact = dimension.active &&
            dimension.version == value.dimensionVersion &&
            dimension.label == value.dimensionLabel &&
            option != null &&
            option.active &&
            option.version == value.optionVersion &&
            option.label == value.optionLabel &&
            option.sortOrder == value.optionSortOrder
        if (!exact) {
            throw ru.andriyshkoy.lifeagent.wellbeing.domain.InvalidWellbeingException(
                "Selected wellbeing option is stale or unavailable",
            )
        }
    }

    private suspend fun requireCurrent(eventId: UUID): CurrentRevisionRow {
        val current = mutationDao.findCurrentRevision(eventId.toString())
            ?: throw WellbeingNotFoundException(eventId)
        if (current.eventKind != WELLBEING_KIND) throw WellbeingNotFoundException(eventId)
        return current
    }

    private fun requireExpectedCurrent(eventId: UUID, expected: UUID, actual: UUID) {
        if (expected != actual) {
            throw StaleWellbeingRevisionException(eventId, expected, actual)
        }
    }

    private suspend fun moveHead(ids: MutationIds, expected: UUID, updatedAt: Instant) {
        val changed = mutationDao.compareAndSetHead(
            eventId = ids.eventId.toString(),
            expectedRevisionId = expected.toString(),
            newRevisionId = ids.revisionId.toString(),
            updatedAtUtc = updatedAt.toString(),
        )
        if (changed != 1) {
            val actual = mutationDao.findCurrentRevision(ids.eventId.toString())
                ?.headRevisionId
                ?.let(UUID::fromString)
                ?: throw WellbeingNotFoundException(ids.eventId)
            throw StaleWellbeingRevisionException(ids.eventId, expected, actual)
        }
    }

    private fun capture(
        ids: MutationIds,
        identity: LocalIdentityRow,
        payload: WellbeingPayload,
        recordedAt: OffsetDateTime,
    ): LocalCaptureEntity {
        val content = codec.encodeCaptureContent(payload)
        require(recordedAt.offset.totalSeconds % 60 == 0) {
            "Capture offset must be representable in whole minutes"
        }
        return LocalCaptureEntity(
            captureId = ids.captureId.toString(),
            operationId = ids.operationId.toString(),
            installationId = identity.installationId,
            localOwnerId = identity.localOwnerId,
            schemaVersion = CanonicalWellbeingCodec.CAPTURE_SCHEMA_VERSION,
            sourceChannel = ANDROID_MANUAL,
            recordedAtRfc3339 = CanonicalWellbeingCodec.formatOffset(recordedAt),
            recordedAtEpochMs = recordedAt.toInstant().toEpochMilli(),
            timezoneId = recordedAt.offset.id,
            utcOffsetMinutes = recordedAt.offset.totalSeconds / 60,
            originProvider = null,
            originApp = ORIGIN_APP,
            originDevice = null,
            originSourceRecordId = null,
            originSourceRecordVersion = null,
            originUserEntered = true,
            collectorName = CanonicalWellbeingCodec.COLLECTOR_NAME,
            collectorVersion = collectorVersion,
            contentJcs = content.bytes,
            contentSha256 = content.sha256,
            byteSize = content.bytes.size.toLong(),
        )
    }

    private fun revision(
        ids: MutationIds,
        revisionNo: Int,
        encoding: WellbeingRevisionEncoding,
        commandFingerprint: String,
        status: WellbeingRecordStatus,
        effectiveTime: ResolvedPointTime,
        recordedAt: OffsetDateTime,
        correctionReason: String?,
    ): LocalEventRevisionEntity = LocalEventRevisionEntity(
        revisionId = ids.revisionId.toString(),
        eventId = ids.eventId.toString(),
        captureId = ids.captureId.toString(),
        operationId = ids.operationId.toString(),
        revisionNo = revisionNo,
        schemaVersion = CanonicalWellbeingCodec.EVENT_SCHEMA_VERSION,
        assertionStatus = ASSERTION_OBSERVED,
        recordStatus = status.storageValue,
        verificationStatus = VERIFICATION_USER_CONFIRMED,
        sourceChannel = ANDROID_MANUAL,
        sourceRecordId = null,
        sourceRecordVersion = null,
        sourceModifiedAt = null,
        recordedAtRfc3339 = CanonicalWellbeingCodec.formatOffset(recordedAt),
        originProvider = null,
        originApp = ORIGIN_APP,
        originDevice = null,
        originUserEntered = true,
        collectorName = CanonicalWellbeingCodec.COLLECTOR_NAME,
        collectorVersion = collectorVersion,
        effectiveStartUtc = CanonicalWellbeingCodec.formatInstant(effectiveTime.effectiveAt),
        effectiveStartEpochMs = effectiveTime.effectiveAt.toEpochMilli(),
        effectiveEndUtc = null,
        effectiveEndEpochMs = null,
        originalLocalStart =
            CanonicalWellbeingCodec.formatLocalDateTime(effectiveTime.originalLocal),
        originalLocalEnd = null,
        timezoneId = effectiveTime.timezoneId.id,
        startOffsetSeconds = effectiveTime.offset.totalSeconds,
        endOffsetSeconds = null,
        temporalPrecision = effectiveTime.precision.storageValue,
        localDate = effectiveTime.localDate.toString(),
        sourceExpression = null,
        payloadJcs = encoding.payload.bytes,
        evidenceJcs = encoding.evidence.bytes,
        qualityFlagsJcs = encoding.qualityFlags.bytes,
        createdAtRfc3339 = CanonicalWellbeingCodec.formatOffset(recordedAt),
        contentSha256 = encoding.contentSha256,
        commandFingerprintSha256 = commandFingerprint,
        actor = ACTOR_USER,
        correctionReason = correctionReason,
    )

    private fun receipt(
        revision: LocalEventRevisionEntity,
        disposition: WellbeingMutationDisposition,
    ): WellbeingMutationReceipt = WellbeingMutationReceipt(
        wellbeing = revision.toSnapshot(codec),
        disposition = disposition,
    )

    private fun decodePayload(revision: LocalEventRevisionEntity): WellbeingPayload = try {
        codec.decodePayload(revision.payloadJcs)
    } catch (error: IllegalArgumentException) {
        throw CorruptLocalWellbeingException("Stored wellbeing payload is invalid", error)
    }

    private companion object {
        const val WELLBEING_KIND = "wellbeing"
        const val WELLBEING_CATALOG_KIND = "wellbeing_dimension"
        const val ANDROID_MANUAL = "android_manual"
        const val ORIGIN_APP = "Life Agent Android"
        const val ASSERTION_OBSERVED = "observed"
        const val VERIFICATION_USER_CONFIRMED = "user_confirmed"
        const val ACTOR_USER = "user"
        const val PARENT_SUPERSEDES = "supersedes"
    }
}

private fun RevisionContextRow.toReceipt(
    codec: CanonicalWellbeingCodec,
    disposition: WellbeingMutationDisposition,
): WellbeingMutationReceipt = WellbeingMutationReceipt(
    wellbeing = revision.toSnapshot(codec),
    disposition = disposition,
)

private fun CurrentRevisionRow.toSnapshot(codec: CanonicalWellbeingCodec): WellbeingSnapshot =
    revision.toSnapshot(codec)

private fun LocalEventRevisionEntity.toSnapshot(
    codec: CanonicalWellbeingCodec,
): WellbeingSnapshot {
    val payload = try {
        codec.decodePayload(payloadJcs)
    } catch (error: IllegalArgumentException) {
        throw CorruptLocalWellbeingException("Stored wellbeing payload is invalid", error)
    }
    return WellbeingSnapshot(
        eventId = UUID.fromString(eventId),
        revisionId = UUID.fromString(revisionId),
        operationId = UUID.fromString(operationId),
        revisionNo = revisionNo,
        payload = payload,
        status = WellbeingRecordStatus.fromStorage(recordStatus),
        effectiveTime = toResolvedPointTime(),
        recordedAt = OffsetDateTime.parse(recordedAtRfc3339),
        createdAt = OffsetDateTime.parse(createdAtRfc3339),
        correctionReason = correctionReason,
    )
}

private fun LocalEventRevisionEntity.toResolvedPointTime(): ResolvedPointTime =
    ResolvedPointTime(
        effectiveAt = Instant.parse(effectiveStartUtc),
        originalLocal = LocalDateTime.parse(originalLocalStart),
        timezoneId = ZoneId.of(timezoneId),
        offset = ZoneOffset.ofTotalSeconds(startOffsetSeconds),
        precision = TemporalPrecision.entries.firstOrNull {
            it.storageValue == temporalPrecision
        } ?: throw CorruptLocalWellbeingException(
            "Stored wellbeing has unknown temporal precision",
        ),
        localDate = LocalDate.parse(localDate),
    )
