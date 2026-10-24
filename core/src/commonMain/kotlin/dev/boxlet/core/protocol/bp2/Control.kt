package dev.boxlet.core.protocol.bp2

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Strict UTF-8 / JSON and duplicate-key rejection, including nested objects and escaped keys. */
object Bp2Controls {
    fun decode(bytes: ByteArray): JsonObject {
        require(bytes.size in 2..Bp2.CONTROL_BYTES)
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        DuplicateKeyValidator(text).validate()
        val result = Bp2.json.parseToJsonElement(text).jsonObject
        val type = result["type"] as? JsonPrimitive ?: error("Missing control type")
        require(type.isString && Regex("[a-z][a-z0-9-]{0,47}").matches(type.content))
        listOf("requestId", "replyTo").forEach { key ->
            result[key]?.let { require(it is JsonPrimitive && it.isString); Bp2.requireId(it.content) }
        }
        return result
    }

    fun encode(control: JsonObject): ByteArray = control.toString().encodeToByteArray().also { decode(it) }

    private class DuplicateKeyValidator(private val text: String) {
        private var cursor = 0
        private var nodes = 0
        fun validate() { value(0); whitespace(); require(cursor == text.length) }
        private fun whitespace() { while (cursor < text.length && text[cursor] in " \t\n\r") cursor++ }
        private fun take(char: Char): Boolean {
            whitespace()
            return if (cursor < text.length && text[cursor] == char) { cursor++; true } else false
        }
        private fun string(): String {
            whitespace()
            require(cursor < text.length && text[cursor] == '"')
            val start = cursor++
            while (cursor < text.length) {
                val char = text[cursor++]
                require(char >= ' ') { "Unescaped control character in JSON string" }
                when (char) {
                    '\\' -> { require(cursor < text.length); cursor++ }
                    '"' -> return Bp2.json.decodeFromString<String>(text.substring(start, cursor))
                }
            }
            error("Unterminated JSON string")
        }
        private fun value(depth: Int) {
            require(depth <= 64 && ++nodes <= 100000) { "Control JSON too complex" }
            whitespace(); require(cursor < text.length)
            when (text[cursor]) {
                '{' -> {
                    cursor++
                    val keys = mutableSetOf<String>()
                    if (take('}')) return
                    do {
                        require(keys.add(string())) { "Duplicate JSON key" }
                        require(take(':')); value(depth + 1)
                    } while (take(','))
                    require(take('}'))
                }
                '[' -> {
                    cursor++
                    if (take(']')) return
                    do { value(depth + 1) } while (take(','))
                    require(take(']'))
                }
                '"' -> { string() }
                else -> {
                    val start = cursor
                    while (cursor < text.length && text[cursor] !in " \t\r\n,]}:") cursor++
                    require(cursor > start)
                    val token = text.substring(start, cursor)
                    require(token in listOf("null", "true", "false") ||
                        Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matches(token))
                }
            }
        }
    }
}
