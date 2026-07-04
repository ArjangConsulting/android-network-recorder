package com.apitrace

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
        OkHttpClient.Builder().addNetworkInterceptor(backend.interceptor).build()

    private fun executeRequest(client: OkHttpClient) {
        val request = Request.Builder().url(server.url("/ping")).build()
        client.newCall(request).execute().close()
    }

    @Test
    fun `capture is disabled by default until start is called`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val backend = APITraceOkHttpBackend()
        val client = clientFor(backend)

        executeRequest(client)

        assertTrue("Expected no records before start()", backend.records().isEmpty())
    }

    @Test
    fun `capture records requests after start is called`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val backend = APITraceOkHttpBackend()
        val client = clientFor(backend)

        backend.start()
        executeRequest(client)

        assertEquals(1, backend.records().size)
    }

    @Test
    fun `capture stops recording after stop is called`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val backend = APITraceOkHttpBackend()
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
        val backend = APITraceOkHttpBackend()
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
        val backend = APITraceOkHttpBackend(maxBodyBytes = 10)
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
    fun `clear removes all captured records`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val backend = APITraceOkHttpBackend()
        val client = clientFor(backend)

        backend.start()
        executeRequest(client)
        backend.clear()

        assertTrue(backend.records().isEmpty())
    }
}
