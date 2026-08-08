package ru.andriyshkoy.lifeagent.domain.export

import ru.andriyshkoy.lifeagent.data.export.NotesExportCodec
import ru.andriyshkoy.lifeagent.data.export.CanonicalNoteRevisionJson
import ru.andriyshkoy.lifeagent.data.export.NoteEventPointerSnapshot
import ru.andriyshkoy.lifeagent.data.export.NotesExportSnapshot
import ru.andriyshkoy.lifeagent.notes.domain.NotesRepository

/**
 * Narrow persistence boundary for local note exports.
 *
 * A Room adapter can implement this once its notes snapshot query is
 * available, without coupling the canonical export codec to entities or DAOs.
 */
fun interface NotesExportSnapshotSource {
    suspend fun loadNotesExportSnapshot(): NotesExportSnapshot
}

class ExportNotesUseCase(
    private val source: NotesExportSnapshotSource,
    private val codec: NotesExportCodec,
) {
    suspend operator fun invoke(): ByteArray =
        codec.encode(source.loadNotesExportSnapshot())
}

class NotesRepositoryExportSnapshotSource(
    private val repository: NotesRepository,
) : NotesExportSnapshotSource {
    override suspend fun loadNotesExportSnapshot(): NotesExportSnapshot {
        val snapshot = repository.exportSnapshot()
        return NotesExportSnapshot(
            events = snapshot.events.map { pointer ->
                NoteEventPointerSnapshot(
                    eventId = pointer.eventId.toString(),
                    currentRevisionId = pointer.currentRevisionId.toString(),
                )
            },
            revisions = snapshot.revisions.map { revision ->
                CanonicalNoteRevisionJson.fromJson(
                    revision.canonicalJson.toByteArray(Charsets.UTF_8),
                )
            },
        )
    }
}
