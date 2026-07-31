package ru.andriyshkoy.lifeagent.data.sync.wire

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WireRequestCodecTest {
    @Test
    fun materializesAllAuthFixturesAsExactCanonicalBytes() {
        WireTestFixtures.enrollmentRequest().use { request ->
            WireRequestCodec.materialize(request).use { materialized ->
                assertArrayEquals(
                    WireTestFixtures.canonical("auth-enrollment-claim-request.json"),
                    materialized.copyBody(),
                )
            }
        }
        WireTestFixtures.refreshRequest().use { request ->
            WireRequestCodec.materialize(request).use { materialized ->
                assertArrayEquals(
                    WireTestFixtures.canonical("auth-refresh-request.json"),
                    materialized.copyBody(),
                )
            }
        }
        WireTestFixtures.revokeRequest().use { request ->
            WireRequestCodec.materialize(request).use { materialized ->
                assertArrayEquals(
                    WireTestFixtures.canonical("auth-revoke-request.json"),
                    materialized.copyBody(),
                )
            }
        }
    }

    @Test
    fun materializesEveryBootstrapAndPullRequestFixture() {
        listOf(
            "sync-bootstrap-request.json",
            "sync-bootstrap-page-2-request.json",
            "sync-bootstrap-replacement-request.json",
        ).forEach { name ->
            WireRequestCodec.materialize(WireTestFixtures.bootstrapRequest(name)).use { body ->
                assertArrayEquals(WireTestFixtures.canonical(name), body.copyBody())
            }
        }
        listOf("sync-pull-request.json", "sync-pull-page-2-request.json").forEach { name ->
            WireRequestCodec.materialize(WireTestFixtures.pullRequest(name)).use { body ->
                assertArrayEquals(WireTestFixtures.canonical(name), body.copyBody())
            }
        }
    }

    @Test
    fun pushFixtureRoundTripsThroughValidatedDtosAndCanonicalMaterialization() {
        val request = WireRequestCodec.decodePushBatch(
            WireTestFixtures.bytes("sync-push-batch-request.json"),
        )
        WireRequestCodec.materialize(request).use { materialized ->
            assertEquals(M2Endpoint.SYNC_PUSH, materialized.endpoint)
            assertEquals(request.batchId, materialized.idempotencyKey)
            assertArrayEquals(
                WireTestFixtures.canonical("sync-push-batch-request.json"),
                materialized.copyBody(),
            )
            assertEquals(sha256Hex(materialized.copyBody()), materialized.bodySha256)
        }
    }

    @Test
    fun operationAndBatchHashesMatchFrozenFixture() {
        val request = WireRequestCodec.decodePushBatch(
            WireTestFixtures.bytes("sync-push-batch-request.json"),
        )
        val first = request.operations.first()
        assertEquals(
            "81411dbf4319ab8d7b4bbfd1dfcac76ab3b212e4fcd9988907248b4a3b55e87b",
            first.operationContentSha256,
        )
        val root = WireTestFixtures.objectFrom("sync-push-batch-request.json")
        assertEquals(
            root.requireString("batch_content_sha256"),
            StrictJson.canonicalSha256(root.without("batch_content_sha256")),
        )
    }

    @Test
    fun builderRecreatesFrozenOperationFromValidatedPendingDocuments() {
        val request = WireRequestCodec.decodePushBatch(
            WireTestFixtures.bytes("sync-push-batch-request.json"),
        )
        val frozen = request.operations.first()
        val capture = M2NoteWireDocuments.decodePendingCapture(
            StrictJson.canonicalBytes(frozen.capture.document),
        )
        val event = M2NoteWireDocuments.decodePendingEvent(
            StrictJson.canonicalBytes(frozen.event.document),
        )
        val rebuilt = WireRequestCodec.createPushOperation(
            ordinal = 0,
            clientSequence = frozen.clientSequence,
            expectedCurrentRevisionId = frozen.expectedCurrentRevisionId,
            capture = capture,
            event = event,
        )

        assertEquals(frozen.operationContentSha256, rebuilt.operationContentSha256)
        assertArrayEquals(
            StrictJson.canonicalBytes(frozen.document),
            StrictJson.canonicalBytes(rebuilt.document),
        )
    }

    @Test
    fun movingOperationsChangesOrdinalsAndBatchHashButNotOperationHashes() {
        val original = WireRequestCodec.decodePushBatch(
            WireTestFixtures.bytes("sync-push-batch-request.json"),
        )
        val reordered = PushBatchRequest(
            batchId = "96000000-0000-4000-8000-000000000009",
            deviceId = original.deviceId,
            operations = original.operations.reversed(),
        )
        val originalDigests = original.operations.map { it.operationContentSha256 }.reversed()
        WireRequestCodec.materialize(reordered).use { materialized ->
            val decoded = WireRequestCodec.decodePushBatch(materialized.copyBody())
            assertEquals(listOf(0, 1, 2), decoded.operations.map { it.ordinal })
            assertEquals(originalDigests, decoded.operations.map { it.operationContentSha256 })
            assertFalse(
                WireTestFixtures.objectFrom("sync-push-batch-request.json")
                    .requireString("batch_content_sha256") ==
                    StrictJson.parse(
                        materialized.copyBody(),
                        StrictJsonLimits.request(M2Endpoint.SYNC_PUSH.requestMaxBytes),
                    ).let { (it as WireJsonObject).requireString("batch_content_sha256") },
            )
        }
    }

    @Test
    fun materializedPushBatchesStayWithinFrozenStructuralIngressLimit() {
        val original = WireRequestCodec.decodePushBatch(
            WireTestFixtures.bytes("sync-push-batch-request.json"),
        )
        val template = original.operations.first()
        fun requestWith(operationCount: Int) = PushBatchRequest(
            batchId = "96000000-0000-4000-8000-000000000099",
            deviceId = original.deviceId,
            operations = List(operationCount) { template },
        )

        assertProtocolFailure(WireProtocolFailure.SCHEMA_MISMATCH) {
            WireRequestCodec.materialize(requestWith(M2_MAX_PUSH_OPERATIONS))
        }

        var largestRoundTrippedCount = M2_MAX_PUSH_OPERATIONS - 1
        while (true) {
            try {
                WireRequestCodec.materialize(requestWith(largestRoundTrippedCount)).use { body ->
                    val decoded = WireRequestCodec.decodePushBatch(body.copyBody())
                    assertEquals(largestRoundTrippedCount, decoded.operations.size)
                }
                break
            } catch (error: WireProtocolException) {
                assertEquals(WireProtocolFailure.SCHEMA_MISMATCH, error.failure)
                largestRoundTrippedCount -= 1
            }
        }
        assertTrue(largestRoundTrippedCount in 1 until M2_MAX_PUSH_OPERATIONS)
    }

    @Test
    fun rejectsChangedBatchAndOperationHashes() {
        val root = WireTestFixtures.objectFrom("sync-push-batch-request.json")
        val badBatch = WireTestFixtures.withProperty(
            root,
            "batch_content_sha256",
            "0".repeat(64).asJson(),
        )
        assertProtocolFailure(WireProtocolFailure.HASH_MISMATCH) {
            WireRequestCodec.decodePushBatch(StrictJson.canonicalBytes(badBatch))
        }

        val operations = root.requireArray("operations").elements.toMutableList()
        val first = operations.first() as WireJsonObject
        operations[0] = WireTestFixtures.withProperty(
            first,
            "operation_content_sha256",
            "0".repeat(64).asJson(),
        )
        val changed = WireTestFixtures.withProperty(root, "operations", WireJsonArray(operations))
        val rehashed = WireTestFixtures.withProperty(
            changed,
            "batch_content_sha256",
            StrictJson.canonicalSha256(changed.without("batch_content_sha256")).asJson(),
        )
        assertProtocolFailure(WireProtocolFailure.HASH_MISMATCH) {
            WireRequestCodec.decodePushBatch(StrictJson.canonicalBytes(rehashed))
        }

        val unexpectedOperationField = WireJsonObject(
            first.properties + ("unexpected" to WireJsonNull),
        )
        val combinedFailure = WireTestFixtures.withProperty(
            root,
            "operations",
            WireJsonArray(listOf(unexpectedOperationField) + operations.drop(1)),
        )
        assertProtocolFailure(WireProtocolFailure.HASH_MISMATCH) {
            WireRequestCodec.decodePushBatch(StrictJson.canonicalBytes(combinedFailure))
        }
    }

    @Test
    fun rejectsBatchSpanningMultipleInstallationOwnerNamespaces() {
        val root = WireTestFixtures.objectFrom("sync-push-batch-request.json")
        val original = root.requireArray("operations").elements
        val secondNamespace = operationWithNamespace(
            original[1] as WireJsonObject,
            installationId = "91000000-0000-4000-8000-000000000009",
            localOwnerId = "91000000-0000-4000-8000-000000000008",
        )
        val firstOnly = batchWithOperations(root, listOf(original[0]))
        val secondOnly = batchWithOperations(
            root,
            listOf(WireTestFixtures.withProperty(secondNamespace, "ordinal", 0.asJson())),
        )
        val firstOperation = WireRequestCodec.decodePushBatch(
            StrictJson.canonicalBytes(firstOnly),
        ).operations.single()
        val secondOperation = WireRequestCodec.decodePushBatch(
            StrictJson.canonicalBytes(secondOnly),
        ).operations.single()
        val mixed = batchWithOperations(root, listOf(original[0], secondNamespace))

        assertProtocolFailure(WireProtocolFailure.SCHEMA_MISMATCH) {
            WireRequestCodec.decodePushBatch(StrictJson.canonicalBytes(mixed))
        }
        assertProtocolFailure(WireProtocolFailure.SCHEMA_MISMATCH) {
            WireRequestCodec.materialize(
                PushBatchRequest(
                    batchId = root.requireString("batch_id"),
                    deviceId = root.requireString("device_id"),
                    operations = listOf(firstOperation, secondOperation),
                ),
            )
        }
    }

    @Test
    fun defersFullyRehashedSelfParentRevisionToPostClaimClassification() {
        val root = WireTestFixtures.objectFrom("sync-push-batch-request.json")
        val operation = root.requireArray("operations").elements.first() as WireJsonObject
        val body = operation.requireObject("body")
        val revisionId = body.requireString("revision_id")
        val revision = WireTestFixtures.withProperty(
            body.requireObject("revision"),
            "parents",
            jsonArrayOf(
                listOf(
                    jsonObjectOf(
                        "revision_id" to revisionId.asJson(),
                        "relation" to "supersedes".asJson(),
                    ),
                ),
            ),
        )
        val changedBody = rehashRevision(
            WireTestFixtures.withProperty(
                WireTestFixtures.withProperty(body, "revision_no", 2.asJson()),
                "revision",
                revision,
            ),
        )
        val changedOperation = WireTestFixtures.withProperty(
            WireTestFixtures.withProperty(
                operation,
                "expected_current_revision_id",
                revisionId.asJson(),
            ),
            "body",
            changedBody,
        )
        val rehashedOperation = WireTestFixtures.withProperty(
            changedOperation,
            "operation_content_sha256",
            StrictJson.canonicalSha256(
                WireJsonObject(
                    changedOperation.properties - setOf("ordinal", "operation_content_sha256"),
                ),
            ).asJson(),
        )
        val rehashedBatch = batchWithOperations(root, listOf(rehashedOperation))

        val decoded = WireRequestCodec.decodePushBatch(
            StrictJson.canonicalBytes(rehashedBatch),
        )
        assertEquals(revisionId, decoded.operations.single().event.parentRevisionId)
    }

    @Test
    fun defersFullyRehashedExpectedCurrentParentMismatchToDependencyClassification() {
        val root = WireTestFixtures.objectFrom("sync-push-batch-request.json")
        val operation = root.requireArray("operations").elements.first() as WireJsonObject
        val expectedCurrentRevisionId = "83000000-0000-4000-8000-000000000001"
        val changedOperation = rehashOperation(
            WireTestFixtures.withProperty(
                operation,
                "expected_current_revision_id",
                expectedCurrentRevisionId.asJson(),
            ),
        )
        val decoded = WireRequestCodec.decodePushBatch(
            StrictJson.canonicalBytes(batchWithOperations(root, listOf(changedOperation))),
        ).operations.single()

        assertEquals(expectedCurrentRevisionId, decoded.expectedCurrentRevisionId)
        assertEquals(null, decoded.event.parentRevisionId)
        val rebuilt = WireRequestCodec.createPushOperation(
            ordinal = 0,
            clientSequence = decoded.clientSequence,
            expectedCurrentRevisionId = expectedCurrentRevisionId,
            capture = decoded.capture,
            event = decoded.event,
        )
        assertEquals(expectedCurrentRevisionId, rebuilt.expectedCurrentRevisionId)
        WireRequestCodec.materialize(
            PushBatchRequest(
                batchId = "96000000-0000-4000-8000-000000000030",
                deviceId = root.requireString("device_id"),
                operations = listOf(rebuilt),
            ),
        ).close()
    }

    @Test
    fun rejectsSyntheticZoneRegionsAfterRehashingEveryAffectedDigest() {
        val root = WireTestFixtures.objectFrom("sync-push-batch-request.json")
        val operation = root.requireArray("operations").elements.first() as WireJsonObject

        val capture = operation.requireObject("capture")
        val syntheticCapture = WireTestFixtures.withProperty(
            capture,
            "source",
            WireTestFixtures.withProperty(
                capture.requireObject("source"),
                "timezone_id",
                "GMT+07:00".asJson(),
            ),
        )
        val captureOperation = rehashOperation(
            WireTestFixtures.withProperty(operation, "capture", syntheticCapture),
        )
        assertProtocolFailure(WireProtocolFailure.SCHEMA_MISMATCH) {
            WireRequestCodec.decodePushBatch(
                StrictJson.canonicalBytes(batchWithOperations(root, listOf(captureOperation))),
            )
        }

        val event = operation.requireObject("body")
        val syntheticEvent = rehashRevision(
            WireTestFixtures.withProperty(
                event,
                "time",
                WireTestFixtures.withProperty(
                    event.requireObject("time"),
                    "timezone_id",
                    "UTC+07:00".asJson(),
                ),
            ),
        )
        val eventOperation = rehashOperation(
            WireTestFixtures.withProperty(operation, "body", syntheticEvent),
        )
        assertProtocolFailure(WireProtocolFailure.SCHEMA_MISMATCH) {
            WireRequestCodec.decodePushBatch(
                StrictJson.canonicalBytes(batchWithOperations(root, listOf(eventOperation))),
            )
        }
    }

    @Test
    fun rejectsOffsetTimestampsWhoseNormalizedUtcYearLeavesContractRange() {
        val root = WireTestFixtures.objectFrom("sync-push-batch-request.json")
        val operation = root.requireArray("operations").elements.first() as WireJsonObject
        listOf(
            "0001-01-01T00:00:00+14:00",
            "9999-12-31T23:59:59-14:00",
        ).forEach { overflowTimestamp ->
            val event = operation.requireObject("body")
            val changedEvent = rehashRevision(
                WireTestFixtures.withProperty(
                    event,
                    "source",
                    WireTestFixtures.withProperty(
                        event.requireObject("source"),
                        "recorded_at",
                        overflowTimestamp.asJson(),
                    ),
                ),
            )
            val changedOperation = rehashOperation(
                WireTestFixtures.withProperty(operation, "body", changedEvent),
            )
            assertProtocolFailure(WireProtocolFailure.SCHEMA_MISMATCH) {
                WireRequestCodec.decodePushBatch(
                    StrictJson.canonicalBytes(
                        batchWithOperations(root, listOf(changedOperation)),
                    ),
                )
            }
        }
    }

    @Test
    fun requestSecretsAndMaterializedBodiesAreRedactedAndClosable() {
        val request = WireTestFixtures.refreshRequest()
        val token = request.refreshToken.useBytes { it.toString(StandardCharsets.US_ASCII) }
        assertFalse(request.toString().contains(token))
        assertFalse(request.refreshToken.toString().contains(token))
        val materialized = WireRequestCodec.materialize(request)
        assertFalse(materialized.toString().contains(token))
        materialized.close()
        assertThrows(IllegalStateException::class.java) { materialized.copyBody() }
        request.close()
        assertThrows(IllegalStateException::class.java) { request.refreshToken.copyBytes() }
    }

    @Test
    fun borrowedSecretCopiesAreWipedAfterCallback() {
        val secret = WipeableSecret.ascii("lar_RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRE")
        lateinit var borrowed: ByteArray
        secret.useBytes { bytes ->
            borrowed = bytes
            assertTrue(bytes.any { it != 0.toByte() })
        }
        assertTrue(borrowed.all { it == 0.toByte() })
        secret.close()
    }

    private fun operationWithNamespace(
        operation: WireJsonObject,
        installationId: String,
        localOwnerId: String,
    ): WireJsonObject {
        fun changedIdentity(document: WireJsonObject): WireJsonObject {
            val identity = document.requireObject("identity")
            return WireTestFixtures.withProperty(
                document,
                "identity",
                WireTestFixtures.withProperty(
                    WireTestFixtures.withProperty(
                        identity,
                        "installation_id",
                        installationId.asJson(),
                    ),
                    "local_owner_id",
                    localOwnerId.asJson(),
                ),
            )
        }

        val changed = WireTestFixtures.withProperty(
            WireTestFixtures.withProperty(
                operation,
                "capture",
                changedIdentity(operation.requireObject("capture")),
            ),
            "body",
            changedIdentity(operation.requireObject("body")),
        )
        return WireTestFixtures.withProperty(
            changed,
            "operation_content_sha256",
            StrictJson.canonicalSha256(
                WireJsonObject(
                    changed.properties - setOf("ordinal", "operation_content_sha256"),
                ),
            ).asJson(),
        )
    }

    private fun batchWithOperations(
        root: WireJsonObject,
        operations: List<WireJsonValue>,
    ): WireJsonObject {
        val changed = WireTestFixtures.withProperty(
            root,
            "operations",
            WireJsonArray(operations),
        )
        return WireTestFixtures.withProperty(
            changed,
            "batch_content_sha256",
            StrictJson.canonicalSha256(changed.without("batch_content_sha256")).asJson(),
        )
    }

    private fun rehashRevision(event: WireJsonObject): WireJsonObject {
        val source = event.requireObject("source")
        val revision = event.requireObject("revision")
        val parentRevisionId = revision.requireArray("parents").elements.singleOrNull()?.let {
            (it as WireJsonObject).requireString("revision_id")
        }
        val digest = StrictJson.canonicalSha256(
            jsonObjectOf(
                "event_id" to event.requireValue("event_id"),
                "revision_id" to event.requireValue("revision_id"),
                "revision_no" to event.requireValue("revision_no"),
                "capture_id" to source.requireValue("capture_id"),
                "operation_id" to source.requireValue("operation_id"),
                "record_status" to event.requireValue("record_status"),
                "effective_time" to event.requireValue("time"),
                "recorded_at" to source.requireValue("recorded_at"),
                "payload" to event.requireValue("payload"),
                "correction_reason" to revision.requireValue("correction_reason"),
                "parent_revision_id" to parentRevisionId.asNullableJson(),
            ),
        )
        return WireTestFixtures.withProperty(
            event,
            "revision",
            WireTestFixtures.withProperty(revision, "content_sha256", digest.asJson()),
        )
    }

    private fun rehashOperation(operation: WireJsonObject): WireJsonObject =
        WireTestFixtures.withProperty(
            operation,
            "operation_content_sha256",
            StrictJson.canonicalSha256(
                WireJsonObject(
                    operation.properties - setOf("ordinal", "operation_content_sha256"),
                ),
            ).asJson(),
        )

    private fun assertProtocolFailure(
        expected: WireProtocolFailure,
        block: () -> Unit,
    ) {
        val error = assertThrows(WireProtocolException::class.java, block)
        assertEquals(expected, error.failure)
    }
}
