package ru.andriyshkoy.lifeagent.data.local.serialization

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import ru.andriyshkoy.lifeagent.core.id.MutationIds
import ru.andriyshkoy.lifeagent.core.time.PointTimeResolver
import ru.andriyshkoy.lifeagent.notes.domain.CreateNoteCommand
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

class CanonicalNoteCodecTest {
    private val codec = CanonicalNoteCodec()

    @Test
    fun `canonical JSON recursively sorts object keys`() {
        val encoded = codec.canonical(
            buildJsonObject {
                put("z", 1)
                put("a", buildJsonObject {
                    put("y", 2)
                    put("b", 3)
                })
            },
        )

        assertEquals("""{"a":{"b":3,"y":2},"z":1}""", encoded.utf8)
        assertEquals(64, encoded.sha256.length)
    }

    @Test
    fun `same frozen command has stable fingerprint and content change differs`() {
        val command = createCommand("точный текст")

        assertEquals(
            codec.commandFingerprint(command),
            codec.commandFingerprint(command.copy()),
        )
        assertNotEquals(
            codec.commandFingerprint(command),
            codec.commandFingerprint(command.copy(text = "другой текст")),
        )
    }

    private fun createCommand(text: String) = CreateNoteCommand(
        ids = MutationIds(
            operationId = uuid(1),
            captureId = uuid(2),
            eventId = uuid(3),
            revisionId = uuid(4),
        ),
        text = text,
        effectiveTime = PointTimeResolver.resolveInstant(
            Instant.parse("2026-07-27T06:12:00Z"),
            ZoneId.of("Asia/Novosibirsk"),
        ),
        recordedAt = OffsetDateTime.parse("2026-07-27T13:12:00+07:00"),
    )

    private fun uuid(value: Int): UUID =
        UUID.fromString("00000000-0000-4000-8000-${value.toString().padStart(12, '0')}")
}
