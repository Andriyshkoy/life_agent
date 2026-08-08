package ru.andriyshkoy.lifeagent.domain.export

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.andriyshkoy.lifeagent.data.export.LifeAgentExportTestFixtures
import ru.andriyshkoy.lifeagent.notes.domain.CanonicalNoteRevisionSnapshot
import ru.andriyshkoy.lifeagent.notes.domain.CorrectNoteCommand
import ru.andriyshkoy.lifeagent.notes.domain.CreateNoteCommand
import ru.andriyshkoy.lifeagent.notes.domain.NoteEventPointer
import ru.andriyshkoy.lifeagent.notes.domain.NoteMutationOutcome
import ru.andriyshkoy.lifeagent.notes.domain.NoteMutationReceipt
import ru.andriyshkoy.lifeagent.notes.domain.NoteRecordStatus
import ru.andriyshkoy.lifeagent.notes.domain.NoteSnapshot
import ru.andriyshkoy.lifeagent.notes.domain.NoteSummary
import ru.andriyshkoy.lifeagent.notes.domain.NotesExportSnapshot
import ru.andriyshkoy.lifeagent.notes.domain.NotesRepository
import ru.andriyshkoy.lifeagent.notes.domain.RetractNoteCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.ArchiveWellbeingDimensionCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.CanonicalWellbeingRevisionSnapshot
import ru.andriyshkoy.lifeagent.wellbeing.domain.CorrectWellbeingCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.CreateWellbeingCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.CreateWellbeingDimensionCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.RetractWellbeingCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.UpdateWellbeingDimensionCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingCatalogExportSnapshot
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingCatalogHeadSnapshot
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingCatalogItemSnapshot
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingCatalogRepository
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingCatalogVersionSnapshot
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingDimension
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingEventPointer
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingExportSnapshot
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingMutationOutcome
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingMutationReceipt
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingRecordStatus
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingRepository
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingSnapshot
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingSummary

class RepositoriesLifeAgentExportSnapshotSourceTest {
    @Test
    fun composesNotesWellbeingAndCatalogSnapshotsWithoutDaoCoupling() = runTest {
        val fixture = LifeAgentExportTestFixtures.snapshot()
        val noteEvent = fixture.events[0]
        val wellbeingEvent = fixture.events[1]
        val noteRevision = fixture.revisions[0]
        val wellbeingRevision = fixture.revisions[1]
        val item = fixture.catalogs.items.single()
        val version = fixture.catalogs.versions.single()
        val head = fixture.catalogs.heads.single()
        val source = RepositoriesLifeAgentExportSnapshotSource(
            notesRepository = FakeNotesRepository(
                NotesExportSnapshot(
                    events = listOf(
                        NoteEventPointer(uuid(noteEvent.eventId), uuid(noteEvent.currentRevisionId)),
                    ),
                    revisions = listOf(
                        CanonicalNoteRevisionSnapshot(
                            eventId = uuid(noteEvent.eventId),
                            revisionId = uuid(noteEvent.currentRevisionId),
                            revisionNo = 1,
                            status = NoteRecordStatus.ACTIVE,
                            canonicalJson = noteRevision.toByteArray().toString(Charsets.UTF_8),
                            contentSha256 = "unused-by-export-adapter",
                        ),
                    ),
                ),
            ),
            wellbeingRepository = FakeWellbeingRepository(
                WellbeingExportSnapshot(
                    events = listOf(
                        WellbeingEventPointer(
                            uuid(wellbeingEvent.eventId),
                            uuid(wellbeingEvent.currentRevisionId),
                        ),
                    ),
                    revisions = listOf(
                        CanonicalWellbeingRevisionSnapshot(
                            eventId = uuid(wellbeingEvent.eventId),
                            revisionId = uuid(wellbeingEvent.currentRevisionId),
                            revisionNo = 1,
                            status = WellbeingRecordStatus.ACTIVE,
                            canonicalJson = wellbeingRevision.toByteArray().toString(Charsets.UTF_8),
                            contentSha256 = "unused-by-export-adapter",
                        ),
                    ),
                ),
            ),
            wellbeingCatalogRepository = FakeCatalogRepository(
                WellbeingCatalogExportSnapshot(
                    items = listOf(
                        WellbeingCatalogItemSnapshot(
                            catalogItemId = uuid(item.catalogItemId),
                            localOwnerId = uuid(item.localOwnerId),
                            catalogKind = item.catalogKind,
                            createdAt = Instant.parse(item.createdAt),
                        ),
                    ),
                    versions = listOf(
                        WellbeingCatalogVersionSnapshot(
                            catalogVersionId = uuid(version.catalogVersionId),
                            catalogItemId = uuid(version.catalogItemId),
                            version = version.versionNo,
                            schemaVersion = version.schemaVersion,
                            canonicalPayloadJson =
                                version.payload.toByteArray().toString(Charsets.UTF_8),
                            contentSha256 = version.contentSha256,
                            createdAt = Instant.parse(version.createdAt),
                        ),
                    ),
                    heads = listOf(
                        WellbeingCatalogHeadSnapshot(
                            catalogItemId = uuid(head.catalogItemId),
                            currentVersionId = uuid(head.currentVersionId),
                            updatedAt = Instant.parse(head.updatedAt),
                        ),
                    ),
                ),
            ),
        )

        val combined = source.loadLifeAgentExportSnapshot()

        assertEquals(fixture.catalogs, combined.catalogs)
        assertEquals(fixture.events, combined.events)
        assertEquals(fixture.revisions, combined.revisions)
    }

