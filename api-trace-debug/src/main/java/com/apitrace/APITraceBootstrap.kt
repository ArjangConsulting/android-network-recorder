package com.apitrace

import okhttp3.OkHttpClient

/** Debug bootstrap that installs the OkHttp capture backend. */
object APITraceBootstrap {
    @JvmStatic
    fun install(
        okHttpBuilder: OkHttpClient.Builder,
        maxRecords: Int = 500,
        redactor: APITraceRedactor = APITraceRedactor.DEFAULT,
    ) {
        val backend = APITraceOkHttpBackend(maxRecords = maxRecords, redactor = redactor)
        okHttpBuilder.addNetworkInterceptor(backend.interceptor)
        APITrace.install(backend)
    }
}
