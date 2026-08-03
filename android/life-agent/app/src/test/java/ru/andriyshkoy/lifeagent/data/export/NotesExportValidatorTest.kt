package ru.andriyshkoy.lifeagent.data.export

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesExportValidatorTest {
    private val codec = CanonicalNotesExportCodec()

    @Test
    fun duplicateEventPointerIsRejected() {
        val valid = fixture()
        val duplicate = valid.copy(events = valid.events + valid.events.first())

        assertInvalid(duplicate)
    }

    @Test
    fun unresolvedCurrentRevisionIsRejected() {
        val valid = fixture()
        val invalid = valid.copy(
            events = valid.events.toMutableList().also { events ->
                events[0] = events[0].copy(
                    currentRevisionId = "30000000-0000-4000-8000-000000000099",
                )
            },
        )

        assertInvalid(invalid)
    }

    @Test
    fun orphanRevisionIsRejected() {
        val valid = fixture()
        val orphan = valid.revisions.first().withEventId(
            "20000000-0000-4000-8000-000000000099",
        )
        val invalid = valid.copy(
            revisions = listOf(orphan) + valid.revisions.drop(1),
        )

        assertInvalid(invalid)
    }

    @Test
    fun duplicateOperationIdIsRejected() {
        val valid = fixture()
        val firstOperationId = valid.revisions.first().operationId()
        val duplicate = valid.revisions[1].withOperationId(firstOperationId)
        val invalid = valid.copy(
            revisions = listOf(valid.revisions.first(), duplicate) +
                valid.revisions.drop(2),
        )

        assertInvalid(invalid)
    }

    @Test
    fun unresolvedParentIsRejected() {
        val valid = fixture()
        val invalidRevision = valid.revisions[1].withParents(
            "30000000-0000-4000-8000-000000000099",
        )
        val invalid = valid.copy(
            revisions = listOf(valid.revisions.first(), invalidRevision) +
                valid.revisions.drop(2),
        )

        assertInvalid(invalid)
    }

    @Test
    fun ancestryCycleIsRejected() {
        val valid = fixture()
        val firstRevisionId = valid.revisions[0].revisionId()
        val secondRevisionId = valid.revisions[1].revisionId()
        val first = valid.revisions[0].withParents(secondRevisionId)
        val second = valid.revisions[1].withParents(firstRevisionId)
        val invalid = valid.copy(
            revisions = listOf(first, second) + valid.revisions.drop(2),
        )

        assertInvalid(invalid)
    }

    @Test
    fun unknownNestedPayloadFieldIsRejected() {
        val valid = fixture()
        val payload = valid.revisions.first().objectField("payload")
        val changed = valid.revisions.first()
            .replaceRootField(
                "payload",
                payload.copy(
                    properties = payload.properties +
                        ("unexpected" to CanonicalJsonBoolean(true)),
                ),
            )
            .withRecomputedContentHash()

        assertViolation(
            valid.copy(revisions = listOf(changed) + valid.revisions.drop(1)),
            "payload has unknown fields",
        )
    }

    @Test
    fun invalidTimezoneIsRejectedEvenWithMatchingContentHash() {
        val valid = fixture()
        val time = valid.revisions.first().objectField("time")
        val changed = valid.revisions.first()
            .replaceRootField(
                "time",
                time.copy(
                    properties = time.properties +
                        ("timezone_id" to CanonicalJsonString("Mars/Olympus_Mons")),
                ),
            )
            .withRecomputedContentHash()

        assertViolation(
            valid.copy(revisions = listOf(changed) + valid.revisions.drop(1)),
            "timezone_id",
        )
    }

    @Test
    fun utcLocalOffsetMismatchIsRejectedEvenWithMatchingContentHash() {
        val valid = fixture()
        val time = valid.revisions.first().objectField("time")
        val changed = valid.revisions.first()
            .replaceRootField(
                "time",
                time.copy(
                    properties = time.properties +
                        (
                            "effective_start_utc" to
                                CanonicalJsonString("2026-01-10T10:01:00Z")
                            ),
                ),
            )
            .withRecomputedContentHash()

        assertViolation(
            valid.copy(revisions = listOf(changed) + valid.revisions.drop(1)),
            "do not identify one instant",
        )
    }

    @Test
    fun localPendingServerMetadataIsRejected() {
        val valid = fixture()
        val server = valid.revisions.first().objectField("server")
        val changed = valid.revisions.first().replaceRootField(
            "server",
            server.copy(
                properties = server.properties +
                    (
                        "received_at" to
                            CanonicalJsonString("2026-01-10T10:02:00Z")
                        ) +
                    ("server_sequence" to CanonicalJsonInteger(java.math.BigInteger.ONE)),
            ),
        )

        assertViolation(
            valid.copy(revisions = listOf(changed) + valid.revisions.drop(1)),
            "server must be empty for local_pending",
        )
    }

    @Test
    fun unresolvedEvidencePointerIsRejected() {
        val valid = fixture()
        val evidence = valid.revisions.first()
            .arrayField("evidence")
        val item = evidence.elements.single() as CanonicalJsonObject
        val changed = valid.revisions.first().replaceRootField(
            "evidence",
            CanonicalJsonArray(
                listOf(
                    item.copy(
                        properties = item.properties +
                            (
                                "field_path" to
                                    CanonicalJsonString("/payload/missing")
                                ),
                    ),
                ),
            ),
        )

        assertViolation(
            valid.copy(revisions = listOf(changed) + valid.revisions.drop(1)),
            "field_path does not resolve",
        )
    }

    @Test
    fun retractionWithoutReasonIsRejected() {
        val valid = fixture()
        val tombstoneIndex = valid.revisions.lastIndex
        val tombstone = valid.revisions[tombstoneIndex]
        val revision = tombstone.objectField("revision")
        val changed = tombstone
            .replaceRootField(
                "revision",
                revision.copy(
                    properties = revision.properties +
                        ("correction_reason" to CanonicalJsonNull),
                ),
            )
            .withRecomputedContentHash()
        val revisions = valid.revisions.toMutableList().also {
            it[tombstoneIndex] = changed
        }

        assertViolation(
            valid.copy(revisions = revisions),
            "correction_reason is required for a retraction",
        )
    }

    @Test
    fun contentHashMismatchIsRejected() {
        val valid = fixture()
        val revision = valid.revisions.first().objectField("revision")
        val changed = valid.revisions.first().replaceRootField(
            "revision",
            revision.copy(
                properties = revision.properties +
                    ("content_sha256" to CanonicalJsonString("0".repeat(64))),
            ),
        )

        assertViolation(
            valid.copy(revisions = listOf(changed) + valid.revisions.drop(1)),
            "canonical immutable revision content",
        )
    }

    private fun fixture(): NotesExportSnapshot =
        NotesExportTestFixtures.snapshot()

    private fun assertInvalid(snapshot: NotesExportSnapshot) {
        assertThrows(NotesExportValidationException::class.java) {
            codec.encode(snapshot)
        }
    }

    private fun assertViolation(
        snapshot: NotesExportSnapshot,
        expectedText: String,
    ) {
        val failure = assertThrows(NotesExportValidationException::class.java) {
            codec.encode(snapshot)
        }
        assertTrue(
            "Expected violation containing '$expectedText': ${failure.violations}",
            failure.violations.any { expectedText in it },
        )
    }

    private fun CanonicalNoteRevisionJson.revisionId(): String =
        (document.properties.getValue("revision_id") as CanonicalJsonString).value

    private fun CanonicalNoteRevisionJson.operationId(): String {
        val source = document.properties.getValue("source") as CanonicalJsonObject
        return (source.properties.getValue("operation_id") as CanonicalJsonString).value
    }

    private fun CanonicalNoteRevisionJson.withEventId(
        eventId: String,
    ): CanonicalNoteRevisionJson =
        replaceRootField("event_id", CanonicalJsonString(eventId))

    private fun CanonicalNoteRevisionJson.withOperationId(
        operationId: String,
    ): CanonicalNoteRevisionJson {
        val source = document.properties.getValue("source") as CanonicalJsonObject
        val changedSource = source.copy(
            properties = source.properties +
                ("operation_id" to CanonicalJsonString(operationId)),
        )
        return replaceRootField("source", changedSource)
    }

    private fun CanonicalNoteRevisionJson.withParents(
        vararg revisionIds: String,
    ): CanonicalNoteRevisionJson {
        val revision = document.properties.getValue("revision") as CanonicalJsonObject
        val parents = CanonicalJsonArray(
            revisionIds.map { revisionId ->
                CanonicalJsonObject(
                    mapOf(
                        "revision_id" to CanonicalJsonString(revisionId),
                        "relation" to CanonicalJsonString("supersedes"),
                    ),
                )
            },
        )
        val changedRevision = revision.copy(
            properties = revision.properties + ("parents" to parents),
        )
        return replaceRootField("revision", changedRevision)
    }

    private fun CanonicalNoteRevisionJson.objectField(
        name: String,
    ): CanonicalJsonObject =
        document.properties.getValue(name) as CanonicalJsonObject

    private fun CanonicalNoteRevisionJson.arrayField(
        name: String,
    ): CanonicalJsonArray =
        document.properties.getValue(name) as CanonicalJsonArray

    private fun CanonicalNoteRevisionJson.withRecomputedContentHash(): CanonicalNoteRevisionJson {
        val hash = checkNotNull(
            NoteRevisionContentHash.expectedForLinearRevision(document),
        )
        val revision = objectField("revision")
        return replaceRootField(
            "revision",
            revision.copy(
                properties = revision.properties +
                    ("content_sha256" to CanonicalJsonString(hash)),
            ),
        )
    }

    private fun CanonicalNoteRevisionJson.replaceRootField(
        name: String,
        value: CanonicalJsonValue,
    ): CanonicalNoteRevisionJson =
        CanonicalNoteRevisionJson.fromDocument(
            document.copy(properties = document.properties + (name to value)),
        )
}
