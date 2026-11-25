package com.apitrace

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class APITraceOkHttpBackendTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun clientFor(backend: APITraceOkHttpBackend): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(backend.interceptor).build()

    private fun executeRequest(client: OkHttpClient) {
        val request = Request.Builder().url(server.url("/ping")).build()
        client.newCall(request).execute().close()
    }

    @Test
    fun writtenHarExcludesCredentials() {
        val secret = "example-secret-8297"
        val json = """{"items":[{"password":"$secret","author":"pilot","note":"eyJhbGci.eyJzdWIi.$secret"}]}"""
        server.enqueue(
            MockResponse().setBody(json)
                .setHeader("Content-Type", "application/json")
                .addHeader("Set-Cookie", "first=$secret; Expires=Wed, 21 Oct 2030 07:28:00 GMT")
                .addHeader("Set-Cookie", "second=$secret; HttpOnly")
                .addHeader("X-Goog-Api-Key", secret)
                .addHeader("Content-Location", "/next?state=$secret"),
        )
        val backend = APITraceOkHttpBackend(
            captureRequestBodies = true,
            captureResponseBodies = true,
            allowInNonDebuggableBuilds = true,
        )
        val har = File.createTempFile("apitrace-", ".har")
        APITrace.install(backend)
        APITraceHarFile.enable(har)
        try {
            APITrace.start()
            val originalForm = "client_secret=$secret&author=pilot"
            val request = Request.Builder()
                .url(server.url("/exchange?API_KEY=$secret&keyword=telescope"))
                .header("Authorization", "Bearer $secret")
                .header("Cookie", "first=$secret; second=$secret")
                .header("X-Session-Ref", "session-42")
                .post(originalForm.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            clientFor(backend).newCall(request).execute().use { response -> assertEquals(json, response.body.string()) }
            assertEquals(originalForm, server.takeRequest().body.readUtf8())
            assertTrue(!APITrace.exportJson().contains(secret))

            APITraceHarFile.flush()
            val archive = har.readText()
            assertTrue(!archive.contains(secret))
            val entry = JSONObject(archive).getJSONObject("log").getJSONArray("entries").getJSONObject(0)
            val capturedRequest = entry.getJSONObject("request")
            val capturedResponse = entry.getJSONObject("response")
            assertTrue(capturedRequest.getString("url").endsWith("API_KEY=%5Bredacted%5D&keyword=telescope"))
            val query = capturedRequest.getJSONArray("queryString").getJSONObject(0)
            assertEquals("API_KEY", query.getString("name"))
            assertEquals("[redacted]", query.getString("value"))
            val postData = capturedRequest.getJSONObject("postData")
            assertEquals("application/x-www-form-urlencoded", postData.getString("mimeType"))
            assertEquals("client_secret=%5Bredacted%5D&author=pilot", postData.getString("text"))
            listOf(capturedRequest, capturedResponse).forEach { message ->
                val cookies = message.getJSONArray("cookies")
                assertEquals(2, cookies.length())
                assertEquals(listOf("first", "second"), (0 until cookies.length()).map { cookies.getJSONObject(it).getString("name") })
                (0 until cookies.length()).forEach { assertEquals("[redacted]", cookies.getJSONObject(it).getString("value")) }
            }
            val content = capturedResponse.getJSONObject("content")
            val item = JSONObject(content.getString("text")).getJSONArray("items").getJSONObject(0)
            assertEquals("[redacted]", item.getString("password"))
            assertEquals("pilot", item.getString("author"))
            assertEquals("eyJhbGci.eyJzdWIi.redacted", item.getString("note"))
        } finally {
            APITraceHarFile.enable(null)
            APITrace.install(APITraceNoopBackend())
            har.delete()
        }
    }

    @Test
    fun `capture is disabled by default until start is called`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val backend = APITraceOkHttpBackend(allowInNonDebuggableBuilds = true)
        val client = clientFor(backend)

        executeRequest(client)

        assertTrue("Expected no records before start()", backend.records().isEmpty())
    }

    @Test
    fun `capture records requests after start is called`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val backend = APITraceOkHttpBackend(allowInNonDebuggableBuilds = true)
        val client = clientFor(backend)

        backend.start()
        executeRequest(client)

        assertEquals(1, backend.records().size)
    }

    @Test
    fun `capture stops recording after stop is called`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val backend = APITraceOkHttpBackend(allowInNonDebuggableBuilds = true)
        val client = clientFor(backend)

        backend.start()
        executeRequest(client)
        backend.stop()
        executeRequest(client)

        assertEquals(
            "stop() must prevent further capture, matching the documented contract",
            1,
            backend.records().size,
        )
    }

    @Test
    fun `capture resumes if start is called again after stop`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val backend = APITraceOkHttpBackend(allowInNonDebuggableBuilds = true)
        val client = clientFor(backend)

        backend.start()
        executeRequest(client)
        backend.stop()
        backend.start()
        executeRequest(client)

        assertEquals(2, backend.records().size)
    }

    @Test
    fun `over-limit response bodies are omitted rather than persisted as partial data`() {
        val longBody = """{"token":"${"x".repeat(200)}"}"""
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(longBody))
        val backend = APITraceOkHttpBackend(
            maxBodyBytes = 10,
            captureResponseBodies = true,
            allowInNonDebuggableBuilds = true,
        )
        val client = clientFor(backend)

        backend.start()
        executeRequest(client)

        val record = backend.records().single()
        assertNull(record.response?.bodyText)
        assertNull(record.response?.bodyBase64)
    }

    @Test
    fun `request bodies are captured`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val backend = APITraceOkHttpBackend(
            captureRequestBodies = true,
            allowInNonDebuggableBuilds = true,
        )
        val client = clientFor(backend)

        backend.start()
        val payload = """{"name":"widget"}"""
        val request = Request.Builder()
            .url(server.url("/items"))
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().close()

        val record = backend.records().single()
        assertEquals(payload, record.request.bodyText)
    }

    @Test
    fun `request bodies larger than maxBodyBytes are skipped`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val backend = APITraceOkHttpBackend(
            maxBodyBytes = 10,
            captureRequestBodies = true,
            allowInNonDebuggableBuilds = true,
        )
        val client = clientFor(backend)

        backend.start()
        val request = Request.Builder()
            .url(server.url("/items"))
            .post("y".repeat(200).toRequestBody("text/plain".toMediaType()))
            .build()
        client.newCall(request).execute().close()

        val record = backend.records().single()
        assertNull(record.request.bodyText)
    }

    @Test
    fun `body capture can be disabled while metadata is still recorded`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val backend = APITraceOkHttpBackend(
            captureRequestBodies = false,
            captureResponseBodies = false,
            allowInNonDebuggableBuilds = true,
        )
        val client = clientFor(backend)

        backend.start()
        val request = Request.Builder()
            .url(server.url("/items"))
            .post("secret-payload".toRequestBody("text/plain".toMediaType()))
            .build()
        client.newCall(request).execute().close()

        val record = backend.records().single()
        assertNull(record.request.bodyText)
        assertNull(record.request.bodyBase64)
        assertNull(record.response?.bodyText)
        assertNull(record.response?.bodyBase64)
        assertEquals(200, record.response?.statusCode)
    }

    @Test
    fun `sensitive response headers are redacted by default`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", "session=super-secret; HttpOnly")
                .addHeader("X-Request-Id", "req-7")
                .setBody("ok")
        )
        val backend = APITraceOkHttpBackend(allowInNonDebuggableBuilds = true)
        val client = clientFor(backend)

        backend.start()
        executeRequest(client)

        val headers = requireNotNull(backend.records().single().response?.headers)
        assertEquals(listOf("[redacted]"), headers["set-cookie"])
        assertEquals(listOf("req-7"), headers["x-request-id"])
    }

    @Test
    fun `unknown length gzip response bodies are skipped to avoid blocking`() {
        val payload = """{"ok":true}"""
        val gzipped = Buffer()
        GzipSink(gzipped).buffer().use { it.writeUtf8(payload) }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Encoding", "gzip")
                .setBody(gzipped)
        )
        val backend = APITraceOkHttpBackend(
            captureResponseBodies = true,
            allowInNonDebuggableBuilds = true,
        )
        val client = clientFor(backend)

        backend.start()
        executeRequest(client)

        val record = backend.records().single()
        assertNull(record.response?.bodyText)
    }

    @Test
    fun `binary response bodies are not retained as base64`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val body = Buffer().write(bytes)
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
        val backend = APITraceOkHttpBackend(
            captureResponseBodies = true,
            allowInNonDebuggableBuilds = true,
        )
        val client = clientFor(backend)

        backend.start()
        executeRequest(client)

        val record = backend.records().single()
        assertNull(record.response?.bodyText)
        assertNull(record.response?.bodyBase64)
    }

    @Test
    fun `clear removes all captured records`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val backend = APITraceOkHttpBackend(allowInNonDebuggableBuilds = true)
        val client = clientFor(backend)

        backend.start()
        executeRequest(client)
        backend.clear()

        assertTrue(backend.records().isEmpty())
    }
}
