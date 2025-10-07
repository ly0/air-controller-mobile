package com.youngfeng.android.assistant.server.websocket

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import timber.log.Timber
import java.net.InetSocketAddress
import java.nio.ByteBuffer

class ScreenStreamWebSocketServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {

    private val gson = Gson()
    private var inputEventListener: InputEventListener? = null

    interface InputEventListener {
        fun onTouchEvent(x: Float, y: Float, action: String)
        fun onKeyEvent(keyCode: Int, action: String)
        fun onSwipeEvent(startX: Float, startY: Float, endX: Float, endY: Float)
        fun onBackPressed()
        fun onHomePressed()
        fun onRecentAppsPressed()
    }

    fun setInputEventListener(listener: InputEventListener) {
        inputEventListener = listener
    }

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        Timber.d("WebSocket connection opened: ${conn?.remoteSocketAddress}")
        android.util.Log.d("ScreenStreamWS", "★★★ Connection opened from: ${conn?.remoteSocketAddress}")

        // Send initial connection success message
        conn?.let {
            val response = JsonObject().apply {
                addProperty("type", "connected")
                addProperty("message", "Screen sharing connected")
            }
            it.send(gson.toJson(response))
            android.util.Log.d("ScreenStreamWS", "★★★ Sent connection success message")
        }
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        Timber.d("WebSocket connection closed: ${conn?.remoteSocketAddress}, reason: $reason")
    }

    override fun onMessage(conn: WebSocket?, message: String?) {
        try {
            message?.let {
                Timber.d("WebSocket received message: $it")
                android.util.Log.d("ScreenStreamWS", "★★★ Received message: $it")
                val jsonObject = gson.fromJson(it, JsonObject::class.java)
                val eventType = jsonObject.get("type")?.asString

                when (eventType) {
                    "touch" -> {
                        android.util.Log.d("ScreenStreamWS", "★★★ Processing touch event")
                        handleTouchEvent(jsonObject)
                    }
                    "key" -> handleKeyEvent(jsonObject)
                    "swipe" -> handleSwipeEvent(jsonObject)
                    "navigation" -> handleNavigationEvent(jsonObject)
                    "ping" -> handlePing(conn)
                    "quality" -> handleQualityUpdate(jsonObject)
                    "performance" -> handlePerformanceReport(jsonObject)
                    else -> Timber.w("Unknown event type: $eventType")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error processing WebSocket message")
            android.util.Log.e("ScreenStreamWS", "★★★ Error: ${e.message}", e)
        }
    }

    override fun onMessage(conn: WebSocket?, message: ByteBuffer?) {
        // Binary messages not used for input events
        Timber.d("Received binary message of size: ${message?.remaining()}")
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        Timber.e(ex, "WebSocket error occurred")
    }

    override fun onStart() {
        Timber.d("WebSocket server started on port: $port")
        connectionLostTimeout = 30
    }

    fun broadcastBytes(data: ByteArray) {
        try {
            val connections = connections.toList() // Create a copy to avoid concurrent modification
            if (connections.isNotEmpty()) {
                val buffer = ByteBuffer.wrap(data)
                connections.forEach { connection ->
                    if (connection.isOpen) {
                        try {
                            connection.send(buffer)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to send data to connection: ${connection.remoteSocketAddress}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error broadcasting data")
        }
    }

    fun broadcastJson(json: String) {
        try {
            val connections = connections.toList()
            connections.forEach { connection ->
                if (connection.isOpen) {
                    try {
                        connection.send(json)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to send JSON to connection: ${connection.remoteSocketAddress}")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error broadcasting JSON")
        }
    }

    private fun handleTouchEvent(json: JsonObject) {
        try {
            val x = json.get("x")?.asFloat ?: return
            val y = json.get("y")?.asFloat ?: return
            val action = json.get("action")?.asString ?: "tap"

            Timber.d("Received touch event: x=$x, y=$y, action=$action, listener=${inputEventListener != null}")

            if (inputEventListener == null) {
                Timber.e("InputEventListener is null! Touch event will not be processed.")
            } else {
                inputEventListener?.onTouchEvent(x, y, action)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error handling touch event")
        }
    }

    private fun handleKeyEvent(json: JsonObject) {
        try {
            val keyCode = json.get("keyCode")?.asInt ?: return
            val action = json.get("action")?.asString ?: "press"

            inputEventListener?.onKeyEvent(keyCode, action)
        } catch (e: Exception) {
            Timber.e(e, "Error handling key event")
        }
    }

    private fun handleSwipeEvent(json: JsonObject) {
        try {
            val startX = json.get("startX")?.asFloat ?: return
            val startY = json.get("startY")?.asFloat ?: return
            val endX = json.get("endX")?.asFloat ?: return
            val endY = json.get("endY")?.asFloat ?: return

            inputEventListener?.onSwipeEvent(startX, startY, endX, endY)
        } catch (e: Exception) {
            Timber.e(e, "Error handling swipe event")
        }
    }

    private fun handleNavigationEvent(json: JsonObject) {
        try {
            val button = json.get("button")?.asString ?: return

            when (button) {
                "back" -> inputEventListener?.onBackPressed()
                "home" -> inputEventListener?.onHomePressed()
                "recent" -> inputEventListener?.onRecentAppsPressed()
                else -> Timber.w("Unknown navigation button: $button")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error handling navigation event")
        }
    }

    private fun handlePing(conn: WebSocket?) {
        conn?.let {
            val response = JsonObject().apply {
                addProperty("type", "pong")
                addProperty("timestamp", System.currentTimeMillis())
            }
            it.send(gson.toJson(response))
        }
    }

    fun sendScreenInfo(width: Int, height: Int, density: Float) {
        val info = JsonObject().apply {
            addProperty("type", "screen_info")
            addProperty("width", width)
            addProperty("height", height)
            addProperty("density", density)
        }
        broadcastJson(gson.toJson(info))
    }

    fun sendError(errorMessage: String) {
        val error = JsonObject().apply {
            addProperty("type", "error")
            addProperty("message", errorMessage)
        }
        broadcastJson(gson.toJson(error))
    }

    fun getConnectionCount(): Int = connections.size

    fun isAnyClientConnected(): Boolean = connections.isNotEmpty()

    fun closeAllConnections() {
        connections.forEach { connection ->
            connection.close(1000, "Server shutting down")
        }
    }

    private fun handleQualityUpdate(json: JsonObject) {
        try {
            val quality = json.get("value")?.asInt ?: return
            Timber.d("Received quality update request: $quality")

            // Update the quality in ScreenCaptureService
            val captureService = com.youngfeng.android.assistant.service.ScreenCaptureService::class.java
            val method = captureService.getDeclaredMethod("updateQualitySetting", Int::class.java)
            // Note: This would need proper service binding or broadcast to work
            // For now, log the request
            Timber.d("Quality update requested: $quality (implementation needed)")
        } catch (e: Exception) {
            Timber.e(e, "Error handling quality update")
        }
    }

    private fun handlePerformanceReport(json: JsonObject) {
        try {
            val fps = json.get("fps")?.asFloat ?: 0f
            val dropRate = json.get("dropRate")?.asFloat ?: 0f
            val latency = json.get("latency")?.asInt ?: 0

            Timber.d("Client performance report - FPS: $fps, Drop rate: $dropRate, Latency: $latency ms")

            // Adjust server settings based on client performance
            if (dropRate > 0.2f || fps < 15) {
                // Client is struggling, suggest reducing quality
                val suggestion = JsonObject().apply {
                    addProperty("type", "performance_suggestion")
                    addProperty("action", "reduce_quality")
                    addProperty("reason", "high_drop_rate")
                }
                broadcastJson(gson.toJson(suggestion))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error handling performance report")
        }
    }
}