package com.apitrace

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class APITraceHARExportTest {

    private val record = APITraceRecord(
        id = "12345678-1234-1234-1234-123456789012",
        startedAtEpochMs = 1_741_143_000_000L,
        durationMs = 84,
        method = "GET",
        url = "https://api.example.com/v1/users?page=1&token=%3Cmocked%3E",
        endpoint = "/v1/users",
        request = APITraceRecord.RequestData(
            headers = mapOf(
                "X-Trace-Id" to APITraceCapturedField(APITraceCaptureMode.EXACT, listOf("trace-123"))
            ),
            queryItems = mapOf(
                "page" to APITraceCapturedField(APITraceCaptureMode.EXACT, listOf("1")),
                "token" to APITraceCapturedField(APITraceCaptureMode.INCLUDES, listOf("<mocked>"))
            ),
            bodyText = null,
            bodyBase64 = null
        ),
        response = APITraceRecord.ResponseData(
            statusCode = 200,
            headers = mapOf("Content-Type" to listOf("application/json; charset=utf-8")),
            bodyText = """{"ok":true}""",
            bodyBase64 = null
        ),
        errorMessage = null
    )

    private fun har(records: List<APITraceRecord> = listOf(record)): JSONObject {
        val json = APITrace.harString(records, pretty = false)
        return JSONObject(json)
    }

    @Test
    fun topLevelLogObjectHasVersionAndCreator() {
        val root = har()
        val log = root.getJSONObject("log")
        assertEquals("1.2", log.getString("version"))
        val creator = log.getJSONObject("creator")
        assertEquals("APITrace", creator.getString("name"))
    }

    @Test
    fun entryCountMatchesRecordCount() {
        val root = har(listOf(record, record))
        val entries = root.getJSONObject("log").getJSONArray("entries")
        assertEquals(2, entries.length())
    }

    @Test
    fun entryTimingsAreCorrect() {
        val entry = har().getJSONObject("log").getJSONArray("entries").getJSONObject(0)
        assertEquals(84, entry.getInt("time"))
        val timings = entry.getJSONObject("timings")
        assertEquals(0, timings.getInt("send"))
        assertEquals(84, timings.getInt("wait"))
        assertEquals(0, timings.getInt("receive"))
    }

    @Test
    fun startedDateTimeIsIso8601() {
        val entry = har().getJSONObject("log").getJSONArray("entries").getJSONObject(0)
        val dt = entry.getString("startedDateTime")
        assertTrue("Expected ISO 8601 format", dt.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""")))
    }

    @Test
    fun requestMethodAndUrlAreMapped() {
        val entry = har().getJSONObject("log").getJSONArray("entries").getJSONObject(0)
        val request = entry.getJSONObject("request")
        assertEquals("GET", request.getString("method"))
        assertEquals("https://api.example.com/v1/users?page=1&token=%3Cmocked%3E", request.getString("url"))
        assertEquals("HTTP/1.1", request.getString("httpVersion"))
    }

    @Test
    fun requestHeadersFlattenedToNameValuePairs() {
        val entry = har().getJSONObject("log").getJSONArray("entries").getJSONObject(0)
        val headers = entry.getJSONObject("request").getJSONArray("headers")
        assertEquals(1, headers.length())
        val h = headers.getJSONObject(0)
        assertEquals("X-Trace-Id", h.getString("name"))
        assertEquals("trace-123", h.getString("value"))
    }

    @Test
    fun requestQueryStringFlattenedToNameValuePairs() {
        val entry = har().getJSONObject("log").getJSONArray("entries").getJSONObject(0)
        val qs = entry.getJSONObject("request").getJSONArray("queryString")
        assertEquals(2, qs.length())
        val names = (0 until qs.length()).map { qs.getJSONObject(it).getString("name") }.toSet()
        assertEquals(setOf("page", "token"), names)
    }

    @Test
    fun responseStatusAndContentAreMapped() {
        val entry = har().getJSONObject("log").getJSONArray("entries").getJSONObject(0)
        val response = entry.getJSONObject("response")
        assertEquals(200, response.getInt("status"))
        assertEquals("HTTP/1.1", response.getString("httpVersion"))
        val content = response.getJSONObject("content")
        assertEquals("""{"ok":true}""", content.getString("text"))
        assertEquals("application/json", content.getString("mimeType"))
        assertEquals("""{"ok":true}""".toByteArray(Charsets.UTF_8).size, content.getInt("size"))
        assertFalse(content.has("encoding"))
    }

    @Test
    fun binaryResponseBodyExportedAsBase64WithEncodingMarker() {
        val base64 = okio.ByteString.of(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()).base64()
        val binary = record.copy(
            response = APITraceRecord.ResponseData(
                statusCode = 200,
                // toMultimap() lowercases header names; the exporter must still find Content-Type.
                headers = mapOf("content-type" to listOf("image/jpeg")),
                bodyText = null,
                bodyBase64 = base64,
            )
        )
        val entry = har(listOf(binary)).getJSONObject("log").getJSONArray("entries").getJSONObject(0)
        val content = entry.getJSONObject("response").getJSONObject("content")
        assertEquals(base64, content.getString("text"))
        assertEquals("base64", content.getString("encoding"))
        assertEquals(4, content.getInt("size"))
        assertEquals("image/jpeg", content.getString("mimeType"))
    }

    @Test
    fun locationHeaderBecomesRedirectUrl() {
        val redirect = record.copy(
            response = APITraceRecord.ResponseData(
                statusCode = 301,
                headers = mapOf("location" to listOf("https://api.example.com/new")),
            )
        )
        val entry = har(listOf(redirect)).getJSONObject("log").getJSONArray("entries").getJSONObject(0)
        assertEquals("https://api.example.com/new", entry.getJSONObject("response").getString("redirectURL"))
    }

    @Test
    fun postDataMimeTypeTakenFromCapturedContentTypeHeader() {
        val posted = record.copy(
            method = "POST",
            request = APITraceRecord.RequestData(
                headers = mapOf(
                    "Content-Type" to APITraceCapturedField(
                        APITraceCaptureMode.EXACT,
                        listOf("application/json; charset=utf-8"),
                    )
                ),
                bodyText = """{"name":"x"}""",
            )
        )
        val entry = har(listOf(posted)).getJSONObject("log").getJSONArray("entries").getJSONObject(0)
        val postData = entry.getJSONObject("request").getJSONObject("postData")
        assertEquals("application/json", postData.getString("mimeType"))
        assertEquals("""{"name":"x"}""", postData.getString("text"))
    }

    @Test
    fun responseHeadersFlattenedToNameValuePairs() {
        val entry = har().getJSONObject("log").getJSONArray("entries").getJSONObject(0)
        val headers = entry.getJSONObject("response").getJSONArray("headers")
        assertEquals(1, headers.length())
        val h = headers.getJSONObject(0)
        assertEquals("Content-Type", h.getString("name"))
        assertEquals("application/json; charset=utf-8", h.getString("value"))
    }

    @Test
    fun failedRequestProducesStubResponseWithStatus0() {
        val failed = APITraceRecord(
            id = "failed-id",
            startedAtEpochMs = 1_741_143_000_000L,
            durationMs = 200,
            method = "POST",
            url = "https://api.example.com/v1/items",
            endpoint = "/v1/items",
            request = APITraceRecord.RequestData(emptyMap(), emptyMap(), null, null),
            response = null,
            errorMessage = "Network error"
        )
        val entry = har(listOf(failed)).getJSONObject("log").getJSONArray("entries").getJSONObject(0)
        val response = entry.getJSONObject("response")
        assertEquals(0, response.getInt("status"))
    }

    @Test
    fun failedRequestContentOmitsTextRatherThanEncodingJsonNull() {
        // HAR 1.2 requires content.text to be a string when present; a JSON `null` fails
        // strict schema validation (e.g. Charles refuses to import the whole file).
        val failed = APITraceRecord(
            id = "failed-id",
            startedAtEpochMs = 1_741_143_000_000L,
            durationMs = 200,
            method = "POST",
            url = "https://api.example.com/v1/items",
            endpoint = "/v1/items",
            request = APITraceRecord.RequestData(emptyMap(), emptyMap(), null, null),
            response = null,
            errorMessage = "Network error"
        )
        val entry = har(listOf(failed)).getJSONObject("log").getJSONArray("entries").getJSONObject(0)
        val content = entry.getJSONObject("response").getJSONObject("content")
        assertFalse(content.has("text"))
    }

    @Test
    fun bodylessResponseContentOmitsTextRatherThanEncodingJsonNull() {
        val bodyless = record.copy(
            response = APITraceRecord.ResponseData(
                statusCode = 204,
                headers = emptyMap(),
                bodyText = null,
                bodyBase64 = null
            )
        )
        val entry = har(listOf(bodyless)).getJSONObject("log").getJSONArray("entries").getJSONObject(0)
        val content = entry.getJSONObject("response").getJSONObject("content")
        assertFalse(content.has("text"))
    }

    @Test
    fun emptyRecordsProduceEmptyEntriesArray() {
        val entries = har(emptyList()).getJSONObject("log").getJSONArray("entries")
        assertEquals(0, entries.length())
    }

    @Test
    fun entryHasCacheObject() {
        val entry = har().getJSONObject("log").getJSONArray("entries").getJSONObject(0)
        assertNotNull(entry.getJSONObject("cache"))
    }
}
