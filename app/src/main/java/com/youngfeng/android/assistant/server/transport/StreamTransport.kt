package com.youngfeng.android.assistant.server.transport

/**
 * Stream transport abstraction layer
 * Supports both WebSocket and WebRTC transports
 */
interface StreamTransport {
    /**
     * Transport type identifier
     */
    enum class Type {
        WEBSOCKET,
        WEBRTC
    }

    /**
     * Connection state
     */
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        FAILED
    }

    /**
     * Get transport type
     */
    fun getType(): Type

    /**
     * Start the transport
     */
    suspend fun start()

    /**
     * Stop the transport
     */
    fun stop()

    /**
     * Send video frame data
     * @param frameData JPEG encoded frame data for WebSocket, or raw frame for WebRTC
     */
    suspend fun sendVideoFrame(frameData: ByteArray)

    /**
     * Send audio data
     * @param audioData PCM audio data
     */
    suspend fun sendAudioData(audioData: ByteArray)

    /**
     * Get current connection state
     */
    fun getConnectionState(): ConnectionState

    /**
     * Get number of active connections
     */
    fun getConnectionCount(): Int

    /**
     * Check if any clients are connected
     */
    fun hasConnections(): Boolean

    /**
     * Set connection state listener
     */
    fun setStateListener(listener: (ConnectionState) -> Unit)
}

/**
 * Transport capabilities
 */
data class TransportCapabilities(
    val webrtcSupported: Boolean,
    val websocketSupported: Boolean,
    val stunServers: List<String> = listOf("stun:stun.l.google.com:19302"),
    val turnServers: List<TurnServer> = emptyList(),
    val maxBitrate: Int = 5_000_000, // 5 Mbps
    val maxFramerate: Int = 60
)

data class TurnServer(
    val url: String,
    val username: String = "",
    val credential: String = ""
)

/**
 * Transport negotiation result
 */
data class TransportNegotiation(
    val transport: StreamTransport.Type,
    val signalingUrl: String? = null, // For WebRTC
    val websocketUrl: String? = null, // For WebSocket
    val iceServers: List<IceServer> = emptyList()
)

data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null
)
