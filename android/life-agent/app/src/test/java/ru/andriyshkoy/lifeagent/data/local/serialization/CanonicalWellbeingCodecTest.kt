package ru.andriyshkoy.lifeagent.data.local.serialization

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import ru.andriyshkoy.lifeagent.core.id.MutationIds
import ru.andriyshkoy.lifeagent.core.time.PointTimeResolver
import ru.andriyshkoy.lifeagent.wellbeing.domain.CreateWellbeingCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingOption
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingPayload
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingValueSnapshot

class CanonicalWellbeingCodecTest {
    private val codec = CanonicalWellbeingCodec()

    @Test
    fun `payload round trip preserves explicit order and normalized comment`() {
        val payload = WellbeingPayload(
            values = listOf(value(2, 20), value(1, 10)),
            comment = "контекст",
        )
        val encoded = codec.encodeCaptureContent(payload)
        val payloadBytes = codec.encodeRevision(
            ids = ids(),
            revisionNo = 1,
            payload = payload,
            status = ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingRecordStatus.ACTIVE,
            effectiveTime = effectiveTime(),
            recordedAt = recordedAt(),
            correctionReason = null,
            parentRevisionId = null,
        ).payload

        assertEquals(payload, codec.decodePayload(payloadBytes.bytes))
        assertEquals(
            """{"kind":"structured","payload":{"comment":"контекст","values":[{"dimension_id":"00000000-0000-4000-8000-000000000002","dimension_label_snapshot":"Состояние 2","dimension_version":1,"option_id":"00000000-0000-4000-8000-000000000020","option_label_snapshot":"Вариант 20","option_sort_order_snapshot":20,"option_version":1},{"dimension_id":"00000000-0000-4000-8000-000000000001","dimension_label_snapshot":"Состояние 1","dimension_version":1,"option_id":"00000000-0000-4000-8000-000000000010","option_label_snapshot":"Вариант 10","option_sort_order_snapshot":10,"option_version":1}]},"record_type":"wellbeing"}""",
            encoded.utf8,
        )
    }

    @Test
    fun `fingerprint uses normalized semantic content`() {
        val command = command(" контекст ")

        assertEquals(
            codec.commandFingerprint(command),
            codec.commandFingerprint(command.copy(comment = "контекст")),
        )
        assertNotEquals(
            codec.commandFingerprint(command),
            codec.commandFingerprint(command.copy(values = listOf(value(2, 20)))),
        )
    }

    @Test
    fun `decoder rejects noncanonical and repeated dimension payloads`() {
        val repeated = """{"comment":null,"values":[{"dimension_id":"${uuid(1)}","dimension_label_snapshot":"Состояние","dimension_version":1,"option_id":"${uuid(2)}","option_label_snapshot":"Первый","option_sort_order_snapshot":1,"option_version":1},{"dimension_id":"${uuid(1)}","dimension_label_snapshot":"Состояние","dimension_version":1,"option_id":"${uuid(3)}","option_label_snapshot":"Второй","option_sort_order_snapshot":2,"option_version":1}]}"""
        assertThrows(IllegalArgumentException::class.java) {
            codec.decodePayload("{ \"comment\":null,\"values\":[]}".toByteArray())
        }
        assertThrows(ru.andriyshkoy.lifeagent.wellbeing.domain.InvalidWellbeingException::class.java) {
            codec.decodePayload(repeated.toByteArray())
        }
    }

    @Test
    fun `catalog aggregate round trip retains option identities and versions`() {
        val catalogCodec = CanonicalWellbeingCatalogCodec(codec)
        val options = listOf(
            WellbeingOption(uuid(2), 1, "Низко", 10, true),
            WellbeingOption(uuid(3), 2, "Высоко", 20, false),
        )
        val encoded = catalogCodec.encodePayload("Энергия", 30, true, options)
        val decoded = catalogCodec.decodeDimension(uuid(1), uuid(4), 3, encoded.bytes)

        assertEquals("1.0.0", CanonicalWellbeingCatalogCodec.SCHEMA_VERSION)
        assertEquals(3, decoded.version)
        assertEquals(options, decoded.options)
    }

    @Test
    fun `catalog decoder rejects canonical but policy invalid stored labels`() {
        val catalogCodec = CanonicalWellbeingCatalogCodec(codec)
        val encoded = catalogCodec.encodePayload(
            label = " Энергия ",
            sortOrder = 30,
            active = true,
            options = listOf(WellbeingOption(uuid(2), 1, "Низко", 10, true)),
        )

        assertThrows(IllegalArgumentException::class.java) {
            catalogCodec.decodeDimension(uuid(1), uuid(4), 1, encoded.bytes)
        }
    }

    private fun command(comment: String?) = CreateWellbeingCommand(
        ids = ids(),
        values = listOf(value(1, 10)),
        comment = comment,
        effectiveTime = effectiveTime(),
        recordedAt = recordedAt(),
    )

    private fun value(dimension: Int, option: Int) = WellbeingValueSnapshot(
        dimensionId = uuid(dimension),
        dimensionVersion = 1,
        dimensionLabel = "Состояние $dimension",
        optionId = uuid(option),
        optionVersion = 1,
        optionLabel = "Вариант $option",
        optionSortOrder = option,
    )

    private fun ids() = MutationIds(uuid(101), uuid(102), uuid(103), uuid(104))

    private fun effectiveTime() = PointTimeResolver.resolveInstant(
        Instant.parse("2026-07-27T06:12:00Z"),
        ZoneId.of("Asia/Novosibirsk"),
    )

    private fun recordedAt(): OffsetDateTime =
        OffsetDateTime.parse("2026-07-27T13:12:00+07:00")

    private fun uuid(value: Int): UUID =
        UUID.fromString("00000000-0000-4000-8000-${value.toString().padStart(12, '0')}")
}
