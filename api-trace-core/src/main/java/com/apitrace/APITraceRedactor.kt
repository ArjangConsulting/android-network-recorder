package com.apitrace

import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder

/** Sanitizes network data before it enters trace history. */
class APITraceRedactor(
    headerRules: Map<String, APITraceCaptureMode> = emptyMap(),
    queryItemRules: Map<String, APITraceCaptureMode> = emptyMap(),
    responseHeaderRules: Map<String, APITraceCaptureMode> = emptyMap(),
    @Suppress("UNUSED_PARAMETER") replacement: String = "[redacted]",
    redact: APITraceRedactionConfiguration = APITraceRedactionConfiguration(),
) {
    private val credentials = APITraceCredentialPolicy(redact)
    private val normalizedHeaderRules = normalizeRules(headerRules)
    private val normalizedResponseRules = normalizeRules(responseHeaderRules)
    private val normalizedQueryRules = normalizeRules(queryItemRules)

    fun redact(headers: Map<String, List<String>>): Map<String, APITraceCapturedField> =
        headers.mapValues { (name, values) ->
            val rule = headerRule(name, normalizedHeaderRules)
            APITraceCapturedField(rule.mode, values.map { sanitizeHeader(it, name, rule) })
        }

    fun redactResponseHeaders(headers: Map<String, List<String>>): Map<String, List<String>> =
        headers.mapValues { (name, values) ->
            val rule = headerRule(name, normalizedResponseRules)
            values.map { sanitizeHeader(it, name, rule) }
        }

    /** Drops free-form transport details, which can contain unstructured credentials. */
    @Suppress("UNUSED_PARAMETER")
    fun redactErrorMessage(message: String): String = "Network request failed"

    fun redact(url: HttpUrl): APITraceRedactedUrl {
        val captured = linkedMapOf<String, APITraceCapturedField>()
        val sanitizedUrl = url.newBuilder().username("").password("").fragment(null).query(null)
            .encodedPath(stripJwt(url.encodedPath))
        for (index in 0 until url.querySize) {
            val name = url.queryParameterName(index)
            val value = url.queryParameterValue(index)
            val rule = parameterRule(name)
            val sanitized = rule.apply(value.orEmpty())
            if (name.isNotEmpty() || value != null) {
                captured[name] = APITraceCapturedField(rule.mode, captured[name]?.values.orEmpty() + sanitized)
            }
            sanitizedUrl.addQueryParameter(name, if (value == null && rule != Rule.Replace) null else sanitized)
        }
        return APITraceRedactedUrl(sanitizedUrl.build().toString(), captured)
    }

    /** Returns only valid cookie names, never cookie values or attributes. */
    fun cookieNames(headers: Map<String, List<String>>, response: Boolean = false): List<String> {
        val headerName = if (response) "setcookie" else "cookie"
        return headers.asSequence()
            .filter { APITraceCredentialNames.normalize(it.key) == headerName }
            .flatMap { it.value.asSequence() }
            .flatMap { if (response) it.splitToSequence(SET_COOKIE_SEPARATOR) else it.splitToSequence(';') }
            .mapNotNull { COOKIE_NAME.find(it)?.value?.trim() }
            .toList()
    }

    /** Sanitizes complete UTF-8 JSON or form data. Uninspectable payloads are not retained. */
    fun redactBody(body: ByteArray, contentType: String?): String? {
        if (body.isEmpty()) return null
        val text = body.toString(Charsets.UTF_8)
        if (!text.toByteArray(Charsets.UTF_8).contentEquals(body)) return null
        val mediaType = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when {
            mediaType == "application/x-www-form-urlencoded" -> sanitizeParameters(text, form = true)
            mediaType == "application/json" || mediaType.endsWith("+json") -> redactJson(text)
            else -> null
        }
    }

    private fun redactJson(text: String): String? = try {
        val tokenizer = JSONTokener(text)
        val value = tokenizer.nextValue()
        if (tokenizer.nextClean() != '\u0000') {
            null
        } else {
            when (val sanitized = sanitizeJson(value)) {
                is String -> JSONObject.quote(sanitized)
                is Number -> JSONObject.numberToString(sanitized)
                else -> sanitized.toString()
            }
        }
    } catch (_: JSONException) {
        null
    } catch (_: StackOverflowError) {
        null
    }

    private fun sanitizeJson(value: Any, name: String = ""): Any = when (val rule = parameterRule(name)) {
        Rule.Keep -> value
        Rule.Replace -> APITraceCredentialNames.MARKER
        Rule.Inspect -> when (value) {
            is JSONObject -> JSONObject().apply {
                value.keys().forEach { key -> put(key, sanitizeJson(value.get(key), key)) }
            }
            is JSONArray -> JSONArray().apply {
                for (index in 0 until value.length()) put(sanitizeJson(value.get(index)))
            }
            is String -> stripJwt(value)
            else -> value
        }
    }

    private fun sanitizeParameters(input: String, form: Boolean): String? = try {
        input.split('&').joinToString("&") { pair ->
            if (pair.isEmpty()) return@joinToString pair
            val encodedName = pair.substringBefore('=')
            val name = URLDecoder.decode(encodedName, "UTF-8")
            val value = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            val rule = parameterRule(name)
            val sanitized = rule.apply(value)
            if (rule == Rule.Replace || value != sanitized) {
                val encoded = URLEncoder.encode(sanitized, "UTF-8")
                encodedName + "=" + if (form) encoded else encoded.replace("+", "%20")
            } else {
                pair
            }
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun headerRule(name: String, custom: Map<String, APITraceCaptureMode>): Rule {
        val normalized = APITraceCredentialNames.normalize(name)
        return when {
            normalized in credentials.exceptHeaders -> Rule.Keep
            normalized in credentials.headers || custom[normalized] == APITraceCaptureMode.INCLUDES -> Rule.Replace
            else -> Rule.Inspect
        }
    }

    private fun parameterRule(name: String): Rule {
        val normalized = APITraceCredentialNames.normalize(name)
        return when {
            normalized in credentials.exceptQueryParameters -> Rule.Keep
            normalized in credentials.queryParameters ||
                normalizedQueryRules[normalized] == APITraceCaptureMode.INCLUDES -> Rule.Replace
            else -> Rule.Inspect
        }
    }

    private fun sanitizeHeader(value: String, name: String, rule: Rule): String {
        if (rule != Rule.Inspect) return rule.apply(value)
        return when (name.lowercase()) {
            "location", "content-location", "referer" -> sanitizeLink(value) ?: APITraceCredentialNames.MARKER
            "refresh" -> {
                val delimiter = REFRESH_DELIMITER.find(value) ?: return stripJwt(value)
                val url = value.substring(delimiter.range.last + 1).trim(' ', '\'', '"')
                stripJwt(value.substring(0, delimiter.range.last + 1)) +
                    (sanitizeLink(url) ?: APITraceCredentialNames.MARKER)
            }
            else -> stripJwt(value)
        }
    }

    private fun sanitizeLink(value: String): String? {
        val uri = try {
            URI(value)
        } catch (_: URISyntaxException) {
            return null
        }
        if (uri.isOpaque) return null
        val authority = uri.rawAuthority
        val prefix = (uri.scheme?.let { "$it:" } ?: "") +
            (authority?.let { "//" + it.substringAfterLast('@') } ?: "") + uri.rawPath.orEmpty()
        val query = uri.rawQuery ?: return stripJwt(prefix)
        return sanitizeParameters(query, form = false)?.let { stripJwt(prefix) + "?" + it }
    }

    private enum class Rule {
        Keep,
        Replace,
        Inspect,
        ;

        val mode: APITraceCaptureMode
            get() = if (this == Replace) APITraceCaptureMode.INCLUDES else APITraceCaptureMode.EXACT

        fun apply(value: String): String = when (this) {
            Keep -> value
            Replace -> APITraceCredentialNames.MARKER
            Inspect -> stripJwt(value)
        }
    }

    companion object {
        /** Headers redacted by the default credential policy. */
        @JvmField
        val DEFAULT_RESPONSE_HEADER_RULES: Map<String, APITraceCaptureMode> =
            (APITraceCredentialNames.headers + APITraceCredentialNames.importOnlyHeaders)
                .associateWith { APITraceCaptureMode.INCLUDES }

        private val COOKIE_NAME = Regex("""^\s*[!#$%&'*+\-.^_`|~0-9A-Za-z]+(?=\s*=)""")
        private val SET_COOKIE_SEPARATOR = Regex(""",(?=\s*[!#$%&'*+\-.^_`|~0-9A-Za-z]+\s*=)""")
        private val REFRESH_DELIMITER = Regex(""";\s*url\s*=\s*""", RegexOption.IGNORE_CASE)

        @JvmField
        val DEFAULT = APITraceRedactor()

        private fun stripJwt(value: String): String = APITraceCredentialNames.jwtPattern.replace(value, "$1.$2.redacted")

        private fun normalizeRules(rules: Map<String, APITraceCaptureMode>): Map<String, APITraceCaptureMode> =
            buildMap {
                rules.forEach { (name, mode) ->
                    val key = APITraceCredentialNames.normalize(name)
                    if (get(key) != APITraceCaptureMode.INCLUDES) put(key, mode)
                }
            }
    }
}

data class APITraceRedactedUrl(
    val url: String,
    val queryItems: Map<String, APITraceCapturedField>,
)
