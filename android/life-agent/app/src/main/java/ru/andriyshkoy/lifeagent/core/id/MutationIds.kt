package ru.andriyshkoy.lifeagent.core.id

import java.util.UUID

/**
 * Stable identities for one user-visible mutation.
 *
 * A caller must create this value once and retain it across recomposition,
 * retries, and process-state restoration. Reusing only [operationId] while
 * changing another ID is treated as an idempotency collision.
 */
data class MutationIds(
    val operationId: UUID,
    val captureId: UUID,
    val eventId: UUID,
    val revisionId: UUID,
)

fun interface UuidGenerator {
    fun next(): UUID
}

object RandomUuidGenerator : UuidGenerator {
    override fun next(): UUID = UUID.randomUUID()
}

class MutationIdsFactory(
    private val uuidGenerator: UuidGenerator = RandomUuidGenerator,
) {
    fun forNewEvent(): MutationIds = MutationIds(
        operationId = uuidGenerator.next(),
        captureId = uuidGenerator.next(),
        eventId = uuidGenerator.next(),
        revisionId = uuidGenerator.next(),
    )

    fun forExistingEvent(eventId: UUID): MutationIds = MutationIds(
        operationId = uuidGenerator.next(),
        captureId = uuidGenerator.next(),
        eventId = eventId,
        revisionId = uuidGenerator.next(),
    )
}
