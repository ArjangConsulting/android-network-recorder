package com.apitrace

import okhttp3.OkHttpClient

/** Debug bootstrap that installs the OkHttp capture backend. */
object APITraceBootstrap {
    /**
     * Registers capture as an application interceptor so bodies are observed after
     * OkHttp's transparent gzip decoding, and installs the backend.
     */
    @JvmStatic
    @JvmOverloads
    fun install(
        okHttpBuilder: OkHttpClient.Builder,
        maxRecords: Int = 500,
        redactor: APITraceRedactor = APITraceRedactor.DEFAULT,
        maxBodyBytes: Long = 64 * 1024,
        captureRequestBodies: Boolean = true,
        captureResponseBodies: Boolean = true,
        allowInNonDebuggableBuilds: Boolean = false,
    ) {
        val backend = APITraceOkHttpBackend(
            maxRecords = maxRecords,
            redactor = redactor,
            maxBodyBytes = maxBodyBytes,
            captureRequestBodies = captureRequestBodies,
            captureResponseBodies = captureResponseBodies,
            allowInNonDebuggableBuilds = allowInNonDebuggableBuilds,
        )
        okHttpBuilder.addInterceptor(backend.interceptor)
        APITrace.install(backend)
    }
}
