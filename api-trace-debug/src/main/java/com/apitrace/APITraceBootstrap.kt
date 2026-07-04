package com.apitrace

import okhttp3.OkHttpClient

/** Debug bootstrap that installs the OkHttp capture backend. */
object APITraceBootstrap {
    @JvmStatic
    fun install(
        okHttpBuilder: OkHttpClient.Builder,
        maxRecords: Int = 500,
        redactor: APITraceRedactor = APITraceRedactor.DEFAULT,
        maxBodyBytes: Long = 64 * 1024,
    ) {
        val backend = APITraceOkHttpBackend(
            maxRecords = maxRecords,
            redactor = redactor,
            maxBodyBytes = maxBodyBytes,
        )
        okHttpBuilder.addNetworkInterceptor(backend.interceptor)
        APITrace.install(backend)
    }
}
