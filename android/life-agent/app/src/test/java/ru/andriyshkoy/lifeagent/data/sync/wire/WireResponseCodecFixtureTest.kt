package ru.andriyshkoy.lifeagent.data.sync.wire

import java.nio.charset.StandardCharsets
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WireResponseCodecFixtureTest {
    @Test
    fun decodesEnrollmentAndRefreshIntoEphemeralRedactedCredentials() {
        WireTestFixtures.enrollmentRequest().use { request ->
            val response = WireResponseCodec.decode(
                200,
                WireTestFixtures.bytes("auth-enrollment-claim-response.json"),
                EnrollmentResponseExpectation(request),
            ) as EnrollmentClaimSuccess
            val access = response.credentials.useAccessToken {
                it.toString(StandardCharsets.US_ASCII)
            }
            assertEquals(1L, response.credentials.generation)
            assertTrue(
                Instant.parse(response.credentials.accessExpiresAt) <
                    Instant.parse(response.credentials.refreshExpiresAt),
            )
            assertFalse(response.toString().contains(access))
            assertFalse(response.credentials.toString().contains(access))
            response.close()
            assertThrows(IllegalStateException::class.java) {
                response.credentials.copyAccessToken()
            }
        }

        WireTestFixtures.refreshRequest().use { request ->
            val response = WireResponseCodec.decode(
                200,
                WireTestFixtures.bytes("auth-refresh-response.json"),
                RefreshResponseExpectation(
                    request = request,
                    expectedFamilyExpiresAt = "2030-04-01T00:00:00Z",
                    previouslyIssuedTokenSha256 = emptySet(),
                ),
            ) as RefreshSuccess
            assertEquals(2L, response.credentials.generation)
            response.close()
        }
    }

    @Test
    fun validatesTokenExpiryWindowsAtMaximumCanonicalServerYear() {
        fun edgeResponse(fixture: String): WireJsonObject {
            val root = WireTestFixtures.objectFrom(fixture)
            val credentials = WireJsonObject(
                root.requireObject("credentials").properties + mapOf(
                    "access_expires_at" to "9999-12-31T23:59:59.100Z".asJson(),
                    "refresh_expires_at" to "9999-12-31T23:59:59.200Z".asJson(),
                    "family_expires_at" to "9999-12-31T23:59:59.300Z".asJson(),
                ),
            )
            return WireJsonObject(
                root.properties + mapOf(
                    "credentials" to credentials,
                    "server_time" to "9999-12-31T23:59:59Z".asJson(),
                ),
            )
        }

        WireTestFixtures.enrollmentRequest().use { request ->
            val decoded = WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(edgeResponse("auth-enrollment-claim-response.json")),
                EnrollmentResponseExpectation(request),
            ) as EnrollmentClaimSuccess
            decoded.close()
        }
        WireTestFixtures.refreshRequest().use { request ->
            val decoded = WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(edgeResponse("auth-refresh-response.json")),
                RefreshResponseExpectation(
                    request,
                    "9999-12-31T23:59:59.300Z",
                    emptySet(),
                ),
            ) as RefreshSuccess
            decoded.close()
        }
    }

    @Test
    fun ephemeralCredentialConstructorTransfersWipeOwnershipAndUsesIdentityEquality() {
        val access = "laa_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA8"
            .toByteArray(StandardCharsets.US_ASCII)
        val refresh = "lar_RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRE"
            .toByteArray(StandardCharsets.US_ASCII)
        val credentials = EphemeralTokenPair(
            access,
            refresh,
            "2030-01-01T00:15:00Z",
            "2030-01-31T00:00:00Z",
            "2030-04-01T00:00:00Z",
            1,
        )
        val another = EphemeralTokenPair(
            access.copyOf(),
            refresh.copyOf(),
            "2030-01-01T00:15:00Z",
            "2030-01-31T00:00:00Z",
            "2030-04-01T00:00:00Z",
            1,
        )
        assertNotEquals(credentials, another)
        credentials.close()
        assertTrue(access.all { it == 0.toByte() })
        assertTrue(refresh.all { it == 0.toByte() })
        another.close()
    }

    @Test
    fun enforcesStableReplacementPersonAndDeviceBindings() {
        val root = WireTestFixtures.objectFrom("auth-enrollment-claim-response.json")
        WireTestFixtures.enrollmentRequest().use { request ->
            val matched = WireResponseCodec.decode(
                200,
                WireTestFixtures.bytes("auth-enrollment-claim-response.json"),
                EnrollmentResponseExpectation(
                    request = request,
                    expectedStableDeviceId = root.requireString("device_id"),
                    expectedStablePersonId = root.requireString("person_id"),
                ),
            ) as EnrollmentClaimSuccess
            matched.close()
        }
        WireTestFixtures.enrollmentRequest().use { request ->
            assertFailure(WireProtocolFailure.AUTH_INVARIANT) {
                WireResponseCodec.decode(
                    200,
                    WireTestFixtures.bytes("auth-enrollment-claim-response.json"),
                    EnrollmentResponseExpectation(
                        request = request,
                        expectedStableDeviceId = root.requireString("device_id"),
                        expectedStablePersonId =
                            "90000000-0000-4000-8000-000000000009",
                    ),
                )
            }
        }
        WireTestFixtures.enrollmentRequest().use { request ->
            assertFailure(WireProtocolFailure.AUTH_INVARIANT) {
                WireResponseCodec.decode(
                    200,
                    WireTestFixtures.bytes("auth-enrollment-claim-response.json"),
                    EnrollmentResponseExpectation(
                        request = request,
                        expectedStablePersonId = root.requireString("person_id"),
                        forbiddenExistingDeviceIds = setOf(root.requireString("device_id")),
                    ),
                )
            }
        }
    }

    @Test
    fun decodesRevokeAndItsFrozenReplayReceipt() {
        WireTestFixtures.revokeRequest().use { request ->
            val expectation = RevokeResponseExpectation(request)
            val first = WireResponseCodec.decode(
                200,
                WireTestFixtures.bytes("auth-revoke-response.json"),
                expectation,
            ) as RevokeSuccess
            val replay = WireResponseCodec.decode(
                200,
                WireTestFixtures.bytes("auth-revoke-replay-response.json"),
                expectation,
            ) as RevokeSuccess
            assertEquals(first, replay)
            assertEquals("RevokeSuccess(redacted=true)", first.toString())
        }
    }

    @Test
    fun decodesPushAndFrozenReplayAgainstExactOperationOrder() {
        val request = WireRequestCodec.decodePushBatch(
            WireTestFixtures.bytes("sync-push-batch-request.json"),
        )
        val expectation = PushResponseExpectation(request)
        val first = WireResponseCodec.decode(
            200,
            WireTestFixtures.bytes("sync-push-batch-response.json"),
            expectation,
        ) as PushBatchSuccess
        val replay = WireResponseCodec.decode(
            200,
            WireTestFixtures.bytes("sync-push-batch-replay-response.json"),
            expectation,
        ) as PushBatchSuccess

        assertEquals(3, first.results.size)
        assertEquals(listOf(1L, 2L, 3L), first.results.filterIsInstance<PushOperationAck>().map { it.serverSequence })
        assertEquals(first.results, replay.results)
        assertEquals("PushBatchSuccess(resultCount=3,redacted=true)", first.toString())
    }

    @Test
    fun acceptsPhysicalPushOrderWithOlderReplayedAckSequence() {
        val frozen = WireRequestCodec.decodePushBatch(
            WireTestFixtures.bytes("sync-push-batch-request.json"),
        )
        val template = frozen.operations.first().document
        val second = rootOperationVariant(
            template,
            ordinal = 1,
            clientSequence = 10,
        )
        val third = rootOperationVariant(
            template,
            ordinal = 2,
            clientSequence = 11,
            operationId = "95000000-0000-4000-8000-000000000020",
            captureId = "94000000-0000-4000-8000-000000000020",
            eventId = "92000000-0000-4000-8000-000000000020",
            revisionId = "93000000-0000-4000-8000-000000000020",
        )
        val request = WireRequestCodec.decodePushBatch(
            StrictJson.canonicalBytes(
                pushRequestWithOperations(
                    "96000000-0000-4000-8000-000000000031",
                    listOf(template, second, third),
                ),
            ),
        )
        val response = pushAppliedAckResponse(
            request,
            sequences = listOf(10L, 2L, 12L),
            replayed = listOf(false, true, false),
        )

        val decoded = WireResponseCodec.decode(
            200,
            StrictJson.canonicalBytes(response),
            PushResponseExpectation(request),
        ) as PushBatchSuccess
        assertEquals(
            listOf(10L, 2L, 12L),
            decoded.results.filterIsInstance<PushOperationAck>().map { it.serverSequence },
        )

        assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(
                    pushAppliedAckResponse(
                        request,
                        sequences = listOf(10L, 9L, 12L),
                        replayed = listOf(false, false, false),
                    ),
                ),
                PushResponseExpectation(request),
            )
        }
        assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(
                    pushAppliedAckResponse(
                        request,
                        sequences = listOf(10L, 20L, 30L),
                        replayed = listOf(false, true, false),
                    ),
                ),
                PushResponseExpectation(request),
            )
        }
    }

    @Test
    fun decodesOperationCollisionResponse() {
        val request = WireRequestCodec.decodePushBatch(
            WireTestFixtures.bytes("sync-push-operation-id-collision-request.json"),
        )
        val response = WireResponseCodec.decode(
            200,
            WireTestFixtures.bytes("sync-push-operation-id-collision-response.json"),
            PushResponseExpectation(request),
        ) as PushBatchSuccess
        val error = response.results.single() as PushOperationError
        assertEquals(PushOperationErrorCode.OPERATION_ID_COLLISION, error.errorCode)
        assertFalse(error.retryable)
    }

    @Test
    fun strictLocalPushAcceptsOnlyPostValidationOperationErrors() {
        val request = WireRequestCodec.decodePushBatch(
            WireTestFixtures.bytes("sync-push-operation-id-collision-request.json"),
        )
        val root = WireTestFixtures.objectFrom(
            "sync-push-operation-id-collision-response.json",
        )
        val original = root.requireArray("results").elements.single() as WireJsonObject
        val accepted = setOf(
            PushOperationErrorCode.OPERATION_ID_COLLISION,
            PushOperationErrorCode.CLIENT_SEQUENCE_COLLISION,
            PushOperationErrorCode.CAPTURE_ID_COLLISION,
            PushOperationErrorCode.REVISION_ID_COLLISION,
            PushOperationErrorCode.EVENT_ID_COLLISION,
            PushOperationErrorCode.MISSING_PARENT,
            PushOperationErrorCode.INVALID_PARENT,
            PushOperationErrorCode.OWNERSHIP_VIOLATION,
        )
        assertEquals(
            PushOperationErrorCode.entries.toSet(),
            accepted + setOf(
                PushOperationErrorCode.UNSUPPORTED_SCHEMA_VERSION,
                PushOperationErrorCode.UNSUPPORTED_OPERATION_KIND,
                PushOperationErrorCode.UNSUPPORTED_EVENT_KIND,
                PushOperationErrorCode.UNSUPPORTED_SOURCE_CHANNEL,
                PushOperationErrorCode.SCHEMA_INVALID,
                PushOperationErrorCode.OPERATION_HASH_MISMATCH,
            ),
        )

        PushOperationErrorCode.entries.forEach { code ->
            val changedResult = WireTestFixtures.withProperty(
                WireTestFixtures.withProperty(
                    original,
                    "error_code",
                    code.wireName.asJson(),
                ),
                "retryable",
                code.retryable.asJson(),
            )
            val response = WireTestFixtures.withProperty(
                root,
                "results",
                WireJsonArray(listOf(changedResult)),
            )
            if (code in accepted) {
                val decoded = WireResponseCodec.decode(
                    200,
                    StrictJson.canonicalBytes(response),
                    PushResponseExpectation(request),
                ) as PushBatchSuccess
                assertEquals(code, (decoded.results.single() as PushOperationError).errorCode)
            } else {
                assertFailure(WireProtocolFailure.SCHEMA_MISMATCH) {
                    WireResponseCodec.decode(
                        200,
                        StrictJson.canonicalBytes(response),
                        PushResponseExpectation(request),
                    )
                }
            }
        }

        listOf(
            PushOperationErrorCode.CLIENT_SEQUENCE_COLLISION,
            PushOperationErrorCode.MISSING_PARENT,
        ).forEach { code ->
            val forged = WireTestFixtures.withProperty(
                WireTestFixtures.withProperty(
                    WireTestFixtures.withProperty(
                        original,
                        "error_code",
                        code.wireName.asJson(),
                    ),
                    "retryable",
                    code.retryable.asJson(),
                ),
                "field_errors",
                jsonArrayOf(
                    listOf(
                        jsonObjectOf(
                            "path" to "/operations/0".asJson(),
                            "code" to FieldErrorCode.SCHEMA_INVALID.wireName.asJson(),
                        ),
                    ),
                ),
            )
            val response = WireTestFixtures.withProperty(
                root,
                "results",
                WireJsonArray(listOf(forged)),
            )
            assertFailure(WireProtocolFailure.SCHEMA_MISMATCH) {
                WireResponseCodec.decode(
                    200,
                    StrictJson.canonicalBytes(response),
                    PushResponseExpectation(request),
                )
            }
        }
    }

    @Test
    fun enforcesDeterministicIntraBatchRegistryPrecedence() {
        val frozen = WireRequestCodec.decodePushBatch(
            WireTestFixtures.bytes("sync-push-batch-request.json"),
        )
        val first = frozen.operations.first()
        val duplicateRequest = WireRequestCodec.materialize(
            PushBatchRequest(
                batchId = "96000000-0000-4000-8000-000000000010",
                deviceId = frozen.deviceId,
                operations = listOf(first, first),
            ),
        ).use { materialized ->
            WireRequestCodec.decodePushBatch(materialized.copyBody())
        }
        val duplicateResponse = pushCollisionResponse(
            duplicateRequest,
            PushOperationErrorCode.OPERATION_ID_COLLISION,
        )
        val acceptedDuplicate = WireResponseCodec.decode(
            200,
            StrictJson.canonicalBytes(duplicateResponse),
            PushResponseExpectation(duplicateRequest),
        ) as PushBatchSuccess
        assertEquals(
            PushOperationErrorCode.OPERATION_ID_COLLISION,
            (acceptedDuplicate.results[1] as PushOperationError).errorCode,
        )
        assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(pushDuplicateAckResponse(duplicateRequest)),
                PushResponseExpectation(duplicateRequest),
            )
        }
        val ownershipResponse = pushAllErrorResponse(
            duplicateRequest,
            PushOperationErrorCode.OWNERSHIP_VIOLATION,
        )
        val ownershipErrors = WireResponseCodec.decode(
            200,
            StrictJson.canonicalBytes(ownershipResponse),
            PushResponseExpectation(duplicateRequest),
        ) as PushBatchSuccess
        assertEquals(
            listOf(
                PushOperationErrorCode.OWNERSHIP_VIOLATION,
                PushOperationErrorCode.OWNERSHIP_VIOLATION,
            ),
            ownershipErrors.results.map { (it as PushOperationError).errorCode },
        )

        val template = first.document
        listOf(
            selfParentOperation(template),
            expectedCurrentMismatchOperation(template),
        ).forEachIndexed { index, deferredOperation ->
            val deferredRequest = WireRequestCodec.decodePushBatch(
                StrictJson.canonicalBytes(
                    pushRequestWithOperations(
                        "96000000-0000-4000-8000-00000000001${index + 4}",
                        listOf(
                            deferredOperation,
                            WireTestFixtures.withProperty(
                                deferredOperation,
                                "ordinal",
                                1.asJson(),
                            ),
                        ),
                    ),
                ),
            )
            val deferredParentResults = WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(
                    pushErrorResponse(
                        deferredRequest,
                        listOf(
                            PushOperationErrorCode.INVALID_PARENT,
                            PushOperationErrorCode.OPERATION_ID_COLLISION,
                        ),
                    ),
                ),
                PushResponseExpectation(deferredRequest),
            ) as PushBatchSuccess
            assertEquals(
                listOf(
                    PushOperationErrorCode.INVALID_PARENT,
                    PushOperationErrorCode.OPERATION_ID_COLLISION,
                ),
                deferredParentResults.results.map { (it as PushOperationError).errorCode },
            )
        }

        val cases = listOf(
            Triple(
                "96000000-0000-4000-8000-000000000011",
                rootOperationVariant(template, ordinal = 1, clientSequence = 1),
                PushOperationErrorCode.CLIENT_SEQUENCE_COLLISION,
            ),
            Triple(
                "96000000-0000-4000-8000-000000000012",
                rootOperationVariant(
                    template,
                    ordinal = 1,
                    captureId = first.captureId,
                ),
                PushOperationErrorCode.CAPTURE_ID_COLLISION,
            ),
            Triple(
                "96000000-0000-4000-8000-000000000013",
                rootOperationVariant(
                    template,
                    ordinal = 1,
                    revisionId = first.revisionId,
                ),
                PushOperationErrorCode.REVISION_ID_COLLISION,
            ),
        )
        cases.forEach { (batchId, secondDocument, expectedError) ->
            val request = WireRequestCodec.decodePushBatch(
                StrictJson.canonicalBytes(
                    pushRequestWithOperations(batchId, listOf(template, secondDocument)),
                ),
            )
            val correct = pushCollisionResponse(request, expectedError)
            val decoded = WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(correct),
                PushResponseExpectation(request),
            ) as PushBatchSuccess
            assertEquals(expectedError, (decoded.results[1] as PushOperationError).errorCode)

            val wrong = pushCollisionResponse(
                request,
                PushOperationErrorCode.OWNERSHIP_VIOLATION,
            )
            assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
                WireResponseCodec.decode(
                    200,
                    StrictJson.canonicalBytes(wrong),
                    PushResponseExpectation(request),
                )
            }
        }

        cases.forEachIndexed { index, (_, secondDocument, expectedError) ->
            val thirdDocument = WireTestFixtures.withProperty(
                secondDocument,
                "ordinal",
                2.asJson(),
            )
            val request = WireRequestCodec.decodePushBatch(
                StrictJson.canonicalBytes(
                    pushRequestWithOperations(
                        "96000000-0000-4000-8000-00000000002${index + 1}",
                        listOf(template, secondDocument, thirdDocument),
                    ),
                ),
            )
            val decoded = WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(
                    pushAckThenErrorsResponse(
                        request,
                        listOf(
                            expectedError,
                            PushOperationErrorCode.OPERATION_ID_COLLISION,
                        ),
                    ),
                ),
                PushResponseExpectation(request),
            ) as PushBatchSuccess
            assertEquals(
                listOf(expectedError, PushOperationErrorCode.OPERATION_ID_COLLISION),
                decoded.results.drop(1).map { (it as PushOperationError).errorCode },
            )
        }
    }

    @Test
    fun preseedsRegistryClaimsFromLaterPhysicalReplayAcks() {
        val template = WireRequestCodec.decodePushBatch(
            WireTestFixtures.bytes("sync-push-batch-request.json"),
        ).operations.first().document
        val laterReplay = rootOperationVariant(
            template = template,
            ordinal = 1,
            clientSequence = 90,
            operationId = "95000000-0000-4000-8000-000000000090",
            captureId = "94000000-0000-4000-8000-000000000090",
            eventId = "92000000-0000-4000-8000-000000000090",
            revisionId = "93000000-0000-4000-8000-000000000090",
        )
        val earlierCaptureCollision = rootOperationVariant(
            template = template,
            ordinal = 0,
            clientSequence = 91,
            operationId = "95000000-0000-4000-8000-000000000091",
            captureId = "94000000-0000-4000-8000-000000000090",
            eventId = "92000000-0000-4000-8000-000000000091",
            revisionId = "93000000-0000-4000-8000-000000000091",
        )
        val earlierClientCollision = rootOperationVariant(
            template = template,
            ordinal = 0,
            clientSequence = 90,
            operationId = "95000000-0000-4000-8000-000000000092",
            captureId = "94000000-0000-4000-8000-000000000092",
            eventId = "92000000-0000-4000-8000-000000000092",
            revisionId = "93000000-0000-4000-8000-000000000092",
        )
        listOf(
            Triple(
                "96000000-0000-4000-8000-000000000090",
                earlierCaptureCollision,
                PushOperationErrorCode.CAPTURE_ID_COLLISION,
            ),
            Triple(
                "96000000-0000-4000-8000-000000000091",
                earlierClientCollision,
                PushOperationErrorCode.CLIENT_SEQUENCE_COLLISION,
            ),
        ).forEach { (batchId, earlier, expectedCode) ->
            val request = WireRequestCodec.decodePushBatch(
                StrictJson.canonicalBytes(
                    pushRequestWithOperations(batchId, listOf(earlier, laterReplay)),
                ),
            )
            val replayAck = pushAckDocument(
                request.operations[1],
                ordinal = 1,
                resultCode = PushResultCode.APPLIED,
                replayed = true,
                sequence = 2,
                currentRevisionId = request.operations[1].revisionId,
            )
            val correct = pushResponseWithResults(
                request,
                listOf(
                    pushErrorDocument(request.operations[0], 0, expectedCode),
                    replayAck,
                ),
            )
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(correct),
                PushResponseExpectation(request),
            )

            val laterCode = if (expectedCode == PushOperationErrorCode.CLIENT_SEQUENCE_COLLISION) {
                PushOperationErrorCode.CAPTURE_ID_COLLISION
            } else {
                PushOperationErrorCode.REVISION_ID_COLLISION
            }
            assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
                WireResponseCodec.decode(
                    200,
                    StrictJson.canonicalBytes(
                        pushResponseWithResults(
                            request,
                            listOf(
                                pushErrorDocument(request.operations[0], 0, laterCode),
                                replayAck,
                            ),
                        ),
                    ),
                    PushResponseExpectation(request),
                )
            }
        }

        val contradictory = WireRequestCodec.decodePushBatch(
            StrictJson.canonicalBytes(
                pushRequestWithOperations(
                    "96000000-0000-4000-8000-000000000092",
                    listOf(earlierCaptureCollision, laterReplay),
                ),
            ),
        )
        assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(
                    pushAppliedAckResponse(
                        contradictory,
                        sequences = listOf(1L, 2L),
                        replayed = listOf(true, true),
                    ),
                ),
                PushResponseExpectation(contradictory),
            )
        }
    }

    @Test
    fun resolvesUnknownRegistryOwnerOnlyAfterALaterOperationPassesGate() {
        val template = WireRequestCodec.decodePushBatch(
            WireTestFixtures.bytes("sync-push-batch-request.json"),
        ).operations.first().document
        val clientOwner = rootOperationVariant(
            template = template,
            ordinal = 1,
            clientSequence = 100,
            operationId = "95000000-0000-4000-8000-000000000100",
            captureId = "94000000-0000-4000-8000-000000000100",
            eventId = "92000000-0000-4000-8000-000000000100",
            revisionId = "93000000-0000-4000-8000-000000000100",
        )
        val captureOwner = rootOperationVariant(
            template = template,
            ordinal = 1,
            clientSequence = 102,
            operationId = "95000000-0000-4000-8000-000000000102",
            captureId = "94000000-0000-4000-8000-000000000102",
            eventId = "92000000-0000-4000-8000-000000000102",
            revisionId = "93000000-0000-4000-8000-000000000102",
        )
        val revisionOwner = rootOperationVariant(
            template = template,
            ordinal = 1,
            clientSequence = 104,
            operationId = "95000000-0000-4000-8000-000000000104",
            captureId = "94000000-0000-4000-8000-000000000104",
            eventId = "92000000-0000-4000-8000-000000000104",
            revisionId = "93000000-0000-4000-8000-000000000104",
        )
        val cases = listOf(
            Triple(
                PushOperationErrorCode.CLIENT_SEQUENCE_COLLISION,
                rootOperationVariant(
                    template = template,
                    ordinal = 0,
                    clientSequence = 100,
                    operationId = "95000000-0000-4000-8000-000000000101",
                    captureId = "94000000-0000-4000-8000-000000000101",
                    eventId = "92000000-0000-4000-8000-000000000101",
                    revisionId = "93000000-0000-4000-8000-000000000101",
                ),
                clientOwner,
            ),
            Triple(
                PushOperationErrorCode.CAPTURE_ID_COLLISION,
                rootOperationVariant(
                    template = template,
                    ordinal = 0,
                    clientSequence = 103,
                    operationId = "95000000-0000-4000-8000-000000000103",
                    captureId = "94000000-0000-4000-8000-000000000102",
                    eventId = "92000000-0000-4000-8000-000000000103",
                    revisionId = "93000000-0000-4000-8000-000000000103",
                ),
                captureOwner,
            ),
            Triple(
                PushOperationErrorCode.REVISION_ID_COLLISION,
                rootOperationVariant(
                    template = template,
                    ordinal = 0,
                    clientSequence = 105,
                    operationId = "95000000-0000-4000-8000-000000000105",
                    captureId = "94000000-0000-4000-8000-000000000105",
                    eventId = "92000000-0000-4000-8000-000000000105",
                    revisionId = "93000000-0000-4000-8000-000000000104",
                ),
                revisionOwner,
            ),
        )
        cases.forEachIndexed { index, (collisionCode, colliding, owner) ->
            val invalidOwnerRequest = WireRequestCodec.decodePushBatch(
                StrictJson.canonicalBytes(
                    pushRequestWithOperations(
                        "96000000-0000-4000-8000-00000000010${index * 2}",
                        listOf(colliding, selfParentOperation(owner)),
                    ),
                ),
            )
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(
                    pushResponseWithResults(
                        invalidOwnerRequest,
                        listOf(
                            pushErrorDocument(
                                invalidOwnerRequest.operations[0],
                                0,
                                collisionCode,
                            ),
                            pushErrorDocument(
                                invalidOwnerRequest.operations[1],
                                1,
                                PushOperationErrorCode.INVALID_PARENT,
                            ),
                        ),
                    ),
                ),
                PushResponseExpectation(invalidOwnerRequest),
            )

            val impossibleFreshRootRequest = WireRequestCodec.decodePushBatch(
                StrictJson.canonicalBytes(
                    pushRequestWithOperations(
                        "96000000-0000-4000-8000-00000000010${index * 2 + 1}",
                        listOf(colliding, owner),
                    ),
                ),
            )
            assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
                WireResponseCodec.decode(
                    200,
                    StrictJson.canonicalBytes(
                        pushResponseWithResults(
                            impossibleFreshRootRequest,
                            listOf(
                                pushErrorDocument(
                                    impossibleFreshRootRequest.operations[0],
                                    0,
                                    collisionCode,
                                ),
                                pushAckDocument(
                                    impossibleFreshRootRequest.operations[1],
                                    ordinal = 1,
                                    resultCode = PushResultCode.APPLIED,
                                    replayed = false,
                                    sequence = 100L + index,
                                    currentRevisionId =
                                        impossibleFreshRootRequest.operations[1].revisionId,
                                ),
                            ),
                        ),
                    ),
                    PushResponseExpectation(impossibleFreshRootRequest),
                )
            }

            val pendingChildOwner = childOperationVariant(
                template = template,
                ordinal = 1,
                clientSequence = owner.requireInteger(
                    "client_sequence",
                    0L..JSON_SAFE_INTEGER_MAX,
                ),
                operationId = owner.requireString("operation_id"),
                captureId = owner.requireString("capture_id"),
                eventId = owner.requireString("event_id"),
                revisionId = owner.requireString("revision_id"),
                parentRevisionId = "93000000-0000-4000-8000-00000000012$index",
            )
            val pendingOwnerRequest = WireRequestCodec.decodePushBatch(
                StrictJson.canonicalBytes(
                    pushRequestWithOperations(
                        "96000000-0000-4000-8000-00000000012$index",
                        listOf(colliding, pendingChildOwner),
                    ),
                ),
            )
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(
                    pushResponseWithResults(
                        pendingOwnerRequest,
                        listOf(
                            pushErrorDocument(
                                pendingOwnerRequest.operations[0],
                                0,
                                collisionCode,
                            ),
                            pushAckDocument(
                                pendingOwnerRequest.operations[1],
                                ordinal = 1,
                                resultCode = PushResultCode.APPLIED,
                                replayed = false,
                                sequence = 103L + index,
                                currentRevisionId = pendingOwnerRequest.operations[1].revisionId,
                            ),
                        ),
                    ),
                ),
                PushResponseExpectation(pendingOwnerRequest),
            )
        }

        val capturePassThrough = rootOperationVariant(
            template = template,
            ordinal = 1,
            clientSequence = 103,
            operationId = "95000000-0000-4000-8000-000000000108",
            captureId = "94000000-0000-4000-8000-000000000108",
            eventId = "92000000-0000-4000-8000-000000000108",
            revisionId = "93000000-0000-4000-8000-000000000108",
        )
        val revisionPassThrough = rootOperationVariant(
            template = template,
            ordinal = 1,
            clientSequence = 105,
            operationId = "95000000-0000-4000-8000-000000000109",
            captureId = "94000000-0000-4000-8000-000000000105",
            eventId = "92000000-0000-4000-8000-000000000109",
            revisionId = "93000000-0000-4000-8000-000000000109",
        )
        listOf(
            Triple(cases[1].second, capturePassThrough, cases[1].first),
            Triple(cases[2].second, revisionPassThrough, cases[2].first),
        ).forEachIndexed { index, (colliding, laterFresh, collisionCode) ->
            val request = WireRequestCodec.decodePushBatch(
                StrictJson.canonicalBytes(
                    pushRequestWithOperations(
                        "96000000-0000-4000-8000-00000000010${index + 8}",
                        listOf(colliding, laterFresh),
                    ),
                ),
            )
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(
                    pushResponseWithResults(
                        request,
                        listOf(
                            pushErrorDocument(request.operations[0], 0, collisionCode),
                            pushAckDocument(
                                request.operations[1],
                                ordinal = 1,
                                resultCode = PushResultCode.APPLIED,
                                replayed = false,
                                sequence = 108L + index,
                                currentRevisionId = request.operations[1].revisionId,
                            ),
                        ),
                    ),
                ),
                PushResponseExpectation(request),
            )
        }

        val clientBarrierFollower = rootOperationVariant(
            template = template,
            ordinal = 1,
            clientSequence = 100,
            operationId = "95000000-0000-4000-8000-000000000106",
            captureId = "94000000-0000-4000-8000-000000000106",
            eventId = "92000000-0000-4000-8000-000000000106",
            revisionId = "93000000-0000-4000-8000-000000000106",
        )
        val clientBarrierRequest = WireRequestCodec.decodePushBatch(
            StrictJson.canonicalBytes(
                pushRequestWithOperations(
                    "96000000-0000-4000-8000-000000000106",
                    listOf(cases[0].second, clientBarrierFollower),
                ),
            ),
        )
        assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(
                    pushErrorResponse(
                        clientBarrierRequest,
                        listOf(
                            PushOperationErrorCode.CLIENT_SEQUENCE_COLLISION,
                            PushOperationErrorCode.CAPTURE_ID_COLLISION,
                        ),
                    ),
                ),
                PushResponseExpectation(clientBarrierRequest),
            )
        }

        val captureBarrierFollower = rootOperationVariant(
            template = template,
            ordinal = 1,
            clientSequence = 107,
            operationId = "95000000-0000-4000-8000-000000000107",
            captureId = "94000000-0000-4000-8000-000000000102",
            eventId = "92000000-0000-4000-8000-000000000107",
            revisionId = "93000000-0000-4000-8000-000000000107",
        )
        val captureBarrierRequest = WireRequestCodec.decodePushBatch(
            StrictJson.canonicalBytes(
                pushRequestWithOperations(
                    "96000000-0000-4000-8000-000000000107",
                    listOf(cases[1].second, captureBarrierFollower),
                ),
            ),
        )
        assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(
                    pushErrorResponse(
                        captureBarrierRequest,
                        listOf(
                            PushOperationErrorCode.CAPTURE_ID_COLLISION,
                            PushOperationErrorCode.REVISION_ID_COLLISION,
                        ),
                    ),
                ),
                PushResponseExpectation(captureBarrierRequest),
            )
        }
    }

    @Test
    fun rejectsImpossibleIntraBatchEventAndHeadOutcomes() {
        val frozenRequest = WireRequestCodec.decodePushBatch(
            WireTestFixtures.bytes("sync-push-batch-request.json"),
        )
        val template = frozenRequest.operations.first().document
        val secondRoot = rootOperationVariant(
            template,
            ordinal = 1,
            clientSequence = 20,
            operationId = "95000000-0000-4000-8000-000000000040",
            captureId = "94000000-0000-4000-8000-000000000040",
            eventId = frozenRequest.operations.first().eventId,
            revisionId = "93000000-0000-4000-8000-000000000040",
        )
        val roots = WireRequestCodec.decodePushBatch(
            StrictJson.canonicalBytes(
                pushRequestWithOperations(
                    "96000000-0000-4000-8000-000000000040",
                    listOf(template, secondRoot),
                ),
            ),
        )
        val bothApplied = pushAppliedAckResponse(
            roots,
            sequences = listOf(20L, 21L),
            replayed = listOf(false, false),
        )
        assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(bothApplied),
                PushResponseExpectation(roots),
            )
        }
        assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(
                    pushAppliedAckResponse(
                        roots,
                        sequences = listOf(20L, 21L),
                        replayed = listOf(true, true),
                    ),
                ),
                PushResponseExpectation(roots),
            )
        }
        val firstRootAck = bothApplied.requireArray("results").elements.first()
        val eventCollision = pushResponseWithResults(
            roots,
            listOf(
                firstRootAck,
                pushErrorDocument(
                    roots.operations[1],
                    1,
                    PushOperationErrorCode.EVENT_ID_COLLISION,
                ),
            ),
        )
        WireResponseCodec.decode(
            200,
            StrictJson.canonicalBytes(eventCollision),
            PushResponseExpectation(roots),
        )

        val singleRoot = WireRequestCodec.decodePushBatch(
            StrictJson.canonicalBytes(
                pushRequestWithOperations(
                    "96000000-0000-4000-8000-000000000041",
                    listOf(template),
                ),
            ),
        )
        listOf(
            PushOperationErrorCode.MISSING_PARENT,
            PushOperationErrorCode.INVALID_PARENT,
        ).forEach { code ->
            assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
                WireResponseCodec.decode(
                    200,
                    StrictJson.canonicalBytes(pushErrorResponse(singleRoot, listOf(code))),
                    PushResponseExpectation(singleRoot),
                )
            }
        }
        val rootApplied = pushAppliedAckResponse(
            singleRoot,
            sequences = listOf(30L),
            replayed = listOf(false),
        )
        val rootAck = rootApplied.requireArray("results").elements.single() as WireJsonObject
        val rootConflict = WireTestFixtures.withProperty(
            WireTestFixtures.withProperty(
                rootAck,
                "result_code",
                PushResultCode.CONFLICT.wireName.asJson(),
            ),
            "current_revision_id",
            "93000000-0000-4000-8000-000000000099".asJson(),
        )
        assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(
                    pushResponseWithResults(singleRoot, listOf(rootConflict)),
                ),
                PushResponseExpectation(singleRoot),
            )
        }

        listOf(
            selfParentOperation(template),
            expectedCurrentMismatchOperation(template),
        ).forEachIndexed { index, invalidOperation ->
            val request = WireRequestCodec.decodePushBatch(
                StrictJson.canonicalBytes(
                    pushRequestWithOperations(
                        "96000000-0000-4000-8000-00000000004${index + 2}",
                        listOf(invalidOperation),
                    ),
                ),
            )
            assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
                WireResponseCodec.decode(
                    200,
                    StrictJson.canonicalBytes(
                        pushAppliedAckResponse(
                            request,
                            sequences = listOf(40L + index),
                            replayed = listOf(false),
                        ),
                    ),
                    PushResponseExpectation(request),
                )
            }
            assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
                WireResponseCodec.decode(
                    200,
                    StrictJson.canonicalBytes(
                        pushErrorResponse(
                            request,
                            listOf(PushOperationErrorCode.MISSING_PARENT),
                        ),
                    ),
                    PushResponseExpectation(request),
                )
            }
        }

        val canonicalRoot = WireTestFixtures.objectFrom("sync-push-batch-response.json")
        val canonicalResults = canonicalRoot.requireArray("results").elements
        val sibling = frozenRequest.operations[2]
        val appliedSibling = WireTestFixtures.withProperty(
            WireTestFixtures.withProperty(
                canonicalResults[2] as WireJsonObject,
                "result_code",
                PushResultCode.APPLIED.wireName.asJson(),
            ),
            "current_revision_id",
            sibling.revisionId.asJson(),
        )
        assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(
                    WireTestFixtures.withProperty(
                        canonicalRoot,
                        "results",
                        WireJsonArray(
                            listOf(canonicalResults[0], canonicalResults[1], appliedSibling),
                        ),
                    ),
                ),
                PushResponseExpectation(frozenRequest),
            )
        }
        val missingParentResponse = WireTestFixtures.withProperty(
            canonicalRoot,
            "results",
            WireJsonArray(
                listOf(
                    canonicalResults[0],
                    canonicalResults[1],
                    pushErrorDocument(
                        sibling,
                        2,
                        PushOperationErrorCode.MISSING_PARENT,
                    ),
                ),
            ),
        )
        assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(missingParentResponse),
                PushResponseExpectation(frozenRequest),
            )
        }
        listOf(
            PushOperationErrorCode.EVENT_ID_COLLISION,
            PushOperationErrorCode.INVALID_PARENT,
        ).forEach { code ->
            val response = WireTestFixtures.withProperty(
                canonicalRoot,
                "results",
                WireJsonArray(
                    listOf(
                        canonicalResults[0],
                        canonicalResults[1],
                        pushErrorDocument(sibling, 2, code),
                    ),
                ),
            )
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(response),
                PushResponseExpectation(frozenRequest),
            )
        }
    }

    @Test
    fun handlesOpaqueTerminalReceiptsAndRejectsCrossWiredParentFacts() {
        val template = WireRequestCodec.decodePushBatch(
            WireTestFixtures.bytes("sync-push-batch-request.json"),
        ).operations.first().document
        val eventId = "92000000-0000-4000-8000-000000000050"
        val firstChild = childOperationVariant(
            template = template,
            ordinal = 0,
            clientSequence = 50,
            operationId = "95000000-0000-4000-8000-000000000050",
            captureId = "94000000-0000-4000-8000-000000000050",
            eventId = eventId,
            revisionId = "93000000-0000-4000-8000-000000000050",
            parentRevisionId = "93000000-0000-4000-8000-000000000080",
        )
        val secondChild = childOperationVariant(
            template = template,
            ordinal = 1,
            clientSequence = 51,
            operationId = "95000000-0000-4000-8000-000000000051",
            captureId = "94000000-0000-4000-8000-000000000051",
            eventId = eventId,
            revisionId = "93000000-0000-4000-8000-000000000051",
            parentRevisionId = "93000000-0000-4000-8000-000000000081",
        )
        val incompatibleRequest = WireRequestCodec.decodePushBatch(
            StrictJson.canonicalBytes(
                pushRequestWithOperations(
                    "96000000-0000-4000-8000-000000000050",
                    listOf(firstChild, secondChild),
                ),
            ),
        )
        val firstEventCollision = pushErrorDocument(
            incompatibleRequest.operations[0],
            0,
            PushOperationErrorCode.EVENT_ID_COLLISION,
        )
        val secondMissing = pushErrorDocument(
            incompatibleRequest.operations[1],
            1,
            PushOperationErrorCode.MISSING_PARENT,
        )
        WireResponseCodec.decode(
            200,
            StrictJson.canonicalBytes(
                pushResponseWithResults(
                    incompatibleRequest,
                    listOf(firstEventCollision, secondMissing),
                ),
            ),
            PushResponseExpectation(incompatibleRequest),
        )
        val laterEventCollision = pushErrorDocument(
            incompatibleRequest.operations[1],
            1,
            PushOperationErrorCode.EVENT_ID_COLLISION,
        )
        WireResponseCodec.decode(
            200,
            StrictJson.canonicalBytes(
                pushResponseWithResults(
                    incompatibleRequest,
                    listOf(
                        pushErrorDocument(
                            incompatibleRequest.operations[0],
                            0,
                            PushOperationErrorCode.INVALID_PARENT,
                        ),
                        laterEventCollision,
                    ),
                ),
            ),
            PushResponseExpectation(incompatibleRequest),
        )
        val secondAck = pushAckDocument(
            incompatibleRequest.operations[1],
            ordinal = 1,
            resultCode = PushResultCode.APPLIED,
            replayed = false,
            sequence = 51,
            currentRevisionId = incompatibleRequest.operations[1].revisionId,
        )
        WireResponseCodec.decode(
            200,
            StrictJson.canonicalBytes(
                pushResponseWithResults(
                    incompatibleRequest,
                    listOf(firstEventCollision, secondAck),
                ),
            ),
            PushResponseExpectation(incompatibleRequest),
        )
        WireResponseCodec.decode(
            200,
            StrictJson.canonicalBytes(
                pushResponseWithResults(
                    incompatibleRequest,
                    listOf(
                        pushErrorDocument(
                            incompatibleRequest.operations[0],
                            0,
                            PushOperationErrorCode.MISSING_PARENT,
                        ),
                        laterEventCollision,
                    ),
                ),
            ),
            PushResponseExpectation(incompatibleRequest),
        )

        val headEventId = "92000000-0000-4000-8000-000000000060"
        val currentHead = "93000000-0000-4000-8000-000000000090"
        val headSource = childOperationVariant(
            template = template,
            ordinal = 0,
            clientSequence = 60,
            operationId = "95000000-0000-4000-8000-000000000060",
            captureId = "94000000-0000-4000-8000-000000000060",
            eventId = headEventId,
            revisionId = "93000000-0000-4000-8000-000000000060",
            parentRevisionId = "93000000-0000-4000-8000-000000000082",
        )
        val crossEventChild = childOperationVariant(
            template = template,
            ordinal = 1,
            clientSequence = 61,
            operationId = "95000000-0000-4000-8000-000000000061",
            captureId = "94000000-0000-4000-8000-000000000061",
            eventId = "92000000-0000-4000-8000-000000000061",
            revisionId = "93000000-0000-4000-8000-000000000061",
            parentRevisionId = currentHead,
        )
        val crossEventRequest = WireRequestCodec.decodePushBatch(
            StrictJson.canonicalBytes(
                pushRequestWithOperations(
                    "96000000-0000-4000-8000-000000000051",
                    listOf(headSource, crossEventChild),
                ),
            ),
        )
        val headConflict = pushAckDocument(
            crossEventRequest.operations[0],
            ordinal = 0,
            resultCode = PushResultCode.CONFLICT,
            replayed = false,
            sequence = 60,
            currentRevisionId = currentHead,
        )
        listOf(
            pushErrorDocument(
                crossEventRequest.operations[1],
                1,
                PushOperationErrorCode.MISSING_PARENT,
            ),
            pushAckDocument(
                crossEventRequest.operations[1],
                ordinal = 1,
                resultCode = PushResultCode.APPLIED,
                replayed = false,
                sequence = 61,
                currentRevisionId = crossEventRequest.operations[1].revisionId,
            ),
        ).forEach { impossible ->
            assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
                WireResponseCodec.decode(
                    200,
                    StrictJson.canonicalBytes(
                        pushResponseWithResults(
                            crossEventRequest,
                            listOf(headConflict, impossible),
                        ),
                    ),
                    PushResponseExpectation(crossEventRequest),
                )
            }
        }
        WireResponseCodec.decode(
            200,
            StrictJson.canonicalBytes(
                pushResponseWithResults(
                    crossEventRequest,
                    listOf(
                        headConflict,
                        pushErrorDocument(
                            crossEventRequest.operations[1],
                            1,
                            PushOperationErrorCode.INVALID_PARENT,
                        ),
                    ),
                ),
            ),
            PushResponseExpectation(crossEventRequest),
        )

        val matchingCasConflict = pushAckDocument(
            incompatibleRequest.operations[0],
            ordinal = 0,
            resultCode = PushResultCode.CONFLICT,
            replayed = false,
            sequence = 70,
            currentRevisionId = incompatibleRequest.operations[0]
                .expectedCurrentRevisionId ?: error("missing expected revision"),
        )
        val singleChildRequest = WireRequestCodec.decodePushBatch(
            StrictJson.canonicalBytes(
                pushRequestWithOperations(
                    "96000000-0000-4000-8000-000000000052",
                    listOf(firstChild),
                ),
            ),
        )
        assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(
                    pushResponseWithResults(singleChildRequest, listOf(matchingCasConflict)),
                ),
                PushResponseExpectation(singleChildRequest),
            )
        }

        val sharedParent = "93000000-0000-4000-8000-000000000095"
        val replayA = childOperationVariant(
            template,
            0,
            70,
            "95000000-0000-4000-8000-000000000070",
            "94000000-0000-4000-8000-000000000070",
            "92000000-0000-4000-8000-000000000070",
            "93000000-0000-4000-8000-000000000070",
            sharedParent,
        )
        val replayB = childOperationVariant(
            template,
            1,
            71,
            "95000000-0000-4000-8000-000000000071",
            "94000000-0000-4000-8000-000000000071",
            "92000000-0000-4000-8000-000000000071",
            "93000000-0000-4000-8000-000000000071",
            sharedParent,
        )
        val replayRequest = WireRequestCodec.decodePushBatch(
            StrictJson.canonicalBytes(
                pushRequestWithOperations(
                    "96000000-0000-4000-8000-000000000053",
                    listOf(replayA, replayB),
                ),
            ),
        )
        assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(
                    pushAppliedAckResponse(
                        replayRequest,
                        sequences = listOf(70L, 71L),
                        replayed = listOf(true, true),
                    ),
                ),
                PushResponseExpectation(replayRequest),
            )
        }
    }

    @Test
    fun rejectsFreshDependencyCreatedOnlyAtALaterOrdinal() {
        val template = WireRequestCodec.decodePushBatch(
            WireTestFixtures.bytes("sync-push-batch-request.json"),
        ).operations.first().document
        val eventId = "92000000-0000-4000-8000-000000000080"
        val rootRevisionId = "93000000-0000-4000-8000-000000000180"
        val parentRevisionId = "93000000-0000-4000-8000-000000000181"
        val dependentRevisionId = "93000000-0000-4000-8000-000000000182"
        val dependent = childOperationVariant(
            template = template,
            ordinal = 0,
            clientSequence = 80,
            operationId = "95000000-0000-4000-8000-000000000080",
            captureId = "94000000-0000-4000-8000-000000000080",
            eventId = eventId,
            revisionId = dependentRevisionId,
            parentRevisionId = parentRevisionId,
            revisionNo = 3,
        )
        val parent = childOperationVariant(
            template = template,
            ordinal = 1,
            clientSequence = 81,
            operationId = "95000000-0000-4000-8000-000000000081",
            captureId = "94000000-0000-4000-8000-000000000081",
            eventId = eventId,
            revisionId = parentRevisionId,
            parentRevisionId = rootRevisionId,
        )
        val request = WireRequestCodec.decodePushBatch(
            StrictJson.canonicalBytes(
                pushRequestWithOperations(
                    "96000000-0000-4000-8000-000000000080",
                    listOf(dependent, parent),
                ),
            ),
        )
        val dependentAck = pushAckDocument(
            request.operations[0],
            ordinal = 0,
            resultCode = PushResultCode.APPLIED,
            replayed = false,
            sequence = 80,
            currentRevisionId = dependentRevisionId,
        )
        val laterFreshParent = pushAckDocument(
            request.operations[1],
            ordinal = 1,
            resultCode = PushResultCode.CONFLICT,
            replayed = false,
            sequence = 81,
            currentRevisionId = dependentRevisionId,
        )
        assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(
                    pushResponseWithResults(
                        request,
                        listOf(dependentAck, laterFreshParent),
                    ),
                ),
                PushResponseExpectation(request),
            )
        }
        assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(
                    pushResponseWithResults(
                        request,
                        listOf(
                            pushErrorDocument(
                                request.operations[0],
                                0,
                                PushOperationErrorCode.INVALID_PARENT,
                            ),
                            laterFreshParent,
                        ),
                    ),
                ),
                PushResponseExpectation(request),
            )
        }

        val laterReplayedParent = pushAckDocument(
            request.operations[1],
            ordinal = 1,
            resultCode = PushResultCode.APPLIED,
            replayed = true,
            sequence = 2,
            currentRevisionId = parentRevisionId,
        )
        val accepted = WireResponseCodec.decode(
            200,
            StrictJson.canonicalBytes(
                pushResponseWithResults(
                    request,
                    listOf(dependentAck, laterReplayedParent),
                ),
            ),
            PushResponseExpectation(request),
        ) as PushBatchSuccess
        assertEquals(listOf(80L, 2L), accepted.results.map { (it as PushOperationAck).serverSequence })
    }

    @Test
    fun rejectsFreshRevisionThatAReplayAckProvesExistedPreBatch() {
        val template = WireRequestCodec.decodePushBatch(
            WireTestFixtures.bytes("sync-push-batch-request.json"),
        ).operations.first().document
        val parentProofEventId = "92000000-0000-4000-8000-000000000090"
        val preParentRevisionId = "93000000-0000-4000-8000-000000000190"
        val freshParentRevisionId = "93000000-0000-4000-8000-000000000191"
        val replayedChildRevisionId = "93000000-0000-4000-8000-000000000192"
        val freshParent = childOperationVariant(
            template = template,
            ordinal = 0,
            clientSequence = 90,
            operationId = "95000000-0000-4000-8000-000000000090",
            captureId = "94000000-0000-4000-8000-000000000090",
            eventId = parentProofEventId,
            revisionId = freshParentRevisionId,
            parentRevisionId = preParentRevisionId,
        )
        val replayedChild = childOperationVariant(
            template = template,
            ordinal = 1,
            clientSequence = 91,
            operationId = "95000000-0000-4000-8000-000000000091",
            captureId = "94000000-0000-4000-8000-000000000091",
            eventId = parentProofEventId,
            revisionId = replayedChildRevisionId,
            parentRevisionId = freshParentRevisionId,
            revisionNo = 3,
        )
        val parentProofRequest = WireRequestCodec.decodePushBatch(
            StrictJson.canonicalBytes(
                pushRequestWithOperations(
                    "96000000-0000-4000-8000-000000000090",
                    listOf(freshParent, replayedChild),
                ),
            ),
        )
        assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(
                    pushResponseWithResults(
                        parentProofRequest,
                        listOf(
                            pushAckDocument(
                                parentProofRequest.operations[0],
                                ordinal = 0,
                                resultCode = PushResultCode.APPLIED,
                                replayed = false,
                                sequence = 90,
                                currentRevisionId = freshParentRevisionId,
                            ),
                            pushAckDocument(
                                parentProofRequest.operations[1],
                                ordinal = 1,
                                resultCode = PushResultCode.APPLIED,
                                replayed = true,
                                sequence = 2,
                                currentRevisionId = replayedChildRevisionId,
                            ),
                        ),
                    ),
                ),
                PushResponseExpectation(parentProofRequest),
            )
        }

        val headProofEventId = "92000000-0000-4000-8000-000000000092"
        val freshHeadRevisionId = "93000000-0000-4000-8000-000000000193"
        val freshHead = childOperationVariant(
            template = template,
            ordinal = 0,
            clientSequence = 92,
            operationId = "95000000-0000-4000-8000-000000000092",
            captureId = "94000000-0000-4000-8000-000000000092",
            eventId = headProofEventId,
            revisionId = freshHeadRevisionId,
            parentRevisionId = "93000000-0000-4000-8000-000000000194",
        )
        val replayedConflict = childOperationVariant(
            template = template,
            ordinal = 1,
            clientSequence = 93,
            operationId = "95000000-0000-4000-8000-000000000093",
            captureId = "94000000-0000-4000-8000-000000000093",
            eventId = headProofEventId,
            revisionId = "93000000-0000-4000-8000-000000000195",
            parentRevisionId = "93000000-0000-4000-8000-000000000196",
        )
        val headProofRequest = WireRequestCodec.decodePushBatch(
            StrictJson.canonicalBytes(
                pushRequestWithOperations(
                    "96000000-0000-4000-8000-000000000091",
                    listOf(freshHead, replayedConflict),
                ),
            ),
        )
        assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(
                    pushResponseWithResults(
                        headProofRequest,
                        listOf(
                            pushAckDocument(
                                headProofRequest.operations[0],
                                ordinal = 0,
                                resultCode = PushResultCode.APPLIED,
                                replayed = false,
                                sequence = 92,
                                currentRevisionId = freshHeadRevisionId,
                            ),
                            pushAckDocument(
                                headProofRequest.operations[1],
                                ordinal = 1,
                                resultCode = PushResultCode.CONFLICT,
                                replayed = true,
                                sequence = 3,
                                currentRevisionId = freshHeadRevisionId,
                            ),
                        ),
                    ),
                ),
                PushResponseExpectation(headProofRequest),
            )
        }
    }

    @Test
    fun rejectsMissingParentContradictedByLaterFreshAckDependencies() {
        val template = WireRequestCodec.decodePushBatch(
            WireTestFixtures.bytes("sync-push-batch-request.json"),
        ).operations.first().document
        val eventId = "92000000-0000-4000-8000-000000000110"
        val missingParentRevisionId = "93000000-0000-4000-8000-000000000210"
        val missingChild = childOperationVariant(
            template = template,
            ordinal = 0,
            clientSequence = 110,
            operationId = "95000000-0000-4000-8000-000000000110",
            captureId = "94000000-0000-4000-8000-000000000110",
            eventId = eventId,
            revisionId = "93000000-0000-4000-8000-000000000211",
            parentRevisionId = missingParentRevisionId,
        )
        val laterChild = childOperationVariant(
            template = template,
            ordinal = 1,
            clientSequence = 111,
            operationId = "95000000-0000-4000-8000-000000000111",
            captureId = "94000000-0000-4000-8000-000000000111",
            eventId = eventId,
            revisionId = "93000000-0000-4000-8000-000000000212",
            parentRevisionId = missingParentRevisionId,
        )
        val parentDependencyRequest = WireRequestCodec.decodePushBatch(
            StrictJson.canonicalBytes(
                pushRequestWithOperations(
                    "96000000-0000-4000-8000-000000000110",
                    listOf(missingChild, laterChild),
                ),
            ),
        )
        assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(
                    pushResponseWithResults(
                        parentDependencyRequest,
                        listOf(
                            pushErrorDocument(
                                parentDependencyRequest.operations[0],
                                0,
                                PushOperationErrorCode.MISSING_PARENT,
                            ),
                            pushAckDocument(
                                parentDependencyRequest.operations[1],
                                ordinal = 1,
                                resultCode = PushResultCode.APPLIED,
                                replayed = false,
                                sequence = 110,
                                currentRevisionId = parentDependencyRequest.operations[1].revisionId,
                            ),
                        ),
                    ),
                ),
                PushResponseExpectation(parentDependencyRequest),
            )
        }

        val conflictChild = childOperationVariant(
            template = template,
            ordinal = 1,
            clientSequence = 112,
            operationId = "95000000-0000-4000-8000-000000000112",
            captureId = "94000000-0000-4000-8000-000000000112",
            eventId = eventId,
            revisionId = "93000000-0000-4000-8000-000000000213",
            parentRevisionId = "93000000-0000-4000-8000-000000000214",
        )
        val conflictDependencyRequest = WireRequestCodec.decodePushBatch(
            StrictJson.canonicalBytes(
                pushRequestWithOperations(
                    "96000000-0000-4000-8000-000000000111",
                    listOf(missingChild, conflictChild),
                ),
            ),
        )
        assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(
                    pushResponseWithResults(
                        conflictDependencyRequest,
                        listOf(
                            pushErrorDocument(
                                conflictDependencyRequest.operations[0],
                                0,
                                PushOperationErrorCode.MISSING_PARENT,
                            ),
                            pushAckDocument(
                                conflictDependencyRequest.operations[1],
                                ordinal = 1,
                                resultCode = PushResultCode.CONFLICT,
                                replayed = false,
                                sequence = 111,
                                currentRevisionId = missingParentRevisionId,
                            ),
                        ),
                    ),
                ),
                PushResponseExpectation(conflictDependencyRequest),
            )
        }
    }

    @Test
    fun rejectsAckRevisionFactsReservedByNoncommittingOperationResults() {
        fun uuid(prefix: String, value: Int): String =
            "$prefix-0000-4000-8000-${value.toString().padStart(12, '0')}"

        val template = WireRequestCodec.decodePushBatch(
            WireTestFixtures.bytes("sync-push-batch-request.json"),
        ).operations.first().document
        val errorCodes = listOf(
            PushOperationErrorCode.EVENT_ID_COLLISION,
            PushOperationErrorCode.MISSING_PARENT,
            PushOperationErrorCode.INVALID_PARENT,
        )
        errorCodes.forEachIndexed { index, errorCode ->
            val eventId = uuid("92000000", 230 + index)
            val claimedRevisionId = uuid("93000000", 230 + index)
            fun claimedOperation(ordinal: Int): WireJsonObject {
                val base = if (errorCode == PushOperationErrorCode.INVALID_PARENT) {
                    rootOperationVariant(
                        template = template,
                        ordinal = ordinal,
                        clientSequence = 230L + index,
                        operationId = uuid("95000000", 230 + index),
                        captureId = uuid("94000000", 230 + index),
                        eventId = eventId,
                        revisionId = claimedRevisionId,
                    ).let(::selfParentOperation)
                } else {
                    childOperationVariant(
                        template = template,
                        ordinal = ordinal,
                        clientSequence = 230L + index,
                        operationId = uuid("95000000", 230 + index),
                        captureId = uuid("94000000", 230 + index),
                        eventId = eventId,
                        revisionId = claimedRevisionId,
                        parentRevisionId = uuid("93000000", 240 + index),
                    )
                }
                return base
            }

            listOf(false, true).forEach { replayed ->
                val earlierClaim = claimedOperation(0)
                val laterDependency = childOperationVariant(
                    template = template,
                    ordinal = 1,
                    clientSequence = 250L + index,
                    operationId = uuid("95000000", 250 + index),
                    captureId = uuid("94000000", 250 + index),
                    eventId = eventId,
                    revisionId = uuid("93000000", 250 + index),
                    parentRevisionId = claimedRevisionId,
                    revisionNo = 3,
                )
                val earlierClaimRequest = WireRequestCodec.decodePushBatch(
                    StrictJson.canonicalBytes(
                        pushRequestWithOperations(
                            uuid("96000000", 230 + index),
                            listOf(earlierClaim, laterDependency),
                        ),
                    ),
                )
                assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
                    WireResponseCodec.decode(
                        200,
                        StrictJson.canonicalBytes(
                            pushResponseWithResults(
                                earlierClaimRequest,
                                listOf(
                                    pushErrorDocument(
                                        earlierClaimRequest.operations[0],
                                        0,
                                        errorCode,
                                    ),
                                    pushAckDocument(
                                        earlierClaimRequest.operations[1],
                                        ordinal = 1,
                                        resultCode = PushResultCode.APPLIED,
                                        replayed = replayed,
                                        sequence = if (replayed) 2 else 230L + index,
                                        currentRevisionId =
                                            earlierClaimRequest.operations[1].revisionId,
                                    ),
                                ),
                            ),
                        ),
                        PushResponseExpectation(earlierClaimRequest),
                    )
                }

                val earlierDependency = childOperationVariant(
                    template = template,
                    ordinal = 0,
                    clientSequence = 260L + index,
                    operationId = uuid("95000000", 260 + index),
                    captureId = uuid("94000000", 260 + index),
                    eventId = eventId,
                    revisionId = uuid("93000000", 260 + index),
                    parentRevisionId = uuid("93000000", 270 + index),
                )
                val laterClaim = claimedOperation(1)
                val laterClaimRequest = WireRequestCodec.decodePushBatch(
                    StrictJson.canonicalBytes(
                        pushRequestWithOperations(
                            uuid("96000000", 240 + index),
                            listOf(earlierDependency, laterClaim),
                        ),
                    ),
                )
                assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
                    WireResponseCodec.decode(
                        200,
                        StrictJson.canonicalBytes(
                            pushResponseWithResults(
                                laterClaimRequest,
                                listOf(
                                    pushAckDocument(
                                        laterClaimRequest.operations[0],
                                        ordinal = 0,
                                        resultCode = PushResultCode.CONFLICT,
                                        replayed = replayed,
                                        sequence = if (replayed) 3 else 240L + index,
                                        currentRevisionId = claimedRevisionId,
                                    ),
                                    pushErrorDocument(
                                        laterClaimRequest.operations[1],
                                        1,
                                        errorCode,
                                    ),
                                ),
                            ),
                        ),
                        PushResponseExpectation(laterClaimRequest),
                    )
                }
            }
        }
    }

    @Test
    fun validatesBootstrapPagesAsOneStableOrderedSnapshot() {
        val firstRequest = WireTestFixtures.bootstrapRequest()
        val beforeFirst = ReplicaStreamValidationState()
        val first = WireResponseCodec.decode(
            200,
            WireTestFixtures.bytes("sync-bootstrap-response.json"),
            BootstrapResponseExpectation(firstRequest, beforeFirst),
        ) as ValidatedBootstrapPage
        val exactRetryBeforeCommit = WireResponseCodec.decode(
            200,
            WireTestFixtures.bytes("sync-bootstrap-page-1-replay-response.json"),
            BootstrapResponseExpectation(firstRequest, beforeFirst),
        ) as ValidatedBootstrapPage
        assertEquals(2, first.page.changes.size)
        assertFalse(first.page.complete)
        assertEquals(2L, first.nextState.lastServerSequence)
        assertEquals(first.nextState.lastServerSequence, exactRetryBeforeCommit.nextState.lastServerSequence)
        assertEquals(BootstrapValidationPhase.IN_PROGRESS, first.nextState.bootstrapPhase)
        assertEquals(firstRequest.deviceId, first.nextState.receivingDeviceId)
        assertEquals(firstRequest.bootstrapId, first.nextState.activeBootstrapId)
        assertEquals(first.page.nextPageCursor, first.nextState.expectedBootstrapPageCursor)
        assertEquals(null, first.nextState.expectedPullCursor)
        assertEquals(setOf(firstRequest.requestId), first.nextState.seenSuccessfulBootstrapRequestIds)

        val secondRequest = WireTestFixtures.bootstrapRequest("sync-bootstrap-page-2-request.json")
        val second = WireResponseCodec.decode(
            200,
            WireTestFixtures.bytes("sync-bootstrap-page-2-response.json"),
            BootstrapResponseExpectation(secondRequest, first.nextState),
        ) as ValidatedBootstrapPage
        assertTrue(second.page.complete)
        assertEquals(3L, second.nextState.lastServerSequence)
        assertEquals(3, second.nextState.revisionsById.size)
        assertEquals(1, second.nextState.currentRevisionByEvent.size)
        assertEquals(BootstrapValidationPhase.COMPLETE, second.nextState.bootstrapPhase)
        assertEquals(null, second.nextState.expectedBootstrapPageCursor)
        assertEquals(second.page.incrementalCursor, second.nextState.expectedPullCursor)
        assertEquals(
            setOf(firstRequest.requestId, secondRequest.requestId),
            second.nextState.seenSuccessfulBootstrapRequestIds,
        )
    }

    @Test
    fun acceptsCanonicalPointNoteAndRejectsFullyRehashedIntervalNote() {
        val request = WireTestFixtures.bootstrapRequest()
        val accepted = WireResponseCodec.decode(
            200,
            WireTestFixtures.bytes("sync-bootstrap-response.json"),
            BootstrapResponseExpectation(request, ReplicaStreamValidationState()),
        ) as ValidatedBootstrapPage
        val canonicalTime = accepted.page.changes.first().event.document.requireObject("time")
        assertEquals(WireJsonNull, canonicalTime.requireValue("effective_end_utc"))
        assertEquals(WireJsonNull, canonicalTime.requireValue("original_local_end"))
        assertEquals(WireJsonNull, canonicalTime.requireValue("end_offset_seconds"))

        val root = WireTestFixtures.objectFrom("sync-bootstrap-response.json")
        val changes = root.requireArray("changes").elements
        val firstChange = changes.first() as WireJsonObject
        val committedCapture = rehashCapture(firstChange.requireObject("capture"))
        val originalEvent = firstChange.requireObject("event")
        val intervalTime = WireTestFixtures.withProperty(
            WireTestFixtures.withProperty(
                WireTestFixtures.withProperty(
                    originalEvent.requireObject("time"),
                    "effective_end_utc",
                    "2030-01-01T00:01:00Z".asJson(),
                ),
                "original_local_end",
                "2030-01-01T07:01:00".asJson(),
            ),
            "end_offset_seconds",
            25_200.asJson(),
        )
        val committedEvent = rehashRevision(
            WireTestFixtures.withProperty(originalEvent, "time", intervalTime),
        )
        val pendingCapture = pendingCapture(committedCapture)
        val pendingEvent = pendingEvent(committedEvent)
        val operationTemplate =
            WireTestFixtures.objectFrom("sync-push-batch-request.json")
                .requireArray("operations").elements.first() as WireJsonObject
        val changedOperation = WireTestFixtures.withProperty(
            WireTestFixtures.withProperty(
                operationTemplate,
                "capture",
                pendingCapture,
            ),
            "body",
            pendingEvent,
        )
        val operationDigest = StrictJson.canonicalSha256(
            WireJsonObject(
                changedOperation.properties - setOf("ordinal", "operation_content_sha256"),
            ),
        )
        val changedFirst = WireTestFixtures.withProperty(
            WireTestFixtures.withProperty(
                WireTestFixtures.withProperty(
                    firstChange,
                    "capture",
                    committedCapture,
                ),
                "event",
                committedEvent,
            ),
            "operation_content_sha256",
            operationDigest.asJson(),
        )
        val changedRoot = WireTestFixtures.withPageHash(
            WireTestFixtures.withProperty(
                root,
                "changes",
                WireJsonArray(listOf(changedFirst) + changes.drop(1)),
            ),
        )

        assertFailure(WireProtocolFailure.SCHEMA_MISMATCH) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(changedRoot),
                BootstrapResponseExpectation(request, ReplicaStreamValidationState()),
            )
        }
    }

    @Test
    fun rejectsCommittedOffsetTimestampsOutsideNormalizedUtcYearRange() {
        val request = WireTestFixtures.bootstrapRequest()
        val root = WireTestFixtures.objectFrom("sync-bootstrap-response.json")
        val changes = root.requireArray("changes").elements
        val firstChange = changes.first() as WireJsonObject
        val operationTemplate = WireTestFixtures.objectFrom("sync-push-batch-request.json")
            .requireArray("operations").elements.first() as WireJsonObject
        listOf(
            "0001-01-01T00:00:00+14:00",
            "9999-12-31T23:59:59-14:00",
        ).forEach { overflowTimestamp ->
            val originalEvent = firstChange.requireObject("event")
            val changedEvent = rehashRevision(
                WireTestFixtures.withProperty(
                    originalEvent,
                    "source",
                    WireTestFixtures.withProperty(
                        originalEvent.requireObject("source"),
                        "recorded_at",
                        overflowTimestamp.asJson(),
                    ),
                ),
            )
            val changedOperation = WireTestFixtures.withProperty(
                WireTestFixtures.withProperty(
                    operationTemplate,
                    "capture",
                    pendingCapture(firstChange.requireObject("capture")),
                ),
                "body",
                pendingEvent(changedEvent),
            )
            val operationDigest = StrictJson.canonicalSha256(
                WireJsonObject(
                    changedOperation.properties - setOf("ordinal", "operation_content_sha256"),
                ),
            )
            val changedFirst = WireTestFixtures.withProperty(
                WireTestFixtures.withProperty(firstChange, "event", changedEvent),
                "operation_content_sha256",
                operationDigest.asJson(),
            )
            val changedRoot = WireTestFixtures.withPageHash(
                WireTestFixtures.withProperty(
                    root,
                    "changes",
                    WireJsonArray(listOf(changedFirst) + changes.drop(1)),
                ),
            )
            assertFailure(WireProtocolFailure.SCHEMA_MISMATCH) {
                WireResponseCodec.decode(
                    200,
                    StrictJson.canonicalBytes(changedRoot),
                    BootstrapResponseExpectation(request, ReplicaStreamValidationState()),
                )
            }
        }
    }

    @Test
    fun validatesReplacementBootstrapThenBothIncrementalPullPages() {
        val bootstrapRequest = WireTestFixtures.bootstrapRequest(
            "sync-bootstrap-replacement-request.json",
        )
        val bootstrap = WireResponseCodec.decode(
            200,
            WireTestFixtures.bytes("sync-bootstrap-replacement-response.json"),
            BootstrapResponseExpectation(
                bootstrapRequest,
                ReplicaStreamValidationState(),
            ),
        ) as ValidatedBootstrapPage
        assertTrue(bootstrap.page.complete)

        val pullRequest = WireTestFixtures.pullRequest()
        val pull = WireResponseCodec.decode(
            200,
            WireTestFixtures.bytes("sync-pull-response.json"),
            PullResponseExpectation(pullRequest, bootstrap.nextState),
        ) as ValidatedPullPage
        val exactRetryBeforeCommit = WireResponseCodec.decode(
            200,
            WireTestFixtures.bytes("sync-pull-replay-response.json"),
            PullResponseExpectation(pullRequest, bootstrap.nextState),
        ) as ValidatedPullPage
        assertTrue(pull.page.hasMore)
        assertEquals(4L, pull.nextState.lastServerSequence)
        assertEquals(pull.nextState.lastServerSequence, exactRetryBeforeCommit.nextState.lastServerSequence)
        assertEquals(pull.page.nextCursor, pull.nextState.expectedPullCursor)
        assertTrue(pull.nextState.pullContinuationRequired)
        assertEquals(setOf(pullRequest.requestId), pull.nextState.seenSuccessfulPullRequestIds)

        val finalRequest = WireTestFixtures.pullRequest("sync-pull-page-2-request.json")
        val final = WireResponseCodec.decode(
            200,
            WireTestFixtures.bytes("sync-pull-page-2-response.json"),
            PullResponseExpectation(
                finalRequest,
                pull.nextState,
            ),
        ) as ValidatedPullPage
        assertFalse(final.page.hasMore)
        assertFalse(final.nextState.pullContinuationRequired)
        assertEquals(5L, final.nextState.lastServerSequence)
        assertEquals(5, final.nextState.terminalReceiptsByOperationId.size)

        val futureRequest = PullRequest(
            requestId = bootstrapRequest.requestId,
            deviceId = finalRequest.deviceId,
            cursor = final.page.nextCursor,
            pageSize = finalRequest.pageSize,
        )
        val futureRoot = WireTestFixtures.objectFrom("sync-pull-page-2-response.json")
        val futureResponse = WireTestFixtures.withPageHash(
            WireJsonObject(
                futureRoot.properties + mapOf(
                    "request_id" to futureRequest.requestId.asJson(),
                    "from_cursor" to futureRequest.cursor.asJson(),
                    "page_id" to "70000000-0000-4000-8000-000000000007".asJson(),
                    "changes" to jsonArrayOf(emptyList()),
                    "next_cursor" to futureRequest.cursor.asJson(),
                    "has_more" to false.asJson(),
                ),
            ),
        )
        val future = WireResponseCodec.decode(
            200,
            StrictJson.canonicalBytes(futureResponse),
            PullResponseExpectation(futureRequest, final.nextState),
        ) as ValidatedPullPage
        assertFalse(future.page.hasMore)
        assertEquals(final.page.nextCursor, future.nextState.expectedPullCursor)
        assertTrue(futureRequest.requestId in future.nextState.seenSuccessfulPullRequestIds)
    }

    @Test
    fun acceptsOnlyDigestBoundPostCommitPageReplaysAsStateNoOps() {
        val firstBootstrapRequest = WireTestFixtures.bootstrapRequest()
        val firstBootstrapBytes = WireTestFixtures.bytes("sync-bootstrap-response.json")
        val firstBootstrap = WireResponseCodec.decode(
            200,
            firstBootstrapBytes,
            BootstrapResponseExpectation(
                firstBootstrapRequest,
                ReplicaStreamValidationState(),
            ),
        ) as ValidatedBootstrapPage
        val secondBootstrapRequest = WireTestFixtures.bootstrapRequest(
            "sync-bootstrap-page-2-request.json",
        )
        val secondBootstrapBytes = WireTestFixtures.bytes("sync-bootstrap-page-2-response.json")
        val secondBootstrap = WireResponseCodec.decode(
            200,
            secondBootstrapBytes,
            BootstrapResponseExpectation(secondBootstrapRequest, firstBootstrap.nextState),
        ) as ValidatedBootstrapPage

        listOf(
            Triple(firstBootstrapRequest, firstBootstrapBytes, firstBootstrap.requestBodySha256),
            Triple(
                secondBootstrapRequest,
                secondBootstrapBytes,
                secondBootstrap.requestBodySha256,
            ),
        ).forEach { (request, body, requestDigest) ->
            val replay = WireResponseCodec.decode(
                200,
                body,
                BootstrapResponseExpectation(
                    request,
                    secondBootstrap.nextState,
                    persistedRequestBodySha256 = requestDigest,
                ),
            ) as ValidatedBootstrapPage
            assertTrue(replay.replayed)
            assertTrue(replay.nextState === secondBootstrap.nextState)
            assertEquals(sha256Hex(body), replay.responseBodySha256)
        }

        val resetBootstrapState = secondBootstrap.nextState.resetReplicaStream()
        val replayAfterReset = WireResponseCodec.decode(
            200,
            firstBootstrapBytes,
            BootstrapResponseExpectation(firstBootstrapRequest, resetBootstrapState),
        ) as ValidatedBootstrapPage
        assertTrue(replayAfterReset.replayed)
        assertTrue(replayAfterReset.nextState === resetBootstrapState)

        val noLocalReceiptState = secondBootstrap.nextState.copy(
            terminalReceiptsByOperationId = emptyMap(),
        )
        val remoteReplay = WireResponseCodec.decode(
            200,
            firstBootstrapBytes,
            BootstrapResponseExpectation(firstBootstrapRequest, noLocalReceiptState),
        ) as ValidatedBootstrapPage
        assertTrue(remoteReplay.replayed)
        assertTrue(remoteReplay.nextState === noLocalReceiptState)

        val firstOperationId = firstBootstrap.page.changes.first().operationId
        val exactOperationReceipt = secondBootstrap.nextState
            .terminalReceiptsByOperationId.getValue(firstOperationId)
        val conflictingOperationReceiptState = secondBootstrap.nextState.copy(
            terminalReceiptsByOperationId =
                secondBootstrap.nextState.terminalReceiptsByOperationId +
                    (
                        firstOperationId to exactOperationReceipt.copy(
                            serverSequence = exactOperationReceipt.serverSequence + 1,
                        )
                        ),
        )
        assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
            WireResponseCodec.decode(
                200,
                firstBootstrapBytes,
                BootstrapResponseExpectation(
                    firstBootstrapRequest,
                    conflictingOperationReceiptState,
                ),
            )
        }

        assertFailure(WireProtocolFailure.PAGE_INVARIANT) {
            WireResponseCodec.decode(
                200,
                firstBootstrapBytes + byteArrayOf(' '.code.toByte()),
                BootstrapResponseExpectation(firstBootstrapRequest, secondBootstrap.nextState),
            )
        }
        assertFailure(WireProtocolFailure.PAGE_INVARIANT) {
            WireResponseCodec.decode(
                200,
                firstBootstrapBytes,
                BootstrapResponseExpectation(
                    firstBootstrapRequest.copy(pageSize = firstBootstrapRequest.pageSize + 1),
                    secondBootstrap.nextState,
                    persistedRequestBodySha256 = firstBootstrap.requestBodySha256,
                ),
            )
        }
        val changedBootstrapResponse = WireTestFixtures.withPageHash(
            WireTestFixtures.withProperty(
                WireTestFixtures.objectFrom("sync-bootstrap-response.json"),
                "page_id",
                "70000000-0000-4000-8000-000000000020".asJson(),
            ),
        )
        assertFailure(WireProtocolFailure.PAGE_INVARIANT) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(changedBootstrapResponse),
                BootstrapResponseExpectation(firstBootstrapRequest, secondBootstrap.nextState),
            )
        }
        val retainedBootstrapReceipt = secondBootstrap.nextState
            .successfulBootstrapPageReceipts.getValue(firstBootstrapRequest.requestId)
        val conflictingReceiptState = secondBootstrap.nextState.copy(
            successfulBootstrapPageReceipts =
                secondBootstrap.nextState.successfulBootstrapPageReceipts +
                    (
                        firstBootstrapRequest.requestId to retainedBootstrapReceipt.copy(
                            responseBodySha256 = "0".repeat(64),
                        )
                        ),
        )
        assertFailure(WireProtocolFailure.PAGE_INVARIANT) {
            WireResponseCodec.decode(
                200,
                firstBootstrapBytes,
                BootstrapResponseExpectation(firstBootstrapRequest, conflictingReceiptState),
            )
        }

        val replacementRequest = WireTestFixtures.bootstrapRequest(
            "sync-bootstrap-replacement-request.json",
        )
        val replacement = WireResponseCodec.decode(
            200,
            WireTestFixtures.bytes("sync-bootstrap-replacement-response.json"),
            BootstrapResponseExpectation(replacementRequest, ReplicaStreamValidationState()),
        ) as ValidatedBootstrapPage
        val firstPullRequest = WireTestFixtures.pullRequest()
        val firstPullBytes = WireTestFixtures.bytes("sync-pull-response.json")
        val firstPull = WireResponseCodec.decode(
            200,
            firstPullBytes,
            PullResponseExpectation(firstPullRequest, replacement.nextState),
        ) as ValidatedPullPage
        val finalPullRequest = WireTestFixtures.pullRequest("sync-pull-page-2-request.json")
        val finalPullBytes = WireTestFixtures.bytes("sync-pull-page-2-response.json")
        val finalPull = WireResponseCodec.decode(
            200,
            finalPullBytes,
            PullResponseExpectation(finalPullRequest, firstPull.nextState),
        ) as ValidatedPullPage
        listOf(
            Triple(firstPullRequest, firstPullBytes, firstPull.requestBodySha256),
            Triple(finalPullRequest, finalPullBytes, finalPull.requestBodySha256),
        ).forEach { (request, body, requestDigest) ->
            val replay = WireResponseCodec.decode(
                200,
                body,
                PullResponseExpectation(
                    request,
                    finalPull.nextState,
                    persistedRequestBodySha256 = requestDigest,
                ),
            ) as ValidatedPullPage
            assertTrue(replay.replayed)
            assertTrue(replay.nextState === finalPull.nextState)
            assertEquals(sha256Hex(body), replay.responseBodySha256)
        }
        assertFailure(WireProtocolFailure.PAGE_INVARIANT) {
            WireResponseCodec.decode(
                200,
                firstPullBytes + byteArrayOf('\n'.code.toByte()),
                PullResponseExpectation(firstPullRequest, finalPull.nextState),
            )
        }
        assertFailure(WireProtocolFailure.PAGE_INVARIANT) {
            WireResponseCodec.decode(
                200,
                firstPullBytes,
                PullResponseExpectation(
                    firstPullRequest.copy(pageSize = firstPullRequest.pageSize + 1),
                    finalPull.nextState,
                ),
            )
        }
    }

    @Test
    fun rejectsBootstrapPagesOutsideTheBoundCursorDeviceAndRequestChain() {
        val continuation = WireTestFixtures.bootstrapRequest("sync-bootstrap-page-2-request.json")
        assertFailure(WireProtocolFailure.PAGE_INVARIANT) {
            WireResponseCodec.decode(
                200,
                WireTestFixtures.bytes("sync-bootstrap-page-2-response.json"),
                BootstrapResponseExpectation(continuation, ReplicaStreamValidationState()),
            )
        }

        val firstRequest = WireTestFixtures.bootstrapRequest()
        val first = WireResponseCodec.decode(
            200,
            WireTestFixtures.bytes("sync-bootstrap-response.json"),
            BootstrapResponseExpectation(firstRequest, ReplicaStreamValidationState()),
        ) as ValidatedBootstrapPage
        val mismatchedRequests = listOf(
            continuation.copy(pageCursor = first.page.incrementalCursor),
            continuation.copy(bootstrapId = "70000000-0000-4000-8000-000000000009"),
            continuation.copy(deviceId = "91000000-0000-4000-8000-000000000009"),
        )
        mismatchedRequests.forEach { request ->
            assertFailure(WireProtocolFailure.PAGE_INVARIANT) {
                WireResponseCodec.decode(
                    200,
                    StrictJson.canonicalBytes(
                        bootstrapResponseFor("sync-bootstrap-page-2-response.json", request),
                    ),
                    BootstrapResponseExpectation(request, first.nextState),
                )
            }
        }
        val emptyContinuation = WireTestFixtures.withPageHash(
            WireTestFixtures.withProperty(
                WireTestFixtures.objectFrom("sync-bootstrap-page-2-response.json"),
                "changes",
                jsonArrayOf(emptyList()),
            ),
        )
        val emptyComplete = WireResponseCodec.decode(
            200,
            StrictJson.canonicalBytes(emptyContinuation),
            BootstrapResponseExpectation(continuation, first.nextState),
        ) as ValidatedBootstrapPage
        assertTrue(emptyComplete.page.complete)
        assertEquals(first.nextState.lastServerSequence, emptyComplete.nextState.lastServerSequence)

        val reusedRequestId = continuation.copy(requestId = firstRequest.requestId)
        assertFailure(WireProtocolFailure.PAGE_INVARIANT) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(
                    bootstrapResponseFor(
                        "sync-bootstrap-page-2-response.json",
                        reusedRequestId,
                    ),
                ),
                BootstrapResponseExpectation(reusedRequestId, first.nextState),
            )
        }

        val complete = WireResponseCodec.decode(
            200,
            WireTestFixtures.bytes("sync-bootstrap-page-2-response.json"),
            BootstrapResponseExpectation(continuation, first.nextState),
        ) as ValidatedBootstrapPage
        val afterComplete = firstRequest.copy(
            requestId = "82000000-0000-4000-8000-000000000009",
        )
        assertFailure(WireProtocolFailure.PAGE_INVARIANT) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(
                    bootstrapResponseFor("sync-bootstrap-response.json", afterComplete),
                ),
                BootstrapResponseExpectation(afterComplete, complete.nextState),
            )
        }
    }

    @Test
    fun rejectsPullPagesOutsideTheCommittedCursorDeviceAndRequestChain() {
        val bootstrap = WireResponseCodec.decode(
            200,
            WireTestFixtures.bytes("sync-bootstrap-replacement-response.json"),
            BootstrapResponseExpectation(
                WireTestFixtures.bootstrapRequest("sync-bootstrap-replacement-request.json"),
                ReplicaStreamValidationState(),
            ),
        ) as ValidatedBootstrapPage
        val firstRequest = WireTestFixtures.pullRequest()
        assertFailure(WireProtocolFailure.PAGE_INVARIANT) {
            WireResponseCodec.decode(
                200,
                WireTestFixtures.bytes("sync-pull-response.json"),
                PullResponseExpectation(firstRequest, ReplicaStreamValidationState()),
            )
        }
        val mismatchedRequests = listOf(
            firstRequest.copy(cursor = "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDE"),
            firstRequest.copy(deviceId = "91000000-0000-4000-8000-000000000009"),
        )
        mismatchedRequests.forEach { request ->
            assertFailure(WireProtocolFailure.PAGE_INVARIANT) {
                WireResponseCodec.decode(
                    200,
                    StrictJson.canonicalBytes(pullResponseFor("sync-pull-response.json", request)),
                    PullResponseExpectation(request, bootstrap.nextState),
                )
            }
        }
        val inconsistentCursor = "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDE"
        val malformedCompleteState = bootstrap.nextState.copy(
            expectedPullCursor = inconsistentCursor,
        )
        val malformedStateRequest = firstRequest.copy(cursor = inconsistentCursor)
        assertFailure(WireProtocolFailure.PAGE_INVARIANT) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(
                    pullResponseFor("sync-pull-response.json", malformedStateRequest),
                ),
                PullResponseExpectation(malformedStateRequest, malformedCompleteState),
            )
        }

        val first = WireResponseCodec.decode(
            200,
            WireTestFixtures.bytes("sync-pull-response.json"),
            PullResponseExpectation(firstRequest, bootstrap.nextState),
        ) as ValidatedPullPage
        val continuationRequest = WireTestFixtures.pullRequest("sync-pull-page-2-request.json")
        val emptyRequiredContinuation = WireTestFixtures.withPageHash(
            WireJsonObject(
                WireTestFixtures.objectFrom("sync-pull-page-2-response.json").properties +
                    mapOf(
                        "changes" to jsonArrayOf(emptyList()),
                        "next_cursor" to continuationRequest.cursor.asJson(),
                        "has_more" to false.asJson(),
                    ),
            ),
        )
        val emptyFinal = WireResponseCodec.decode(
            200,
            StrictJson.canonicalBytes(emptyRequiredContinuation),
            PullResponseExpectation(continuationRequest, first.nextState),
        ) as ValidatedPullPage
        assertFalse(emptyFinal.page.hasMore)
        assertFalse(emptyFinal.nextState.pullContinuationRequired)
        assertEquals(first.nextState.lastServerSequence, emptyFinal.nextState.lastServerSequence)
        val reusedRequest = WireTestFixtures.pullRequest("sync-pull-page-2-request.json")
            .copy(requestId = firstRequest.requestId)
        assertFailure(WireProtocolFailure.PAGE_INVARIANT) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(
                    pullResponseFor("sync-pull-page-2-response.json", reusedRequest),
                ),
                PullResponseExpectation(reusedRequest, first.nextState),
            )
        }
    }

    @Test
    fun decodesEveryFrozenApiErrorWithEndpointStatusMatrix() {
        WireTestFixtures.enrollmentRequest().use { request ->
            assertApiError(
                "api-error-enrollment-unavailable.json",
                401,
                EnrollmentResponseExpectation(request),
                ApiErrorCode.ENROLLMENT_UNAVAILABLE,
            )
        }
        WireTestFixtures.refreshRequest().use { request ->
            assertApiError(
                "api-error-credential-unavailable.json",
                401,
                RefreshResponseExpectation(
                    request,
                    "2030-04-01T00:00:00Z",
                    emptySet(),
                ),
                ApiErrorCode.CREDENTIAL_UNAVAILABLE,
            )
        }
        assertApiError(
            "api-error-request-id-collision.json",
            409,
            BootstrapResponseExpectation(
                WireTestFixtures.bootstrapRequest(),
                ReplicaStreamValidationState(),
            ),
            ApiErrorCode.REQUEST_ID_COLLISION,
        )
        assertApiError(
            "api-error-cursor-expired.json",
            410,
            BootstrapResponseExpectation(
                WireTestFixtures.bootstrapRequest("sync-bootstrap-page-2-request.json"),
                ReplicaStreamValidationState(),
            ),
            ApiErrorCode.CURSOR_EXPIRED,
        )
    }

    @Test
    fun rejectsUnknownFieldsDuplicatesAndWrongCorrelationBeforeSuccessOutput() {
        WireTestFixtures.revokeRequest().use { request ->
            val root = WireTestFixtures.objectFrom("auth-revoke-response.json")
            val unknown = WireJsonObject(root.properties + ("unexpected" to WireJsonNull))
            assertFailure(WireProtocolFailure.JSON_TRUST_BOUNDARY) {
                WireResponseCodec.decode(
                    200,
                    StrictJson.canonicalBytes(unknown),
                    RevokeResponseExpectation(request),
                )
            }

            val duplicate = WireTestFixtures.bytes("auth-revoke-response.json")
                .toString(StandardCharsets.UTF_8)
                .replaceFirst(
                    "\"status\": \"revoked\"",
                    "\"status\": \"revoked\", \"status\": \"revoked\"",
                )
                .toByteArray()
            assertFailure(WireProtocolFailure.JSON_TRUST_BOUNDARY) {
                WireResponseCodec.decode(200, duplicate, RevokeResponseExpectation(request))
            }

            val wrongCorrelation = WireTestFixtures.withProperty(
                root,
                "request_id",
                "81000000-0000-4000-8000-000000000009".asJson(),
            )
            assertFailure(WireProtocolFailure.CORRELATION_MISMATCH) {
                WireResponseCodec.decode(
                    200,
                    StrictJson.canonicalBytes(wrongCorrelation),
                    RevokeResponseExpectation(request),
                )
            }
        }
    }

    @Test
    fun rejectsStatusErrorAndRetryabilityMismatches() {
        val pullExpectation = PullResponseExpectation(
            WireTestFixtures.pullRequest("sync-pull-page-2-request.json"),
            ReplicaStreamValidationState(),
        )
        assertFailure(WireProtocolFailure.STATUS_ERROR_MISMATCH) {
            WireResponseCodec.decode(
                410,
                WireTestFixtures.bytes("api-error-cursor-expired.json"),
                pullExpectation,
            )
        }

        WireTestFixtures.refreshRequest().use { request ->
            val root = WireTestFixtures.objectFrom("api-error-credential-unavailable.json")
            val retryable = WireTestFixtures.withProperty(root, "retryable", true.asJson())
            assertFailure(WireProtocolFailure.STATUS_ERROR_MISMATCH) {
                WireResponseCodec.decode(
                    401,
                    StrictJson.canonicalBytes(retryable),
                    RefreshResponseExpectation(request, "2030-04-01T00:00:00Z", emptySet()),
                )
            }
        }
    }

    @Test
    fun alwaysPreIdentityErrorsRequireNullCorrelation() {
        WireTestFixtures.refreshRequest().use { request ->
            val base = WireTestFixtures.objectFrom("api-error-credential-unavailable.json")
            val expectation = RefreshResponseExpectation(
                request,
                "2030-04-01T00:00:00Z",
                emptySet(),
            )
            listOf(
                ApiErrorCode.MALFORMED_JSON to 400,
                ApiErrorCode.REQUEST_TOO_LARGE to 413,
                ApiErrorCode.UNSUPPORTED_MEDIA_TYPE to 415,
            ).forEach { (code, status) ->
                val withoutIdentity = apiError(base, WireJsonNull, code, status)
                val decoded = WireResponseCodec.decode(
                    status,
                    StrictJson.canonicalBytes(withoutIdentity),
                    expectation,
                ) as DecodedApiError
                assertEquals(null, decoded.value.requestId)

                listOf(
                    request.requestId,
                    "81000000-0000-4000-8000-000000000009",
                ).forEach { forbiddenIdentity ->
                    val withIdentity = apiError(
                        base,
                        forbiddenIdentity.asJson(),
                        code,
                        status,
                    )
                    assertFailure(WireProtocolFailure.CORRELATION_MISMATCH) {
                        WireResponseCodec.decode(
                            status,
                            StrictJson.canonicalBytes(withIdentity),
                            expectation,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun enforcesEndpointAndStageAwareApiErrorCorrelationMatrix() {
        val base = WireTestFixtures.objectFrom("api-error-credential-unavailable.json")
        WireTestFixtures.refreshRequest().use { request ->
            val expectation = RefreshResponseExpectation(
                request,
                "2030-04-01T00:00:00Z",
                emptySet(),
            )
            val exactPostOnly = WireResponseCodec.decode(
                401,
                WireTestFixtures.bytes("api-error-credential-unavailable.json"),
                expectation,
            ) as DecodedApiError
            assertEquals(request.requestId, exactPostOnly.value.requestId)
            listOf<WireJsonValue>(
                WireJsonNull,
                "81000000-0000-4000-8000-000000000009".asJson(),
            ).forEach { forbiddenIdentity ->
                val postOnly = apiError(
                    base,
                    forbiddenIdentity,
                    ApiErrorCode.CREDENTIAL_UNAVAILABLE,
                    401,
                )
                assertFailure(WireProtocolFailure.CORRELATION_MISMATCH) {
                    WireResponseCodec.decode(
                        401,
                        StrictJson.canonicalBytes(postOnly),
                        expectation,
                    )
                }
            }
            listOf<WireJsonValue>(WireJsonNull, request.requestId.asJson()).forEach { requestId ->
                val response = apiError(
                    base,
                    requestId,
                    ApiErrorCode.REQUEST_SCHEMA_INVALID,
                    422,
                )
                val decoded = WireResponseCodec.decode(
                    422,
                    StrictJson.canonicalBytes(response),
                    expectation,
                ) as DecodedApiError
                assertEquals(ApiErrorCode.REQUEST_SCHEMA_INVALID, decoded.value.errorCode)
            }

            val wrong = apiError(
                base,
                "81000000-0000-4000-8000-000000000009".asJson(),
                ApiErrorCode.REQUEST_SCHEMA_INVALID,
                422,
            )
            assertFailure(WireProtocolFailure.CORRELATION_MISMATCH) {
                WireResponseCodec.decode(422, StrictJson.canonicalBytes(wrong), expectation)
            }
        }

        val bearerNullCredential = apiError(
            base,
            WireJsonNull,
            ApiErrorCode.CREDENTIAL_UNAVAILABLE,
            401,
        )
        val bearerDecoded = WireResponseCodec.decode(
            401,
            StrictJson.canonicalBytes(bearerNullCredential),
            BootstrapResponseExpectation(
                WireTestFixtures.bootstrapRequest(),
                ReplicaStreamValidationState(),
            ),
        ) as DecodedApiError
        assertEquals(null, bearerDecoded.value.requestId)

        val pushNullIdempotency = apiError(
            base,
            WireJsonNull,
            ApiErrorCode.IDEMPOTENCY_KEY_MISMATCH,
            400,
        )
        val pushRequest = WireRequestCodec.decodePushBatch(
            WireTestFixtures.bytes("sync-push-batch-request.json"),
        )
        val pushDecoded = WireResponseCodec.decode(
            400,
            StrictJson.canonicalBytes(pushNullIdempotency),
            PushResponseExpectation(pushRequest),
        ) as DecodedApiError
        assertEquals(null, pushDecoded.value.requestId)
    }

    @Test
    fun expectationAndStreamStateDefensivelyCopyTrustedCollections() {
        WireTestFixtures.refreshRequest().use { request ->
            val forbidden = mutableSetOf<String>()
            val expectation = RefreshResponseExpectation(
                request,
                "2030-04-01T00:00:00Z",
                forbidden,
            )
            val responseRoot = WireTestFixtures.objectFrom("auth-refresh-response.json")
            forbidden += sha256Hex(
                responseRoot.requireObject("credentials")
                    .requireString("access_token")
                    .toByteArray(StandardCharsets.US_ASCII),
            )
            val decoded = WireResponseCodec.decode(
                200,
                WireTestFixtures.bytes("auth-refresh-response.json"),
                expectation,
            ) as RefreshSuccess
            decoded.close()
        }

        val pages = mutableSetOf("page-a")
        val bootstrapRequests = mutableSetOf("bootstrap-request-a")
        val pullRequests = mutableSetOf("pull-request-a")
        val revisions = mutableMapOf("revision-a" to RevisionStreamFact("event-a", 1))
        val heads = mutableMapOf("event-a" to "revision-a")
        val receipts = mutableMapOf<String, OperationReceiptFact>()
        val captures = mutableSetOf("capture-a")
        val state = ReplicaStreamValidationState(
            seenPageIds = pages,
            seenSuccessfulBootstrapRequestIds = bootstrapRequests,
            seenSuccessfulPullRequestIds = pullRequests,
            revisionsById = revisions,
            currentRevisionByEvent = heads,
            terminalReceiptsByOperationId = receipts,
            captureIds = captures,
        )
        pages += "page-b"
        bootstrapRequests += "bootstrap-request-b"
        pullRequests.clear()
        revisions.clear()
        heads.clear()
        receipts["operation-b"] = OperationReceiptFact(
            operationContentSha256 = "0".repeat(64),
            resultCode = PushResultCode.APPLIED,
            captureId = "capture-b",
            eventId = "event-b",
            revisionId = "revision-b",
            currentRevisionId = "revision-b",
            serverSequence = 2,
            committedAt = "2030-01-01T00:00:00Z",
        )
        captures.clear()
        assertEquals(setOf("page-a"), state.seenPageIds)
        assertEquals(
            setOf("bootstrap-request-a"),
            state.seenSuccessfulBootstrapRequestIds,
        )
        assertEquals(setOf("pull-request-a"), state.seenSuccessfulPullRequestIds)
        assertEquals(setOf("revision-a"), state.revisionsById.keys)
        assertEquals(setOf("event-a"), state.currentRevisionByEvent.keys)
        assertTrue(state.terminalReceiptsByOperationId.isEmpty())
        assertEquals(setOf("capture-a"), state.captureIds)
    }

    @Test
    fun rejectsAuthGenerationFamilyAndLifetimeDrift() {
        WireTestFixtures.refreshRequest().use { request ->
            val root = WireTestFixtures.objectFrom("auth-refresh-response.json")
            val credentials = root.requireObject("credentials")
            val badGeneration = WireTestFixtures.withProperty(credentials, "generation", 3.asJson())
            val changed = WireTestFixtures.withProperty(root, "credentials", badGeneration)
            assertFailure(WireProtocolFailure.AUTH_INVARIANT) {
                WireResponseCodec.decode(
                    200,
                    StrictJson.canonicalBytes(changed),
                    RefreshResponseExpectation(request, "2030-04-01T00:00:00Z", emptySet()),
                )
            }

            val equalAccessAndRefreshExpiry = WireTestFixtures.withProperty(
                credentials,
                "refresh_expires_at",
                credentials.requireString("access_expires_at").asJson(),
            )
            assertFailure(WireProtocolFailure.AUTH_INVARIANT) {
                WireResponseCodec.decode(
                    200,
                    StrictJson.canonicalBytes(
                        WireTestFixtures.withProperty(
                            root,
                            "credentials",
                            equalAccessAndRefreshExpiry,
                        ),
                    ),
                    RefreshResponseExpectation(request, "2030-04-01T00:00:00Z", emptySet()),
                )
            }

            val badFamily = WireTestFixtures.withProperty(
                credentials,
                "family_expires_at",
                "2030-04-02T00:00:00Z".asJson(),
            )
            assertFailure(WireProtocolFailure.AUTH_INVARIANT) {
                WireResponseCodec.decode(
                    200,
                    StrictJson.canonicalBytes(
                        WireTestFixtures.withProperty(root, "credentials", badFamily),
                    ),
                    RefreshResponseExpectation(request, "2030-04-01T00:00:00Z", emptySet()),
                )
            }
        }
    }

    @Test
    fun rejectsPushCardinalityOrderAndReflectionDrift() {
        val request = WireRequestCodec.decodePushBatch(
            WireTestFixtures.bytes("sync-push-batch-request.json"),
        )
        val root = WireTestFixtures.objectFrom("sync-push-batch-response.json")
        val results = root.requireArray("results").elements
        val short = WireTestFixtures.withProperty(root, "results", WireJsonArray(results.dropLast(1)))
        assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(short),
                PushResponseExpectation(request),
            )
        }

        val swapped = WireTestFixtures.withProperty(
            root,
            "results",
            WireJsonArray(listOf(results[1], results[0], results[2])),
        )
        assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(swapped),
                PushResponseExpectation(request),
            )
        }

        val first = results.first() as WireJsonObject
        val drifted = WireTestFixtures.withProperty(
            first,
            "operation_content_sha256",
            "0".repeat(64).asJson(),
        )
        val reflectionDrift = WireTestFixtures.withProperty(
            root,
            "results",
            WireJsonArray(listOf(drifted) + results.drop(1)),
        )
        assertFailure(WireProtocolFailure.CORRELATION_MISMATCH) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(reflectionDrift),
                PushResponseExpectation(request),
            )
        }

        val duplicateSequence = WireTestFixtures.withProperty(
            results[1] as WireJsonObject,
            "server_sequence",
            (results[0] as WireJsonObject).requireValue("server_sequence"),
        )
        val duplicateAckSequence = WireTestFixtures.withProperty(
            root,
            "results",
            WireJsonArray(listOf(results[0], duplicateSequence, results[2])),
        )
        assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(duplicateAckSequence),
                PushResponseExpectation(request),
            )
        }
    }

    @Test
    fun rejectsPageHashSizeReplayAndTopologicalOrderBeforeReducerOutput() {
        val request = WireTestFixtures.bootstrapRequest()
        val root = WireTestFixtures.objectFrom("sync-bootstrap-response.json")
        val badHash = WireTestFixtures.withProperty(root, "page_sha256", "0".repeat(64).asJson())
        assertFailure(WireProtocolFailure.HASH_MISMATCH) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(badHash),
                BootstrapResponseExpectation(request, ReplicaStreamValidationState()),
            )
        }

        val tooSmallRequest = request.copy(pageSize = 1)
        assertFailure(WireProtocolFailure.PAGE_INVARIANT) {
            WireResponseCodec.decode(
                200,
                WireTestFixtures.bytes("sync-bootstrap-response.json"),
                BootstrapResponseExpectation(tooSmallRequest, ReplicaStreamValidationState()),
            )
        }

        val accepted = WireResponseCodec.decode(
            200,
            WireTestFixtures.bytes("sync-bootstrap-response.json"),
            BootstrapResponseExpectation(request, ReplicaStreamValidationState()),
        ) as ValidatedBootstrapPage
        val replay = WireResponseCodec.decode(
            200,
            WireTestFixtures.bytes("sync-bootstrap-response.json"),
            BootstrapResponseExpectation(request, accepted.nextState),
        ) as ValidatedBootstrapPage
        assertTrue(replay.replayed)
        assertTrue(replay.nextState === accepted.nextState)

        val changes = root.requireArray("changes").elements
        val reorderedRoot = WireTestFixtures.withProperty(
            root,
            "changes",
            WireJsonArray(listOf(changes[1], changes[0])),
        )
        val reordered = WireTestFixtures.withPageHash(reorderedRoot)
        assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(reordered),
                BootstrapResponseExpectation(request, ReplicaStreamValidationState()),
            )
        }
    }

    @Test
    fun validatesDirectResponseExpectationPageSizeBounds() {
        val bootstrapRequest = WireTestFixtures.bootstrapRequest(
            "sync-bootstrap-replacement-request.json",
        )
        val bootstrapRoot = WireTestFixtures.objectFrom(
            "sync-bootstrap-replacement-response.json",
        )
        val emptyBootstrap = WireTestFixtures.withPageHash(
            WireTestFixtures.withProperty(
                bootstrapRoot,
                "changes",
                jsonArrayOf(emptyList()),
            ),
        )
        listOf(
            bootstrapRequest.copy(pageSize = 0) to emptyBootstrap,
            bootstrapRequest.copy(pageSize = 501) to bootstrapRoot,
        ).forEach { (invalidRequest, response) ->
            assertFailure(WireProtocolFailure.PAGE_INVARIANT) {
                WireResponseCodec.decode(
                    200,
                    StrictJson.canonicalBytes(response),
                    BootstrapResponseExpectation(
                        invalidRequest,
                        ReplicaStreamValidationState(),
                    ),
                )
            }
        }
        WireResponseCodec.decode(
            200,
            StrictJson.canonicalBytes(emptyBootstrap),
            BootstrapResponseExpectation(
                bootstrapRequest.copy(pageSize = 1),
                ReplicaStreamValidationState(),
            ),
        )
        val bootstrap = WireResponseCodec.decode(
            200,
            StrictJson.canonicalBytes(bootstrapRoot),
            BootstrapResponseExpectation(
                bootstrapRequest.copy(pageSize = 500),
                ReplicaStreamValidationState(),
            ),
        ) as ValidatedBootstrapPage

        val pullRequest = WireTestFixtures.pullRequest()
        val pullRoot = WireTestFixtures.objectFrom("sync-pull-response.json")
        val emptyPull = WireTestFixtures.withPageHash(
            WireJsonObject(
                pullRoot.properties + mapOf(
                    "changes" to jsonArrayOf(emptyList()),
                    "next_cursor" to pullRequest.cursor.asJson(),
                    "has_more" to false.asJson(),
                ),
            ),
        )
        listOf(
            pullRequest.copy(pageSize = 0) to emptyPull,
            pullRequest.copy(pageSize = 501) to pullRoot,
        ).forEach { (invalidRequest, response) ->
            assertFailure(WireProtocolFailure.PAGE_INVARIANT) {
                WireResponseCodec.decode(
                    200,
                    StrictJson.canonicalBytes(response),
                    PullResponseExpectation(invalidRequest, bootstrap.nextState),
                )
            }
        }
        WireResponseCodec.decode(
            200,
            StrictJson.canonicalBytes(emptyPull),
            PullResponseExpectation(
                pullRequest.copy(pageSize = 1),
                bootstrap.nextState,
            ),
        )
        WireResponseCodec.decode(
            200,
            StrictJson.canonicalBytes(pullRoot),
            PullResponseExpectation(
                pullRequest.copy(pageSize = 500),
                bootstrap.nextState,
            ),
        )
    }

    @Test
    fun acceptsStrictlyIncreasingServerSequenceGapsWithinAndAcrossPages() {
        val firstRequest = WireTestFixtures.bootstrapRequest()
        val root = WireTestFixtures.objectFrom("sync-bootstrap-response.json")
        val changes = root.requireArray("changes").elements
        val gappedFirstRoot = WireTestFixtures.withPageHash(
            WireTestFixtures.withProperty(
                root,
                "changes",
                WireJsonArray(
                    listOf(
                        changeWithServerSequence(changes[0] as WireJsonObject, 5),
                        changeWithServerSequence(changes[1] as WireJsonObject, 7),
                    ),
                ),
            ),
        )
        val first = WireResponseCodec.decode(
            200,
            StrictJson.canonicalBytes(gappedFirstRoot),
            BootstrapResponseExpectation(firstRequest, ReplicaStreamValidationState()),
        ) as ValidatedBootstrapPage
        assertEquals(7L, first.nextState.lastServerSequence)

        val secondRequest = WireTestFixtures.bootstrapRequest("sync-bootstrap-page-2-request.json")
        val secondRoot = WireTestFixtures.objectFrom("sync-bootstrap-page-2-response.json")
        val gappedSecondRoot = WireTestFixtures.withPageHash(
            WireTestFixtures.withProperty(
                secondRoot,
                "changes",
                WireJsonArray(
                    listOf(
                        changeWithServerSequence(
                            secondRoot.requireArray("changes").elements.single() as WireJsonObject,
                            10,
                        ),
                    ),
                ),
            ),
        )
        val second = WireResponseCodec.decode(
            200,
            StrictJson.canonicalBytes(gappedSecondRoot),
            BootstrapResponseExpectation(secondRequest, first.nextState),
        ) as ValidatedBootstrapPage
        assertEquals(10L, second.nextState.lastServerSequence)
    }

    @Test
    fun rejectsDuplicateAndDecreasingServerSequencesGlobally() {
        val request = WireTestFixtures.bootstrapRequest()
        val root = WireTestFixtures.objectFrom("sync-bootstrap-response.json")
        val changes = root.requireArray("changes").elements
        listOf(2L to 2L, 3L to 2L).forEach { (firstSequence, secondSequence) ->
            val changedRoot = WireTestFixtures.withPageHash(
                WireTestFixtures.withProperty(
                    root,
                    "changes",
                    WireJsonArray(
                        listOf(
                            changeWithServerSequence(
                                changes[0] as WireJsonObject,
                                firstSequence,
                            ),
                            changeWithServerSequence(
                                changes[1] as WireJsonObject,
                                secondSequence,
                            ),
                        ),
                    ),
                ),
            )
            assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
                WireResponseCodec.decode(
                    200,
                    StrictJson.canonicalBytes(changedRoot),
                    BootstrapResponseExpectation(request, ReplicaStreamValidationState()),
                )
            }
        }

        val first = WireResponseCodec.decode(
            200,
            WireTestFixtures.bytes("sync-bootstrap-response.json"),
            BootstrapResponseExpectation(request, ReplicaStreamValidationState()),
        ) as ValidatedBootstrapPage
        val continuation = WireTestFixtures.objectFrom("sync-bootstrap-page-2-response.json")
        val duplicateAcrossPages = WireTestFixtures.withPageHash(
            WireTestFixtures.withProperty(
                continuation,
                "changes",
                WireJsonArray(
                    listOf(
                        changeWithServerSequence(
                            continuation.requireArray("changes").elements.single()
                                as WireJsonObject,
                            2,
                        ),
                    ),
                ),
            ),
        )
        assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(duplicateAcrossPages),
                BootstrapResponseExpectation(
                    WireTestFixtures.bootstrapRequest("sync-bootstrap-page-2-request.json"),
                    first.nextState,
                ),
            )
        }
    }

    @Test
    fun rejectsConflictWhoseSupersedesParentIsCurrentHead() {
        val request = WireTestFixtures.bootstrapRequest(
            "sync-bootstrap-replacement-request.json",
        )
        val root = WireTestFixtures.objectFrom("sync-bootstrap-replacement-response.json")
        val changes = root.requireArray("changes").elements
        val rootChange = changes[0] as WireJsonObject
        val conflict = changes[2] as WireJsonObject
        val rootRevisionId = rootChange.requireString("revision_id")
        val eventId = rootChange.requireString("event_id")
        val changedConflict = WireTestFixtures.withProperty(
            conflict,
            "current_revision_id",
            rootRevisionId.asJson(),
        )
        val changedRoot = WireTestFixtures.withPageHash(
            WireTestFixtures.withProperty(
                root,
                "changes",
                WireJsonArray(listOf(changedConflict)),
            ),
        )
        val state = ReplicaStreamValidationState(
            lastServerSequence = 2,
            revisionsById = mapOf(rootRevisionId to RevisionStreamFact(eventId, 1)),
            currentRevisionByEvent = mapOf(eventId to rootRevisionId),
        )

        assertFailure(WireProtocolFailure.ORDER_MISMATCH) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(changedRoot),
                BootstrapResponseExpectation(request, state),
            )
        }
    }

    @Test
    fun rejectsIncompleteBootstrapCursorSelfLoop() {
        val first = WireResponseCodec.decode(
            200,
            WireTestFixtures.bytes("sync-bootstrap-response.json"),
            BootstrapResponseExpectation(
                WireTestFixtures.bootstrapRequest(),
                ReplicaStreamValidationState(),
            ),
        ) as ValidatedBootstrapPage
        val request = WireTestFixtures.bootstrapRequest("sync-bootstrap-page-2-request.json")
        val root = WireTestFixtures.objectFrom("sync-bootstrap-page-2-response.json")
        val loop = WireTestFixtures.withPageHash(
            WireTestFixtures.withProperty(
                WireTestFixtures.withProperty(root, "complete", false.asJson()),
                "next_page_cursor",
                checkNotNull(request.pageCursor).asJson(),
            ),
        )

        assertFailure(WireProtocolFailure.PAGE_INVARIANT) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(loop),
                BootstrapResponseExpectation(request, first.nextState),
            )
        }
    }

    @Test
    fun rejectsCursorCyclesAndCrossRoleAliasesAcrossReplicaPages() {
        val firstRequest = WireTestFixtures.bootstrapRequest()
        val first = WireResponseCodec.decode(
            200,
            WireTestFixtures.bytes("sync-bootstrap-response.json"),
            BootstrapResponseExpectation(firstRequest, ReplicaStreamValidationState()),
        ) as ValidatedBootstrapPage
        val pageCursorA = checkNotNull(first.page.nextPageCursor)
        val pageCursorB = "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDE"
        val secondRequest = WireTestFixtures.bootstrapRequest(
            "sync-bootstrap-page-2-request.json",
        )
        val secondRoot = WireTestFixtures.withPageHash(
            WireJsonObject(
                WireTestFixtures.objectFrom("sync-bootstrap-page-2-response.json").properties +
                    mapOf(
                        "complete" to false.asJson(),
                        "next_page_cursor" to pageCursorB.asJson(),
                    ),
            ),
        )
        val second = WireResponseCodec.decode(
            200,
            StrictJson.canonicalBytes(secondRoot),
            BootstrapResponseExpectation(secondRequest, first.nextState),
        ) as ValidatedBootstrapPage
        val thirdRequest = secondRequest.copy(
            requestId = "82000000-0000-4000-8000-000000000020",
            pageCursor = pageCursorB,
        )
        val cycleRoot = WireTestFixtures.withPageHash(
            WireJsonObject(
                WireTestFixtures.objectFrom("sync-bootstrap-page-2-response.json").properties +
                    mapOf(
                        "request_id" to thirdRequest.requestId.asJson(),
                        "from_page_cursor" to pageCursorB.asJson(),
                        "complete" to false.asJson(),
                        "next_page_cursor" to pageCursorA.asJson(),
                    ),
            ),
        )
        assertFailure(WireProtocolFailure.PAGE_INVARIANT) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(cycleRoot),
                BootstrapResponseExpectation(thirdRequest, second.nextState),
            )
        }

        val aliasRoot = WireTestFixtures.objectFrom("sync-bootstrap-response.json")
        val incrementalCursor = aliasRoot.requireString("incremental_cursor")
        val aliasedRoles = WireTestFixtures.withPageHash(
            WireTestFixtures.withProperty(
                aliasRoot,
                "next_page_cursor",
                incrementalCursor.asJson(),
            ),
        )
        assertFailure(WireProtocolFailure.PAGE_INVARIANT) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(aliasedRoles),
                BootstrapResponseExpectation(firstRequest, ReplicaStreamValidationState()),
            )
        }

        val completedStandard = WireResponseCodec.decode(
            200,
            WireTestFixtures.bytes("sync-bootstrap-page-2-response.json"),
            BootstrapResponseExpectation(secondRequest, first.nextState),
        ) as ValidatedBootstrapPage
        val crossRoleRequest = PullRequest(
            requestId = "85000000-0000-4000-8000-000000000020",
            deviceId = secondRequest.deviceId,
            cursor = checkNotNull(completedStandard.nextState.expectedPullCursor),
            pageSize = 100,
        )
        val crossRoleRoot = WireTestFixtures.withPageHash(
            WireTestFixtures.withProperty(
                pullResponseFor("sync-pull-response.json", crossRoleRequest),
                "next_cursor",
                pageCursorA.asJson(),
            ),
        )
        assertFailure(WireProtocolFailure.PAGE_INVARIANT) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(crossRoleRoot),
                PullResponseExpectation(crossRoleRequest, completedStandard.nextState),
            )
        }

        val replacement = WireResponseCodec.decode(
            200,
            WireTestFixtures.bytes("sync-bootstrap-replacement-response.json"),
            BootstrapResponseExpectation(
                WireTestFixtures.bootstrapRequest("sync-bootstrap-replacement-request.json"),
                ReplicaStreamValidationState(),
            ),
        ) as ValidatedBootstrapPage
        val firstPullRequest = WireTestFixtures.pullRequest()
        val firstPull = WireResponseCodec.decode(
            200,
            WireTestFixtures.bytes("sync-pull-response.json"),
            PullResponseExpectation(firstPullRequest, replacement.nextState),
        ) as ValidatedPullPage
        val continuationRequest = WireTestFixtures.pullRequest("sync-pull-page-2-request.json")
        val pullCycleRoot = WireTestFixtures.withPageHash(
            WireTestFixtures.withProperty(
                WireTestFixtures.objectFrom("sync-pull-page-2-response.json"),
                "next_cursor",
                firstPullRequest.cursor.asJson(),
            ),
        )
        assertFailure(WireProtocolFailure.PAGE_INVARIANT) {
            WireResponseCodec.decode(
                200,
                StrictJson.canonicalBytes(pullCycleRoot),
                PullResponseExpectation(continuationRequest, firstPull.nextState),
            )
        }
    }

    @Test
    fun allIdentifierBearingResponseTypesRenderOnlyRedactedSummaries() {
        val request = WireTestFixtures.bootstrapRequest()
        val decoded = WireResponseCodec.decode(
            200,
            WireTestFixtures.bytes("sync-bootstrap-response.json"),
            BootstrapResponseExpectation(request, ReplicaStreamValidationState()),
        ) as ValidatedBootstrapPage
        val sensitiveFragments = listOf(
            request.requestId,
            request.bootstrapId,
            request.deviceId,
            decoded.page.incrementalCursor,
            decoded.page.pageId,
            decoded.page.changes.first().eventId,
        )
        val rendered = listOf(
            request,
            decoded,
            decoded.page,
            decoded.nextState,
            decoded.page.changes.first(),
            decoded.page.changes.first().event,
        ).joinToString("|")
        sensitiveFragments.forEach { fragment -> assertFalse(rendered.contains(fragment)) }
        assertTrue(rendered.contains("redacted=true"))
    }

    private fun assertApiError(
        name: String,
        status: Int,
        expectation: ResponseExpectation,
        expectedCode: ApiErrorCode,
    ) {
        val decoded = WireResponseCodec.decode(
            status,
            WireTestFixtures.bytes(name),
            expectation,
        ) as DecodedApiError
        assertEquals(expectedCode, decoded.value.errorCode)
        assertEquals(status, decoded.value.httpStatus)
        assertFalse(decoded.toString().contains(decoded.value.requestId.orEmpty()))
    }

    private fun apiError(
        root: WireJsonObject,
        requestId: WireJsonValue,
        code: ApiErrorCode,
        status: Int,
    ): WireJsonObject = WireTestFixtures.withProperty(
        WireTestFixtures.withProperty(
            WireTestFixtures.withProperty(root, "request_id", requestId),
            "error_code",
            code.wireName.asJson(),
        ),
        "http_status",
        status.asJson(),
    )

    private fun rehashCapture(capture: WireJsonObject): WireJsonObject {
        val contentBytes = StrictJson.canonicalBytes(capture.requireObject("content"))
        val integrity = capture.requireObject("integrity")
        return WireTestFixtures.withProperty(
            capture,
            "integrity",
            WireTestFixtures.withProperty(
                WireTestFixtures.withProperty(
                    integrity,
                    "sha256",
                    sha256Hex(contentBytes).asJson(),
                ),
                "byte_size",
                contentBytes.size.asJson(),
            ),
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

    private fun selfParentOperation(template: WireJsonObject): WireJsonObject {
        val body = template.requireObject("body")
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
        val changed = WireTestFixtures.withProperty(
            WireTestFixtures.withProperty(
                template,
                "expected_current_revision_id",
                revisionId.asJson(),
            ),
            "body",
            changedBody,
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

    private fun expectedCurrentMismatchOperation(template: WireJsonObject): WireJsonObject {
        val changed = WireTestFixtures.withProperty(
            template,
            "expected_current_revision_id",
            "83000000-0000-4000-8000-000000000001".asJson(),
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

    private fun pendingCapture(committed: WireJsonObject): WireJsonObject =
        WireTestFixtures.withProperty(
            WireTestFixtures.withProperty(
                committed,
                "persistence_state",
                "local_pending".asJson(),
            ),
            "identity",
            WireTestFixtures.withProperty(
                committed.requireObject("identity"),
                "device_id",
                WireJsonNull,
            ),
        )

    private fun pendingEvent(committed: WireJsonObject): WireJsonObject =
        WireTestFixtures.withProperty(
            WireTestFixtures.withProperty(
                WireTestFixtures.withProperty(
                    committed,
                    "persistence_state",
                    "local_pending".asJson(),
                ),
                "identity",
                WireTestFixtures.withProperty(
                    committed.requireObject("identity"),
                    "device_id",
                    WireJsonNull,
                ),
            ),
            "server",
            WireJsonObject(
                mapOf(
                    "received_at" to WireJsonNull,
                    "server_sequence" to WireJsonNull,
                ),
            ),
        )

    private fun rootOperationVariant(
        template: WireJsonObject,
        ordinal: Int,
        clientSequence: Long = 10,
        operationId: String = "95000000-0000-4000-8000-000000000010",
        captureId: String = "94000000-0000-4000-8000-000000000010",
        eventId: String = "92000000-0000-4000-8000-000000000010",
        revisionId: String = "93000000-0000-4000-8000-000000000010",
    ): WireJsonObject {
        val capture = WireTestFixtures.withProperty(
            WireTestFixtures.withProperty(
                template.requireObject("capture"),
                "capture_id",
                captureId.asJson(),
            ),
            "operation_id",
            operationId.asJson(),
        )
        val originalEvent = template.requireObject("body")
        val source = WireTestFixtures.withProperty(
            WireTestFixtures.withProperty(
                originalEvent.requireObject("source"),
                "capture_id",
                captureId.asJson(),
            ),
            "operation_id",
            operationId.asJson(),
        )
        val event = rehashRevision(
            WireTestFixtures.withProperty(
                WireTestFixtures.withProperty(
                    WireTestFixtures.withProperty(
                        originalEvent,
                        "event_id",
                        eventId.asJson(),
                    ),
                    "revision_id",
                    revisionId.asJson(),
                ),
                "source",
                source,
            ),
        )
        val changed = WireJsonObject(
            template.properties + mapOf(
                "ordinal" to ordinal.asJson(),
                "client_sequence" to clientSequence.asJson(),
                "operation_id" to operationId.asJson(),
                "capture_id" to captureId.asJson(),
                "event_id" to eventId.asJson(),
                "revision_id" to revisionId.asJson(),
                "capture" to capture,
                "body" to event,
            ),
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

    private fun childOperationVariant(
        template: WireJsonObject,
        ordinal: Int,
        clientSequence: Long,
        operationId: String,
        captureId: String,
        eventId: String,
        revisionId: String,
        parentRevisionId: String,
        revisionNo: Int = 2,
    ): WireJsonObject {
        val capture = WireTestFixtures.withProperty(
            WireTestFixtures.withProperty(
                template.requireObject("capture"),
                "capture_id",
                captureId.asJson(),
            ),
            "operation_id",
            operationId.asJson(),
        )
        val originalEvent = template.requireObject("body")
        val source = WireTestFixtures.withProperty(
            WireTestFixtures.withProperty(
                originalEvent.requireObject("source"),
                "capture_id",
                captureId.asJson(),
            ),
            "operation_id",
            operationId.asJson(),
        )
        val revision = WireTestFixtures.withProperty(
            originalEvent.requireObject("revision"),
            "parents",
            jsonArrayOf(
                listOf(
                    jsonObjectOf(
                        "revision_id" to parentRevisionId.asJson(),
                        "relation" to "supersedes".asJson(),
                    ),
                ),
            ),
        )
        val event = rehashRevision(
            WireJsonObject(
                originalEvent.properties + mapOf(
                    "event_id" to eventId.asJson(),
                    "revision_id" to revisionId.asJson(),
                    "revision_no" to revisionNo.asJson(),
                    "source" to source,
                    "revision" to revision,
                ),
            ),
        )
        val changed = WireJsonObject(
            template.properties + mapOf(
                "ordinal" to ordinal.asJson(),
                "client_sequence" to clientSequence.asJson(),
                "operation_id" to operationId.asJson(),
                "capture_id" to captureId.asJson(),
                "event_id" to eventId.asJson(),
                "revision_id" to revisionId.asJson(),
                "expected_current_revision_id" to parentRevisionId.asJson(),
                "capture" to capture,
                "body" to event,
            ),
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

    private fun pushRequestWithOperations(
        batchId: String,
        operations: List<WireJsonValue>,
    ): WireJsonObject {
        val frozen = WireTestFixtures.objectFrom("sync-push-batch-request.json")
        val changed = WireJsonObject(
            frozen.properties + mapOf(
                "batch_id" to batchId.asJson(),
                "operations" to WireJsonArray(operations),
            ),
        )
        return WireTestFixtures.withProperty(
            changed,
            "batch_content_sha256",
            StrictJson.canonicalSha256(changed.without("batch_content_sha256")).asJson(),
        )
    }

    private fun bootstrapResponseFor(
        fixture: String,
        request: BootstrapRequest,
    ): WireJsonObject {
        val root = WireTestFixtures.objectFrom(fixture)
        return WireTestFixtures.withPageHash(
            WireJsonObject(
                root.properties + mapOf(
                    "request_id" to request.requestId.asJson(),
                    "bootstrap_id" to request.bootstrapId.asJson(),
                    "device_id" to request.deviceId.asJson(),
                    "from_page_cursor" to request.pageCursor.asNullableJson(),
                ),
            ),
        )
    }

    private fun pullResponseFor(
        fixture: String,
        request: PullRequest,
    ): WireJsonObject {
        val root = WireTestFixtures.objectFrom(fixture)
        return WireTestFixtures.withPageHash(
            WireJsonObject(
                root.properties + mapOf(
                    "request_id" to request.requestId.asJson(),
                    "device_id" to request.deviceId.asJson(),
                    "from_cursor" to request.cursor.asJson(),
                ),
            ),
        )
    }

    private fun changeWithServerSequence(
        change: WireJsonObject,
        sequence: Long,
    ): WireJsonObject {
        val event = change.requireObject("event")
        val changedEvent = WireTestFixtures.withProperty(
            event,
            "server",
            WireTestFixtures.withProperty(
                event.requireObject("server"),
                "server_sequence",
                sequence.asJson(),
            ),
        )
        return WireTestFixtures.withProperty(
            WireTestFixtures.withProperty(change, "server_sequence", sequence.asJson()),
            "event",
            changedEvent,
        )
    }

    private fun pushCollisionResponse(
        request: PushBatchRequest,
        errorCode: PushOperationErrorCode,
    ): WireJsonObject {
        val frozen = WireTestFixtures.objectFrom("sync-push-batch-response.json")
        val firstAck = frozen.requireArray("results").elements.first()
        val second = request.operations[1]
        val error = jsonObjectOf(
            "ordinal" to 1.asJson(),
            "operation_id" to second.operationId.asJson(),
            "status" to "error".asJson(),
            "operation_content_sha256" to second.operationContentSha256.asJson(),
            "error_code" to errorCode.wireName.asJson(),
            "retryable" to errorCode.retryable.asJson(),
            "field_errors" to jsonArrayOf(emptyList()),
        )
        return WireJsonObject(
            frozen.properties + mapOf(
                "batch_id" to request.batchId.asJson(),
                "results" to WireJsonArray(listOf(firstAck, error)),
            ),
        )
    }

    private fun pushErrorDocument(
        operation: PushOperationWire,
        ordinal: Int,
        errorCode: PushOperationErrorCode,
    ): WireJsonObject = jsonObjectOf(
        "ordinal" to ordinal.asJson(),
        "operation_id" to operation.operationId.asJson(),
        "status" to "error".asJson(),
        "operation_content_sha256" to operation.operationContentSha256.asJson(),
        "error_code" to errorCode.wireName.asJson(),
        "retryable" to errorCode.retryable.asJson(),
        "field_errors" to jsonArrayOf(emptyList()),
    )

    private fun pushAckDocument(
        operation: PushOperationWire,
        ordinal: Int,
        resultCode: PushResultCode,
        replayed: Boolean,
        sequence: Long,
        currentRevisionId: String,
    ): WireJsonObject = jsonObjectOf(
        "ordinal" to ordinal.asJson(),
        "operation_id" to operation.operationId.asJson(),
        "status" to "ack".asJson(),
        "operation_content_sha256" to operation.operationContentSha256.asJson(),
        "result_code" to resultCode.wireName.asJson(),
        "replayed" to replayed.asJson(),
        "capture_id" to operation.captureId.asJson(),
        "event_id" to operation.eventId.asJson(),
        "revision_id" to operation.revisionId.asJson(),
        "current_revision_id" to currentRevisionId.asJson(),
        "server_sequence" to sequence.asJson(),
        "committed_at" to "2030-01-01T00:00:01Z".asJson(),
    )

    private fun pushResponseWithResults(
        request: PushBatchRequest,
        results: List<WireJsonValue>,
    ): WireJsonObject {
        val frozen = WireTestFixtures.objectFrom("sync-push-batch-response.json")
        return WireJsonObject(
            frozen.properties + mapOf(
                "batch_id" to request.batchId.asJson(),
                "results" to WireJsonArray(results),
            ),
        )
    }

    private fun pushDuplicateAckResponse(request: PushBatchRequest): WireJsonObject {
        val frozen = WireTestFixtures.objectFrom("sync-push-batch-response.json")
        val firstAck = frozen.requireArray("results").elements.first() as WireJsonObject
        val secondAck = WireTestFixtures.withProperty(
            WireTestFixtures.withProperty(firstAck, "ordinal", 1.asJson()),
            "server_sequence",
            10.asJson(),
        )
        return WireJsonObject(
            frozen.properties + mapOf(
                "batch_id" to request.batchId.asJson(),
                "results" to WireJsonArray(listOf(firstAck, secondAck)),
            ),
        )
    }

    private fun pushAckThenErrorsResponse(
        request: PushBatchRequest,
        errorCodes: List<PushOperationErrorCode>,
    ): WireJsonObject {
        require(errorCodes.size == request.operations.size - 1)
        val frozen = WireTestFixtures.objectFrom("sync-push-batch-response.json")
        val firstAck = frozen.requireArray("results").elements.first()
        val errors = request.operations.drop(1).mapIndexed { index, operation ->
            val ordinal = index + 1
            val errorCode = errorCodes[index]
            jsonObjectOf(
                "ordinal" to ordinal.asJson(),
                "operation_id" to operation.operationId.asJson(),
                "status" to "error".asJson(),
                "operation_content_sha256" to operation.operationContentSha256.asJson(),
                "error_code" to errorCode.wireName.asJson(),
                "retryable" to errorCode.retryable.asJson(),
                "field_errors" to jsonArrayOf(emptyList()),
            )
        }
        return WireJsonObject(
            frozen.properties + mapOf(
                "batch_id" to request.batchId.asJson(),
                "results" to WireJsonArray(listOf(firstAck) + errors),
            ),
        )
    }

    private fun pushAppliedAckResponse(
        request: PushBatchRequest,
        sequences: List<Long>,
        replayed: List<Boolean>,
    ): WireJsonObject {
        require(sequences.size == request.operations.size)
        require(replayed.size == request.operations.size)
        val frozen = WireTestFixtures.objectFrom("sync-push-batch-response.json")
        val results = request.operations.mapIndexed { ordinal, operation ->
            jsonObjectOf(
                "ordinal" to ordinal.asJson(),
                "operation_id" to operation.operationId.asJson(),
                "status" to "ack".asJson(),
                "operation_content_sha256" to operation.operationContentSha256.asJson(),
                "result_code" to PushResultCode.APPLIED.wireName.asJson(),
                "replayed" to replayed[ordinal].asJson(),
                "capture_id" to operation.captureId.asJson(),
                "event_id" to operation.eventId.asJson(),
                "revision_id" to operation.revisionId.asJson(),
                "current_revision_id" to operation.revisionId.asJson(),
                "server_sequence" to sequences[ordinal].asJson(),
                "committed_at" to "2030-01-01T00:00:01Z".asJson(),
            )
        }
        return WireJsonObject(
            frozen.properties + mapOf(
                "batch_id" to request.batchId.asJson(),
                "results" to WireJsonArray(results),
            ),
        )
    }

    private fun pushAllErrorResponse(
        request: PushBatchRequest,
        errorCode: PushOperationErrorCode,
    ): WireJsonObject = pushErrorResponse(request, List(request.operations.size) { errorCode })

    private fun pushErrorResponse(
        request: PushBatchRequest,
        errorCodes: List<PushOperationErrorCode>,
    ): WireJsonObject {
        require(errorCodes.size == request.operations.size)
        val frozen = WireTestFixtures.objectFrom("sync-push-batch-response.json")
        val results = request.operations.mapIndexed { ordinal, operation ->
            val errorCode = errorCodes[ordinal]
            jsonObjectOf(
                "ordinal" to ordinal.asJson(),
                "operation_id" to operation.operationId.asJson(),
                "status" to "error".asJson(),
                "operation_content_sha256" to operation.operationContentSha256.asJson(),
                "error_code" to errorCode.wireName.asJson(),
                "retryable" to errorCode.retryable.asJson(),
                "field_errors" to jsonArrayOf(emptyList()),
            )
        }
        return WireJsonObject(
            frozen.properties + mapOf(
                "batch_id" to request.batchId.asJson(),
                "results" to WireJsonArray(results),
            ),
        )
    }

    private fun assertFailure(expected: WireProtocolFailure, block: () -> Unit) {
        val error = assertThrows(WireProtocolException::class.java, block)
        assertEquals(expected, error.failure)
    }
}
