package com.apitrace

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/** OkHttp backend that stores one record per request/response exchange. */
class APITraceOkHttpBackend(
    private val maxRecords: Int = 500,
    private val redactor: APITraceRedactor = APITraceRedactor.DEFAULT,
    private val maxBodyBytes: Long = 64 * 1024,
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
                    errorMessage = ioe.message,
                )
            )
            throw ioe
        }
    }

    override fun start() {
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
        val bodyCapture = extractRequestBody(request)

        return APITraceRecord.RequestData(
            headers = headers,
            queryItems = redactedUrl.queryItems,
            bodyText = bodyCapture.text,
            bodyBase64 = bodyCapture.base64,
        )
    }

    private fun buildResponseCapture(response: Response): APITraceRecord.ResponseData {
        val headers = response.headers.toMultimap()
        val bodyCapture = peekResponseBody(response)

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
            val buffer = Buffer()
            body.writeTo(buffer)
            decodeBytes(buffer.readByteArray())
        }.getOrElse { BodyCapture() }
    }

    private fun peekResponseBody(response: Response): BodyCapture {
        val body = response.body ?: return BodyCapture()

        return runCatching {
            val peeked = response.peekBody(maxBodyBytes)
            decodeBytes(peeked.bytes())
        }.getOrElse { BodyCapture() }
    }

    private fun decodeBytes(bytes: ByteArray): BodyCapture {
        if (bytes.isEmpty()) {
            return BodyCapture()
        }

        val text = bytes.toString(Charsets.UTF_8)
        return if (text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
            BodyCapture(text = text)
        } else {
            BodyCapture(base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        }
    }

    private data class BodyCapture(
        val text: String? = null,
        val base64: String? = null,
    )
}
