package com.apitrace

import android.app.Application
import android.content.Context
import java.io.File

/** Release counterpart of the debug `APITraceHarFile` with the same API. It never writes a file. */
object APITraceHarFile {
    const val EXTRA_KEY = "APITRACE_HAR_FILE"
    const val FILE_NAME = "apitrace.har"

    @JvmStatic
    fun defaultFile(context: Context): File = File(context.externalCacheDir ?: context.cacheDir, FILE_NAME)

    @JvmStatic
    fun isRequested(value: String?): Boolean = value?.trim()?.lowercase() in setOf("1", "true", "yes")

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun enable(file: File?) = Unit

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun enableFromLaunchIntent(application: Application) = Unit
}
