package ru.andriyshkoy.lifeagent.data.sync.wire

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StrictJsonTest {
    @Test
    fun canonicalWriterUsesJcsMemberOrderAndMinimalEscapes() {
        val value = StrictJson.parse(
            """{"z":"/","a":"\u000f\b\ud83d\ude00","n":-0}"""
                .toByteArray(),
            StrictJsonLimits.request(1024),
        )

        assertEquals(
            """{"a":"\u000f\b😀","n":0,"z":"/"}""",
            StrictJson.canonicalBytes(value).toString(StandardCharsets.UTF_8),
        )
    }

    @Test
    fun rejectsBomAndMalformedUtf8() {
        assertFailure(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x7B, 0x7D)) {
            it == StrictJsonFailure.BOM
        }
        assertFailure(byteArrayOf(0x22, 0xC3.toByte(), 0x28, 0x22)) {
            it == StrictJsonFailure.MALFORMED_UTF8
        }
    }

    @Test
    fun rejectsDuplicateKeysAtEveryDepth() {
        assertFailure("""{"a":1,"a":2}""".toByteArray()) {
            it == StrictJsonFailure.DUPLICATE_KEY
        }
        assertFailure("""{"a":[{"b":1,"b":2}]}""".toByteArray()) {
            it == StrictJsonFailure.DUPLICATE_KEY
        }
    }

    @Test
    fun rejectsFloatsExponentsAndUnsafeIntegers() {
        listOf("1.0", "1e0", "1E+2", "-0.1").forEach { text ->
            assertFailure(text.toByteArray()) { it == StrictJsonFailure.UNSUPPORTED_NUMBER }
        }
        listOf("9007199254740992", "-9007199254740992", "999999999999999999999").forEach { text ->
            assertFailure(text.toByteArray()) { it == StrictJsonFailure.UNSAFE_INTEGER }
        }
        val maximum = StrictJson.parse(
            JSON_SAFE_INTEGER_MAX.toString().toByteArray(),
            StrictJsonLimits.request(64),
        ) as WireJsonInteger
        assertEquals(JSON_SAFE_INTEGER_MAX, maximum.value)
    }

    @Test
    fun rejectsLoneSurrogatesAndTrailingInput() {
        listOf("\"\\ud800\"", "\"\\udc00\"", "\"\\ud800\\u0041\"").forEach { text ->
            assertFailure(text.toByteArray()) { it == StrictJsonFailure.INVALID_SURROGATE }
        }
        assertFailure("{} []".toByteArray()) { it == StrictJsonFailure.TRAILING_INPUT }
    }

    @Test
    fun requestDepthThirtyTwoIsFrozen() {
        val accepted = "[".repeat(32) + "0" + "]".repeat(32)
        StrictJson.parse(accepted.toByteArray(), StrictJsonLimits.request(1024))
        val rejected = "[".repeat(33) + "0" + "]".repeat(33)
        assertFailure(rejected.toByteArray()) { it == StrictJsonFailure.STRUCTURAL_LIMIT }
    }

    @Test
    fun requestTenThousandNodeLimitIsFrozen() {
        val belowLimit = buildString {
            append('[')
            repeat(999) { outer ->
                if (outer > 0) append(',')
                append("[0,0,0,0,0,0,0,0,0]")
            }
            append(']')
        }
        StrictJson.parse(belowLimit.toByteArray(), StrictJsonLimits.request(64 * 1024))

        val aboveLimit = buildString {
            append('[')
            repeat(1000) { outer ->
                if (outer > 0) append(',')
                append("[0,0,0,0,0,0,0,0,0]")
            }
            append(']')
        }
        assertFailure(aboveLimit.toByteArray(), 64 * 1024) {
            it == StrictJsonFailure.STRUCTURAL_LIMIT
        }
    }

    @Test
    fun rejectsNonSubsetKeysAndContainerCardinality() {
        assertFailure("""{"Upper":1}""".toByteArray()) {
            it == StrictJsonFailure.SHAPE_MISMATCH
        }
        val tooMany = "[" + List(1001) { "0" }.joinToString(",") + "]"
        assertFailure(tooMany.toByteArray(), 4096) {
            it == StrictJsonFailure.STRUCTURAL_LIMIT
        }
    }

    @Test
    fun responseNodeLimitIsCapDerivedNotRequestLimit() {
        val cap = M2Endpoint.SYNC_BOOTSTRAP.successMaxBytes
        val limits = StrictJsonLimits.response(cap)
        assertEquals(2_097_153, limits.maxNodes)
        assertNotEquals(10_000, limits.maxNodes)
    }

    @Test
    fun denseLegalJsonAtErrorCapParsesWithoutArbitraryNodeRejection() {
        val cap = M2_API_ERROR_MAX_BYTES
        val elementCount = 8_191
        val text = "[" + List(elementCount) { "0" }.joinToString(",") + "] "
        assertEquals(cap, text.toByteArray().size)
        val parsed = StrictJson.parse(text.toByteArray(), StrictJsonLimits.response(cap))
            as WireJsonArray
        assertEquals(elementCount, parsed.elements.size)
    }

    @Test
    fun fourMiBDenseWrongPageFailsAtSchemaCardinalityWithoutExpansion() {
        val cap = M2Endpoint.SYNC_BOOTSTRAP.successMaxBytes
        val body = ByteArray(cap) { ' '.code.toByte() }
        val prefix = buildString {
            append("{\"changes\":[")
            repeat(501) { index ->
                if (index > 0) append(',')
                append("{}")
            }
        }.toByteArray()
        prefix.copyInto(body)

        val error = expectStrictFailure {
            StrictJson.parse(
                body,
                StrictJsonLimits.response(cap),
                WireResponseShape(M2Endpoint.SYNC_BOOTSTRAP, apiError = false),
            )
        }
        assertEquals(StrictJsonFailure.STRUCTURAL_LIMIT, error.failure)
    }

    @Test
    fun fourMiBTrailingWhitespaceScansWithoutPerCharacterAllocation() {
        val cap = M2Endpoint.SYNC_BOOTSTRAP.successMaxBytes
        val accepted = ByteArray(cap) { ' '.code.toByte() }
        accepted[0] = '{'.code.toByte()
        accepted[1] = '}'.code.toByte()
        assertEquals(
            emptyMap<String, WireJsonValue>(),
            (StrictJson.parse(accepted, StrictJsonLimits.response(cap)) as WireJsonObject)
                .properties,
        )

        val trailing = accepted.copyOf()
        trailing[cap - 2] = '['.code.toByte()
        trailing[cap - 1] = ']'.code.toByte()
        val error = expectStrictFailure {
            StrictJson.parse(trailing, StrictJsonLimits.response(cap))
        }
        assertEquals(StrictJsonFailure.TRAILING_INPUT, error.failure)
    }

    @Test
    fun capDenseSchemaAllowedEvidenceUsesCompactStorage() {
        val cap = M2Endpoint.SYNC_BOOTSTRAP.successMaxBytes
        val prefix = "{\"changes\":[{\"event\":{\"evidence\":["
        val item = """{"artifact_id":null,"capture_ref":"#/source/capture_id","excerpt":null,"field_path":"/payload","human_confirmed":true,"locator":null}"""
        val suffix = "]}}]}"
        val count = (cap - prefix.length - suffix.length + 1) / (item.length + 1)
        val text = buildString(cap) {
            append(prefix)
            repeat(count) { index ->
                if (index > 0) append(',')
                append(item)
            }
            append(suffix)
            while (length < cap) append(' ')
        }
        assertEquals(cap, text.toByteArray().size)

        val root = StrictJson.parse(
            text.toByteArray(),
            StrictJsonLimits.response(cap),
            WireResponseShape(M2Endpoint.SYNC_BOOTSTRAP, apiError = false),
        ) as WireJsonObject
        val evidence = ((root.requireArray("changes").elements.single() as WireJsonObject)
            .requireObject("event"))
            .requireArray("evidence")
        assertTrue(evidence.elements is CompactJsonElements)
        assertEquals(count, evidence.elements.size)
        assertEquals(evidence.elements.first(), evidence.elements.last())
    }

    @Test
    fun compactUniqueQualityFlagsRejectDuplicateJsonValues() {
        val body = """{"changes":[{"event":{"quality_flags":["duplicate","du\u0070licate"]}}]}"""
            .toByteArray()
        val error = expectStrictFailure {
            StrictJson.parse(
                body,
                StrictJsonLimits.response(4096),
                WireResponseShape(M2Endpoint.SYNC_PULL, apiError = false),
            )
        }
        assertEquals(StrictJsonFailure.SHAPE_MISMATCH, error.failure)
    }

    private fun assertFailure(
        bytes: ByteArray,
        byteLimit: Int = 4096,
        predicate: (StrictJsonFailure) -> Boolean,
    ) {
        val error = expectStrictFailure {
            StrictJson.parse(bytes, StrictJsonLimits.request(byteLimit))
        }
        assertTrue("unexpected failure ${error.failure}", predicate(error.failure))
    }

    private fun expectStrictFailure(block: () -> Unit): StrictJsonException = try {
        block()
        fail("expected strict JSON rejection")
        throw AssertionError()
    } catch (error: StrictJsonException) {
        error
    }
}
