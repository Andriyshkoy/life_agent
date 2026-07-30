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
import ru.andriyshkoy.lifeagent.data.local.db.dao.CurrentRevisionRow
import ru.andriyshkoy.lifeagent.data.local.db.dao.LocalIdentityRow
import ru.andriyshkoy.lifeagent.data.local.db.dao.RevisionContextRow
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalCaptureEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalEventHeadEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalEventRevisionEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalInstallationEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalIdentityStateEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalLifeEventEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalOwnerEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalRevisionParentEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncOutboxEntity
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
    private val uuidGenerator: UuidGenerator = RandomUuidGenerator,
    private val codec: CanonicalNoteCodec = CanonicalNoteCodec(),
) : NotesRepository {
    private val identityDao = database.identityDao()
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
                status = NoteRecordStatus.ACTIVE,
                effectiveTime = command.effectiveTime,
                recordedAt = command.recordedAt,
                correctionReason = null,
            )
            val operation = outbox(
                ids = command.ids,
                identity = identity,
                baseRevisionId = null,
                status = NoteRecordStatus.ACTIVE,
                revisionContentSha256 = encoding.contentSha256,
                commandFingerprint = fingerprint,
                recordedAt = command.recordedAt,
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
            val localSequence = mutationDao.insertOutbox(operation)
            mutationDao.insertHead(
                LocalEventHeadEntity(
                    eventId = command.ids.eventId.toString(),
                    currentRevisionId = command.ids.revisionId.toString(),
                    serverCurrentRevisionId = null,
                    serverObservedSequence = null,
                    updatedAtUtc = command.recordedAt.toInstant().toString(),
                ),
            )
            NoteMutationOutcome.Persisted(
                receipt(
                    revision = revision,
                    localSequence = localSequence,
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
            val localSequence = mutationDao.insertOutbox(
                outbox(
                    ids = command.ids,
                    identity = identity,
                    baseRevisionId = current.revision.revisionId,
                    status = NoteRecordStatus.ACTIVE,
                    revisionContentSha256 = encoding.contentSha256,
                    commandFingerprint = fingerprint,
                    recordedAt = command.recordedAt,
                ),
            )
            moveHead(command.ids, command.expectedCurrentRevisionId, command.recordedAt.toInstant())
            NoteMutationOutcome.Persisted(
                receipt(
                    revision = revision,
                    localSequence = localSequence,
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
            val localSequence = mutationDao.insertOutbox(
                outbox(
                    ids = command.ids,
                    identity = identity,
                    baseRevisionId = current.revision.revisionId,
                    status = NoteRecordStatus.RETRACTED,
                    revisionContentSha256 = encoding.contentSha256,
                    commandFingerprint = fingerprint,
                    recordedAt = command.recordedAt,
                ),
            )
            moveHead(command.ids, command.expectedCurrentRevisionId, command.recordedAt.toInstant())
            NoteMutationOutcome.Persisted(
                receipt(
                    revision = revision,
                    localSequence = localSequence,
                    disposition = NoteMutationDisposition.COMMITTED,
                ),
            )
        }
    }

    override fun observeLastCommitted(): Flow<NoteSummary?> =
        queryDao.observeLastCommitted().map { row ->
            row?.let {
                val localSequence = it.localSequence
                    ?: throw CorruptLocalNoteException("Latest local note has no outbox sequence")
                val note = it.toSnapshot(codec)
                NoteSummary(
                    eventId = note.eventId,
                    revisionId = note.revisionId,
                    operationId = note.operationId,
                    text = note.text,
                    status = note.status,
                    effectiveTime = note.effectiveTime,
                    localSequence = localSequence,
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
                    serverCurrentRevisionId = pointer.serverCurrentRevisionId?.let(UUID::fromString),
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
        val existing = mutationDao.findOutbox(operationId.toString()) ?: return null
        val row = queryDao.findByOperationId(operationId.toString())
            ?: throw CorruptLocalNoteException(
                "Outbox operation references a missing note revision",
            )
        val actualFingerprint = if (existing.commandFingerprintSha256.isEmpty()) {
            legacyCommandFingerprint(existing, row).also { reconstructed ->
                if (reconstructed == expectedFingerprint) {
                    val changed = mutationDao.setLegacyCommandFingerprint(
                        operationId = existing.operationId,
                        fingerprint = reconstructed,
                    )
                    if (changed != 1) {
                        throw CorruptLocalNoteException(
                            "Legacy outbox fingerprint could not be upgraded",
                        )
                    }
                }
            }
        } else {
            existing.commandFingerprintSha256
        }
        if (actualFingerprint != expectedFingerprint) {
            throw IdempotencyConflictException(operationId)
        }
        return row.toReceipt(codec, NoteMutationDisposition.REPLAYED)
    }

    private fun legacyCommandFingerprint(
        outbox: SyncOutboxEntity,
        row: RevisionContextRow,
    ): String {
        val revision = row.revision
        if (
            outbox.operationKind != APPEND_EVENT_REVISION ||
            outbox.operationId != revision.operationId ||
            outbox.captureId != revision.captureId ||
            outbox.eventId != revision.eventId ||
            outbox.revisionId != revision.revisionId
        ) {
            throw CorruptLocalNoteException(
                "Legacy outbox operation does not match its note revision",
            )
        }
        val ids = MutationIds(
            operationId = UUID.fromString(outbox.operationId),
            captureId = UUID.fromString(outbox.captureId),
            eventId = UUID.fromString(outbox.eventId),
            revisionId = UUID.fromString(outbox.revisionId),
        )
        val status = NoteRecordStatus.fromStorage(revision.recordStatus)
        val recordedAt = OffsetDateTime.parse(revision.recordedAtRfc3339)
        return when {
            outbox.baseRevisionId == null &&
                revision.revisionNo == 1 &&
                status == NoteRecordStatus.ACTIVE -> codec.commandFingerprint(
                CreateNoteCommand(
                    ids = ids,
                    text = decodeText(revision),
                    effectiveTime = revision.toResolvedPointTime(),
                    recordedAt = recordedAt,
                ),
            )

            outbox.baseRevisionId != null &&
                status == NoteRecordStatus.ACTIVE -> codec.commandFingerprint(
                CorrectNoteCommand(
                    ids = ids,
                    expectedCurrentRevisionId = UUID.fromString(outbox.baseRevisionId),
                    text = decodeText(revision),
                    effectiveTime = revision.toResolvedPointTime(),
                    recordedAt = recordedAt,
                    reason = revision.correctionReason,
                ),
            )

            outbox.baseRevisionId != null &&
                status == NoteRecordStatus.RETRACTED -> {
                val reason = revision.correctionReason
                    ?.takeIf(String::isNotBlank)
                    ?: throw CorruptLocalNoteException(
                        "Legacy retraction has no correction reason",
                    )
                codec.commandFingerprint(
                    RetractNoteCommand(
                        ids = ids,
                        expectedCurrentRevisionId = UUID.fromString(outbox.baseRevisionId),
                        recordedAt = recordedAt,
                        reason = reason,
                    ),
                )
            }

            else -> throw CorruptLocalNoteException(
                "Legacy outbox operation cannot be reconstructed",
            )
        }
    }

    private suspend fun ensureIdentity(createdAt: Instant): LocalIdentityRow {
        identityDao.findIdentity()?.let { return it }
        if (identityDao.ownerCount() != 0) {
            throw CorruptLocalNoteException(
                "Current identity marker is missing while historical owners exist",
            )
        }
        val installationId = uuidGenerator.next().toString()
        val ownerId = uuidGenerator.next().toString()
        val createdAtUtc = createdAt.toString()
        identityDao.insertInstallation(
            LocalInstallationEntity(
                installationId = installationId,
                createdAtUtc = createdAtUtc,
            ),
        )
        identityDao.insertOwner(
            LocalOwnerEntity(
                localOwnerId = ownerId,
                installationId = installationId,
                createdAtUtc = createdAtUtc,
            ),
        )
        identityDao.insertIdentityState(
            LocalIdentityStateEntity(
                installationId = installationId,
                localOwnerId = ownerId,
                selectedAtUtc = createdAtUtc,
            ),
        )
        return LocalIdentityRow(
            installationId = installationId,
            localOwnerId = ownerId,
            serverDeviceId = null,
            serverPersonId = null,
        )
    }

    private suspend fun requireIdentity(): LocalIdentityRow =
        identityDao.findIdentity()
            ?: throw CorruptLocalNoteException("Local identity is missing")

    private suspend fun ensureUnusedMutationIds(
        ids: MutationIds,
        eventMustBeUnused: Boolean,
    ) {
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
            persistenceState = LOCAL_PENDING,
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
        lifecycle = null,
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
        actor = ACTOR_USER,
        correctionReason = correctionReason,
        serverReceivedAt = null,
        serverSequence = null,
    )

    private fun outbox(
        ids: MutationIds,
        identity: LocalIdentityRow,
        baseRevisionId: String?,
        status: NoteRecordStatus,
        revisionContentSha256: String,
        commandFingerprint: String,
        recordedAt: OffsetDateTime,
    ): SyncOutboxEntity {
        val operation = codec.encodePendingOperation(
            ids = ids,
            baseRevisionId = baseRevisionId,
            status = status,
            revisionContentSha256 = revisionContentSha256,
        )
        return SyncOutboxEntity(
            operationId = ids.operationId.toString(),
            captureId = ids.captureId.toString(),
            installationId = identity.installationId,
            localOwnerId = identity.localOwnerId,
            operationKind = APPEND_EVENT_REVISION,
            eventId = ids.eventId.toString(),
            revisionId = ids.revisionId.toString(),
            baseRevisionId = baseRevisionId,
            schemaVersion = CanonicalNoteCodec.EVENT_SCHEMA_VERSION,
            legacyOperationJcs = operation.bytes,
            legacyOperationContentSha256 = operation.sha256,
            commandFingerprintSha256 = commandFingerprint,
            createdAtUtc = recordedAt.toInstant().toString(),
            createdAtEpochMs = recordedAt.toInstant().toEpochMilli(),
            state = OUTBOX_PENDING,
            attemptCount = 0,
            nextAttemptAtEpochMs = null,
            lastAttemptAtEpochMs = null,
            serverSequence = null,
            ackedAtUtc = null,
            lastErrorCode = null,
        )
    }

    private fun receipt(
        revision: LocalEventRevisionEntity,
        localSequence: Long,
        disposition: NoteMutationDisposition,
    ): NoteMutationReceipt = NoteMutationReceipt(
        note = revision.toSnapshot(codec),
        localSequence = localSequence,
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
        const val LOCAL_PENDING = "local_pending"
        const val ANDROID_MANUAL = "android_manual"
        const val ORIGIN_APP = "Life Agent Android"
        const val ASSERTION_OBSERVED = "observed"
        const val VERIFICATION_USER_CONFIRMED = "user_confirmed"
        const val ACTOR_USER = "user"
        const val PARENT_SUPERSEDES = "supersedes"
        const val APPEND_EVENT_REVISION = "append_event_revision"
        const val OUTBOX_PENDING = "pending"
    }
}

private fun RevisionContextRow.toReceipt(
    codec: CanonicalNoteCodec,
    disposition: NoteMutationDisposition,
): NoteMutationReceipt {
    val sequence = localSequence
        ?: throw CorruptLocalNoteException("Local note operation has no outbox sequence")
    return NoteMutationReceipt(
        note = revision.toSnapshot(codec),
        localSequence = sequence,
        disposition = disposition,
    )
}

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
