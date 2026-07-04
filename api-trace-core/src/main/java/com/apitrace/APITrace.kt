package com.apitrace

import org.json.JSONArray
import org.json.JSONObject

/** Public facade to install, control, and export API trace history. */
object APITrace {
    @Volatile
    private var backend: APITraceBackend = APITraceNoopBackend()

    /** Installs a backend implementation, usually during app startup. */
    fun install(backend: APITraceBackend) {
        this.backend.stop()
        this.backend = backend
    }

    /** Enables capture on the installed backend. */
    fun start() {
        backend.start()
    }

    /** Disables capture on the installed backend. */
    fun stop() {
        backend.stop()
    }

    /** Clears in-memory trace history. */
    fun clear() {
        backend.clear()
    }

    /** Returns all captured exchanges. */
    fun records(): List<APITraceRecord> = backend.records()

    /** Exports captured exchanges as JSON. */
    fun exportJson(pretty: Boolean = true): String {
        val array = JSONArray()

        records().forEach { record ->
            val obj = JSONObject()
            obj.put("id", record.id)
            obj.put("startedAtEpochMs", record.startedAtEpochMs)
            obj.put("durationMs", record.durationMs)
            obj.put("method", record.method)
            obj.put("url", record.url)
            obj.put("endpoint", record.endpoint)
            obj.put("request", requestToJson(record.request))
            obj.put("response", record.response?.let(::responseToJson) ?: JSONObject.NULL)
            obj.put("errorMessage", record.errorMessage ?: JSONObject.NULL)
            array.put(obj)
        }

        return if (pretty) array.toString(2) else array.toString()
    }

    /** Exports captured exchanges as a HAR 1.2 archive. */
    fun exportHar(pretty: Boolean = true): String = harString(records(), pretty)

    internal fun harString(records: List<APITraceRecord>, pretty: Boolean = true): String {
        val entries = JSONArray()
        records.forEach { record ->
            val entry = JSONObject()
            entry.put("startedDateTime", epochMsToIso8601(record.startedAtEpochMs))
            entry.put("time", record.durationMs)
            entry.put("request", recordRequestToHar(record))
            entry.put("response", record.response?.let(::responseToHar) ?: failedHarResponse())
            entry.put("cache", JSONObject())
            val timings = JSONObject()
            timings.put("send", 0)
            timings.put("wait", record.durationMs)
            timings.put("receive", 0)
            entry.put("timings", timings)
            entries.put(entry)
        }

        val creator = JSONObject()
        creator.put("name", "APITrace")
        creator.put("version", "1.0")

        val log = JSONObject()
        log.put("version", "1.2")
        log.put("creator", creator)
        log.put("entries", entries)

        val root = JSONObject()
        root.put("log", log)

        return if (pretty) root.toString(2) else root.toString()
    }

    private fun recordRequestToHar(record: APITraceRecord): JSONObject {
        val obj = JSONObject()
        obj.put("method", record.method)
        obj.put("url", record.url)
        obj.put("httpVersion", "HTTP/1.1")
        obj.put("headers", capturedFieldsToHarArray(record.request.headers))
        obj.put("queryString", capturedFieldsToHarArray(record.request.queryItems))
        obj.put("cookies", JSONArray())
        obj.put("headersSize", -1)
        obj.put("bodySize", -1)
        val bodyText = record.request.bodyText
        if (bodyText != null) {
            val postData = JSONObject()
            postData.put("mimeType", "")
            postData.put("text", bodyText)
            obj.put("postData", postData)
        }
        return obj
    }

    private fun responseToHar(response: APITraceRecord.ResponseData): JSONObject {
        val obj = JSONObject()
        obj.put("status", response.statusCode)
        obj.put("statusText", "")
        obj.put("httpVersion", "HTTP/1.1")
        obj.put("headers", headersToHarArray(response.headers))
        obj.put("cookies", JSONArray())
        val mimeType = response.headers["Content-Type"]?.firstOrNull()
            ?.split(";")?.firstOrNull()?.trim() ?: ""
        val content = JSONObject()
        content.put("size", -1)
        content.put("mimeType", mimeType)
        content.put("text", response.bodyText ?: JSONObject.NULL)
        obj.put("content", content)
        obj.put("redirectURL", "")
        obj.put("headersSize", -1)
        obj.put("bodySize", -1)
        return obj
    }

    private fun failedHarResponse(): JSONObject {
        val obj = JSONObject()
        obj.put("status", 0)
        obj.put("statusText", "")
        obj.put("httpVersion", "HTTP/1.1")
        obj.put("headers", JSONArray())
        obj.put("cookies", JSONArray())
        val content = JSONObject()
        content.put("size", -1)
        content.put("mimeType", "")
        content.put("text", JSONObject.NULL)
        obj.put("content", content)
        obj.put("redirectURL", "")
        obj.put("headersSize", -1)
        obj.put("bodySize", -1)
        return obj
    }

    private fun headersToHarArray(headers: Map<String, List<String>>): JSONArray {
        val array = JSONArray()
        headers.forEach { (name, values) ->
            values.forEach { value ->
                val obj = JSONObject()
                obj.put("name", name)
                obj.put("value", value)
                array.put(obj)
            }
        }
        return array
    }

    private fun capturedFieldsToHarArray(fields: Map<String, APITraceCapturedField>): JSONArray {
        val array = JSONArray()
        fields.forEach { (name, field) ->
            field.values.forEach { value ->
                val obj = JSONObject()
                obj.put("name", name)
                obj.put("value", value)
                array.put(obj)
            }
        }
        return array
    }

    private fun epochMsToIso8601(epochMs: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date(epochMs))
    }

    private fun requestToJson(request: APITraceRecord.RequestData): JSONObject {
        val obj = JSONObject()
        obj.put("headers", capturedFieldsToJson(request.headers))
        obj.put("queryItems", capturedFieldsToJson(request.queryItems))
        obj.put("bodyText", request.bodyText ?: JSONObject.NULL)
        obj.put("bodyBase64", request.bodyBase64 ?: JSONObject.NULL)
        return obj
    }

    private fun responseToJson(response: APITraceRecord.ResponseData): JSONObject {
        val obj = JSONObject()
        obj.put("statusCode", response.statusCode)
        obj.put("headers", headersToJson(response.headers))
        obj.put("bodyText", response.bodyText ?: JSONObject.NULL)
        obj.put("bodyBase64", response.bodyBase64 ?: JSONObject.NULL)
        return obj
    }

    private fun headersToJson(headers: Map<String, List<String>>): JSONObject {
        val obj = JSONObject()
        headers.forEach { (name, values) ->
            val array = JSONArray()
            values.forEach(array::put)
            obj.put(name, array)
        }
        return obj
    }

    private fun capturedFieldsToJson(fields: Map<String, APITraceCapturedField>): JSONObject {
        val obj = JSONObject()
        fields.forEach { (name, field) ->
            val item = JSONObject()
            val values = JSONArray()
            field.values.forEach(values::put)
            item.put("mode", field.mode.name.lowercase())
            item.put("values", values)
            obj.put(name, item)
        }
        return obj
    }
}
