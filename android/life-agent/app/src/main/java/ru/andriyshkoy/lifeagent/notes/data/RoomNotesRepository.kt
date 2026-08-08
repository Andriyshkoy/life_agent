package ru.andriyshkoy.lifeagent.notes.data

import androidx.room.withTransaction
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
import ru.andriyshkoy.lifeagent.data.local.serialization.CanonicalNoteCodec
import ru.andriyshkoy.lifeagent.data.local.serialization.NoteRevisionEncoding
import ru.andriyshkoy.lifeagent.notes.domain.CanonicalNoteRevisionSnapshot
import ru.andriyshkoy.lifeagent.notes.domain.CorrectNoteCommand
import ru.andriyshkoy.lifeagent.notes.domain.CorruptLocalNoteException
import ru.andriyshkoy.lifeagent.notes.domain.CreateNoteCommand
import ru.andriyshkoy.lifeagent.notes.domain.IdempotencyConflictException
import ru.andriyshkoy.lifeagent.notes.domain.LocalIdentityCollisionException
import ru.andriyshkoy.lifeagent.notes.domain.NoteEventPointer
import ru.andriyshkoy.lifeagent.notes.domain.NoteMutationDisposition
import ru.andriyshkoy.lifeagent.notes.domain.NoteMutationOutcome
import ru.andriyshkoy.lifeagent.notes.domain.NoteMutationReceipt
import ru.andriyshkoy.lifeagent.notes.domain.NoteNotFoundException
import ru.andriyshkoy.lifeagent.notes.domain.NoteRecordStatus
import ru.andriyshkoy.lifeagent.notes.domain.NoteSnapshot
import ru.andriyshkoy.lifeagent.notes.domain.NoteSummary
import ru.andriyshkoy.lifeagent.notes.domain.NoteTextPolicy
import ru.andriyshkoy.lifeagent.notes.domain.NotesExportSnapshot
import ru.andriyshkoy.lifeagent.notes.domain.NotesRepository
import ru.andriyshkoy.lifeagent.notes.domain.RetractNoteCommand
import ru.andriyshkoy.lifeagent.notes.domain.RetractedNoteCorrectionException
import ru.andriyshkoy.lifeagent.notes.domain.StaleNoteRevisionException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class RoomNotesRepository(
    private val database: LifeAgentDatabase,
    private val collectorVersion: String,
    uuidGenerator: UuidGenerator = RandomUuidGenerator,
    private val codec: CanonicalNoteCodec = CanonicalNoteCodec(),
) : NotesRepository {
    private val identityStore = LocalIdentityStore(database, uuidGenerator)
    private val mutationDao = database.noteMutationDao()
    private val queryDao = database.noteQueryDao()

    init {
        require(collectorVersion.isNotBlank()) { "Collector version must not be blank" }
    }

    override suspend fun create(command: CreateNoteCommand): NoteMutationOutcome {
        NoteTextPolicy.validate(command.text)
        val fingerprint = codec.commandFingerprint(command)
        return database.withTransaction {
            replay(command.ids.operationId, fingerprint)?.let {
                return@withTransaction NoteMutationOutcome.Persisted(it)
            }
            ensureUnusedMutationIds(command.ids, eventMustBeUnused = true)
            val identity = ensureIdentity(command.recordedAt.toInstant())
            val encoding = codec.encodeRevision(
                ids = command.ids,
                revisionNo = 1,
                text = command.text,
                status = NoteRecordStatus.ACTIVE,
                effectiveTime = command.effectiveTime,
                recordedAt = command.recordedAt,
                correctionReason = null,
                parentRevisionId = null,
            )
            val capture = capture(
                ids = command.ids,
                identity = identity,
                text = command.text,
                recordedAt = command.recordedAt,
            )
            val revision = revision(
                ids = command.ids,
                revisionNo = 1,
                textEncoding = encoding,
                commandFingerprint = fingerprint,
                status = NoteRecordStatus.ACTIVE,
                effectiveTime = command.effectiveTime,
                recordedAt = command.recordedAt,
                correctionReason = null,
            )
            mutationDao.insertCapture(capture)
            mutationDao.insertEvent(
                LocalLifeEventEntity(
                    eventId = command.ids.eventId.toString(),
                    localOwnerId = identity.localOwnerId,
                    kind = NOTE_KIND,
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
            NoteMutationOutcome.Persisted(
                receipt(
                    revision = revision,
                    disposition = NoteMutationDisposition.COMMITTED,
                ),
            )
        }
    }

    override suspend fun correct(command: CorrectNoteCommand): NoteMutationOutcome {
        NoteTextPolicy.validate(command.text)
        val fingerprint = codec.commandFingerprint(command)
        return database.withTransaction {
            replay(command.ids.operationId, fingerprint)?.let {
                return@withTransaction NoteMutationOutcome.Persisted(it)
            }
            ensureUnusedMutationIds(command.ids, eventMustBeUnused = false)
            val current = requireCurrent(command.ids.eventId)
            requireExpectedCurrent(
                eventId = command.ids.eventId,
                expected = command.expectedCurrentRevisionId,
                actual = UUID.fromString(current.headRevisionId),
            )
            if (NoteRecordStatus.fromStorage(current.revision.recordStatus) ==
                NoteRecordStatus.RETRACTED
            ) {
                throw RetractedNoteCorrectionException(command.ids.eventId)
            }
            val identity = requireIdentity()
            val revisionNo = current.revision.revisionNo + 1
            val encoding = codec.encodeRevision(
                ids = command.ids,
                revisionNo = revisionNo,
                text = command.text,
                status = NoteRecordStatus.ACTIVE,
                effectiveTime = command.effectiveTime,
                recordedAt = command.recordedAt,
                correctionReason = command.reason,
                parentRevisionId = current.revision.revisionId,
            )
            val revision = revision(
                ids = command.ids,
                revisionNo = revisionNo,
                textEncoding = encoding,
                commandFingerprint = fingerprint,
                status = NoteRecordStatus.ACTIVE,
                effectiveTime = command.effectiveTime,
                recordedAt = command.recordedAt,
                correctionReason = command.reason,
            )
            mutationDao.insertCapture(
                capture(
                    ids = command.ids,
                    identity = identity,
                    text = command.text,
                    recordedAt = command.recordedAt,
                ),
            )
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
            NoteMutationOutcome.Persisted(
                receipt(
                    revision = revision,
                    disposition = NoteMutationDisposition.COMMITTED,
                ),
            )
        }
    }

    override suspend fun retract(command: RetractNoteCommand): NoteMutationOutcome {
        val fingerprint = codec.commandFingerprint(command)
        return database.withTransaction {
            replay(command.ids.operationId, fingerprint)?.let {
                return@withTransaction NoteMutationOutcome.Persisted(it)
            }
            val current = requireCurrent(command.ids.eventId)
            if (NoteRecordStatus.fromStorage(current.revision.recordStatus) ==
                NoteRecordStatus.RETRACTED
            ) {
                return@withTransaction NoteMutationOutcome.AlreadyRetracted(
                    current.toSnapshot(codec),
                )
            }
            requireExpectedCurrent(
                eventId = command.ids.eventId,
                expected = command.expectedCurrentRevisionId,
                actual = UUID.fromString(current.headRevisionId),
            )
            ensureUnusedMutationIds(command.ids, eventMustBeUnused = false)
            val identity = requireIdentity()
            val originalTime = current.revision.toResolvedPointTime()
            val text = decodeText(current.revision)
            val revisionNo = current.revision.revisionNo + 1
            val encoding = codec.encodeRevision(
                ids = command.ids,
                revisionNo = revisionNo,
                text = text,
                status = NoteRecordStatus.RETRACTED,
                effectiveTime = originalTime,
                recordedAt = command.recordedAt,
                correctionReason = command.reason,
                parentRevisionId = current.revision.revisionId,
            )
            val revision = revision(
                ids = command.ids,
                revisionNo = revisionNo,
                textEncoding = encoding,
                commandFingerprint = fingerprint,
                status = NoteRecordStatus.RETRACTED,
                effectiveTime = originalTime,
                recordedAt = command.recordedAt,
                correctionReason = command.reason,
            )
            mutationDao.insertCapture(
                capture(
                    ids = command.ids,
                    identity = identity,
                    text = text,
                    recordedAt = command.recordedAt,
                ),
            )
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
            NoteMutationOutcome.Persisted(
                receipt(
                    revision = revision,
                    disposition = NoteMutationDisposition.COMMITTED,
                ),
            )
        }
    }

    override fun observeLastCommitted(): Flow<NoteSummary?> =
        queryDao.observeLastCommitted().map { row ->
            row?.let {
                val note = it.toSnapshot(codec)
                NoteSummary(
                    eventId = note.eventId,
                    revisionId = note.revisionId,
                    operationId = note.operationId,
                    text = note.text,
                    status = note.status,
                    effectiveTime = note.effectiveTime,
                )
            }
        }

    override suspend fun getByEventId(eventId: UUID): NoteSnapshot? =
        queryDao.findCurrentNote(eventId.toString())?.toSnapshot(codec)

    override suspend fun findByOperationId(operationId: UUID): NoteMutationReceipt? {
        val row = queryDao.findByOperationId(operationId.toString()) ?: return null
        return row.toReceipt(codec, NoteMutationDisposition.COMMITTED)
    }

    override suspend fun exportSnapshot(): NotesExportSnapshot =
        database.withTransaction {
            val noteEventIds = queryDao.findAllNoteEventIds()
            val pointers = queryDao.findEventPointers().map { pointer ->
                NoteEventPointer(
                    eventId = UUID.fromString(pointer.eventId),
                    currentRevisionId = UUID.fromString(pointer.currentRevisionId),
                )
            }
            if (pointers.map { it.eventId.toString() } != noteEventIds) {
                throw CorruptLocalNoteException(
                    "Cannot export notes because local event heads are incomplete",
                )
            }
            val revisions = queryDao.findAllRevisionContexts().map { row ->
                val parents = queryDao.findParents(row.revision.revisionId)
                val canonical = codec.encodeCanonicalEvent(row, parents)
                CanonicalNoteRevisionSnapshot(
                    eventId = UUID.fromString(row.revision.eventId),
                    revisionId = UUID.fromString(row.revision.revisionId),
                    revisionNo = row.revision.revisionNo,
                    status = NoteRecordStatus.fromStorage(row.revision.recordStatus),
                    canonicalJson = canonical.utf8,
                    contentSha256 = row.revision.contentSha256,
                )
            }
            NotesExportSnapshot(events = pointers, revisions = revisions)
        }

    private suspend fun replay(
        operationId: UUID,
        expectedFingerprint: String,
    ): NoteMutationReceipt? {
        val row = queryDao.findByOperationId(operationId.toString()) ?: return null
        if (row.revision.commandFingerprintSha256 != expectedFingerprint) {
            throw IdempotencyConflictException(operationId)
        }
        return row.toReceipt(codec, NoteMutationDisposition.REPLAYED)
    }

    private suspend fun ensureIdentity(createdAt: Instant): LocalIdentityRow {
        return identityStore.ensureIdentityInCurrentTransaction(createdAt)
    }

    private suspend fun requireIdentity(): LocalIdentityRow =
        identityStore.requireIdentity()

    private suspend fun ensureUnusedMutationIds(
        ids: MutationIds,
        eventMustBeUnused: Boolean,
    ) {
        if (mutationDao.operationExists(ids.operationId.toString())) {
            throw LocalIdentityCollisionException("Operation ID was already used")
        }
        if (mutationDao.captureExists(ids.captureId.toString())) {
            throw LocalIdentityCollisionException("Capture ID was already used")
        }
        if (mutationDao.revisionExists(ids.revisionId.toString())) {
            throw LocalIdentityCollisionException("Revision ID was already used")
        }
        if (eventMustBeUnused && mutationDao.eventExists(ids.eventId.toString())) {
            throw LocalIdentityCollisionException("Event ID was already used")
        }
    }

    private suspend fun requireCurrent(eventId: UUID): CurrentRevisionRow {
        val current = mutationDao.findCurrentRevision(eventId.toString())
            ?: throw NoteNotFoundException(eventId)
        if (current.eventKind != NOTE_KIND) {
            throw NoteNotFoundException(eventId)
        }
        return current
    }

    private fun requireExpectedCurrent(
        eventId: UUID,
        expected: UUID,
        actual: UUID,
    ) {
        if (expected != actual) {
            throw StaleNoteRevisionException(eventId, expected, actual)
        }
    }

    private suspend fun moveHead(
        ids: MutationIds,
        expectedRevisionId: UUID,
        updatedAt: Instant,
    ) {
        val changed = mutationDao.compareAndSetHead(
            eventId = ids.eventId.toString(),
            expectedRevisionId = expectedRevisionId.toString(),
            newRevisionId = ids.revisionId.toString(),
            updatedAtUtc = updatedAt.toString(),
        )
        if (changed != 1) {
            val actual = mutationDao.findCurrentRevision(ids.eventId.toString())
                ?.headRevisionId
                ?.let(UUID::fromString)
                ?: throw NoteNotFoundException(ids.eventId)
            throw StaleNoteRevisionException(ids.eventId, expectedRevisionId, actual)
        }
    }

    private fun capture(
        ids: MutationIds,
        identity: LocalIdentityRow,
        text: String,
        recordedAt: OffsetDateTime,
    ): LocalCaptureEntity {
        val content = codec.encodeCaptureContent(text)
        require(recordedAt.offset.totalSeconds % 60 == 0) {
            "Capture offset must be representable in whole minutes"
        }
        return LocalCaptureEntity(
            captureId = ids.captureId.toString(),
            operationId = ids.operationId.toString(),
            installationId = identity.installationId,
            localOwnerId = identity.localOwnerId,
            schemaVersion = CanonicalNoteCodec.CAPTURE_SCHEMA_VERSION,
            sourceChannel = ANDROID_MANUAL,
            recordedAtRfc3339 = CanonicalNoteCodec.formatOffset(recordedAt),
            recordedAtEpochMs = recordedAt.toInstant().toEpochMilli(),
            timezoneId = recordedAt.offset.id,
            utcOffsetMinutes = recordedAt.offset.totalSeconds / 60,
            originProvider = null,
            originApp = ORIGIN_APP,
            originDevice = null,
            originSourceRecordId = null,
            originSourceRecordVersion = null,
            originUserEntered = true,
            collectorName = CanonicalNoteCodec.COLLECTOR_NAME,
            collectorVersion = collectorVersion,
            contentJcs = content.bytes,
            contentSha256 = content.sha256,
            byteSize = content.bytes.size.toLong(),
        )
    }

    private fun revision(
        ids: MutationIds,
        revisionNo: Int,
        textEncoding: NoteRevisionEncoding,
        commandFingerprint: String,
        status: NoteRecordStatus,
        effectiveTime: ResolvedPointTime,
        recordedAt: OffsetDateTime,
        correctionReason: String?,
    ): LocalEventRevisionEntity = LocalEventRevisionEntity(
        revisionId = ids.revisionId.toString(),
        eventId = ids.eventId.toString(),
        captureId = ids.captureId.toString(),
        operationId = ids.operationId.toString(),
        revisionNo = revisionNo,
        schemaVersion = CanonicalNoteCodec.EVENT_SCHEMA_VERSION,
        assertionStatus = ASSERTION_OBSERVED,
        recordStatus = status.storageValue,
        verificationStatus = VERIFICATION_USER_CONFIRMED,
        sourceChannel = ANDROID_MANUAL,
        sourceRecordId = null,
        sourceRecordVersion = null,
        sourceModifiedAt = null,
        recordedAtRfc3339 = CanonicalNoteCodec.formatOffset(recordedAt),
        originProvider = null,
        originApp = ORIGIN_APP,
        originDevice = null,
        originUserEntered = true,
        collectorName = CanonicalNoteCodec.COLLECTOR_NAME,
        collectorVersion = collectorVersion,
        effectiveStartUtc = CanonicalNoteCodec.formatInstant(effectiveTime.effectiveAt),
        effectiveStartEpochMs = effectiveTime.effectiveAt.toEpochMilli(),
        effectiveEndUtc = null,
        effectiveEndEpochMs = null,
        originalLocalStart =
            CanonicalNoteCodec.formatLocalDateTime(effectiveTime.originalLocal),
        originalLocalEnd = null,
        timezoneId = effectiveTime.timezoneId.id,
        startOffsetSeconds = effectiveTime.offset.totalSeconds,
        endOffsetSeconds = null,
        temporalPrecision = effectiveTime.precision.storageValue,
        localDate = effectiveTime.localDate.toString(),
        sourceExpression = null,
        payloadJcs = textEncoding.payload.bytes,
        evidenceJcs = textEncoding.evidence.bytes,
        qualityFlagsJcs = textEncoding.qualityFlags.bytes,
        createdAtRfc3339 = CanonicalNoteCodec.formatOffset(recordedAt),
        contentSha256 = textEncoding.contentSha256,
        commandFingerprintSha256 = commandFingerprint,
        actor = ACTOR_USER,
        correctionReason = correctionReason,
    )

    private fun receipt(
        revision: LocalEventRevisionEntity,
        disposition: NoteMutationDisposition,
    ): NoteMutationReceipt = NoteMutationReceipt(
        note = revision.toSnapshot(codec),
        disposition = disposition,
    )

    private fun decodeText(revision: LocalEventRevisionEntity): String =
        try {
            codec.decodeNoteText(revision.payloadJcs)
        } catch (error: IllegalArgumentException) {
            throw CorruptLocalNoteException("Stored note payload is invalid", error)
        }

    private companion object {
        const val NOTE_KIND = "note"
        const val ANDROID_MANUAL = "android_manual"
        const val ORIGIN_APP = "Life Agent Android"
        const val ASSERTION_OBSERVED = "observed"
        const val VERIFICATION_USER_CONFIRMED = "user_confirmed"
        const val ACTOR_USER = "user"
        const val PARENT_SUPERSEDES = "supersedes"
    }
}

private fun RevisionContextRow.toReceipt(
    codec: CanonicalNoteCodec,
    disposition: NoteMutationDisposition,
): NoteMutationReceipt =
    NoteMutationReceipt(
        note = revision.toSnapshot(codec),
        disposition = disposition,
    )

private fun CurrentRevisionRow.toSnapshot(codec: CanonicalNoteCodec): NoteSnapshot =
    revision.toSnapshot(codec)

private fun LocalEventRevisionEntity.toSnapshot(codec: CanonicalNoteCodec): NoteSnapshot {
    val text = try {
        codec.decodeNoteText(payloadJcs)
    } catch (error: IllegalArgumentException) {
        throw CorruptLocalNoteException("Stored note payload is invalid", error)
    }
    return NoteSnapshot(
        eventId = UUID.fromString(eventId),
        revisionId = UUID.fromString(revisionId),
        operationId = UUID.fromString(operationId),
        revisionNo = revisionNo,
        text = text,
        status = NoteRecordStatus.fromStorage(recordStatus),
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
        } ?: throw CorruptLocalNoteException("Stored note has unknown temporal precision"),
        localDate = LocalDate.parse(localDate),
    )
