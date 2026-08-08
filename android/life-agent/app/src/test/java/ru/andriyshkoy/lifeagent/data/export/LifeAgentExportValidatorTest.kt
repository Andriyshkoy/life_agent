package ru.andriyshkoy.lifeagent.data.export

import java.math.BigInteger
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class LifeAgentExportValidatorTest {
    private val codec = CanonicalLifeAgentExportCodec()

    @Test
    fun mixedFixtureIsValid() {
        LifeAgentExportValidator().validate(fixture())
    }

    @Test
    fun rejectsCatalogPayloadDigestMismatch() {
        val snapshot = fixture()
        val invalid = snapshot.copy(
            catalogs = snapshot.catalogs.copy(
                versions = snapshot.catalogs.versions.mapIndexed { index, version ->
                    if (index == 0) version.copy(contentSha256 = "0".repeat(64)) else version
                },
            ),
        )

        assertInvalidContains(invalid, "canonical catalog payload")
    }

    @Test
    fun rejectsRepeatedWellbeingDimension() {
        val snapshot = fixture()
        val wellbeing = snapshot.revisions.single { it.kind() == "wellbeing" }
        val payload = wellbeing.objectField("payload")
        val values = payload.arrayField("values")
        val invalidRevision = wellbeing.replaceRootField(
            "payload",
            CanonicalJsonObject(
                payload.properties + ("values" to CanonicalJsonArray(values.elements + values.elements)),
            ),
        )

        assertInvalidContains(snapshot.replaceRevision(wellbeing, invalidRevision), "repeats dimension_id")
    }

    @Test
    fun rejectsWellbeingOptionOutsideCatalogVersion() {
        val snapshot = fixture()
        val wellbeing = snapshot.revisions.single { it.kind() == "wellbeing" }
        val payload = wellbeing.objectField("payload")
        val values = payload.arrayField("values")
        val first = values.elements.single() as CanonicalJsonObject
        val changed = CanonicalJsonObject(
            first.properties +
                ("option_id" to CanonicalJsonString("62000000-0000-4000-8000-000000000099")),
        )
        val invalidRevision = wellbeing.replaceRootField(
            "payload",
            CanonicalJsonObject(
                payload.properties + ("values" to CanonicalJsonArray(listOf(changed))),
            ),
        )

        assertInvalidContains(
            snapshot.replaceRevision(wellbeing, invalidRevision),
            "not a member of the dimension version",
        )
    }

    @Test
    fun rejectsWellbeingDimensionVersionOutsideLocalIntRange() {
        val snapshot = fixture()
        val wellbeing = snapshot.revisions.single { it.kind() == "wellbeing" }
        val payload = wellbeing.objectField("payload")
        val values = payload.arrayField("values")
        val first = values.elements.single() as CanonicalJsonObject
        val changed = CanonicalJsonObject(
            first.properties +
                (
                    "dimension_version" to
                        CanonicalJsonInteger(BigInteger.valueOf(Int.MAX_VALUE.toLong() + 1L))
                    ),
        )
        val invalidRevision = wellbeing.replaceRootField(
            "payload",
            CanonicalJsonObject(
                payload.properties + ("values" to CanonicalJsonArray(listOf(changed))),
            ),
        )

        assertInvalidContains(
            snapshot.replaceRevision(wellbeing, invalidRevision),
            "version exceeds the local integer range",
        )
    }

    @Test
    fun rejectsUnresolvedCurrentRevisionPointer() {
        val snapshot = fixture()
        val invalid = snapshot.copy(
            events = snapshot.events.mapIndexed { index, pointer ->
                if (index == 0) {
                    pointer.copy(currentRevisionId = "30000000-0000-4000-8000-000000000099")
                } else {
                    pointer
                }
            },
        )

        assertInvalidContains(invalid, "current_revision_id does not resolve")
    }

    @Test
    fun rejectsGlobalIdentityCollision() {
        val snapshot = fixture()
        val eventId = snapshot.events.first().eventId
        val item = snapshot.catalogs.items.single()
        val invalid = snapshot.copy(
            catalogs = snapshot.catalogs.copy(items = listOf(item.copy(catalogItemId = eventId))),
        )

        assertInvalidContains(invalid, "global IDs collide")
    }

    private fun fixture(): LifeAgentExportSnapshot = LifeAgentExportTestFixtures.snapshot()

    private fun assertInvalidContains(snapshot: LifeAgentExportSnapshot, expected: String) {
        val failure = assertThrows(LifeAgentExportValidationException::class.java) {
            codec.encode(snapshot)
        }
        assertTrue(
            "Expected a violation containing '$expected', got ${failure.violations}",
            failure.violations.any { it.contains(expected) },
        )
    }

    private fun CanonicalLifeEventJson.kind(): String =
        (document.properties.getValue("kind") as CanonicalJsonString).value

    private fun CanonicalLifeEventJson.objectField(name: String): CanonicalJsonObject =
        document.properties.getValue(name) as CanonicalJsonObject

    private fun CanonicalJsonObject.arrayField(name: String): CanonicalJsonArray =
        properties.getValue(name) as CanonicalJsonArray

    private fun CanonicalLifeEventJson.replaceRootField(
        name: String,
        value: CanonicalJsonValue,
    ): CanonicalLifeEventJson = CanonicalLifeEventJson.fromDocument(
        CanonicalJsonObject(document.properties + (name to value)),
    )

    private fun LifeAgentExportSnapshot.replaceRevision(
        current: CanonicalLifeEventJson,
        replacement: CanonicalLifeEventJson,
    ): LifeAgentExportSnapshot = copy(
        revisions = revisions.map { if (it === current) replacement else it },
    )
}
