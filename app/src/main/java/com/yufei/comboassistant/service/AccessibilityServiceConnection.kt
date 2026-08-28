package com.yufei.comboassistant.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class AccessibilityServiceConnectionTracker {
    private val mutableConnected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = mutableConnected.asStateFlow()

    fun markConnected() {
        mutableConnected.value = true
    }

    fun markDisconnected() {
        mutableConnected.value = false
    }
}

internal val comboAccessibilityServiceConnection = AccessibilityServiceConnectionTracker()
