package com.apitrace

import okhttp3.OkHttpClient

/** Release bootstrap with matching API that installs a no-op backend. */
object APITraceBootstrap {
    @JvmStatic
    fun install(
        okHttpBuilder: OkHttpClient.Builder,
        maxRecords: Int = 500,
        redactor: APITraceRedactor = APITraceRedactor.DEFAULT,
    ) {
        @Suppress("UNUSED_PARAMETER")
        val unusedBuilder = okHttpBuilder
        @Suppress("UNUSED_PARAMETER")
        val unusedMaxRecords = maxRecords
        @Suppress("UNUSED_PARAMETER")
        val unusedRedactor = redactor
        APITrace.install(APITraceNoopBackend())
    }
}
