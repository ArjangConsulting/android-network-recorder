package com.apitrace

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/** OkHttp backend that stores one record per request/response exchange. */
class APITraceOkHttpBackend(
    private val maxRecords: Int = 500,
    private val redactor: APITraceRedactor = APITraceRedactor.DEFAULT,
    private val maxBodyBytes: Long = 64 * 1024,
    private val captureRequestBodies: Boolean = true,
    private val captureResponseBodies: Boolean = true,
    private val allowInNonDebuggableBuilds: Boolean = false,
) : APITraceBackend {
    private val buffer = CopyOnWriteArrayList<APITraceRecord>()

    @Volatile
    private var isEnabled = false

    val interceptor: Interceptor = Interceptor { chain ->
        val request = chain.request()

        if (!isEnabled) {
            return@Interceptor chain.proceed(request)
        }

        val startNs = System.nanoTime()
        val startedAtEpochMs = System.currentTimeMillis()

        val redactedUrl = redactor.redact(request.url)
        val requestCapture = buildRequestCapture(request, redactedUrl)

        try {
            val response = chain.proceed(request)
            append(
                APITraceRecord(
                    startedAtEpochMs = startedAtEpochMs,
                    durationMs = elapsedMs(startNs),
                    method = request.method,
                    url = redactedUrl.url,
                    endpoint = request.url.encodedPath,
                    request = requestCapture,
                    response = buildResponseCapture(response),
                )
            )
            response
        } catch (ioe: IOException) {
            append(
                APITraceRecord(
                    startedAtEpochMs = startedAtEpochMs,
                    durationMs = elapsedMs(startNs),
                    method = request.method,
                    url = redactedUrl.url,
                    endpoint = request.url.encodedPath,
                    request = requestCapture,
                    response = null,
                    errorMessage = ioe.message?.let(redactor::redactErrorMessage),
                )
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
        isEnabled = true
    }

    override fun stop() {
        isEnabled = false
    }

    override fun clear() {
        buffer.clear()
    }

    override fun records(): List<APITraceRecord> {
        return Collections.unmodifiableList(buffer.toList())
    }

    private fun buildRequestCapture(
        request: Request,
        redactedUrl: APITraceRedactedUrl,
    ): APITraceRecord.RequestData {
        val headers = redactor.redact(request.headers.toMultimap())
        val bodyCapture = if (captureRequestBodies) extractRequestBody(request) else BodyCapture()

        return APITraceRecord.RequestData(
            headers = headers,
            queryItems = redactedUrl.queryItems,
            bodyText = bodyCapture.text,
            bodyBase64 = bodyCapture.base64,
        )
    }

    private fun buildResponseCapture(response: Response): APITraceRecord.ResponseData {
        val headers = redactor.redactResponseHeaders(response.headers.toMultimap())
        val bodyCapture = if (captureResponseBodies) peekResponseBody(response) else BodyCapture()

        return APITraceRecord.ResponseData(
            statusCode = response.code,
            headers = headers,
            bodyText = bodyCapture.text,
            bodyBase64 = bodyCapture.base64,
        )
    }

    private fun elapsedMs(startNs: Long): Long {
        val elapsedNs = System.nanoTime() - startNs
        return elapsedNs / 1_000_000L
    }

    private fun append(record: APITraceRecord) {
        buffer.add(record)
        val overflow = buffer.size - maxRecords
        if (overflow > 0) {
            repeat(overflow) {
                if (buffer.isNotEmpty()) {
                    buffer.removeAt(0)
                }
            }
        }
    }

    private fun extractRequestBody(request: Request): BodyCapture {
        val body = request.body ?: return BodyCapture()
        if (body.isOneShot() || body.isDuplex()) {
            return BodyCapture()
        }

        return runCatching {
            val sink = Buffer()
            body.writeTo(sink)
            val truncated = sink.size > maxBodyBytes
            decodeBytes(sink.readByteArray(minOf(sink.size, maxBodyBytes)), truncated)
        }.getOrElse { BodyCapture() }
    }

    private fun peekResponseBody(response: Response): BodyCapture {
        val body = response.body ?: return BodyCapture()

        // peekBody blocks until maxBodyBytes arrive or the stream ends, which would
        // stall server-sent events and other never-ending responses.
        if (response.code == 101 || isEventStream(response.header("Content-Type"))) {
            return BodyCapture()
        }

        return runCatching {
            val peeked = response.peekBody(maxBodyBytes)
            val bytes = peeked.bytes()
            decodeBytes(bytes, truncated = bytes.size.toLong() >= maxBodyBytes)
        }.getOrElse { BodyCapture() }
    }

    private fun isEventStream(contentType: String?): Boolean {
        return contentType?.substringBefore(';')?.trim()
            .equals("text/event-stream", ignoreCase = true)
    }

    private fun decodeBytes(bytes: ByteArray, truncated: Boolean): BodyCapture {
        if (bytes.isEmpty()) {
            return BodyCapture()
        }

        // A byte-limit cut can split a multi-byte UTF-8 character; drop the partial
        // trailing character instead of misclassifying the body as binary.
        val maxTrim = if (truncated) minOf(3, bytes.size - 1) else 0
        for (trim in 0..maxTrim) {
            val candidate = if (trim == 0) bytes else bytes.copyOf(bytes.size - trim)
            val text = candidate.toString(Charsets.UTF_8)
            if (text.toByteArray(Charsets.UTF_8).contentEquals(candidate)) {
                return BodyCapture(text = text)
            }
        }

        return BodyCapture(base64 = bytes.toByteString().base64())
    }

    private fun isDebuggableApp(): Boolean {
        return runCatching {
            val application = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context
                ?: return@runCatching true
            val flags = application.applicationInfo.flags
            (flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        }.getOrDefault(true)
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

    private data class BodyCapture(
        val text: String? = null,
        val base64: String? = null,
    )
}
