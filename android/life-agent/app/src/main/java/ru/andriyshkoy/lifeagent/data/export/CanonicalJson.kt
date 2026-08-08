package ru.andriyshkoy.lifeagent.data.export

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal sealed interface CanonicalJsonValue

internal data class CanonicalJsonObject(
    val properties: Map<String, CanonicalJsonValue>,
) : CanonicalJsonValue

internal data class CanonicalJsonArray(
    val elements: List<CanonicalJsonValue>,
) : CanonicalJsonValue

internal data class CanonicalJsonString(
    val value: String,
) : CanonicalJsonValue

internal data class CanonicalJsonInteger(
    val value: BigInteger,
) : CanonicalJsonValue

internal data class CanonicalJsonBoolean(
    val value: Boolean,
) : CanonicalJsonValue

internal data object CanonicalJsonNull : CanonicalJsonValue

/**
 * Deterministic JSON codec for local note exports.
 *
 * Canonical local note revisions contain strings, integers, booleans, nulls,
 * arrays and objects only. Object names use UTF-16 lexical order, matching the
 * RFC 8785/JCS ordering rule, and integers are emitted in their shortest
 * base-ten form. Floating-point values are rejected instead of implementing a
 * subtly incompatible number canonicalizer before a domain needs one.
 */
internal object CanonicalJson {
    private const val MAX_NESTING_DEPTH = 128

    fun parse(bytes: ByteArray): CanonicalJsonValue {
        val text = decodeUtf8(bytes)
        return Parser(text).parseDocument()
    }

