package com.apitrace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class APITraceConsoleFormatterTest {
    @Test
    fun `formats successful exchange with indented sections`() {
        val record =
            APITraceRecord(
                durationMs = 42,
                method = "POST",
                url = "https://api.example.com/videos",
                endpoint = "/videos",
                request =
                    APITraceRecord.RequestData(
                        headers =
                            mapOf(
                                "Content-Type" to
                                    APITraceCapturedField(
                                        APITraceCaptureMode.EXACT,
                                        listOf("application/json"),
                                    ),
                                "X-Request-ID" to
                                    APITraceCapturedField(
                                        APITraceCaptureMode.EXACT,
                                        listOf("trace-123"),
                                    ),
                            ),
                        bodyText = "{\n  \"title\": \"Hello\"\n}",
                    ),
                response =
                    APITraceRecord.ResponseData(
                        statusCode = 201,
                        headers = mapOf("Content-Type" to listOf("application/json")),
                        bodyText = "{\n  \"id\": 7\n}",
                    ),
            )

        assertEquals(
            """
            → REQUEST POST https://api.example.com/videos
              Headers:
                Content-Type: application/json
                X-Request-ID: trace-123
              Body:
                {
                  "title": "Hello"
                }
            ← RESPONSE 201 (42ms)
              Headers:
                Content-Type: application/json
              Body:
                {
                  "id": 7
                }
            """.trimIndent(),
            APITraceConsoleFormatter().format(record),
        )
    }

    @Test
    fun `formats failure and omits empty request body`() {
        val record =
            APITraceRecord(
                durationMs = 8,
                method = "GET",
                url = "https://api.example.com/videos",
                endpoint = "/videos",
                request = APITraceRecord.RequestData(),
                errorMessage = "Connection refused",
            )

        assertEquals(
            """
            → REQUEST GET https://api.example.com/videos
              Headers:
                <none>
            ✗ FAILURE (8ms)
              Error:
                Connection refused
            """.trimIndent(),
            APITraceConsoleFormatter().format(record),
        )
    }

    @Test
    fun `truncates text and describes binary bodies`() {
        val record =
            APITraceRecord(
                durationMs = 1,
                method = "POST",
                url = "https://api.example.com/upload",
                endpoint = "/upload",
                request = APITraceRecord.RequestData(bodyText = "abcdef"),
                response = APITraceRecord.ResponseData(statusCode = 200, bodyBase64 = "AQID"),
            )

        val output = APITraceConsoleFormatter(maxBodyCharacters = 3).format(record)

        assertTrue(output.contains("abc…[truncated]"))
        assertTrue(output.contains("<binary body: base64, 4 characters>"))
    }

    @Test
    fun `pretty prints compact JSON object and array bodies`() {
        val record =
            APITraceRecord(
                durationMs = 1,
                method = "POST",
                url = "https://api.example.com/items",
                endpoint = "/items",
                request =
                    APITraceRecord.RequestData(bodyText = """{"request":{"value":true}}"""),
                response =
                    APITraceRecord.ResponseData(
                        statusCode = 200,
                        bodyText = """[{"id":1},{"id":2}]""",
                    ),
            )

        val output = APITraceConsoleFormatter().format(record)

        assertFalse(output.contains("""{"request":{"value":true}}"""))
        assertFalse(output.contains("""[{"id":1},{"id":2}]"""))
        assertTrue(output.contains("\n      \"request\": {"))
        assertTrue(output.contains("\n        \"value\": true"))
        assertTrue(output.contains("\n      {"))
    }

    @Test
    fun `omits empty whitespace and empty binary bodies`() {
        val record =
            APITraceRecord(
                durationMs = 1,
                method = "POST",
                url = "https://api.example.com/items",
                endpoint = "/items",
                request = APITraceRecord.RequestData(bodyText = " \n\t "),
                response = APITraceRecord.ResponseData(statusCode = 204, bodyBase64 = ""),
            )

        val output = APITraceConsoleFormatter().format(record)

        assertFalse(output.contains("Body:"))
        assertFalse(output.contains("<empty>"))
    }

    @Test
    fun `preserves plain and malformed text bodies`() {
        val record =
            APITraceRecord(
                durationMs = 1,
                method = "POST",
                url = "https://api.example.com/items",
                endpoint = "/items",
                request = APITraceRecord.RequestData(bodyText = "plain text"),
                response =
                    APITraceRecord.ResponseData(statusCode = 400, bodyText = "{not json}"),
            )

        val output = APITraceConsoleFormatter().format(record)

        assertTrue(output.contains("\n    plain text"))
        assertTrue(output.contains("\n    {not json}"))
    }

    @Test
    fun `truncates after pretty printing JSON`() {
        val record =
            APITraceRecord(
                durationMs = 1,
                method = "POST",
                url = "https://api.example.com/items",
                endpoint = "/items",
                request =
                    APITraceRecord.RequestData(bodyText = """{"longValue":"abcdefghijk"}"""),
            )

        val output = APITraceConsoleFormatter(maxBodyCharacters = 12).format(record)

        assertTrue(output.contains("…[truncated]"))
        assertFalse(output.contains("""{"longValue""""))
    }
}
