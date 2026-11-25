package com.apitrace

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class APITraceRedactorTest {

    @Test
    fun `request headers are all captured while configured rules redact values`() {
        val redactor = APITraceRedactor(
            headerRules = mapOf("Authorization" to APITraceCaptureMode.INCLUDES),
        )

        val headers = redactor.redact(
            mapOf(
                "Accept" to listOf("application/json"),
                "Authorization" to listOf("Bearer secret"),
                "X-Request-Id" to listOf("request-42"),
            ),
        )

        assertEquals(listOf("application/json"), headers["Accept"]?.values)
        assertEquals(listOf("[redacted]"), headers["Authorization"]?.values)
        assertEquals(listOf("request-42"), headers["X-Request-Id"]?.values)
    }

    @Test
    fun `response headers are captured by default with sensitive defaults redacted`() {
        val redactor = APITraceRedactor.DEFAULT

        val headers = redactor.redactResponseHeaders(
            mapOf(
                "content-type" to listOf("application/json"),
                "set-cookie" to listOf("session=abc123; HttpOnly", "theme=dark"),
                "WWW-Authenticate" to listOf("Bearer realm=\"api\""),
                "x-request-id" to listOf("req-42"),
            )
        )

        assertEquals(listOf("application/json"), headers["content-type"])
        assertEquals(listOf("[redacted]", "[redacted]"), headers["set-cookie"])
        assertEquals(listOf("[redacted]"), headers["WWW-Authenticate"])
        assertEquals(listOf("req-42"), headers["x-request-id"])
    }

    @Test
    fun `legacy rules can add sensitive headers but exact cannot bypass redaction`() {
        val redactor = APITraceRedactor(
            responseHeaderRules = mapOf(
                "Set-Cookie" to APITraceCaptureMode.EXACT,
                "X-Internal-Token" to APITraceCaptureMode.INCLUDES,
            ),
            replacement = "<legacy>",
        )

        val headers = redactor.redactResponseHeaders(
            mapOf(
                "set-cookie" to listOf("session=abc123"),
                "x-internal-token" to listOf("secret"),
                "www-authenticate" to listOf("Bearer secret"),
            )
        )

        assertEquals(listOf("[redacted]"), headers["set-cookie"])
        assertEquals(listOf("[redacted]"), headers["x-internal-token"])
        assertEquals(listOf("[redacted]"), headers["www-authenticate"])
    }

    @Test
    fun `free-form error details are not retained`() {
        val redactor = APITraceRedactor.DEFAULT

        assertEquals(
            "Network request failed",
            redactor.redactErrorMessage("Failed to connect to https://api.example.com/v1/users?token=secret&page=1"),
        )
        assertEquals(
            "Network request failed",
            redactor.redactErrorMessage("The request timed out. Retry? Yes/no"),
        )
    }

    @Test
    fun `url credentials and fragments are always removed`() {
        val redactor = APITraceRedactor(queryItemRules = mapOf("page" to APITraceCaptureMode.EXACT))
        val url = "https://user:password@api.example.com/v1/users?page=1#access-token".toHttpUrl()

        val redacted = redactor.redact(url)

        assertEquals("https://api.example.com/v1/users?page=1", redacted.url)
    }
}
