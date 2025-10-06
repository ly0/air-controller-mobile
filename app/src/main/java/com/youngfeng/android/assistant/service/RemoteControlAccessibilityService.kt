package com.youngfeng.android.assistant.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import timber.log.Timber

class RemoteControlAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_ACCESSIBILITY_CONNECTED = "com.youngfeng.android.assistant.ACCESSIBILITY_CONNECTED"
        const val ACTION_ACCESSIBILITY_DISCONNECTED = "com.youngfeng.android.assistant.ACCESSIBILITY_DISCONNECTED"

        private var instance: RemoteControlAccessibilityService? = null

        fun getInstance(): RemoteControlAccessibilityService? = instance

        fun isServiceEnabled(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Timber.d("RemoteControlAccessibilityService connected")

        // Notify ScreenCaptureService if running
        val intent = Intent(ACTION_ACCESSIBILITY_CONNECTED)
        sendBroadcast(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to handle accessibility events
        // This service is only used for gesture dispatching
    }

    override fun onInterrupt() {
        Timber.d("RemoteControlAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Timber.d("RemoteControlAccessibilityService destroyed")

        // Notify ScreenCaptureService
        val intent = Intent(ACTION_ACCESSIBILITY_DISCONNECTED)
        sendBroadcast(intent)
    }
}
