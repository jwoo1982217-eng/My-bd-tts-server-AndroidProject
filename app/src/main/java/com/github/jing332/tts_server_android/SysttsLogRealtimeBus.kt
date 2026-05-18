package com.github.jing332.tts_server_android

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object SysttsLogRealtimeBus {
    private val _events = MutableSharedFlow<Long>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events = _events.asSharedFlow()

    fun notifyChanged() {
        _events.tryEmit(System.currentTimeMillis())
    }
}