    private fun uuid(value: String): UUID = UUID.fromString(value)
}

private class FakeNotesRepository(
    private val snapshot: NotesExportSnapshot,
) : NotesRepository {
    override suspend fun create(command: CreateNoteCommand): NoteMutationOutcome = unused()
    override suspend fun correct(command: CorrectNoteCommand): NoteMutationOutcome = unused()
    override suspend fun retract(command: RetractNoteCommand): NoteMutationOutcome = unused()
    override fun observeLastCommitted(): Flow<NoteSummary?> = emptyFlow()
    override suspend fun getByEventId(eventId: UUID): NoteSnapshot? = unused()
    override suspend fun findByOperationId(operationId: UUID): NoteMutationReceipt? = unused()
    override suspend fun exportSnapshot(): NotesExportSnapshot = snapshot
}

private class FakeWellbeingRepository(
    private val snapshot: WellbeingExportSnapshot,
) : WellbeingRepository {
    override suspend fun create(command: CreateWellbeingCommand): WellbeingMutationOutcome = unused()
    override suspend fun correct(command: CorrectWellbeingCommand): WellbeingMutationOutcome = unused()
    override suspend fun retract(command: RetractWellbeingCommand): WellbeingMutationOutcome = unused()
    override fun observeLastCommitted(): Flow<WellbeingSummary?> = emptyFlow()
    override suspend fun getByEventId(eventId: UUID): WellbeingSnapshot? = unused()
    override suspend fun findByOperationId(operationId: UUID): WellbeingMutationReceipt? = unused()
    override suspend fun exportSnapshot(): WellbeingExportSnapshot = snapshot
}

private class FakeCatalogRepository(
    private val snapshot: WellbeingCatalogExportSnapshot,
) : WellbeingCatalogRepository {
    override suspend fun ensureSeeded(createdAt: Instant) = Unit
    override fun observeDimensions(includeArchived: Boolean): Flow<List<WellbeingDimension>> =
        emptyFlow()
    override suspend fun getDimension(dimensionId: UUID): WellbeingDimension? = unused()
    override suspend fun create(command: CreateWellbeingDimensionCommand): WellbeingDimension =
        unused()
    override suspend fun update(command: UpdateWellbeingDimensionCommand): WellbeingDimension =
        unused()
    override suspend fun archive(command: ArchiveWellbeingDimensionCommand): WellbeingDimension =
        unused()
    override suspend fun exportSnapshot(): WellbeingCatalogExportSnapshot = snapshot
}

private fun unused(): Nothing = error("Not used by export snapshot test")
