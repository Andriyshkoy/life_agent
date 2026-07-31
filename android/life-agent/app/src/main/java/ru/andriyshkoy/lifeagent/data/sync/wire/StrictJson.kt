package ru.andriyshkoy.lifeagent.data.sync.wire

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val JSON_SAFE_INTEGER_MAX: Long = 9_007_199_254_740_991L

internal enum class StrictJsonFailure {
    BYTE_LIMIT,
    BOM,
    MALFORMED_UTF8,
    MALFORMED_JSON,
    DUPLICATE_KEY,
    UNSUPPORTED_NUMBER,
    UNSAFE_INTEGER,
    INVALID_SURROGATE,
    TRAILING_INPUT,
    STRUCTURAL_LIMIT,
    SHAPE_MISMATCH,
}

/** Deliberately content-free: payload fragments never enter logs through an exception. */
internal class StrictJsonException(
    val failure: StrictJsonFailure,
) : IllegalArgumentException("strict JSON rejected: ${failure.name.lowercase()}")

internal sealed interface WireJsonValue

internal class WireJsonObject(
    properties: Map<String, WireJsonValue>,
) : WireJsonValue {
    val properties: Map<String, WireJsonValue> = properties.toMap()

    override fun equals(other: Any?): Boolean =
        other is WireJsonObject && properties == other.properties

    override fun hashCode(): Int = properties.hashCode()

    override fun toString(): String = "WireJsonObject(redacted=true,size=${properties.size})"
}

internal class WireJsonArray private constructor(
    val elements: List<WireJsonValue>,
    @Suppress("UNUSED_PARAMETER") ownership: CompactOwnership,
) : WireJsonValue {
    constructor(elements: List<WireJsonValue>) : this(elements.toList(), CompactOwnership)

    internal constructor(
        compactElements: CompactJsonElements,
    ) : this(compactElements, CompactOwnership)

    internal val hasParserVerifiedUniqueItems: Boolean
        get() = (elements as? CompactJsonElements)?.uniqueItemsVerified == true

    override fun equals(other: Any?): Boolean =
        other is WireJsonArray && elements == other.elements

    override fun hashCode(): Int = elements.hashCode()

    override fun toString(): String = "WireJsonArray(redacted=true,size=${elements.size})"
}

private data object CompactOwnership

/**
 * Array elements retained as one canonical character buffer plus primitive spans.
 *
 * This keeps schema-unbounded leaf arrays proportional to their wire bytes instead
 * of retaining one JVM object graph per item. Individual values are reconstructed
 * only for the duration of schema validation, hashing, or reducer consumption.
 */
internal class CompactJsonElements(
    private val canonicalElements: String,
    private val spans: LongArray,
    private val limits: StrictJsonLimits,
    val uniqueItemsVerified: Boolean,
) : AbstractList<WireJsonValue>() {
    override val size: Int
        get() = spans.size

    override fun get(index: Int): WireJsonValue {
        val span = spans[index]
        val start = (span ushr 32).toInt()
        val end = span.toInt()
        return StrictJson.parseTrustedSlice(canonicalElements, start, end, limits)
    }
}

internal class WireJsonString(
    val value: String,
) : WireJsonValue {
    override fun equals(other: Any?): Boolean =
        other is WireJsonString && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "WireJsonString(redacted=true,length=${value.length})"
}

internal class WireJsonInteger(
    val value: Long,
) : WireJsonValue {
    override fun equals(other: Any?): Boolean =
        other is WireJsonInteger && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "WireJsonInteger(redacted=true)"
}

internal class WireJsonBoolean(
    val value: Boolean,
) : WireJsonValue {
    override fun equals(other: Any?): Boolean =
        other is WireJsonBoolean && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "WireJsonBoolean(redacted=true)"
}

internal data object WireJsonNull : WireJsonValue {
    override fun toString(): String = "WireJsonNull"
}

internal enum class WireJsonKind {
    OBJECT,
    ARRAY,
    STRING,
    INTEGER,
    BOOLEAN,
    NULL,
}

/**
 * Optional schema-shaped early gates. They prevent a wrong-but-dense response
 * from first expanding into a large object graph. Array indices are represented
 * by `*`, so a path such as `changes.*.event.evidence` is stable for every item.
 */
