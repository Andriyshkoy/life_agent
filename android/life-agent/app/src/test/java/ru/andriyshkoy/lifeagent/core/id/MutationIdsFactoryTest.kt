package ru.andriyshkoy.lifeagent.core.id

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.ArrayDeque
import java.util.UUID

class MutationIdsFactoryTest {
    @Test
    fun `new event allocates four stable distinct identities`() {
        val values = ArrayDeque(
            listOf(
                uuid(1),
                uuid(2),
                uuid(3),
                uuid(4),
            ),
        )
        val ids = MutationIdsFactory { values.removeFirst() }.forNewEvent()

        assertEquals(uuid(1), ids.operationId)
        assertEquals(uuid(2), ids.captureId)
        assertEquals(uuid(3), ids.eventId)
        assertEquals(uuid(4), ids.revisionId)
    }

    @Test
    fun `existing event keeps event identity and rotates mutation identities`() {
        val values = ArrayDeque(listOf(uuid(5), uuid(6), uuid(7)))
        val ids = MutationIdsFactory { values.removeFirst() }
            .forExistingEvent(uuid(99))

        assertEquals(uuid(5), ids.operationId)
        assertEquals(uuid(6), ids.captureId)
        assertEquals(uuid(99), ids.eventId)
        assertEquals(uuid(7), ids.revisionId)
    }

    private fun uuid(value: Int): UUID =
        UUID.fromString("00000000-0000-4000-8000-${value.toString().padStart(12, '0')}")
}
