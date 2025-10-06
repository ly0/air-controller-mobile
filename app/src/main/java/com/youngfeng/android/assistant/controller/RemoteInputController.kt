package com.youngfeng.android.assistant.controller

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.view.KeyEvent
import com.youngfeng.android.assistant.server.websocket.ScreenStreamWebSocketServer
import kotlinx.coroutines.*
import timber.log.Timber

class RemoteInputController(private val context: Context) : ScreenStreamWebSocketServer.InputEventListener {

    private val controllerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var accessibilityService: AccessibilityService? = null

    // Screen dimensions for coordinate conversion
    private var screenWidth: Int = 1080
    private var screenHeight: Int = 1920
    private var scaleFactor: Int = 2 // Should match the scale factor in ScreenCaptureService

    fun setScreenDimensions(width: Int, height: Int, scale: Int = 2) {
        screenWidth = width
        screenHeight = height
        scaleFactor = scale
    }

    fun setAccessibilityService(service: AccessibilityService?) {
        accessibilityService = service
        Timber.d("AccessibilityService ${if (service != null) "connected" else "disconnected"}")
    }

    fun updateAccessibilityService() {
        val service = com.youngfeng.android.assistant.service.RemoteControlAccessibilityService.getInstance()
        setAccessibilityService(service)
    }

