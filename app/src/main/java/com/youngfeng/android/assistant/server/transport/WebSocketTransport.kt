package com.youngfeng.android.assistant.server.transport

import com.youngfeng.android.assistant.server.websocket.ScreenStreamManager
import timber.log.Timber

/**
 * WebSocket-based transport implementation
 * Wraps existing ScreenStreamManager for backward compatibility
 */
class WebSocketTransport : StreamTransport {
    private var state: StreamTransport.ConnectionState = StreamTransport.ConnectionState.DISCONNECTED
    private var stateListener: ((StreamTransport.ConnectionState) -> Unit)? = null

    override fun getType(): StreamTransport.Type = StreamTransport.Type.WEBSOCKET

    override suspend fun start() {
        Timber.d("WebSocketTransport: Starting")
        updateState(StreamTransport.ConnectionState.CONNECTING)
        // WebSocket is started via Ktor server, so we just update state
        updateState(StreamTransport.ConnectionState.CONNECTED)
    }

    override fun stop() {
        Timber.d("WebSocketTransport: Stopping")
        ScreenStreamManager.cleanup()
        updateState(StreamTransport.ConnectionState.DISCONNECTED)
    }

    override suspend fun sendVideoFrame(frameData: ByteArray) {
        // Delegate to ScreenStreamManager
        ScreenStreamManager.broadcastFrame(frameData)
    }

    override suspend fun sendAudioData(audioData: ByteArray) {
        // Delegate to ScreenStreamManager
        ScreenStreamManager.broadcastAudio(audioData)
    }

    override fun getConnectionState(): StreamTransport.ConnectionState = state

    override fun getConnectionCount(): Int = ScreenStreamManager.getConnectionCount()

    override fun hasConnections(): Boolean = ScreenStreamManager.hasConnections()

    override fun setStateListener(listener: (StreamTransport.ConnectionState) -> Unit) {
        this.stateListener = listener
    }

    private fun updateState(newState: StreamTransport.ConnectionState) {
        state = newState
        stateListener?.invoke(newState)
    }
}
