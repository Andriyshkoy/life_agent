package ru.andriyshkoy.lifeagent.data.export

const val NOTES_EXPORT_FORMAT = "life-agent-notes"
const val NOTES_EXPORT_FORMAT_VERSION = "1.0.0"

data class NoteEventPointerSnapshot(
    val eventId: String,
    val currentRevisionId: String,
)

/**
 * Raw canonical event revision used by the export boundary.
 *
 * Persistence can hand the exporter its already-materialized canonical JSON
 * without sharing a second, export-only copy of the large life-event DTO.
 */
class CanonicalNoteRevisionJson private constructor(
    internal val document: CanonicalJsonObject,
    private val canonicalBytes: ByteArray,
) {
    fun toByteArray(): ByteArray = canonicalBytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is CanonicalNoteRevisionJson &&
            canonicalBytes.contentEquals(other.canonicalBytes)

    override fun hashCode(): Int = canonicalBytes.contentHashCode()

    override fun toString(): String =
        "CanonicalNoteRevisionJson(${canonicalBytes.size} bytes)"

    companion object {
        fun fromJson(bytes: ByteArray): CanonicalNoteRevisionJson {
            val document = CanonicalJson.parse(bytes) as? CanonicalJsonObject
                ?: throw NotesExportFormatException(
                    "canonical event revision must be a JSON object",
                )
            return fromDocument(document)
        }

        internal fun fromDocument(
            document: CanonicalJsonObject,
        ): CanonicalNoteRevisionJson =
            CanonicalNoteRevisionJson(
                document = document,
                canonicalBytes = CanonicalJson.encode(document),
            )
    }
}

data class NotesExportSnapshot(
    val events: List<NoteEventPointerSnapshot>,
    val revisions: List<CanonicalNoteRevisionJson>,
)

open class NotesExportException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

class NotesExportFormatException(
    message: String,
    cause: Throwable? = null,
) : NotesExportException(message, cause)

class NotesExportValidationException(
    val violations: List<String>,
) : NotesExportException(
    violations.joinToString(
        prefix = "Invalid notes export: ",
        separator = "; ",
    ),
)
