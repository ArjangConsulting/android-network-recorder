package com.apitrace

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.Sink
import okio.Timeout
import okio.buffer
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/** OkHttp backend that stores one record per request/response exchange. */
class APITraceOkHttpBackend(
    private val maxRecords: Int = 500,
    private val redactor: APITraceRedactor = APITraceRedactor.DEFAULT,
    private val maxBodyBytes: Long = 64 * 1024,
    private val captureRequestBodies: Boolean = false,
    private val captureResponseBodies: Boolean = false,
    private val allowInNonDebuggableBuilds: Boolean = false,
) : APITraceBackend {
    private val buffer = mutableListOf<APITraceRecord>()
    private val bufferLock = Any()
    private val generation = AtomicLong()

    init {
        require(maxRecords > 0) { "maxRecords must be greater than zero" }
        require(maxBodyBytes >= 0) { "maxBodyBytes must not be negative" }
    }

    @Volatile
    private var isEnabled = false

    val interceptor: Interceptor = Interceptor { chain ->
        val request = chain.request()

        if (!isEnabled) {
            return@Interceptor chain.proceed(request)
        }
        val captureGeneration = generation.get()

        val startNs = System.nanoTime()
        val startedAtEpochMs = System.currentTimeMillis()

        val redactedUrl = redactor.redact(request.url)
        val endpoint = redactedUrl.url.toHttpUrlOrNull()?.encodedPath.orEmpty()
        val requestCapture = buildRequestCapture(request, redactedUrl)

        try {
            val response = chain.proceed(request)
            append(
                APITraceRecord(
                    startedAtEpochMs = startedAtEpochMs,
                    durationMs = elapsedMs(startNs),
                    method = request.method,
                    url = redactedUrl.url,
                    endpoint = endpoint,
                    request = requestCapture,
                    response = buildResponseCapture(response),
                ),
                captureGeneration,
            )
            response
        } catch (ioe: IOException) {
            append(
                APITraceRecord(
                    startedAtEpochMs = startedAtEpochMs,
                    durationMs = elapsedMs(startNs),
                    method = request.method,
                    url = redactedUrl.url,
                    endpoint = endpoint,
                    request = requestCapture,
                    response = null,
                    errorMessage = "Network request failed",
                ),
                captureGeneration,
            )
            throw ioe
        }
    }

    override fun start() {
        if (!allowInNonDebuggableBuilds && !isDebuggableApp()) {
            // Defense in depth for a misconfigured release build: never capture user
            // traffic unless the host app is debuggable or explicitly opted in.
            logRefusal()
            return
        }
        generation.incrementAndGet()
        isEnabled = true
    }

    override fun stop() {
        isEnabled = false
        generation.incrementAndGet()
    }

    override fun clear() {
        generation.incrementAndGet()
        synchronized(bufferLock) {
            buffer.clear()
        }
        APITraceHarFile.recordsChanged()
    }

    override fun records(): List<APITraceRecord> {
        return synchronized(bufferLock) {
            Collections.unmodifiableList(buffer.toList())
        }
    }

    private fun buildRequestCapture(
        request: Request,
        redactedUrl: APITraceRedactedUrl,
    ): APITraceRecord.RequestData {
        val rawHeaders = request.headers.toMultimap().toMutableMap()
        val contentType = request.body?.contentType()?.toString() ?: request.header("Content-Type")
        if (contentType != null) rawHeaders["content-type"] = listOf(contentType)

        return APITraceRecord.RequestData(
            headers = redactor.redact(rawHeaders),
            queryItems = redactedUrl.queryItems,
            bodyText = if (captureRequestBodies) extractRequestBody(request, contentType) else null,
            cookieNames = redactor.cookieNames(rawHeaders),
        )
    }

    private fun buildResponseCapture(response: Response): APITraceRecord.ResponseData {
        val rawHeaders = response.headers.toMultimap()

        return APITraceRecord.ResponseData(
            statusCode = response.code,
            headers = redactor.redactResponseHeaders(rawHeaders),
            bodyText = if (captureResponseBodies) peekResponseBody(response) else null,
            cookieNames = redactor.cookieNames(rawHeaders, response = true),
        )
    }

    private fun elapsedMs(startNs: Long): Long {
        val elapsedNs = System.nanoTime() - startNs
        return elapsedNs / 1_000_000L
    }

    private fun append(record: APITraceRecord, captureGeneration: Long) {
        synchronized(bufferLock) {
            if (!isEnabled || generation.get() != captureGeneration) {
                return
            }
            buffer.add(record)
            if (buffer.size > maxRecords) {
                buffer.subList(0, buffer.size - maxRecords).clear()
            }
        }
        APITraceHarFile.recordsChanged()
    }

    private fun extractRequestBody(request: Request, contentType: String?): String? {
        val body = request.body ?: return null
        if (body.isOneShot() || body.isDuplex() || !isIdentityEncoding(request.header("Content-Encoding"))) {
            return null
        }

        val contentLength = try {
            body.contentLength()
        } catch (_: IOException) {
            return null
        }
        if (maxBodyBytes == 0L || contentLength < 0 || contentLength > maxBodyBytes) {
            return null
        }

        return try {
            val prefix = Buffer()
            var bytesSeen = 0L
            val boundedSink = object : Sink {
                override fun write(source: Buffer, byteCount: Long) {
                    val remaining = (maxBodyBytes - prefix.size).coerceAtLeast(0)
                    val captured = minOf(remaining, byteCount)
                    if (captured > 0) prefix.write(source, captured)
                    source.skip(byteCount - captured)
                    bytesSeen += byteCount
                }

                override fun flush() = Unit
                override fun timeout(): Timeout = Timeout.NONE
                override fun close() = Unit
            }.buffer()
            body.writeTo(boundedSink)
            boundedSink.flush()
            if (bytesSeen == contentLength) redactor.redactBody(prefix.readByteArray(), contentType) else null
        } catch (_: Exception) {
            null
        }
    }

    private fun peekResponseBody(response: Response): String? {
        val body = response.body ?: return null
        val contentLength = body.contentLength()
        val contentType = response.header("Content-Type")
        if (contentLength <= 0 || contentLength > maxBodyBytes ||
            response.code == 101 || isEventStream(contentType) || !isIdentityEncoding(response.header("Content-Encoding"))
        ) {
            return null
        }

        return try {
            val peeked = response.peekBody(contentLength)
            val bytes = peeked.bytes()
            if (bytes.size.toLong() == contentLength) redactor.redactBody(bytes, contentType) else null
        } catch (_: Exception) {
            null
        }
    }

    private fun isEventStream(contentType: String?): Boolean {
        return contentType?.substringBefore(';')?.trim()
            .equals("text/event-stream", ignoreCase = true)
    }

    private fun isIdentityEncoding(contentEncoding: String?): Boolean =
        contentEncoding == null || contentEncoding.trim().equals("identity", ignoreCase = true)

    private fun isDebuggableApp(): Boolean {
        return runCatching {
            val application = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context
                ?: return@runCatching false
            val flags = application.applicationInfo.flags
            (flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        }.getOrDefault(false)
    }

    private fun logRefusal() {
        runCatching {
            android.util.Log.w(
                "APITrace",
                "start() ignored: app is not debuggable. Use the api-trace-noop module in " +
                    "release builds, or opt in with allowInNonDebuggableBuilds = true.",
            )
        }
    }
}
