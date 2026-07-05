package com.apitrace

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class APITraceJSONExportTest {

    private val record = APITraceRecord(
        id = "12345678-1234-1234-1234-123456789012",
        startedAtEpochMs = 1_741_143_000_000L,
        durationMs = 84,
        method = "GET",
        url = "https://api.example.com/v1/users",
        endpoint = "/v1/users",
        request = APITraceRecord.RequestData(),
        response = APITraceRecord.ResponseData(statusCode = 200),
        errorMessage = null,
    )

    @Test
    fun startedAtIsExportedAsIso8601WithMilliseconds() {
        val exported = JSONArray(APITrace.jsonString(listOf(record), pretty = false)).getJSONObject(0)
        assertEquals("2025-03-05T02:50:00.000Z", exported.getString("startedAt"))
        assertEquals(84, exported.getInt("durationMs"))
    }
}
