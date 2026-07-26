package com.apitrace

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class APITraceRedactorTest {

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
        assertEquals(listOf("<mocked>", "<mocked>"), headers["set-cookie"])
        assertEquals(listOf("<mocked>"), headers["WWW-Authenticate"])
        assertEquals(listOf("req-42"), headers["x-request-id"])
    }

    @Test
    fun `response header rules can opt sensitive headers back in or add new ones`() {
        val redactor = APITraceRedactor(
            responseHeaderRules = mapOf(
                "Set-Cookie" to APITraceCaptureMode.EXACT,
                "X-Internal-Token" to APITraceCaptureMode.INCLUDES,
            ),
        )

        val headers = redactor.redactResponseHeaders(
            mapOf(
                "set-cookie" to listOf("session=abc123"),
                "x-internal-token" to listOf("secret"),
                "www-authenticate" to listOf("Bearer secret"),
            )
        )

        assertEquals(listOf("session=abc123"), headers["set-cookie"])
        assertEquals(listOf("<mocked>"), headers["x-internal-token"])
        assertEquals(listOf("<mocked>"), headers["www-authenticate"])
    }

    @Test
    fun `error messages have url query strings stripped`() {
        val redactor = APITraceRedactor.DEFAULT

        assertEquals(
            "Failed to connect to https://api.example.com/v1/users",
            redactor.redactErrorMessage("Failed to connect to https://api.example.com/v1/users?token=secret&page=1"),
        )
        assertEquals(
            "The request timed out. Retry? Yes/no",
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
