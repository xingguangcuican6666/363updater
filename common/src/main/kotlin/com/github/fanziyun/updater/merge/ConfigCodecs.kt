package com.github.fanziyun.updater.merge

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale

internal enum class FileFormat { JSON, TOML, LINES, UNKNOWN }

internal data class ParsedFile(
    val path: String,
    val format: FileFormat,
    val tree: JsonElement,
    val original: ByteArray,
)

internal object ConfigCodecs {
    fun format(path: String): FileFormat {
        if (path == "options.txt") return FileFormat.LINES
        return when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "json", "json5" -> FileFormat.JSON
            "toml" -> FileFormat.TOML
            "properties", "cfg", "txt" -> FileFormat.LINES
            else -> FileFormat.UNKNOWN
        }
    }

    fun parse(path: String, bytes: ByteArray): ParsedFile? = when (val format = format(path)) {
        FileFormat.JSON -> ParsedFile(path, format, parseJson(bytes), bytes)
        FileFormat.TOML -> ParsedFile(path, format, TomlCodec.parse(bytes), bytes)
        FileFormat.LINES -> ParsedFile(path, format, LineCodec.parse(path, bytes), bytes)
        FileFormat.UNKNOWN -> null
    }

    fun write(parsed: ParsedFile, value: JsonElement): ByteArray = when (parsed.format) {
        FileFormat.JSON -> JsonCodec.write(value)
        FileFormat.TOML -> TomlCodec.write(value)
        FileFormat.LINES -> LineCodec.write(parsed.original, value, parsed.path == "options.txt")
        FileFormat.UNKNOWN -> parsed.original
    }

    fun hasStructuredValues(parsed: ParsedFile): Boolean =
        parsed.format != FileFormat.LINES || parsed.tree.asJsonObject.size() > 0

    private fun parseJson(bytes: ByteArray): JsonElement {
        val reader = JsonReader(InputStreamReader(ByteArrayInputStream(bytes), StandardCharsets.UTF_8))
        reader.isLenient = true
        return JsonParser.parseReader(reader)
    }
}

private object JsonCodec {
    fun write(value: JsonElement): ByteArray = (com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(value) + "\n")
        .toByteArray(StandardCharsets.UTF_8)
}

private object LineCodec {
    private data class ParsedLine(val key: String, val prefix: String)

    fun parse(path: String, bytes: ByteArray): JsonObject {
        val root = JsonObject()
        val delimiter = if (path == "options.txt") ':' else null
        bytes.toString(StandardCharsets.UTF_8).lines().forEach { line ->
            val parsed = parseLine(line, delimiter) ?: return@forEach
            root.addProperty(parsed.key, line.substring(parsed.prefix.length).trim())
        }
        return root
    }

    fun write(original: ByteArray, value: JsonElement, optionsFile: Boolean): ByteArray {
        val values = value.asJsonObject.entrySet().associateTo(linkedMapOf()) { it.key to it.value.asString }
        val originalLines = original.toString(StandardCharsets.UTF_8)
            .replace("\r\n", "\n")
            .split('\n')
            .let { lines -> if (lines.lastOrNull().isNullOrEmpty()) lines.dropLast(1) else lines }
        val output = originalLines.mapNotNull { line ->
            val parsed = parseLine(line, if (optionsFile) ':' else null) ?: return@mapNotNull line
            val replacement = values.remove(parsed.key) ?: return@mapNotNull null
            parsed.prefix + replacement
        }.toMutableList()
        val delimiter = if (optionsFile) ":" else "="
        values.forEach { (key, replacement) -> output += "$key$delimiter$replacement" }
        return (output.joinToString("\n") + "\n").toByteArray(StandardCharsets.UTF_8)
    }

    private fun parseLine(line: String, forcedDelimiter: Char?): ParsedLine? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//") || trimmed.startsWith(";")) return null
        val index = forcedDelimiter?.let { line.indexOf(it) } ?: findDelimiter(line)
        if (index <= 0) return null
        val key = line.substring(0, index).trim()
        if (key.isEmpty()) return null
        val value = line.substring(index + 1)
        val leadingWhitespace = value.takeWhile(Char::isWhitespace)
        return ParsedLine(key, line.substring(0, index + 1) + leadingWhitespace)
    }

    private fun findDelimiter(line: String): Int {
        var quote: Char? = null
        line.forEachIndexed { index, char ->
            if (char == '"' || char == '\'') quote = if (quote == char) null else quote ?: char
            if (quote == null && (char == '=' || char == ':')) return index
        }
        return -1
    }
}

private object TomlCodec {
    fun parse(bytes: ByteArray): JsonObject {
        val root = JsonObject()
        var section = emptyList<String>()
        bytes.toString(StandardCharsets.UTF_8).lineSequence().forEachIndexed { lineNumber, raw ->
            val line = stripComment(raw).trim()
            if (line.isEmpty()) return@forEachIndexed
            if (line.startsWith("[[") || line.endsWith("]]") || line.startsWith("[")) {
                if (!line.endsWith("]") || line.startsWith("[[")) error("Unsupported TOML array table at line ${lineNumber + 1}")
                section = line.substring(1, line.length - 1).trim().split('.').filter(String::isNotBlank)
                return@forEachIndexed
            }
            val equals = findEquals(line)
            if (equals <= 0) error("Invalid TOML at line ${lineNumber + 1}")
            val key = line.substring(0, equals).trim().removeSurrounding("\"")
            val path = section + key.split('.').map { it.trim().removeSurrounding("\"") }
            put(root, path, parseValue(line.substring(equals + 1).trim()))
        }
        return root
    }

