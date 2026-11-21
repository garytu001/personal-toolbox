package com.local.personaltoolbox.modules.aov

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

internal data class AovRunRequest(val date: String, val cleanupOnly: Boolean = false, val key: String = UUID.randomUUID().toString())

internal object AovRunRequests {
    private val mutable = MutableStateFlow<AovRunRequest?>(null)
    val requests = mutable.asStateFlow()
    val status = MutableStateFlow("")
    var ready = false
    private var started: String? = null
    fun begin(date: String, cleanupOnly: Boolean = false): Boolean {
        if (!ready || mutable.value != null) return false
        status.value = "執行中"
        started = null
        mutable.value = AovRunRequest(date, cleanupOnly)
        return true
    }
    fun markStarted(request: AovRunRequest) { if (mutable.value == request) started = request.key }
    fun hasStarted(request: AovRunRequest) = started == request.key
    fun finish(request: AovRunRequest, message: String) {
        if (mutable.value == request) { status.value = message; mutable.value = null }
    }
}
