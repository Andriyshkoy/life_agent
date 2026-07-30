package ru.andriyshkoy.lifeagent.data.export

interface NotesExportCodec {
    fun encode(snapshot: NotesExportSnapshot): ByteArray

    fun decode(bytes: ByteArray): NotesExportSnapshot

    fun canonicalize(bytes: ByteArray): ByteArray = encode(decode(bytes))
}

class CanonicalNotesExportCodec(
    private val validator: NotesExportValidator = NotesExportValidator(),
) : NotesExportCodec {
    override fun encode(snapshot: NotesExportSnapshot): ByteArray {
        val normalized = normalize(snapshot)
        val document = CanonicalJsonObject(
            mapOf(
                "format" to CanonicalJsonString(NOTES_EXPORT_FORMAT),
                "format_version" to CanonicalJsonString(NOTES_EXPORT_FORMAT_VERSION),
                "events" to CanonicalJsonArray(
                    normalized.events.map { pointer ->
                        CanonicalJsonObject(
                            mapOf(
                                "event_id" to CanonicalJsonString(pointer.eventId),
                                "current_revision_id" to
                                    CanonicalJsonString(pointer.currentRevisionId),
                            ),
                        )
                    },
                ),
                "revisions" to CanonicalJsonArray(
                    normalized.revisions.map(CanonicalNoteRevisionJson::document),
                ),
            ),
        )
        return CanonicalJson.encode(document)
    }

    override fun decode(bytes: ByteArray): NotesExportSnapshot {
        if (bytes.size > MAX_EXPORT_BYTES) {
            throw NotesExportFormatException(
                "notes export exceeds the $MAX_EXPORT_BYTES byte safety limit",
            )
        }
        val document = CanonicalJson.parse(bytes) as? CanonicalJsonObject
            ?: throw NotesExportFormatException("notes export must be a JSON object")
        document.requireExactFields(
            expected = setOf("format", "format_version", "events", "revisions"),
            path = "export",
        )

        val format = document.requireString("format", "export")
        if (format != NOTES_EXPORT_FORMAT) {
            throw NotesExportFormatException(
                "unsupported notes export format '$format'",
            )
        }
        val formatVersion = document.requireString("format_version", "export")
        if (formatVersion != NOTES_EXPORT_FORMAT_VERSION) {
            throw NotesExportFormatException(
                "unsupported notes export format_version '$formatVersion'",
            )
        }

        val events = document.requireArray("events", "export")
            .elements
            .mapIndexed { index, value ->
                val path = "events[$index]"
                val pointer = value as? CanonicalJsonObject
                    ?: throw NotesExportFormatException("$path must be an object")
                pointer.requireExactFields(
                    expected = setOf("event_id", "current_revision_id"),
                    path = path,
                )
                NoteEventPointerSnapshot(
                    eventId = pointer.requireString("event_id", path),
                    currentRevisionId = pointer.requireString(
                        "current_revision_id",
                        path,
                    ),
                )
            }

        val revisions = document.requireArray("revisions", "export")
            .elements
            .mapIndexed { index, value ->
                val revision = value as? CanonicalJsonObject
                    ?: throw NotesExportFormatException(
                        "revisions[$index] must be an object",
                    )
                CanonicalNoteRevisionJson.fromDocument(revision)
            }

        return normalize(
            NotesExportSnapshot(
                events = events,
                revisions = revisions,
            ),
        )
    }

    private fun normalize(snapshot: NotesExportSnapshot): NotesExportSnapshot {
        val inspection = validator.inspect(snapshot)
        if (inspection.violations.isNotEmpty()) {
            throw NotesExportValidationException(inspection.violations)
        }
        return NotesExportSnapshot(
            events = snapshot.events.sortedBy(NoteEventPointerSnapshot::eventId),
            revisions = inspection.revisions
                .sortedWith(
                    compareBy<NoteRevisionInspection>(
                        NoteRevisionInspection::eventId,
                        NoteRevisionInspection::revisionId,
                    ),
                )
                .map(NoteRevisionInspection::raw),
        )
    }

    private fun CanonicalJsonObject.requireExactFields(
        expected: Set<String>,
        path: String,
    ) {
        val actual = properties.keys
        val missing = expected - actual
        val unexpected = actual - expected
        if (missing.isNotEmpty() || unexpected.isNotEmpty()) {
            throw NotesExportFormatException(
                buildString {
                    append(path)
                    append(" has an invalid field set")
                    if (missing.isNotEmpty()) {
                        append("; missing=")
                        append(missing.sorted())
                    }
                    if (unexpected.isNotEmpty()) {
                        append("; unexpected=")
                        append(unexpected.sorted())
                    }
                },
            )
        }
    }

    private fun CanonicalJsonObject.requireString(
        name: String,
        path: String,
    ): String =
        (properties[name] as? CanonicalJsonString)?.value
            ?: throw NotesExportFormatException("$path.$name must be a string")

    private fun CanonicalJsonObject.requireArray(
        name: String,
        path: String,
    ): CanonicalJsonArray =
        properties[name] as? CanonicalJsonArray
            ?: throw NotesExportFormatException("$path.$name must be an array")

    private companion object {
        const val MAX_EXPORT_BYTES = 64 * 1024 * 1024
    }
}
