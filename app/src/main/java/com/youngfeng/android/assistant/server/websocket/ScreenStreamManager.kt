package com.youngfeng.android.assistant.server.websocket

import android.content.Context
import com.youngfeng.android.assistant.controller.RemoteInputController
import io.ktor.websocket.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Singleton manager for screen streaming WebSocket connections
 * Handles frame broadcasting and input event distribution
 */
object ScreenStreamManager {
    private val connections = CopyOnWriteArraySet<WebSocketSession>()
    private val _frameFlow = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val frameFlow = _frameFlow.asSharedFlow()

    private var remoteInputController: RemoteInputController? = null

    /**
     * Initialize input controller with screen dimensions
     */
    fun initInputController(context: Context, width: Int, height: Int, scaleFactor: Int = 2) {
        remoteInputController?.onDestroy()
        remoteInputController = RemoteInputController(context).apply {
            setScreenDimensions(width, height, scaleFactor)
            updateAccessibilityService()
        }
        Timber.d("ScreenStreamManager: InputController initialized ${width}x${height}, scale=$scaleFactor")
        android.util.Log.d("ScreenStreamMgr", "★★★ InputController initialized ${width}x${height}")
    }

    /**
     * Update accessibility service connection
     */
    fun updateAccessibilityService() {
        remoteInputController?.let {
            val service = com.youngfeng.android.assistant.service.RemoteControlAccessibilityService.getInstance()
            it.setAccessibilityService(service)
        }
    }

    /**
     * Register a new WebSocket connection
     */
    fun addConnection(session: WebSocketSession) {
        connections.add(session)
        Timber.d("ScreenStreamManager: Connection added, total=${connections.size}")
        android.util.Log.d("ScreenStreamMgr", "★★★ Connection added, total=${connections.size}")
    }

    /**
     * Unregister a WebSocket connection
     */
    fun removeConnection(session: WebSocketSession) {
        connections.remove(session)
        Timber.d("ScreenStreamManager: Connection removed, total=${connections.size}")
        android.util.Log.d("ScreenStreamMgr", "★★★ Connection removed, total=${connections.size}")
    }

    /**
     * Broadcast screen frame to all connected clients
     * Frame format: [0x00 | timestamp(8) | jpeg_data]
     */
    suspend fun broadcastFrame(frameData: ByteArray) {
        // Create packet with type header
        // Type: 0x00 for video frame
        val timestamp = System.currentTimeMillis()
        val packet = ByteArray(1 + 8 + frameData.size)

        // Type byte
        packet[0] = 0x00

        // Timestamp (8 bytes)
        for (i in 0..7) {
            packet[1 + i] = ((timestamp shr (56 - i * 8)) and 0xFF).toByte()
        }

        // Frame data
        System.arraycopy(frameData, 0, packet, 9, frameData.size)

        // Emit to flow for any listeners
        _frameFlow.emit(packet)

        // Also send directly to all connections
        if (connections.isEmpty()) return

        val frame = Frame.Binary(true, packet)
        val iterator = connections.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next()
            try {
                if (!session.outgoing.isClosedForSend) {
                    session.send(frame)
                } else {
                    iterator.remove()
                    Timber.w("Removed inactive connection")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to send frame to connection")
                iterator.remove()
            }
        }
    }

    /**
     * Broadcast audio data to all connected clients
     * Audio packet format: [0x01 | timestamp(8) | size(4) | pcm_data]
     */
    suspend fun broadcastAudio(audioData: ByteArray) {
        // Audio data already contains the header from ScreenCaptureService
        if (connections.isEmpty()) return

        val frame = Frame.Binary(true, audioData)
        val iterator = connections.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next()
            try {
                if (!session.outgoing.isClosedForSend) {
                    session.send(frame)
                } else {
                    iterator.remove()
                    Timber.w("Removed inactive connection during audio broadcast")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to send audio to connection")
                iterator.remove()
            }
        }
    }

    /**
     * Get number of active connections
     */
    fun getConnectionCount(): Int = connections.size

    /**
     * Check if any clients are connected
     */
    fun hasConnections(): Boolean = connections.isNotEmpty()

    /**
     * Get the input controller instance
     */
    fun getInputController(): RemoteInputController? = remoteInputController

    /**
     * Update quality settings based on client feedback
     */
    fun updateQualitySettings(quality: Int) {
        // This would communicate with ScreenCaptureService
        // For now, just log the request
        Timber.d("Quality update requested from client: $quality")
    }

    /**
     * Get current stream statistics
     */
    fun getStreamStats(): StreamStats {
        return StreamStats(
            connectionCount = connections.size,
            isActive = connections.isNotEmpty()
        )
    }

    data class StreamStats(
        val connectionCount: Int,
        val isActive: Boolean
    )

    /**
     * Clean up all resources
     */
    fun cleanup() {
        connections.clear()
        remoteInputController?.onDestroy()
        remoteInputController = null
        Timber.d("ScreenStreamManager: Cleaned up")
        android.util.Log.d("ScreenStreamMgr", "★★★ Cleaned up")
    }
}