    fun write(value: JsonElement): ByteArray {
        val lines = mutableListOf<String>()
        val root = value.asJsonObject
        root.entrySet().filter { !it.value.isJsonObject }.forEach { (key, item) -> lines += "$key = ${render(item)}" }
        root.entrySet().filter { it.value.isJsonObject }.forEach { (section, item) ->
            writeSection(lines, listOf(section), item.asJsonObject)
        }
        return (lines.joinToString("\n") + "\n").toByteArray(StandardCharsets.UTF_8)
    }

    private fun writeSection(lines: MutableList<String>, path: List<String>, objectValue: JsonObject) {
        lines += "[${path.joinToString(".")}]"
        objectValue.entrySet().filter { !it.value.isJsonObject }.forEach { (key, item) -> lines += "$key = ${render(item)}" }
        objectValue.entrySet().filter { it.value.isJsonObject }.forEach { (key, item) ->
            writeSection(lines, path + key, item.asJsonObject)
        }
    }

    private fun put(root: JsonObject, path: List<String>, value: JsonElement) {
        var current = root
        path.dropLast(1).forEach { key ->
            val next = current.get(key)?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: JsonObject().also { current.add(key, it) }
            current = next
        }
        current.add(path.last(), value)
    }

    private fun parseValue(raw: String): JsonElement {
        val value = raw.trim()
        if (value.startsWith("\"") && value.endsWith("\"")) return JsonPrimitive(value.substring(1, value.length - 1).replace("\\\"", "\""))
        if (value.startsWith("'") && value.endsWith("'")) return JsonPrimitive(value.substring(1, value.length - 1))
        if (value.equals("true", true) || value.equals("false", true)) return JsonPrimitive(value.toBoolean())
        if (value.startsWith("[") && value.endsWith("]")) return parseArray(value)
        value.toLongOrNull()?.let { return JsonPrimitive(it) }
        value.toDoubleOrNull()?.let { return JsonPrimitive(it) }
        if (value.equals("inf", true) || value.equals("nan", true)) error("Unsupported TOML number")
        error("Unsupported TOML value: $value")
    }

    private fun parseArray(raw: String): JsonArray {
        val array = JsonArray()
        splitValues(raw.substring(1, raw.length - 1)).filter(String::isNotBlank).forEach { array.add(parseValue(it)) }
        return array
    }

    private fun splitValues(raw: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var depth = 0
        var quote: Char? = null
        raw.forEachIndexed { index, char ->
            if (char == '"' || char == '\'') quote = if (quote == char) null else quote ?: char
            if (quote == null && char == '[') depth++
            if (quote == null && char == ']') depth--
            if (quote == null && depth == 0 && char == ',') {
                result += raw.substring(start, index).trim()
                start = index + 1
            }
        }
        result += raw.substring(start).trim()
        return result
    }

    private fun stripComment(raw: String): String {
        var quote: Char? = null
        raw.forEachIndexed { index, char ->
            if (char == '"' || char == '\'') quote = if (quote == char) null else quote ?: char
            if (char == '#' && quote == null) return raw.substring(0, index)
        }
        return raw
    }

    private fun findEquals(raw: String): Int {
        var quote: Char? = null
        raw.forEachIndexed { index, char ->
            if (char == '"' || char == '\'') quote = if (quote == char) null else quote ?: char
            if (char == '=' && quote == null) return index
        }
        return -1
    }

    private fun render(value: JsonElement): String = when {
        value.isJsonPrimitive && value.asJsonPrimitive.isString -> "\"${value.asString.replace("\"", "\\\"")}\""
        value.isJsonArray -> value.asJsonArray.joinToString(prefix = "[", postfix = "]") { render(it) }
        else -> value.toString()
    }
}

internal fun JsonElement.sameAs(other: JsonElement?): Boolean = other != null && this.toString() == other.toString()

internal fun JsonElement.copyValue(): JsonElement = when (this) {
    is JsonObject -> JsonObject().also { copy -> for ((key, value) in entrySet()) copy.add(key, value.copyValue()) }
    is JsonArray -> JsonArray().also { copy -> for (value in this) copy.add(value.copyValue()) }
    is JsonNull -> JsonNull.INSTANCE
    else -> this
}

internal fun flatten(value: JsonElement, prefix: String = ""): Map<String, JsonElement> {
    if (!value.isJsonObject) return mapOf(prefix to value)
    val result = linkedMapOf<String, JsonElement>()
    value.asJsonObject.entrySet().forEach { (key, child) ->
        val childPrefix = if (prefix.isEmpty()) key else "$prefix.$key"
        result.putAll(flatten(child, childPrefix))
    }
    return result
}
