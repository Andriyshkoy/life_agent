package ru.andriyshkoy.lifeagent.data.export

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class NotesExportCodecTest {
    private val codec = CanonicalNotesExportCodec()

    @Test
    fun fixtureRoundTripsByteForByte() {
        val snapshot = NotesExportTestFixtures.snapshot()

        val canonical = codec.encode(snapshot)
        val decoded = codec.decode(canonical)

        assertEquals(2, snapshot.events.size)
        assertEquals(4, snapshot.revisions.size)
        assertEquals(snapshot.events, decoded.events)
        assertEquals(snapshot.revisions, decoded.revisions)
        assertArrayEquals(canonical, codec.encode(decoded))
        assertFalse(canonical.toString(Charsets.UTF_8).endsWith("\n"))
    }

    @Test
    fun publicCanonicalFixturePassesProductionCodecAndGoldenDigest() {
        val workingDirectory = checkNotNull(System.getProperty("user.dir"))
        val repositoryRoot = generateSequence(File(workingDirectory)) {
            it.parentFile
        }.firstOrNull {
            File(it, "schemas/notes-export.schema.json").isFile
        }
        val root = checkNotNull(repositoryRoot) {
            "public notes export fixture is unavailable; run the Android tests " +
                "from a full Life Agent repository checkout"
        }
        val fixture = File(root, "examples/notes-export.json").readBytes()
        val expectedDigest = File(
            root,
            "examples/notes-export.canonical.sha256",
        ).readText(Charsets.US_ASCII).trim().substringBefore(' ')

        val decoded = codec.decode(fixture)
        val canonical = codec.encode(decoded)

        assertArrayEquals(canonical, codec.canonicalize(fixture))
        assertEquals(
            expectedDigest,
            MessageDigest.getInstance("SHA-256")
                .digest(canonical)
                .joinToString(separator = "") { byte -> "%02x".format(byte) },
        )
    }

    @Test
    fun arrayOrderDoesNotChangeCanonicalExport() {
        val snapshot = NotesExportTestFixtures.snapshot()
        val shuffled = snapshot.copy(
            events = snapshot.events.reversed(),
            revisions = snapshot.revisions.reversed(),
        )

        assertArrayEquals(codec.encode(snapshot), codec.encode(shuffled))
    }

    @Test
    fun randomRevisionIdsCannotOverrideRevisionNumberOrdering() {
        val randomIds = buildSet {
            while (size < 2) {
                add(UUID.randomUUID().toString())
            }
        }.sorted()
        val snapshot = NotesExportTestFixtures.twoRevisionSnapshot(
            firstRevisionId = randomIds.last(),
            secondRevisionId = randomIds.first(),
        )

        val decoded = codec.decode(codec.encode(snapshot))

        assertEquals(
            listOf(1, 2),
            decoded.revisions.map { revision ->
                (revision.document.properties.getValue("revision_no") as CanonicalJsonInteger)
                    .value
                    .toInt()
            },
        )
    }

    @Test
    fun emptyExportIsValidAndDeterministic() {
        val encoded = codec.encode(
            NotesExportSnapshot(
                events = emptyList(),
                revisions = emptyList(),
            ),
        )

        assertEquals(
            """{"events":[],"format":"life-agent-notes","format_version":"2.0.0","revisions":[]}""",
            encoded.toString(Charsets.UTF_8),
        )
        assertArrayEquals(encoded, codec.canonicalize(encoded))
    }

    @Test
    fun strictDecoderRejectsUnknownEnvelopeFields() {
        val invalid =
            """
            {
              "format":"life-agent-notes",
              "format_version":"2.0.0",
              "events":[],
              "revisions":[],
              "unexpected":true
            }
            """.trimIndent().toByteArray()

        assertThrows(NotesExportFormatException::class.java) {
            codec.decode(invalid)
        }
    }

    @Test
    fun strictDecoderRejectsDuplicateObjectNames() {
        val invalid =
            """
            {
              "format":"life-agent-notes",
              "format":"life-agent-notes",
              "format_version":"2.0.0",
              "events":[],
              "revisions":[]
            }
            """.trimIndent().toByteArray()

        assertThrows(NotesExportFormatException::class.java) {
            codec.decode(invalid)
        }
    }

    @Test
    fun strictDecoderRejectsFloatingPointNumbers() {
        val invalid =
            """
            {
              "format":"life-agent-notes",
              "format_version":"2.0.0",
              "events":[],
              "revisions":[],
              "value":1.5
            }
            """.trimIndent().toByteArray()

        assertThrows(NotesExportFormatException::class.java) {
            codec.decode(invalid)
        }
    }
}
