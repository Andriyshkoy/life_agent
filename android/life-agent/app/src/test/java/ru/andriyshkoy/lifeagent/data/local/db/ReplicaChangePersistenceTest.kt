package ru.andriyshkoy.lifeagent.data.local.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import ru.andriyshkoy.lifeagent.data.local.serialization.CanonicalNoteCodec
import java.nio.charset.StandardCharsets

class ReplicaChangePersistenceTest {
    private val codec = ReplicaChangeCodec()
    private val canonical = CanonicalNoteCodec()
    private val json = Json

    @Test
    fun `decoder preserves historical submitting device independent of receiver`() {
        val change = change(sequence = 1)

        val decoded = codec.decode(change)

        assertEquals(SUBMITTING_DEVICE_ID, decoded.installation.serverDeviceId)
        assertEquals(HISTORICAL_INSTALLATION_ID, decoded.installation.installationId)
        assertEquals(HISTORICAL_OWNER_ID, decoded.owner.localOwnerId)
        // The receiving replacement device intentionally is not an input to
        // the historical document decoder.
        assertEquals(false, decoded.installation.serverDeviceId == REPLACEMENT_DEVICE_ID)
    }

    @Test
    fun `decoder rejects capture event provenance drift`() {
        val original = change(sequence = 1)
        val mutated = mutate(original) { root ->
            val event = root.objectValue("event")
            val identity = event.objectValue("identity").toMutableMap()
            identity["device_id"] = JsonPrimitive(REPLACEMENT_DEVICE_ID)
            root.toMutableMap().apply {
                put(
                    "event",
                    JsonObject(
                        event.toMutableMap().apply {
                            put("identity", JsonObject(identity))
                        },
                    ),
                )
            }.let(::JsonObject)
        }

        val failure = assertThrows(ReplicaIntegrityException::class.java) {
            codec.decode(mutated)
        }
        assertEquals("replica_projection_drift", failure.errorCode)
    }

    @Test
    fun `decoder rejects cross nested source record provenance drift`() {
        listOf("source_record_id", "source_record_version").forEach { field ->
            val original = change(sequence = 1)
            val mutated = mutate(original) { root ->
                val capture = root.objectValue("capture")
                val source = capture.objectValue("source")
                val origin = source.objectValue("origin").toMutableMap()
                origin[field] = JsonPrimitive("upstream-$field")
                root.toMutableMap().apply {
                    put(
                        "capture",
                        JsonObject(
                            capture.toMutableMap().apply {
                                put(
                                    "source",
                                    JsonObject(
                                        source.toMutableMap().apply {
                                            put("origin", JsonObject(origin))
                                        },
                                    ),
                                )
                            },
                        ),
                    )
                }.let(::JsonObject)
            }

            val failure = assertThrows(ReplicaIntegrityException::class.java) {
                codec.decode(mutated)
            }
            assertEquals("replica_provenance_drift", failure.errorCode)
        }
    }

    @Test
    fun `decoder rejects capture integrity drift`() {
        val original = change(sequence = 1)
        val mutated = mutate(original) { root ->
            val capture = root.objectValue("capture")
            val integrity = capture.objectValue("integrity").toMutableMap()
            integrity["sha256"] = JsonPrimitive("0".repeat(64))
            root.toMutableMap().apply {
                put(
                    "capture",
                    JsonObject(
                        capture.toMutableMap().apply {
                            put("integrity", JsonObject(integrity))
                        },
                    ),
                )
            }.let(::JsonObject)
        }

        val failure = assertThrows(ReplicaIntegrityException::class.java) {
            codec.decode(mutated)
        }
        assertEquals("replica_projection_drift", failure.errorCode)
    }

    @Test
    fun `topology accepts applied lineage and stale conflict`() {
        val root = codec.decode(change(sequence = 1))
        val second = codec.decode(
            change(
                sequence = 2,
                revisionId = REVISION_TWO_ID,
                captureId = CAPTURE_TWO_ID,
                operationId = OPERATION_TWO_ID,
                revisionNo = 2,
                parentRevisionId = REVISION_ONE_ID,
                currentRevisionId = REVISION_TWO_ID,
            ),
        )
        val staleConflict = codec.decode(
            change(
                sequence = 3,
                revisionId = REVISION_THREE_ID,
                captureId = CAPTURE_THREE_ID,
                operationId = OPERATION_THREE_ID,
                revisionNo = 2,
                parentRevisionId = REVISION_ONE_ID,
                resultCode = "conflict",
                currentRevisionId = REVISION_TWO_ID,
            ),
        )

        val topology = ReplicaTopologyState()
        listOf(root, second, staleConflict).forEach(topology::accept)

        assertEquals(3L, topology.lastServerSequence)
    }

