package com.apitrace

import okhttp3.HttpUrl

/** Sanitizes request metadata before it is persisted in trace history. */
class APITraceRedactor(
    headerRules: Map<String, APITraceCaptureMode> = emptyMap(),
    private val queryItemRules: Map<String, APITraceCaptureMode> = emptyMap(),
    private val replacement: String = "<mocked>",
) {
    private val normalizedHeaderRules: Map<String, APITraceCaptureMode> =
        headerRules.mapKeys { entry -> entry.key.lowercase() }

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

    private fun sanitizeValue(mode: APITraceCaptureMode, originalValue: String): String {
        return when (mode) {
            APITraceCaptureMode.EXACT -> originalValue
            APITraceCaptureMode.INCLUDES -> replacement
        }
    }

    companion object {
        @JvmField
        val DEFAULT = APITraceRedactor()
    }
}

data class APITraceRedactedUrl(
    val url: String,
    val queryItems: Map<String, APITraceCapturedField>,
)
