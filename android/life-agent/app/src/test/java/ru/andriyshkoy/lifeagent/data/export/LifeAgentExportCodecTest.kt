package ru.andriyshkoy.lifeagent.data.export

import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LifeAgentExportCodecTest {
    private val codec = CanonicalLifeAgentExportCodec()

    @Test
    fun mixedPublicFixtureRoundTripsAndMatchesGoldenDigest() {
        val fixture = LifeAgentExportTestFixtures.publicBytes()
        val decoded = codec.decode(fixture)
        val canonical = codec.encode(decoded)

        assertEquals(listOf("note", "wellbeing"), decoded.revisions.map(::kind))
        assertEquals(1, decoded.catalogs.items.size)
        assertArrayEquals(canonical, codec.canonicalize(fixture))
        assertEquals(LifeAgentExportTestFixtures.expectedDigest(), sha256(canonical))
        assertFalse(canonical.toString(Charsets.UTF_8).endsWith("\n"))
    }

    @Test
    fun collectionOrderDoesNotChangeCanonicalExport() {
        val snapshot = LifeAgentExportTestFixtures.snapshot()
        val shuffled = snapshot.copy(
            catalogs = snapshot.catalogs.copy(
                items = snapshot.catalogs.items.reversed(),
                versions = snapshot.catalogs.versions.reversed(),
                heads = snapshot.catalogs.heads.reversed(),
            ),
            events = snapshot.events.reversed(),
            revisions = snapshot.revisions.reversed(),
        )

        assertArrayEquals(codec.encode(snapshot), codec.encode(shuffled))
    }

    @Test
    fun emptyExportIsValidAndDeterministic() {
        val encoded = codec.encode(
            LifeAgentExportSnapshot(
                catalogs = CatalogExportSnapshot.Empty,
                events = emptyList(),
                revisions = emptyList(),
            ),
        )

        assertEquals(
            """{"catalogs":{"heads":[],"items":[],"versions":[]},"events":[],"format":"life-agent","format_version":"1.0.0","revisions":[]}""",
            encoded.toString(Charsets.UTF_8),
        )
        assertArrayEquals(encoded, codec.canonicalize(encoded))
    }

    @Test
    fun strictDecoderRejectsUnknownEnvelopeFields() {
        val invalid =
            """{"catalogs":{"heads":[],"items":[],"versions":[]},"events":[],"format":"life-agent","format_version":"1.0.0","revisions":[],"unexpected":true}"""
                .toByteArray()

        assertThrows(LifeAgentExportFormatException::class.java) { codec.decode(invalid) }
    }

    @Test
    fun strictDecoderRejectsDuplicateObjectNames() {
        val invalid =
            """{"catalogs":{"heads":[],"items":[],"versions":[]},"events":[],"format":"life-agent","format":"life-agent","format_version":"1.0.0","revisions":[]}"""
                .toByteArray()

        assertThrows(LifeAgentExportFormatException::class.java) { codec.decode(invalid) }
    }

    @Test
    fun strictDecoderRejectsCatalogVersionOutsideLocalIntRange() {
        val invalid = LifeAgentExportTestFixtures.publicBytes()
            .toString(Charsets.UTF_8)
            .replace("\"version_no\": 1", "\"version_no\": 2147483648")
            .toByteArray(Charsets.UTF_8)

        val failure = assertThrows(LifeAgentExportFormatException::class.java) {
            codec.decode(invalid)
        }

        assertTrue(failure.message.orEmpty().contains("local integer range"))
    }

    private fun kind(revision: CanonicalLifeEventJson): String =
        (revision.document.properties.getValue("kind") as CanonicalJsonString).value

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