    @Test
    fun `topology rejects out of order parent and divergent current head`() {
        val topology = ReplicaTopologyState()
        topology.accept(codec.decode(change(sequence = 1)))
        val divergent = codec.decode(
            change(
                sequence = 2,
                revisionId = REVISION_TWO_ID,
                captureId = CAPTURE_TWO_ID,
                operationId = OPERATION_TWO_ID,
                revisionNo = 2,
                parentRevisionId = REVISION_ONE_ID,
                resultCode = "conflict",
                currentRevisionId = REVISION_ONE_ID,
            ),
        )

        val failure = assertThrows(ReplicaIntegrityException::class.java) {
            topology.accept(divergent)
        }
        assertEquals("replica_cas_drift", failure.errorCode)
    }

    private fun change(
        sequence: Long,
        revisionId: String = REVISION_ONE_ID,
        captureId: String = CAPTURE_ONE_ID,
        operationId: String = OPERATION_ONE_ID,
        revisionNo: Int = 1,
        parentRevisionId: String? = null,
        resultCode: String = "applied",
        currentRevisionId: String = revisionId,
    ): ReplicaChangePersistence {
        val recordedAt = "2030-01-01T07:0${sequence - 1}:00+07:00"
        val receivedAt = "2030-01-01T00:0${sequence - 1}:01Z"
        val text = "Replica change $sequence"
        val payload = buildJsonObject { put("text", text) }
        val captureContent = buildJsonObject {
            put("kind", "structured")
            put("record_type", "note")
            put("payload", payload)
        }
        val captureContentDigest = canonical.canonical(captureContent)
        val time = buildJsonObject {
            put("effective_start_utc", "2030-01-01T00:00:00Z")
            put("effective_end_utc", JsonNull)
            put("original_local_start", "2030-01-01T07:00:00")
            put("original_local_end", JsonNull)
            put("timezone_id", "Asia/Novosibirsk")
            put("start_offset_seconds", 25_200)
            put("end_offset_seconds", JsonNull)
            put("temporal_precision", "minute")
            put("local_date", "2030-01-01")
            put("source_expression", JsonNull)
        }
        val correctionReason =
            if (parentRevisionId == null) JsonNull else JsonPrimitive("test correction")
        val revisionContent = canonical.canonical(
            buildJsonObject {
                put("event_id", EVENT_ID)
                put("revision_id", revisionId)
                put("revision_no", revisionNo)
                put("capture_id", captureId)
                put("operation_id", operationId)
                put("record_status", "active")
                put("effective_time", time)
                put("recorded_at", recordedAt)
                put("payload", payload)
                put("correction_reason", correctionReason)
                put(
                    "parent_revision_id",
                    parentRevisionId?.let(::JsonPrimitive) ?: JsonNull,
                )
            },
        )
        val root = buildJsonObject {
            put("server_sequence", sequence)
            put("change_kind", "event_revision_committed")
            put("result_code", resultCode)
            put("operation_id", operationId)
            put("capture_id", captureId)
            put("event_id", EVENT_ID)
            put("revision_id", revisionId)
            put("current_revision_id", currentRevisionId)
            put("operation_content_sha256", operationDigest(sequence))
            putJsonObject("capture") {
                put("schema_version", "4.0.0")
                put("persistence_state", "authenticated_ingress")
                put("capture_id", captureId)
                put("operation_id", operationId)
                put("identity", identity())
                putJsonObject("source") {
                    put("channel", "android_manual")
                    put("recorded_at", recordedAt)
                    put("timezone_id", "+07:00")
                    put("utc_offset_minutes", 420)
                    put("origin", captureOrigin())
                    put("collector", collector())
                }
                put("content", captureContent)
                putJsonObject("integrity") {
                    put("sha256", captureContentDigest.sha256)
                    put("byte_size", captureContentDigest.bytes.size)
                }
            }
            putJsonObject("event") {
                put("schema_version", "4.0.0")
                put("persistence_state", "server_committed")
                put("identity", identity())
                put("event_id", EVENT_ID)
                put("revision_id", revisionId)
                put("revision_no", revisionNo)
                put("kind", "note")
                put("assertion_status", "observed")
                put("lifecycle", JsonNull)
                put("record_status", "active")
                put("verification_status", "user_confirmed")
                putJsonObject("source") {
                    put("capture_id", captureId)
                    put("operation_id", operationId)
                    put("channel", "android_manual")
                    put("source_record_id", JsonNull)
                    put("source_record_version", JsonNull)
                    put("source_modified_at", JsonNull)
                    put("recorded_at", recordedAt)
                    put("origin", eventOrigin())
                    put("collector", collector())
                }
                put("time", time)
                put("payload", payload)
                putJsonArray("evidence") {
                    add(
                        buildJsonObject {
                            put("capture_ref", "#/source/capture_id")
                            put("field_path", "/payload/text")
                            put("artifact_id", JsonNull)
                            put("locator", "android_form:/note/text")
                            put("excerpt", JsonNull)
                            put("human_confirmed", true)
                        },
                    )
                }
                put("quality_flags", buildJsonArray {})
                putJsonObject("revision") {
                    put("created_at", recordedAt)
                    put("content_sha256", revisionContent.sha256)
                    put("actor", "user")
                    put("correction_reason", correctionReason)
                    putJsonArray("parents") {
                        parentRevisionId?.let { parent ->
                            add(
                                buildJsonObject {
                                    put("revision_id", parent)
                                    put("relation", "supersedes")
                                },
                            )
                        }
                    }
                }
                putJsonObject("server") {
                    put("received_at", receivedAt)
                    put("server_sequence", sequence)
                }
            }
        }
        return ReplicaChangePersistence(
            serverSequence = sequence,
            operationId = operationId,
            operationContentSha256 = operationDigest(sequence),
            captureId = captureId,
            eventId = EVENT_ID,
            revisionId = revisionId,
            currentRevisionId = currentRevisionId,
            resultCode = resultCode,
            committedAtUtc = receivedAt,
            changeJcs = canonical.canonical(root).bytes,
        )
    }

