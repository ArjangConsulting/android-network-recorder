package com.apitrace

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class APITraceHarFileTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        APITraceHarFile.enable(null)
        APITrace.install(APITraceNoopBackend())
        server.shutdown()
    }

    @Test
    fun `launch extra values that turn export on`() {
        listOf("1", "true", "YES", " yes ").forEach { assertTrue(it, APITraceHarFile.isRequested(it)) }
        listOf(null, "", "0", "false", "no").forEach { assertFalse("$it", APITraceHarFile.isRequested(it)) }
    }

    @Test
    fun `a captured exchange is written to the file and clear empties it`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val backend = APITraceOkHttpBackend(allowInNonDebuggableBuilds = true)
        APITrace.install(backend)
        val file = folder.root.resolve("nested/trace.har")
        APITraceHarFile.enable(file)
        backend.start()

        OkHttpClient.Builder().addInterceptor(backend.interceptor).build()
            .newCall(Request.Builder().url(server.url("/ping")).build()).execute().close()
        APITraceHarFile.flush()

        val entries = JSONObject(file.readText()).getJSONObject("log").getJSONArray("entries")
        assertEquals(1, entries.length())
        assertEquals(server.url("/ping").toString(), entries.getJSONObject(0).getJSONObject("request").getString("url"))

        APITrace.clear()
        APITraceHarFile.flush()
        assertEquals(0, JSONObject(file.readText()).getJSONObject("log").getJSONArray("entries").length())
    }

    @Test
    fun `nothing is written until enabled`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val backend = APITraceOkHttpBackend(allowInNonDebuggableBuilds = true)
        APITrace.install(backend)
        backend.start()

        OkHttpClient.Builder().addInterceptor(backend.interceptor).build()
            .newCall(Request.Builder().url(server.url("/ping")).build()).execute().close()
        APITraceHarFile.flush()

        assertEquals(0, folder.root.listFiles()?.size ?: 0)
    }
}
