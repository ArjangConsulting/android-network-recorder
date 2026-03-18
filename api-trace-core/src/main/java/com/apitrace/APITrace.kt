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