internal interface StrictJsonShape {
    fun expectedKind(path: List<String>): WireJsonKind? = null

    fun allowedKinds(path: List<String>): Set<WireJsonKind>? =
        expectedKind(path)?.let(::setOf)

    fun allowedObjectKeys(path: List<String>): Set<String>? = null

    fun maxArrayItems(path: List<String>): Int? = null

    /** Store large schema-unbounded leaf arrays without a retained per-item AST. */
    fun compactArray(path: List<String>): Boolean = false

    /** Exact JSON-value uniqueness is checked before a compact array is returned. */
    fun uniqueArrayItems(path: List<String>): Boolean = false
}

internal data class StrictJsonLimits(
    val byteLimit: Int,
    val maxDepth: Int,
    val maxNodes: Int,
    val maxArrayItems: Int,
    val maxObjectMembers: Int,
    val maxStringCodePoints: Int,
    val requireSubsetPropertyNames: Boolean,
) {
    init {
        require(byteLimit > 0)
        require(maxDepth >= 0)
        require(maxNodes > 0)
        require(maxArrayItems >= 0)
        require(maxObjectMembers >= 0)
        require(maxStringCodePoints >= 0)
    }

    companion object {
        /** Frozen ingress subset; endpoint request caps are supplied separately. */
        fun request(byteLimit: Int): StrictJsonLimits =
            StrictJsonLimits(
                byteLimit = byteLimit,
                maxDepth = 32,
                maxNodes = 10_000,
                maxArrayItems = 1_000,
                maxObjectMembers = 256,
                maxStringCodePoints = 65_536,
                requireSubsetPropertyNames = true,
            )

        /**
         * Response nodes are not constrained by the request-side 10k limit.
         *
         * A JSON document with N value nodes needs at least `2 * (N - 1) - 1`
         * octets: the densest useful construction is one array plus one-byte
         * integer children separated by commas. Therefore
         * `floor((bodyBytes + 1) / 2) + 1` is a conservative syntactic upper
         * bound and cannot reject a document that fits the endpoint byte cap.
         * Endpoint shapes add the much tighter schema cardinalities while the
         * parser is still reading containers.
         *
         * The largest closed M2 response object is a life-event object with 18
         * members. The closed schemas never exceed depth 6; depth 32 leaves
         * headroom without introducing an unproved payload-cardinality limit.
         */
        fun response(byteLimit: Int): StrictJsonLimits =
            StrictJsonLimits(
                byteLimit = byteLimit,
                maxDepth = 32,
                maxNodes = ((byteLimit.toLong() + 1L) / 2L + 1L)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt(),
                maxArrayItems = ((byteLimit.toLong() + 1L) / 2L)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt(),
                maxObjectMembers = 18,
                maxStringCodePoints = byteLimit,
                requireSubsetPropertyNames = true,
            )
    }
}

