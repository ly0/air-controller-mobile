package com.youngfeng.android.assistant.server.webrtc

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages WebRTC signaling sessions
 * Routes SDP and ICE messages between Android and Web clients
 */
object SignalingManager {
    // Map of sessionId to WebSocket sessions (web clients)
    private val webSessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()

    // Map of sessionId to Android callback
    private val androidCallbacks = ConcurrentHashMap<String, SignalingCallback>()

    interface SignalingCallback {
        suspend fun sendMessage(message: String)
    }

    /**
     * Register a web client session
     */
    fun registerWebSession(sessionId: String, session: DefaultWebSocketServerSession) {
        webSessions[sessionId] = session
        Timber.d("SignalingManager: Registered web session $sessionId")
    }

    /**
     * Unregister a web client session
     */
    fun unregisterWebSession(sessionId: String) {
        webSessions.remove(sessionId)
        Timber.d("SignalingManager: Unregistered web session $sessionId")
    }

    /**
     * Register an Android signaling callback
     */
    fun registerAndroidCallback(sessionId: String, callback: SignalingCallback) {
        androidCallbacks[sessionId] = callback
        Timber.d("SignalingManager: Registered Android callback $sessionId")
    }

    /**
     * Unregister an Android signaling callback
     */
    fun unregisterAndroidCallback(sessionId: String) {
        androidCallbacks.remove(sessionId)
        Timber.d("SignalingManager: Unregistered Android callback $sessionId")
    }

    /**
     * Forward message from web client to Android
     */
    suspend fun forwardToAndroid(sessionId: String, message: String) {
        val callback = androidCallbacks[sessionId]
        if (callback != null) {
            callback.sendMessage(message)
            Timber.d("SignalingManager: Forwarded message to Android for session $sessionId")
        } else {
            Timber.w("SignalingManager: No Android callback for session $sessionId")
        }
    }

    /**
     * Forward message from Android to web client
     */
    suspend fun forwardToWeb(sessionId: String, message: String) {
        val session = webSessions[sessionId]
        if (session != null) {
            session.send(Frame.Text(message))
            Timber.d("SignalingManager: Forwarded message to Web for session $sessionId")
        } else {
            Timber.w("SignalingManager: No web session for $sessionId")
        }
    }

    /**
     * Get active session count
     */
    fun getSessionCount(): Int = webSessions.size

    /**
     * Clean up all sessions
     */
    fun cleanup() {
        webSessions.clear()
        androidCallbacks.clear()
        Timber.d("SignalingManager: Cleaned up all sessions")
    }
}
