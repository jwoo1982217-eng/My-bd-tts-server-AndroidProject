package com.github.jing332.tts.synthesizer

object CacheRequestPayloadRecorder {
    private val lock = Any()
    private var active = false
    private val requests = mutableListOf<RequestPayload>()

    fun begin() {
        synchronized(lock) {
            active = true
            requests.clear()
        }
    }

    fun record(request: RequestPayload) {
        synchronized(lock) {
            if (active) requests += request
        }
    }

    fun end(): List<RequestPayload> {
        return synchronized(lock) {
            val out = requests.toList()
            active = false
            requests.clear()
            out
        }
    }
}
