package com.apitrace

import okhttp3.OkHttpClient

/** Release bootstrap with matching API that installs a no-op backend. */
object APITraceBootstrap {
    @JvmStatic
    @JvmOverloads
    @Suppress("UNUSED_PARAMETER")
    fun install(
        okHttpBuilder: OkHttpClient.Builder,
        maxRecords: Int = 500,
        redactor: APITraceRedactor = APITraceRedactor.DEFAULT,
        maxBodyBytes: Long = 64 * 1024,
        captureRequestBodies: Boolean = true,
        captureResponseBodies: Boolean = true,
        allowInNonDebuggableBuilds: Boolean = false,
    ) {
        APITrace.install(APITraceNoopBackend())
    }
}