    override fun onTouchEvent(x: Float, y: Float, action: String) {
        controllerScope.launch {
            try {
                // Convert relative coordinates (0-1) to absolute screen coordinates
                val absoluteX = (x * screenWidth).toInt()
                val absoluteY = (y * screenHeight).toInt()

                Timber.d("Touch event: x=$x, y=$y, action=$action -> absoluteX=$absoluteX, absoluteY=$absoluteY (screen: ${screenWidth}x${screenHeight})")

                when (action) {
                    "tap", "click" -> performTap(absoluteX, absoluteY)
                    "down" -> performTouchDown(absoluteX, absoluteY)
                    "up" -> performTouchUp(absoluteX, absoluteY)
                    "move" -> performTouchMove(absoluteX, absoluteY)
                    "longpress" -> performLongPress(absoluteX, absoluteY)
                    else -> Timber.w("Unknown touch action: $action")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error handling touch event")
            }
        }
    }

    override fun onKeyEvent(keyCode: Int, action: String) {
        controllerScope.launch {
            try {
                when (action) {
                    "press", "down" -> performKeyPress(keyCode)
                    "up" -> performKeyRelease(keyCode)
                    else -> Timber.w("Unknown key action: $action")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error handling key event")
            }
        }
    }

    override fun onSwipeEvent(startX: Float, startY: Float, endX: Float, endY: Float) {
        controllerScope.launch {
            try {
                // Convert relative coordinates to absolute screen coordinates
                val absStartX = (startX * screenWidth).toInt()
                val absStartY = (startY * screenHeight).toInt()
                val absEndX = (endX * screenWidth).toInt()
                val absEndY = (endY * screenHeight).toInt()

                Timber.d("Swipe event: ($startX, $startY) -> ($endX, $endY) = ($absStartX, $absStartY) -> ($absEndX, $absEndY)")

                performSwipe(absStartX, absStartY, absEndX, absEndY)
            } catch (e: Exception) {
                Timber.e(e, "Error handling swipe event")
            }
        }
    }

    override fun onBackPressed() {
        android.util.Log.d("RemoteInputCtrl", "★★★ onBackPressed called")
        controllerScope.launch {
            val service = accessibilityService as? com.youngfeng.android.assistant.service.RemoteControlAccessibilityService
            if (service != null) {
                val success = service.performBack()
                android.util.Log.d("RemoteInputCtrl", "★★★ performBack result: $success")
            } else {
                android.util.Log.w("RemoteInputCtrl", "★★★ Accessibility service not available for back action")
            }
        }
    }

    override fun onHomePressed() {
        android.util.Log.d("RemoteInputCtrl", "★★★ onHomePressed called")
        controllerScope.launch {
            val service = accessibilityService as? com.youngfeng.android.assistant.service.RemoteControlAccessibilityService
            if (service != null) {
                val success = service.performHome()
                android.util.Log.d("RemoteInputCtrl", "★★★ performHome result: $success")
            } else {
                android.util.Log.w("RemoteInputCtrl", "★★★ Accessibility service not available for home action")
            }
        }
    }

    override fun onRecentAppsPressed() {
        android.util.Log.d("RemoteInputCtrl", "★★★ onRecentAppsPressed called")
        controllerScope.launch {
            val service = accessibilityService as? com.youngfeng.android.assistant.service.RemoteControlAccessibilityService
            if (service != null) {
                val success = service.performRecents()
                android.util.Log.d("RemoteInputCtrl", "★★★ performRecents result: $success")
            } else {
                android.util.Log.w("RemoteInputCtrl", "★★★ Accessibility service not available for recents action")
            }
        }
    }

    private suspend fun performTap(x: Int, y: Int) {
        // Try accessibility service first
        if (performGestureTap(x, y)) {
            return
        }

        // Fallback to shell command
        executeShellCommand("input tap $x $y")
    }

    private suspend fun performLongPress(x: Int, y: Int) {
        // Try accessibility service first
        if (performGestureLongPress(x, y)) {
            return
        }

        // Fallback to shell command
        executeShellCommand("input swipe $x $y $x $y 1000")
    }

    private suspend fun performTouchDown(x: Int, y: Int) {
        // This requires more advanced input injection that's not easily available
        // We'll use tap as a fallback
        executeShellCommand("input tap $x $y")
    }

    private suspend fun performTouchUp(x: Int, y: Int) {
        // This requires more advanced input injection that's not easily available
        // We'll use tap as a fallback
        executeShellCommand("input tap $x $y")
    }

    private suspend fun performTouchMove(x: Int, y: Int) {
        // For continuous touch move, we would need to track the previous position
        // This is a simplified implementation
        executeShellCommand("input tap $x $y")
    }

    private suspend fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long = 300) {
        // Try accessibility service first
        if (performGestureSwipe(startX, startY, endX, endY, duration)) {
            return
        }

        // Fallback to shell command
        executeShellCommand("input swipe $startX $startY $endX $endY $duration")
    }

    private suspend fun performKeyPress(keyCode: Int) {
        executeShellCommand("input keyevent $keyCode")
    }

    private suspend fun performKeyRelease(keyCode: Int) {
        // Android shell commands don't distinguish between press and release for most keys
        // This is handled as part of the keyevent command
    }

    private fun performGestureTap(x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Timber.w("performGestureTap: API level too low (${Build.VERSION.SDK_INT})")
            return false
        }

        if (accessibilityService == null) {
            Timber.w("performGestureTap: AccessibilityService is null")
            return false
        }

        try {
            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
            }

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()

            val result = accessibilityService!!.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Timber.d("Gesture tap completed successfully at ($x, $y)")
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Timber.w("Gesture tap cancelled at ($x, $y)")
                    }
                },
                null
            )

            Timber.d("performGestureTap: dispatchGesture returned $result at ($x, $y)")
            return result
        } catch (e: Exception) {
            Timber.e(e, "Failed to perform gesture tap at ($x, $y)")
            return false
        }
    }

    private fun performGestureLongPress(x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val service = accessibilityService ?: return false

        try {
            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
            }

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 1000))
                .build()

            service.dispatchGesture(gesture, null, null)
            return true
        } catch (e: Exception) {
            Timber.e(e, "Failed to perform gesture long press")
            return false
        }
    }

    private fun performGestureSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Timber.w("performGestureSwipe: API level too low (${Build.VERSION.SDK_INT})")
            return false
        }

        if (accessibilityService == null) {
            Timber.w("performGestureSwipe: AccessibilityService is null")
            return false
        }

        try {
            val path = Path().apply {
                moveTo(startX.toFloat(), startY.toFloat())
                lineTo(endX.toFloat(), endY.toFloat())
            }

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()

            val result = accessibilityService!!.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Timber.d("Gesture swipe completed successfully from ($startX, $startY) to ($endX, $endY)")
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Timber.w("Gesture swipe cancelled from ($startX, $startY) to ($endX, $endY)")
                    }
                },
                null
            )

            Timber.d("performGestureSwipe: dispatchGesture returned $result")
            return result
        } catch (e: Exception) {
            Timber.e(e, "Failed to perform gesture swipe from ($startX, $startY) to ($endX, $endY)")
            return false
        }
    }

    private suspend fun executeShellCommand(command: String): Unit = withContext(Dispatchers.IO) {
        try {
            Timber.d("Executing shell command: $command")
            android.util.Log.d("RemoteInputCtrl", "★★★ Executing: $command")
            // Use sh -c to execute the command
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                val errorOutput = process.errorStream.bufferedReader().readText()
                Timber.w("Shell command failed with exit code $exitCode: $errorOutput")
                android.util.Log.w("RemoteInputCtrl", "★★★ Command failed: exit=$exitCode, error=$errorOutput")
            } else {
                Timber.d("Shell command executed successfully: $command")
                android.util.Log.d("RemoteInputCtrl", "★★★ Command success: $command")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to execute shell command: $command")
            android.util.Log.e("RemoteInputCtrl", "★★★ Command exception: $command", e)
        }
    }

    fun onDestroy() {
        controllerScope.cancel()
    }

    companion object {
        // Common Android key codes
        const val KEY_BACK = KeyEvent.KEYCODE_BACK
        const val KEY_HOME = KeyEvent.KEYCODE_HOME
        const val KEY_MENU = KeyEvent.KEYCODE_MENU
        const val KEY_VOLUME_UP = KeyEvent.KEYCODE_VOLUME_UP
        const val KEY_VOLUME_DOWN = KeyEvent.KEYCODE_VOLUME_DOWN
        const val KEY_POWER = KeyEvent.KEYCODE_POWER
    }
}
