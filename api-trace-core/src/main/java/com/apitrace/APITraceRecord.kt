package com.apitrace

import java.util.UUID

enum class APITraceCaptureMode {
    EXACT,
    INCLUDES,
}

data class APITraceCapturedField(
    val mode: APITraceCaptureMode,
    val values: List<String>,
)

/**
 * One captured HTTP exchange containing request details and response/failure details.
 */
data class APITraceRecord(
    val id: String = UUID.randomUUID().toString(),
    val startedAtEpochMs: Long = System.currentTimeMillis(),
    val durationMs: Long,
    val method: String,
    val url: String,
    val endpoint: String,
    val request: RequestData,
    val response: ResponseData? = null,
    val errorMessage: String? = null,
) {
    /** Request envelope captured before dispatch. */
    data class RequestData(
        val headers: Map<String, APITraceCapturedField> = emptyMap(),
        val queryItems: Map<String, APITraceCapturedField> = emptyMap(),
        val bodyText: String? = null,
        val bodyBase64: String? = null,
    )

    /** Response envelope captured after dispatch succeeds. */
    data class ResponseData(
        val statusCode: Int,
        val headers: Map<String, List<String>> = emptyMap(),
        val bodyText: String? = null,
        val bodyBase64: String? = null,
    )
}
