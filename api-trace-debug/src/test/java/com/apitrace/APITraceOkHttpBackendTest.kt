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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
    fun `response body capture is truncated to maxBodyBytes`() {
        val longBody = "x".repeat(200)
        server.enqueue(MockResponse().setResponseCode(200).setBody(longBody))
        val backend = APITraceOkHttpBackend(
            maxBodyBytes = 10,
            captureResponseBodies = true,
            allowInNonDebuggableBuilds = true,
        )
        val client = clientFor(backend)

        backend.start()
        executeRequest(client)

        val record = backend.records().single()
        val capturedText = requireNotNull(record.response?.bodyText)
        assertTrue(
            "Captured body (${capturedText.length} bytes) must not exceed maxBodyBytes",
            capturedText.toByteArray(Charsets.UTF_8).size <= 10,
        )
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
        assertEquals(listOf("<mocked>"), headers["set-cookie"])
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
    fun `binary response bodies are captured as base64`() {
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
        assertNotNull(record.response?.bodyBase64)
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
