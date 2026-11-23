package com.apitrace

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opt-in export of the captured history to a HAR file, so a test harness can copy a run's
 * traffic off an emulator or device without app-specific export code.
 *
 * Writing follows the installed [APITrace] backend: the file is rewritten after every recorded
 * exchange and after [APITrace.clear]. Writes are coalesced and best effort; a failed write never
 * affects the app's own requests.
 */
object APITraceHarFile {
    /** Launch intent extra that turns HAR file export on. Accepted values: `1`, `true`, `yes`. */
    const val EXTRA_KEY = "APITRACE_HAR_FILE"

    /** Name of the file written in [defaultFile]'s directory. */
    const val FILE_NAME = "apitrace.har"

    @Volatile
    private var writer: HarFileWriter? = null

    /**
     * `apitrace.har` in the app's external cache directory, which `adb pull` can read without
     * root on any build type, other apps cannot read, and backups skip. Falls back to the
     * internal cache directory when no external storage is mounted.
     */
    @JvmStatic
    fun defaultFile(context: Context): File = File(context.externalCacheDir ?: context.cacheDir, FILE_NAME)

    /** Whether an [EXTRA_KEY] value asks for HAR file export. */
    @JvmStatic
    fun isRequested(value: String?): Boolean = value?.trim()?.lowercase() in setOf("1", "true", "yes")

    /** Writes the captured history to [file] from now on; `null` stops writing. */
    @JvmStatic
    @Synchronized
    fun enable(file: File?) {
        writer?.shutdown()
        writer = file?.let { HarFileWriter(it) { APITrace.exportHar(pretty = false) } }
        writer?.recordsChanged()
    }

    /**
     * Turns on writing to [defaultFile] when the first activity is launched with the [EXTRA_KEY]
     * extra (for example `adb shell am start -n <component> --es APITRACE_HAR_FILE 1`). Call it
     * once in `Application.onCreate`, next to [APITraceBootstrap.install].
     */
    @JvmStatic
    fun enableFromLaunchIntent(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                application.unregisterActivityLifecycleCallbacks(this)
                if (isRequested(activity.intent?.getStringExtra(EXTRA_KEY))) {
                    enable(defaultFile(application))
                }
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    internal fun recordsChanged() {
        writer?.recordsChanged()
    }

    /** Waits until any pending write has finished. For tests. */
    internal fun flush() {
        writer?.flush()
    }
}

/** Rewrites [file] after changes, coalescing bursts into one write on a background thread. */
internal class HarFileWriter(private val file: File, private val har: () -> String) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "apitrace-har-file").apply { isDaemon = true }
    }
    private val scheduled = AtomicBoolean(false)

    fun recordsChanged() {
        if (!scheduled.compareAndSet(false, true)) return
        runCatching {
            executor.execute {
                scheduled.set(false)
                write()
            }
        }
    }

    fun flush() {
        runCatching { executor.submit {}.get(5, TimeUnit.SECONDS) }
    }

    fun shutdown() {
        executor.shutdown()
    }

    private fun write() {
        runCatching {
            val directory = file.absoluteFile.parentFile
            directory?.mkdirs()
            val temporary = File(directory, "${file.name}.tmp")
            val content = har()
            temporary.writeText(content)
            if (!temporary.renameTo(file)) {
                file.delete()
                check(temporary.renameTo(file)) { "could not move ${temporary.name} into place" }
            }
            content.length
        }.onSuccess { characters ->
            log(android.util.Log.DEBUG, "Wrote $characters characters to ${file.name}")
        }.onFailure { error ->
            // Export is best effort: a failed write is logged and never affects the app's own traffic.
            log(android.util.Log.WARN, "Failed to write ${file.name}: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun log(priority: Int, message: String) {
        runCatching { android.util.Log.println(priority, "APITrace", message) }
    }
}
