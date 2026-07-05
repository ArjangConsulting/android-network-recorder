package com.apitrace

import okhttp3.HttpUrl

/** Sanitizes request metadata before it is persisted in trace history. */
class APITraceRedactor(
    headerRules: Map<String, APITraceCaptureMode> = emptyMap(),
    private val queryItemRules: Map<String, APITraceCaptureMode> = emptyMap(),
    responseHeaderRules: Map<String, APITraceCaptureMode> = DEFAULT_RESPONSE_HEADER_RULES,
    private val replacement: String = "<mocked>",
) {
    private val normalizedHeaderRules: Map<String, APITraceCaptureMode> =
        headerRules.mapKeys { entry -> entry.key.lowercase() }

    private val normalizedResponseHeaderRules: Map<String, APITraceCaptureMode> =
        responseHeaderRules.mapKeys { entry -> entry.key.lowercase() }

    fun redact(headers: Map<String, List<String>>): Map<String, APITraceCapturedField> {
        if (headers.isEmpty()) {
            return emptyMap()
        }

        val captured = linkedMapOf<String, APITraceCapturedField>()
        headers.forEach { (name, values) ->
            val mode = normalizedHeaderRules[name.lowercase()] ?: return@forEach
            captured[name] = APITraceCapturedField(
                mode = mode,
                values = values.map { sanitizeValue(mode, it) },
            )
        }
        return captured
    }

    fun redact(url: HttpUrl): APITraceRedactedUrl {
        if (url.querySize == 0) {
            return APITraceRedactedUrl(url = url.newBuilder().query(null).build().toString(), queryItems = emptyMap())
        }

        val captured = linkedMapOf<String, APITraceCapturedField>()
        val sanitizedUrl = url.newBuilder().query(null)

        for (index in 0 until url.querySize) {
            val name = url.queryParameterName(index)
            val mode = queryItemRules[name] ?: continue
            val value = url.queryParameterValue(index) ?: ""
            val sanitizedValue = sanitizeValue(mode, value)
            sanitizedUrl.addQueryParameter(name, sanitizedValue)

            val existing = captured[name]
            if (existing == null) {
                captured[name] = APITraceCapturedField(
                    mode = mode,
                    values = mutableListOf(sanitizedValue),
                )
            } else {
                captured[name] = existing.copy(values = existing.values + sanitizedValue)
            }
        }

        return APITraceRedactedUrl(
            url = sanitizedUrl.build().toString(),
            queryItems = captured,
        )
    }

    /**
     * Sanitizes response headers. Unlike request headers, response headers are captured
     * by default; headers with an [APITraceCaptureMode.INCLUDES] rule keep presence only.
     */
    fun redactResponseHeaders(headers: Map<String, List<String>>): Map<String, List<String>> {
        if (headers.isEmpty()) {
            return emptyMap()
        }

        val captured = linkedMapOf<String, List<String>>()
        headers.forEach { (name, values) ->
            val mode = normalizedResponseHeaderRules[name.lowercase()] ?: APITraceCaptureMode.EXACT
            captured[name] = values.map { sanitizeValue(mode, it) }
        }
        return captured
    }

    /**
     * Strips query strings from URLs embedded in error messages, which would otherwise
     * bypass query-item redaction.
     */
    fun redactErrorMessage(message: String): String {
        return message.replace(ERROR_MESSAGE_QUERY_REGEX, "$1")
    }

    private fun sanitizeValue(mode: APITraceCaptureMode, originalValue: String): String {
        return when (mode) {
            APITraceCaptureMode.EXACT -> originalValue
            APITraceCaptureMode.INCLUDES -> replacement
        }
    }

    companion object {
        /**
         * Response headers that carry credentials or session material and are therefore
         * redacted unless a rule explicitly opts them back in.
         *
         * Declared before [DEFAULT], which captures it via a default parameter.
         */
        @JvmField
        val DEFAULT_RESPONSE_HEADER_RULES: Map<String, APITraceCaptureMode> = mapOf(
            "Set-Cookie" to APITraceCaptureMode.INCLUDES,
            "Set-Cookie2" to APITraceCaptureMode.INCLUDES,
            "Authorization" to APITraceCaptureMode.INCLUDES,
            "Proxy-Authenticate" to APITraceCaptureMode.INCLUDES,
            "WWW-Authenticate" to APITraceCaptureMode.INCLUDES,
        )

        @JvmField
        val DEFAULT = APITraceRedactor()

        private val ERROR_MESSAGE_QUERY_REGEX = Regex("""(https?://[^\s?'"<>]*)\?[^\s'"<>]*""")
    }
}

data class APITraceRedactedUrl(
    val url: String,
    val queryItems: Map<String, APITraceCapturedField>,
)
