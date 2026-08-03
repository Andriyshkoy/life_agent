package ru.andriyshkoy.lifeagent.data.sync.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WireFreshReplicaPageCodecTest {
    @Test
    fun decodesBootstrapAndPullWithStrictPageLocalFactsOnly() {
        val bootstrapRequest = WireTestFixtures.bootstrapRequest()
        val bootstrapBody = WireTestFixtures.bytes("sync-bootstrap-response.json")
        val bootstrap = WireResponseCodec.decodeFreshReplicaPage(
            httpStatus = 200,
            body = bootstrapBody,
            expectation = FreshBootstrapPageExpectation(bootstrapRequest),
        ) as FreshBootstrapPage
        assertEquals(bootstrapRequest.requestId, bootstrap.page.requestId)
        assertEquals(bootstrapRequest.bootstrapId, bootstrap.page.bootstrapId)
        assertEquals(bootstrapRequest.deviceId, bootstrap.page.deviceId)
        assertEquals(2, bootstrap.page.changes.size)
        assertEquals(requestSha256(bootstrapRequest), bootstrap.requestBodySha256)
        assertEquals(sha256Hex(bootstrapBody), bootstrap.responseBodySha256)

        val pullRequest = WireTestFixtures.pullRequest()
        val pullBody = WireTestFixtures.bytes("sync-pull-response.json")
        val pull = WireResponseCodec.decodeFreshReplicaPage(
            httpStatus = 200,
            body = pullBody,
            expectation = FreshPullPageExpectation(
                request = pullRequest,
                persistedRequestBodySha256 = requestSha256(pullRequest),
            ),
        ) as FreshPullPage
        assertEquals(pullRequest.requestId, pull.page.requestId)
        assertEquals(pullRequest.cursor, pull.page.fromCursor)
        assertTrue(pull.page.hasMore)
        assertEquals(1, pull.page.changes.size)
        assertEquals(requestSha256(pullRequest), pull.requestBodySha256)
        assertEquals(sha256Hex(pullBody), pull.responseBodySha256)
    }

    @Test
    fun rejectsPullAndBootstrapCursorSelfAliasesAfterRehashing() {
        val pullRequest = WireTestFixtures.pullRequest()
        val pullRoot = WireTestFixtures.objectFrom("sync-pull-response.json")
        val pullSelfLoop = WireTestFixtures.withPageHash(
            WireTestFixtures.withProperty(
                pullRoot,
                "next_cursor",
                pullRoot.requireString("from_cursor").asJson(),
            ),
        )
        assertPageFailure {
            WireResponseCodec.decodeFreshReplicaPage(
                httpStatus = 200,
                body = StrictJson.canonicalBytes(pullSelfLoop),
                expectation = FreshPullPageExpectation(pullRequest),
            )
        }

        val bootstrapRequest = WireTestFixtures.bootstrapRequest(
            "sync-bootstrap-page-2-request.json",
        )
        val bootstrapRoot = WireTestFixtures.objectFrom(
            "sync-bootstrap-page-2-response.json",
        )
        val bootstrapSelfAlias = WireTestFixtures.withPageHash(
            WireTestFixtures.withProperty(
                bootstrapRoot,
                "incremental_cursor",
                bootstrapRoot.requireString("from_page_cursor").asJson(),
            ),
        )
        assertPageFailure {
            WireResponseCodec.decodeFreshReplicaPage(
                httpStatus = 200,
                body = StrictJson.canonicalBytes(bootstrapSelfAlias),
                expectation = FreshBootstrapPageExpectation(bootstrapRequest),
            )
        }
    }

    @Test
    fun rejectsWrongStatusAndPersistedRequestDigest() {
        val request = WireTestFixtures.pullRequest()
        val body = WireTestFixtures.bytes("sync-pull-response.json")
        val wrongStatus = assertThrows(WireProtocolException::class.java) {
            WireResponseCodec.decodeFreshReplicaPage(
                httpStatus = 409,
                body = body,
                expectation = FreshPullPageExpectation(request),
            )
        }
        assertEquals(WireProtocolFailure.STATUS_ERROR_MISMATCH, wrongStatus.failure)

        assertPageFailure {
            WireResponseCodec.decodeFreshReplicaPage(
                httpStatus = 200,
                body = body,
                expectation = FreshPullPageExpectation(
                    request = request,
                    persistedRequestBodySha256 = "e".repeat(64),
                ),
            )
        }
    }

    @Test
    fun freshProductionTypesContainNoSyntheticStreamOrReplayState() {
        listOf(FreshBootstrapPage::class.java, FreshPullPage::class.java).forEach { type ->
            val fields = type.declaredFields.toList()
            assertFalse(
                "$type must not carry ReplicaStreamValidationState",
                fields.any { it.type == ReplicaStreamValidationState::class.java },
            )
            assertFalse(fields.any { it.name == "nextState" || it.name == "replayed" })
        }
    }

    private fun requestSha256(request: M2WireRequest): String =
        WireRequestCodec.materialize(request).use { it.bodySha256 }

    private fun assertPageFailure(block: () -> Unit) {
        val failure = assertThrows(WireProtocolException::class.java, block)
        assertEquals(WireProtocolFailure.PAGE_INVARIANT, failure.failure)
    }
}
