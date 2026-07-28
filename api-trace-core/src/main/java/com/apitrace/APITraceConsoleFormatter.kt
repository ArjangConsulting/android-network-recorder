package com.apitrace

import org.json.JSONArray
import org.json.JSONObject

/**
 * Formats sanitized trace records for readable multiline console output.
 *
 * The formatter operates on [APITraceRecord], so headers and URLs have already passed through
 * the recorder's configured redaction policy.
 */
class APITraceConsoleFormatter(
    /** Maximum number of body characters included before a truncation marker is appended. */
    val maxBodyCharacters: Int = 10_000,
) {
    init {
        require(maxBodyCharacters >= 0) { "maxBodyCharacters must not be negative" }
    }

    /** Returns one readable request/response or request/failure block. */
    fun format(record: APITraceRecord): String {
        val request =
            section(
                heading = "→ REQUEST ${record.method} ${record.url}",
                headers = record.request.headers.mapValues { it.value.values },
                body = bodyText(record.request.bodyText, record.request.bodyBase64),
            )
        val outcome =
            record.response?.let { response ->
                section(
                    heading = "← RESPONSE ${response.statusCode} (${record.durationMs}ms)",
                    headers = response.headers,
                    body = bodyText(response.bodyText, response.bodyBase64),
                )
            } ?: buildString {
                appendLine("✗ FAILURE (${record.durationMs}ms)")
                appendSection("Error", record.errorMessage ?: "<unknown>", terminate = false)
            }

        return "$request\n$outcome"
    }

    private fun section(
        heading: String,
        headers: Map<String, List<String>>,
        body: String?,
    ): String =
        buildString {
            appendLine(heading)
            appendSection("Headers", formattedHeaders(headers), terminate = body != null)
            body?.let { appendSection("Body", it, terminate = false) }
        }

    private fun formattedHeaders(headers: Map<String, List<String>>): String =
        headers.keys
            .sorted()
            .joinToString(separator = "\n") { name ->
                "$name: ${headers[name].orEmpty().joinToString(", ")}"
            }.ifEmpty { "<none>" }

    private fun bodyText(
        text: String?,
        base64: String?,
    ): String? {
        if (text != null) {
            if (text.isBlank()) return null
            val formatted = prettyPrintedJson(text) ?: text
            if (formatted.length <= maxBodyCharacters) return formatted
            return formatted.take(maxBodyCharacters) + "…[truncated]"
        }

        return base64
            ?.takeIf(String::isNotEmpty)
            ?.let { "<binary body: base64, ${it.length} characters>" }
    }

    private fun prettyPrintedJson(text: String): String? {
        val trimmed = text.trim()
        return runCatching {
            val value =
                when (trimmed.firstOrNull()) {
                    '{' -> JSONObject(trimmed)
                    '[' -> JSONArray(trimmed)
                    else -> return null
                }
            formattedJsonValue(value, indentation = 0)
        }.getOrNull()
    }

    private fun formattedJsonValue(
        value: Any,
        indentation: Int,
    ): String =
        when (value) {
            is JSONObject -> {
                val keys = value.keys().asSequence().toList().sorted()
                if (keys.isEmpty()) {
                    "{}"
                } else {
                    val childIndentation = indentation + 2
                    keys.joinToString(
                        prefix = "{\n",
                        postfix = "\n${" ".repeat(indentation)}}",
                        separator = ",\n",
                    ) { key ->
                        "${" ".repeat(childIndentation)}${JSONObject.quote(key)}: ${
                            formattedJsonValue(value.get(key), childIndentation)
                        }"
                    }
                }
            }
            is JSONArray -> {
                if (value.length() == 0) {
                    "[]"
                } else {
                    val childIndentation = indentation + 2
                    (0 until value.length()).joinToString(
                        prefix = "[\n",
                        postfix = "\n${" ".repeat(indentation)}]",
                        separator = ",\n",
                    ) { index ->
                        "${" ".repeat(childIndentation)}${
                            formattedJsonValue(value.get(index), childIndentation)
                        }"
                    }
                }
            }
            is String -> JSONObject.quote(value)
            JSONObject.NULL -> "null"
            is Number, is Boolean -> value.toString()
            else -> JSONObject.quote(value.toString())
        }

    private fun StringBuilder.appendSection(
        label: String,
        value: String,
        terminate: Boolean = true,
    ) {
        appendLine("  $label:")
        val lines = value.lines()
        lines.forEachIndexed { index, line ->
            append("    $line")
            if (terminate || index < lines.lastIndex) appendLine()
        }
    }
}
