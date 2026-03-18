package com.apitrace

/** Backend abstraction for capture implementations. */
interface APITraceBackend {
    fun start()
    fun stop()
    fun clear()
    fun records(): List<APITraceRecord>
}

/** Release-safe backend that records nothing. */
class APITraceNoopBackend : APITraceBackend {
    override fun start() {}
    override fun stop() {}
    override fun clear() {}
    override fun records(): List<APITraceRecord> = emptyList()
}