    fun encode(value: CanonicalJsonValue): ByteArray {
        val output = StringBuilder()
        Writer(output).write(value, depth = 0)
        return output.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        if (
            bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            throw NotesExportFormatException("JSON must not contain a UTF-8 BOM")
        }
        return try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: Exception) {
            throw NotesExportFormatException("JSON is not valid UTF-8", error)
        }
    }

    private class Parser(
        private val source: String,
    ) {
        private var index = 0

        fun parseDocument(): CanonicalJsonValue {
            skipWhitespace()
            val value = parseValue(depth = 0)
            skipWhitespace()
            if (index != source.length) {
                fail("unexpected trailing content")
            }
            return value
        }

        private fun parseValue(depth: Int): CanonicalJsonValue {
            if (depth > MAX_NESTING_DEPTH) {
                fail("JSON nesting exceeds $MAX_NESTING_DEPTH")
            }
            if (index >= source.length) {
                fail("unexpected end of JSON")
            }
            return when (source[index]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> CanonicalJsonString(parseString())
                't' -> {
                    expectLiteral("true")
                    CanonicalJsonBoolean(true)
                }
                'f' -> {
                    expectLiteral("false")
                    CanonicalJsonBoolean(false)
                }
                'n' -> {
                    expectLiteral("null")
                    CanonicalJsonNull
                }
                '-', in '0'..'9' -> parseInteger()
                else -> fail("unexpected character '${source[index]}'")
            }
        }

        private fun parseObject(depth: Int): CanonicalJsonObject {
            expect('{')
            skipWhitespace()
            val properties = linkedMapOf<String, CanonicalJsonValue>()
            if (consumeIf('}')) {
                return CanonicalJsonObject(properties)
            }
            while (true) {
                if (index >= source.length || source[index] != '"') {
                    fail("object property name must be a string")
                }
                val name = parseString()
                if (properties.containsKey(name)) {
                    fail("duplicate object property '$name'")
                }
                skipWhitespace()
                expect(':')
                skipWhitespace()
                properties[name] = parseValue(depth + 1)
                skipWhitespace()
                when {
                    consumeIf('}') -> return CanonicalJsonObject(properties)
                    consumeIf(',') -> skipWhitespace()
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun parseArray(depth: Int): CanonicalJsonArray {
            expect('[')
            skipWhitespace()
            val elements = mutableListOf<CanonicalJsonValue>()
            if (consumeIf(']')) {
                return CanonicalJsonArray(elements)
            }
            while (true) {
                elements += parseValue(depth + 1)
                skipWhitespace()
                when {
                    consumeIf(']') -> return CanonicalJsonArray(elements)
                    consumeIf(',') -> skipWhitespace()
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val value = StringBuilder()
            while (index < source.length) {
                val character = source[index++]
                when {
                    character == '"' -> return value.toString()
                    character == '\\' -> parseEscape(value)
                    character.code < 0x20 -> fail("unescaped control character in string")
                    character.isHighSurrogate() -> {
                        if (index >= source.length || !source[index].isLowSurrogate()) {
                            fail("unpaired high surrogate in string")
                        }
                        value.append(character)
                        value.append(source[index++])
                    }
                    character.isLowSurrogate() -> fail("unpaired low surrogate in string")
                    else -> value.append(character)
                }
            }
            fail("unterminated string")
        }

        private fun parseEscape(output: StringBuilder) {
            if (index >= source.length) {
                fail("unterminated string escape")
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
                                index + 1 >= source.length ||
                                source[index] != '\\' ||
                                source[index + 1] != 'u'
                            ) {
                                fail("escaped high surrogate has no low surrogate")
                            }
                            index += 2
                            val second = parseHexCodeUnit()
                            if (!second.isLowSurrogate()) {
                                fail("escaped high surrogate has an invalid pair")
                            }
                            output.append(first)
                            output.append(second)
                        }
                        first.isLowSurrogate() -> {
                            fail("escaped low surrogate has no high surrogate")
                        }
                        else -> output.append(first)
                    }
                }
                else -> fail("invalid string escape '\\$escaped'")
            }
        }

        private fun parseHexCodeUnit(): Char {
            if (index + 4 > source.length) {
                fail("incomplete unicode escape")
            }
            var value = 0
            repeat(4) {
                val digit = source[index++].digitToIntOrNull(radix = 16)
                    ?: fail("invalid unicode escape")
                value = (value shl 4) or digit
            }
            return value.toChar()
        }

        private fun parseInteger(): CanonicalJsonInteger {
            val start = index
            consumeIf('-')
            if (index >= source.length) {
                fail("incomplete number")
            }
            when (source[index]) {
                '0' -> {
                    index++
                    if (index < source.length && source[index] in '0'..'9') {
                        fail("number has a leading zero")
                    }
                }
                in '1'..'9' -> {
                    index++
                    while (index < source.length && source[index] in '0'..'9') {
                        index++
                    }
                }
                else -> fail("invalid number")
            }
            if (
                index < source.length &&
                (source[index] == '.' || source[index] == 'e' || source[index] == 'E')
            ) {
                fail("Notes export does not allow non-integer JSON numbers")
            }
            return try {
                CanonicalJsonInteger(source.substring(start, index).toBigInteger())
            } catch (error: NumberFormatException) {
                throw NotesExportFormatException("invalid integer at offset $start", error)
            }
        }

        private fun expectLiteral(literal: String) {
            if (!source.startsWith(literal, index)) {
                fail("expected '$literal'")
            }
            index += literal.length
        }

        private fun expect(character: Char) {
            if (index >= source.length || source[index] != character) {
                fail("expected '$character'")
            }
            index++
        }

        private fun consumeIf(character: Char): Boolean {
            if (index < source.length && source[index] == character) {
                index++
                return true
            }
            return false
        }

        private fun skipWhitespace() {
            while (
                index < source.length &&
                source[index] in charArrayOf(' ', '\t', '\n', '\r')
            ) {
                index++
            }
        }

        private fun fail(message: String): Nothing {
            throw NotesExportFormatException("$message at offset $index")
        }
    }

    private class Writer(
        private val output: StringBuilder,
    ) {
        fun write(value: CanonicalJsonValue, depth: Int) {
            if (depth > MAX_NESTING_DEPTH) {
                throw NotesExportFormatException(
                    "JSON nesting exceeds $MAX_NESTING_DEPTH",
                )
            }
            when (value) {
                is CanonicalJsonObject -> writeObject(value, depth)
                is CanonicalJsonArray -> writeArray(value, depth)
                is CanonicalJsonString -> writeString(value.value)
                is CanonicalJsonInteger -> output.append(value.value.toString())
                is CanonicalJsonBoolean -> output.append(value.value)
                CanonicalJsonNull -> output.append("null")
            }
        }

        private fun writeObject(value: CanonicalJsonObject, depth: Int) {
            output.append('{')
            value.properties.entries
                .sortedBy { it.key }
                .forEachIndexed { position, entry ->
                    if (position > 0) {
                        output.append(',')
                    }
                    writeString(entry.key)
                    output.append(':')
                    write(entry.value, depth + 1)
                }
            output.append('}')
        }

        private fun writeArray(value: CanonicalJsonArray, depth: Int) {
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
                    else -> {
                        when {
                            character.code < 0x20 -> {
                                output.append("\\u")
                                output.append(character.code.toString(16).padStart(4, '0'))
                            }
                            character.isHighSurrogate() -> {
                                if (
                                    index >= value.length ||
                                    !value[index].isLowSurrogate()
                                ) {
                                    throw NotesExportFormatException(
                                        "cannot encode an unpaired high surrogate",
                                    )
                                }
                                output.append(character)
                                output.append(value[index++])
                            }
                            character.isLowSurrogate() -> {
                                throw NotesExportFormatException(
                                    "cannot encode an unpaired low surrogate",
                                )
                            }
                            else -> output.append(character)
                        }
                    }
                }
            }
            output.append('"')
        }
    }
}
