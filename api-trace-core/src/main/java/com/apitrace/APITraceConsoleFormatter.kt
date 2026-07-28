package com.apitrace

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
        body: String,
    ): String =
        buildString {
            appendLine(heading)
            appendSection("Headers", formattedHeaders(headers))
            appendSection("Body", body, terminate = false)
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
    ): String {
        if (text == null) {
            return base64?.let { "<binary body: base64, ${it.length} characters>" } ?: "<empty>"
        }
        if (text.isEmpty()) return "<empty>"
        if (text.length <= maxBodyCharacters) return text
        return text.take(maxBodyCharacters) + "…[truncated]"
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
