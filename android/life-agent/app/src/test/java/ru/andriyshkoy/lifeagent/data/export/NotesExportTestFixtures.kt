package ru.andriyshkoy.lifeagent.data.export

internal object NotesExportTestFixtures {
    private const val EVENT_ONE = "20000000-0000-4000-8000-000000000001"
    private const val EVENT_TWO = "20000000-0000-4000-8000-000000000002"
    private const val REVISION_ONE = "30000000-0000-4000-8000-000000000001"
    private const val REVISION_TWO = "30000000-0000-4000-8000-000000000002"
    private const val REVISION_THREE = "30000000-0000-4000-8000-000000000003"
    private const val REVISION_FOUR = "30000000-0000-4000-8000-000000000004"

    fun snapshot(): NotesExportSnapshot =
        NotesExportSnapshot(
            events = listOf(
                NoteEventPointerSnapshot(EVENT_ONE, REVISION_TWO),
                NoteEventPointerSnapshot(EVENT_TWO, REVISION_FOUR),
            ),
            revisions = listOf(
                revision(
                    eventId = EVENT_ONE,
                    revisionId = REVISION_ONE,
                    revisionNo = 1,
                    captureSuffix = "1",
                    status = "active",
                    parentRevisionId = null,
                ),
                revision(
                    eventId = EVENT_ONE,
                    revisionId = REVISION_TWO,
                    revisionNo = 2,
                    captureSuffix = "2",
                    status = "active",
                    parentRevisionId = REVISION_ONE,
                ),
                revision(
                    eventId = EVENT_TWO,
                    revisionId = REVISION_THREE,
                    revisionNo = 1,
                    captureSuffix = "3",
                    status = "active",
                    parentRevisionId = null,
                ),
                revision(
                    eventId = EVENT_TWO,
                    revisionId = REVISION_FOUR,
                    revisionNo = 2,
                    captureSuffix = "4",
                    status = "retracted",
                    parentRevisionId = REVISION_THREE,
                ),
            ),
        )

    private fun revision(
        eventId: String,
        revisionId: String,
        revisionNo: Int,
        captureSuffix: String,
        status: String,
        parentRevisionId: String?,
    ): CanonicalNoteRevisionJson {
        val parents = if (parentRevisionId == null) {
            "[]"
        } else {
            """
            [
              {
                "relation": "supersedes",
                "revision_id": "$parentRevisionId"
              }
            ]
            """.trimIndent()
        }
        val correctionReason = if (parentRevisionId == null) {
            "null"
        } else {
            "\"synthetic correction\""
        }
        val document =
            """
            {
              "schema_version": "4.0.0",
              "persistence_state": "local_pending",
              "identity": {
                "installation_id": "10000000-0000-4000-8000-000000000001",
                "local_owner_id": "10000000-0000-4000-8000-000000000002",
                "device_id": null
              },
              "event_id": "$eventId",
              "revision_id": "$revisionId",
              "revision_no": $revisionNo,
              "kind": "note",
              "assertion_status": "observed",
              "lifecycle": null,
              "record_status": "$status",
              "verification_status": "user_confirmed",
              "source": {
                "capture_id": "40000000-0000-4000-8000-00000000000$captureSuffix",
                "operation_id": "50000000-0000-4000-8000-00000000000$captureSuffix",
                "channel": "android_manual",
                "source_record_id": null,
                "source_record_version": null,
                "source_modified_at": null,
                "recorded_at": "2026-01-10T10:00:00Z",
                "origin": {
                  "provider": null,
                  "app": "Life Agent fixture",
                  "device": "fixture-device",
                  "user_entered": true
                },
                "collector": {
                  "name": "life-agent-android",
                  "version": "0.1.0"
                }
              },
              "time": {
                "effective_start_utc": "2026-01-10T10:00:00Z",
                "effective_end_utc": null,
                "original_local_start": "2026-01-10T10:00:00",
                "original_local_end": null,
                "timezone_id": "Etc/UTC",
                "start_offset_seconds": 0,
                "end_offset_seconds": null,
                "temporal_precision": "minute",
                "local_date": "2026-01-10",
                "source_expression": null
              },
              "payload": {
                "text": "Синтетическая заметка $captureSuffix 🌿"
              },
              "evidence": [
                {
                  "capture_ref": "#/source/capture_id",
                  "field_path": "/payload/text",
                  "artifact_id": null,
                  "locator": "android_form:/note/text",
                  "excerpt": null,
                  "human_confirmed": true
                }
              ],
              "quality_flags": [],
              "revision": {
                "created_at": "2026-01-10T10:00:00Z",
                "content_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "actor": "user",
                "correction_reason": $correctionReason,
                "parents": $parents
              },
              "server": {
                "received_at": null,
                "server_sequence": null
              }
            }
            """.trimIndent()
        val revisionDocument = CanonicalNoteRevisionJson
            .fromJson(document.toByteArray())
            .document
        val contentHash = checkNotNull(
            NoteRevisionContentHash.expectedForLinearRevision(revisionDocument),
        )
        val revisionMetadata =
            revisionDocument.properties.getValue("revision") as CanonicalJsonObject
        val updatedRevisionMetadata = revisionMetadata.copy(
            properties = revisionMetadata.properties +
                ("content_sha256" to CanonicalJsonString(contentHash)),
        )
        return CanonicalNoteRevisionJson.fromDocument(
            revisionDocument.copy(
                properties = revisionDocument.properties +
                    ("revision" to updatedRevisionMetadata),
            ),
        )
    }
}