    private fun mutate(
        original: ReplicaChangePersistence,
        transform: (JsonObject) -> JsonObject,
    ): ReplicaChangePersistence {
        val root = json.parseToJsonElement(
            original.changeJcs.toString(StandardCharsets.UTF_8),
        ) as JsonObject
        return original.copy(changeJcs = canonical.canonical(transform(root)).bytes)
    }

    private fun identity() = buildJsonObject {
        put("installation_id", HISTORICAL_INSTALLATION_ID)
        put("local_owner_id", HISTORICAL_OWNER_ID)
        put("device_id", SUBMITTING_DEVICE_ID)
    }

    private fun captureOrigin() = buildJsonObject {
        put("provider", JsonNull)
        put("app", "Life Agent Android")
        put("device", "Synthetic Android device")
        put("source_record_id", JsonNull)
        put("source_record_version", JsonNull)
        put("user_entered", true)
    }

    private fun eventOrigin() = buildJsonObject {
        put("provider", JsonNull)
        put("app", "Life Agent Android")
        put("device", "Synthetic Android device")
        put("user_entered", true)
    }

    private fun collector() = buildJsonObject {
        put("name", "life-agent-android")
        put("version", "replica-test")
    }

    private fun operationDigest(sequence: Long): String =
        sequence.toString(16).padStart(64, '0')

    private fun JsonObject.objectValue(name: String): JsonObject =
        getValue(name) as JsonObject

    private companion object {
        const val HISTORICAL_INSTALLATION_ID = "91000000-0000-4000-8000-000000000001"
        const val HISTORICAL_OWNER_ID = "91000000-0000-4000-8000-000000000002"
        const val SUBMITTING_DEVICE_ID = "91000000-0000-4000-8000-000000000003"
        const val REPLACEMENT_DEVICE_ID = "91000000-0000-4000-8000-000000000004"
        const val EVENT_ID = "92000000-0000-4000-8000-000000000001"
        const val REVISION_ONE_ID = "93000000-0000-4000-8000-000000000001"
        const val REVISION_TWO_ID = "93000000-0000-4000-8000-000000000002"
        const val REVISION_THREE_ID = "93000000-0000-4000-8000-000000000003"
        const val CAPTURE_ONE_ID = "94000000-0000-4000-8000-000000000001"
        const val CAPTURE_TWO_ID = "94000000-0000-4000-8000-000000000002"
        const val CAPTURE_THREE_ID = "94000000-0000-4000-8000-000000000003"
        const val OPERATION_ONE_ID = "95000000-0000-4000-8000-000000000001"
        const val OPERATION_TWO_ID = "95000000-0000-4000-8000-000000000002"
        const val OPERATION_THREE_ID = "95000000-0000-4000-8000-000000000003"
    }
}
