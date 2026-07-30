package ru.andriyshkoy.lifeagent.notes.domain

import java.util.UUID

sealed class NoteMutationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class InvalidNoteTextException(
    message: String,
) : NoteMutationException(message)

class NoteNotFoundException(
    eventId: UUID,
) : NoteMutationException("Note event $eventId does not exist")

class StaleNoteRevisionException(
    eventId: UUID,
    expected: UUID,
    actual: UUID,
) : NoteMutationException(
    "Note event $eventId changed: expected revision $expected, current revision is $actual",
)

class RetractedNoteCorrectionException(
    eventId: UUID,
) : NoteMutationException("Retracted note event $eventId cannot be corrected")

class IdempotencyConflictException(
    operationId: UUID,
) : NoteMutationException(
    "Operation $operationId was already committed with different immutable content",
)

class LocalIdentityCollisionException(
    message: String,
    cause: Throwable? = null,
) : NoteMutationException(message, cause)

class CorruptLocalNoteException(
    message: String,
    cause: Throwable? = null,
) : NoteMutationException(message, cause)

object NoteTextPolicy {
    const val MAX_CODE_POINTS = 50_000

    fun validate(text: String) {
        if (text.isBlank()) {
            throw InvalidNoteTextException("Note text must contain a visible character")
        }
        val codePoints = text.codePointCount(0, text.length)
        if (codePoints > MAX_CODE_POINTS) {
            throw InvalidNoteTextException(
                "Note text exceeds $MAX_CODE_POINTS Unicode code points",
            )
        }
    }
}