internal object StrictJson {
    fun parse(
        bytes: ByteArray,
        limits: StrictJsonLimits,
        shape: StrictJsonShape? = null,
    ): WireJsonValue {
        if (bytes.size > limits.byteLimit) {
            throw StrictJsonException(StrictJsonFailure.BYTE_LIMIT)
        }
        if (
            bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            throw StrictJsonException(StrictJsonFailure.BOM)
        }
        val text = try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            throw StrictJsonException(StrictJsonFailure.MALFORMED_UTF8)
        }
        return Parser(text, limits, shape).parseDocument()
    }

    fun canonicalBytes(value: WireJsonValue): ByteArray {
        val output = StringBuilder()
        CanonicalWriter(output).write(value, depth = 0)
        return output.toString().toByteArray(StandardCharsets.UTF_8)
    }

    fun canonicalSha256(value: WireJsonValue): String =
        MessageDigest.getInstance("SHA-256")
            .digest(canonicalBytes(value))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    internal fun parseTrustedSlice(
        source: String,
        start: Int,
        end: Int,
        limits: StrictJsonLimits,
    ): WireJsonValue = Parser(
        source = source,
        limits = limits,
        shape = null,
        startIndex = start,
        endIndex = end,
    ).parseDocument()

    private class Parser(
        private val source: String,
        private val limits: StrictJsonLimits,
        private val shape: StrictJsonShape?,
        startIndex: Int = 0,
        private val endIndex: Int = source.length,
    ) {
        private var index = startIndex
        private var nodes = 0

        fun parseDocument(): WireJsonValue {
            skipWhitespace()
            val value = parseValue(depth = 0, path = emptyList())
            skipWhitespace()
            if (index != endIndex) {
                fail(StrictJsonFailure.TRAILING_INPUT)
            }
            return value
        }

        private fun parseValue(depth: Int, path: List<String>): WireJsonValue {
            if (depth > limits.maxDepth) {
                fail(StrictJsonFailure.STRUCTURAL_LIMIT)
            }
            nodes += 1
            if (nodes > limits.maxNodes) {
                fail(StrictJsonFailure.STRUCTURAL_LIMIT)
            }
            if (index >= endIndex) {
                fail(StrictJsonFailure.MALFORMED_JSON)
            }
            val actualKind = when (source[index]) {
                '{' -> WireJsonKind.OBJECT
                '[' -> WireJsonKind.ARRAY
                '"' -> WireJsonKind.STRING
                't', 'f' -> WireJsonKind.BOOLEAN
                'n' -> WireJsonKind.NULL
                '-', in '0'..'9' -> WireJsonKind.INTEGER
                else -> fail(StrictJsonFailure.MALFORMED_JSON)
            }
            val allowedKinds = shape?.allowedKinds(path)
            if (allowedKinds != null && actualKind !in allowedKinds) {
                fail(StrictJsonFailure.SHAPE_MISMATCH)
            }
            return when (actualKind) {
                WireJsonKind.OBJECT -> parseObject(depth, path)
                WireJsonKind.ARRAY -> parseArray(depth, path)
                WireJsonKind.STRING -> WireJsonString(parseString())
                WireJsonKind.BOOLEAN -> {
                    if (source[index] == 't') {
                        expectLiteral("true")
                        WireJsonBoolean(true)
                    } else {
                        expectLiteral("false")
                        WireJsonBoolean(false)
                    }
                }
                WireJsonKind.NULL -> {
                    expectLiteral("null")
                    WireJsonNull
                }
                WireJsonKind.INTEGER -> parseInteger()
            }
        }

        private fun parseObject(depth: Int, path: List<String>): WireJsonObject {
            expect('{')
            skipWhitespace()
            val properties = linkedMapOf<String, WireJsonValue>()
            if (consumeIf('}')) {
                return WireJsonObject(properties)
            }
            val allowedKeys = shape?.allowedObjectKeys(path)
            while (true) {
                if (properties.size >= limits.maxObjectMembers) {
                    fail(StrictJsonFailure.STRUCTURAL_LIMIT)
                }
                if (index >= endIndex || source[index] != '"') {
                    fail(StrictJsonFailure.MALFORMED_JSON)
                }
                val name = parseString()
                if (limits.requireSubsetPropertyNames && !isSubsetPropertyName(name)) {
                    fail(StrictJsonFailure.SHAPE_MISMATCH)
                }
                if (properties.containsKey(name)) {
                    fail(StrictJsonFailure.DUPLICATE_KEY)
                }
                if (allowedKeys != null && name !in allowedKeys) {
                    fail(StrictJsonFailure.SHAPE_MISMATCH)
                }
                skipWhitespace()
                expect(':')
                skipWhitespace()
                properties[name] = parseValue(depth + 1, path + name)
                skipWhitespace()
                when {
                    consumeIf('}') -> return WireJsonObject(properties)
                    consumeIf(',') -> skipWhitespace()
                    else -> fail(StrictJsonFailure.MALFORMED_JSON)
                }
            }
        }

        private fun parseArray(depth: Int, path: List<String>): WireJsonArray {
            expect('[')
            skipWhitespace()
            val elements = mutableListOf<WireJsonValue>()
            if (consumeIf(']')) {
                return WireJsonArray(elements)
            }
            val shapeMaximum = shape?.maxArrayItems(path)
            val maximum = minOf(limits.maxArrayItems, shapeMaximum ?: limits.maxArrayItems)
            if (shape?.compactArray(path) == true) {
                return parseCompactArray(depth, path, maximum)
            }
            val elementPath = path + "*"
            while (true) {
                if (elements.size >= maximum) {
                    fail(StrictJsonFailure.STRUCTURAL_LIMIT)
                }
                elements += parseValue(depth + 1, elementPath)
                skipWhitespace()
                when {
                    consumeIf(']') -> return WireJsonArray(elements)
                    consumeIf(',') -> skipWhitespace()
                    else -> fail(StrictJsonFailure.MALFORMED_JSON)
                }
            }
        }

        private fun parseCompactArray(
            depth: Int,
            path: List<String>,
            maximum: Int,
        ): WireJsonArray {
            val canonical = StringBuilder()
            val spans = PackedSpans()
            val elementPath = path + "*"
            while (true) {
                if (spans.size >= maximum) {
                    fail(StrictJsonFailure.STRUCTURAL_LIMIT)
                }
                val value = parseValue(depth + 1, elementPath)
                val start = canonical.length
                CanonicalWriter(canonical).write(value, depth = 0)
                spans.add(start, canonical.length)
                skipWhitespace()
                when {
                    consumeIf(']') -> {
                        val packed = spans.toLongArray()
                        val requireUnique = shape?.uniqueArrayItems(path) == true
                        if (requireUnique && hasDuplicateCanonicalValue(canonical, packed)) {
                            fail(StrictJsonFailure.SHAPE_MISMATCH)
                        }
                        return WireJsonArray(
                            CompactJsonElements(
                                canonicalElements = canonical.toString(),
                                spans = packed,
                                limits = limits,
                                uniqueItemsVerified = requireUnique,
                            ),
                        )
                    }
                    consumeIf(',') -> skipWhitespace()
                    else -> fail(StrictJsonFailure.MALFORMED_JSON)
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val value = StringBuilder()
            var codePoints = 0
            while (index < endIndex) {
                val character = source[index++]
                when {
                    character == '"' -> return value.toString()
                    character == '\\' -> codePoints += parseEscape(value)
                    character.code < 0x20 -> fail(StrictJsonFailure.MALFORMED_JSON)
                    character.isHighSurrogate() -> {
                        if (index >= endIndex || !source[index].isLowSurrogate()) {
                            fail(StrictJsonFailure.INVALID_SURROGATE)
                        }
                        value.append(character)
                        value.append(source[index++])
                        codePoints += 1
                    }
                    character.isLowSurrogate() -> fail(StrictJsonFailure.INVALID_SURROGATE)
                    else -> {
                        value.append(character)
                        codePoints += 1
                    }
                }
                if (codePoints > limits.maxStringCodePoints) {
                    fail(StrictJsonFailure.STRUCTURAL_LIMIT)
                }
            }
            fail(StrictJsonFailure.MALFORMED_JSON)
        }

        private fun parseEscape(output: StringBuilder): Int {
            if (index >= endIndex) {
                fail(StrictJsonFailure.MALFORMED_JSON)
            }
            when (val escaped = source[index++]) {
                '"', '\\', '/' -> output.append(escaped)
                'b' -> output.append('\b')
                'f' -> output.append('\u000C')
                'n' -> output.append('\n')
                'r' -> output.append('\r')
                't' -> output.append('\t')
                'u' -> {
                    val first = parseHexCodeUnit()
                    when {
                        first.isHighSurrogate() -> {
                            if (
                                index + 1 >= endIndex ||
                                source[index] != '\\' ||
                                source[index + 1] != 'u'
                            ) {
                                fail(StrictJsonFailure.INVALID_SURROGATE)
                            }
                            index += 2
                            val second = parseHexCodeUnit()
                            if (!second.isLowSurrogate()) {
                                fail(StrictJsonFailure.INVALID_SURROGATE)
                            }
                            output.append(first)
                            output.append(second)
                        }
                        first.isLowSurrogate() -> fail(StrictJsonFailure.INVALID_SURROGATE)
                        else -> output.append(first)
                    }
                }
                else -> fail(StrictJsonFailure.MALFORMED_JSON)
            }
            return 1
        }

        private fun parseHexCodeUnit(): Char {
            if (index + 4 > endIndex) {
                fail(StrictJsonFailure.MALFORMED_JSON)
            }
            var value = 0
            repeat(4) {
                val digit = source[index++].digitToIntOrNull(radix = 16)
                    ?: fail(StrictJsonFailure.MALFORMED_JSON)
                value = (value shl 4) or digit
            }
            return value.toChar()
        }

        private fun parseInteger(): WireJsonInteger {
            val start = index
            consumeIf('-')
            if (index >= endIndex) {
                fail(StrictJsonFailure.MALFORMED_JSON)
            }
            when (source[index]) {
                '0' -> {
                    index += 1
                    if (index < endIndex && source[index] in '0'..'9') {
                        fail(StrictJsonFailure.MALFORMED_JSON)
                    }
                }
                in '1'..'9' -> {
                    index += 1
                    while (index < endIndex && source[index] in '0'..'9') {
                        index += 1
                    }
                }
                else -> fail(StrictJsonFailure.MALFORMED_JSON)
            }
            if (
                index < endIndex &&
                source[index] in charArrayOf('.', 'e', 'E')
            ) {
                fail(StrictJsonFailure.UNSUPPORTED_NUMBER)
            }
            val number = try {
                source.substring(start, index).toLong()
            } catch (_: NumberFormatException) {
                fail(StrictJsonFailure.UNSAFE_INTEGER)
            }
            if (number < -JSON_SAFE_INTEGER_MAX || number > JSON_SAFE_INTEGER_MAX) {
                fail(StrictJsonFailure.UNSAFE_INTEGER)
            }
            return WireJsonInteger(number)
        }

        private fun expectLiteral(literal: String) {
            if (index + literal.length > endIndex || !source.startsWith(literal, index)) {
                fail(StrictJsonFailure.MALFORMED_JSON)
            }
            index += literal.length
        }

        private fun expect(character: Char) {
            if (index >= endIndex || source[index] != character) {
                fail(StrictJsonFailure.MALFORMED_JSON)
            }
            index += 1
        }

        private fun consumeIf(character: Char): Boolean {
            if (index < endIndex && source[index] == character) {
                index += 1
                return true
            }
            return false
        }

        private fun skipWhitespace() {
            while (index < endIndex) {
                when (source[index]) {
                    ' ', '\t', '\n', '\r' -> index += 1
                    else -> return
                }
            }
        }

        private fun fail(failure: StrictJsonFailure): Nothing =
            throw StrictJsonException(failure)
    }

    private class PackedSpans {
        private var values = LongArray(16)
        var size: Int = 0
            private set

        fun add(start: Int, end: Int) {
            if (size == values.size) {
                values = values.copyOf(values.size * 2)
            }
            values[size++] = (start.toLong() shl 32) or (end.toLong() and 0xffff_ffffL)
        }

        fun toLongArray(): LongArray = values.copyOf(size)
    }

    /** Exact, allocation-bounded uniqueness using iterative merge-sort over span indices. */
    private fun hasDuplicateCanonicalValue(
        source: CharSequence,
        spans: LongArray,
    ): Boolean {
        if (spans.size < 2) return false
        var ordered = IntArray(spans.size) { it }
        var scratch = IntArray(spans.size)
        var width = 1
        while (width < ordered.size) {
            var start = 0
            while (start < ordered.size) {
                val middle = minOf(start + width, ordered.size)
                val end = minOf(start + width + width, ordered.size)
                var left = start
                var right = middle
                var output = start
                while (left < middle || right < end) {
                    scratch[output++] = when {
                        right >= end -> ordered[left++]
                        left >= middle -> ordered[right++]
                        compareCanonicalSpans(source, spans[ordered[left]], spans[ordered[right]]) <= 0 ->
                            ordered[left++]
                        else -> ordered[right++]
                    }
                }
                start = end
            }
            val previous = ordered
            ordered = scratch
            scratch = previous
            if (width > ordered.size / 2) break
            width *= 2
        }
        for (index in 1 until ordered.size) {
            if (compareCanonicalSpans(source, spans[ordered[index - 1]], spans[ordered[index]]) == 0) {
                return true
            }
        }
        return false
    }

    private fun compareCanonicalSpans(
        source: CharSequence,
        leftSpan: Long,
        rightSpan: Long,
    ): Int {
        var left = (leftSpan ushr 32).toInt()
        var right = (rightSpan ushr 32).toInt()
        val leftEnd = leftSpan.toInt()
        val rightEnd = rightSpan.toInt()
        while (left < leftEnd && right < rightEnd) {
            val comparison = source[left].compareTo(source[right])
            if (comparison != 0) return comparison
            left += 1
            right += 1
        }
        return (leftEnd - left).compareTo(rightEnd - right)
    }

    private class CanonicalWriter(
        private val output: StringBuilder,
    ) {
        fun write(value: WireJsonValue, depth: Int) {
            if (depth > 32) {
                throw StrictJsonException(StrictJsonFailure.STRUCTURAL_LIMIT)
            }
            when (value) {
                is WireJsonObject -> writeObject(value, depth)
                is WireJsonArray -> writeArray(value, depth)
                is WireJsonString -> writeString(value.value)
                is WireJsonInteger -> {
                    if (
                        value.value < -JSON_SAFE_INTEGER_MAX ||
                        value.value > JSON_SAFE_INTEGER_MAX
                    ) {
                        throw StrictJsonException(StrictJsonFailure.UNSAFE_INTEGER)
                    }
                    output.append(value.value)
                }
                is WireJsonBoolean -> output.append(value.value)
                WireJsonNull -> output.append("null")
            }
        }

        private fun writeObject(value: WireJsonObject, depth: Int) {
            output.append('{')
            value.properties.entries
                .sortedWith(compareBy(Map.Entry<String, WireJsonValue>::key))
                .forEachIndexed { position, entry ->
                    if (position > 0) {
                        output.append(',')
                    }
                    if (!isSubsetPropertyName(entry.key)) {
                        throw StrictJsonException(StrictJsonFailure.SHAPE_MISMATCH)
                    }
                    writeString(entry.key)
                    output.append(':')
                    write(entry.value, depth + 1)
                }
            output.append('}')
        }

        private fun writeArray(value: WireJsonArray, depth: Int) {
            output.append('[')
            value.elements.forEachIndexed { position, element ->
                if (position > 0) {
                    output.append(',')
                }
                write(element, depth + 1)
            }
            output.append(']')
        }

        private fun writeString(value: String) {
            output.append('"')
            var index = 0
            while (index < value.length) {
                val character = value[index++]
                when (character) {
                    '"' -> output.append("\\\"")
                    '\\' -> output.append("\\\\")
                    '\b' -> output.append("\\b")
                    '\u000C' -> output.append("\\f")
                    '\n' -> output.append("\\n")
                    '\r' -> output.append("\\r")
                    '\t' -> output.append("\\t")
                    else -> when {
                        character.code < 0x20 -> {
                            output.append("\\u")
                            output.append(character.code.toString(16).padStart(4, '0'))
                        }
                        character.isHighSurrogate() -> {
                            if (index >= value.length || !value[index].isLowSurrogate()) {
                                throw StrictJsonException(StrictJsonFailure.INVALID_SURROGATE)
                            }
                            output.append(character)
                            output.append(value[index++])
                        }
                        character.isLowSurrogate() -> {
                            throw StrictJsonException(StrictJsonFailure.INVALID_SURROGATE)
                        }
                        else -> output.append(character)
                    }
                }
            }
            output.append('"')
        }
    }
}

private fun isSubsetPropertyName(value: String): Boolean {
    if (value.isEmpty() || value.length > 64 || value[0] !in 'a'..'z') {
        return false
    }
    return value.drop(1).all { character ->
        character in 'a'..'z' || character in '0'..'9' || character == '_'
    }
}

internal fun jsonObjectOf(vararg entries: Pair<String, WireJsonValue>): WireJsonObject =
    WireJsonObject(linkedMapOf(*entries))

internal fun jsonArrayOf(values: List<WireJsonValue>): WireJsonArray = WireJsonArray(values)

internal fun String.asJson(): WireJsonString = WireJsonString(this)

internal fun Long.asJson(): WireJsonInteger = WireJsonInteger(this)

internal fun Int.asJson(): WireJsonInteger = toLong().asJson()

internal fun Boolean.asJson(): WireJsonBoolean = WireJsonBoolean(this)

internal fun String?.asNullableJson(): WireJsonValue = this?.asJson() ?: WireJsonNull

internal fun WireJsonObject.without(name: String): WireJsonObject =
    WireJsonObject(properties - name)
