package com.youngfeng.android.assistant.server.webrtc

import com.google.gson.Gson
import kotlinx.coroutines.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import timber.log.Timber
import java.net.URI

/**
 * WebRTC signaling client for SDP and ICE exchange
 */
class SignalingClient(
    private val sessionId: String,
    private val signalingUrl: String,
    private val listener: SignalingListener
) {
    private var webSocketClient: WebSocketClient? = null
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    interface SignalingListener {
        fun onConnected()
        fun onDisconnected()
        fun onOffer(sdp: String)
        fun onAnswer(sdp: String)
        fun onIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int)
        fun onError(error: String)
    }

    fun connect() {
        try {
            val uri = URI(signalingUrl)
            Timber.d("SignalingClient: Connecting to $signalingUrl")

            webSocketClient = object : WebSocketClient(uri) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    Timber.d("SignalingClient: Connected")
                    scope.launch {
                        listener.onConnected()
                        // Send ready message
                        send(SignalingMessage.Ready(sessionId))
                    }
                }

                override fun onMessage(message: String?) {
                    message?.let {
                        scope.launch {
                            handleMessage(it)
                        }
                    }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Timber.d("SignalingClient: Disconnected - code: $code, reason: $reason")
                    scope.launch {
                        listener.onDisconnected()
                    }
                }

                override fun onError(ex: Exception?) {
                    Timber.e(ex, "SignalingClient: Error")
                    scope.launch {
                        listener.onError(ex?.message ?: "Unknown error")
                    }
                }
            }

            webSocketClient?.connect()
        } catch (e: Exception) {
            Timber.e(e, "Failed to connect to signaling server")
            listener.onError(e.message ?: "Connection failed")
        }
    }

    fun disconnect() {
        try {
            // Send bye message
            send(SignalingMessage.Bye(sessionId))

            webSocketClient?.close()
            webSocketClient = null
            scope.cancel()

            Timber.d("SignalingClient: Disconnected")
        } catch (e: Exception) {
            Timber.e(e, "Error disconnecting signaling client")
        }
    }

    fun send(message: SignalingMessage) {
        try {
            val json = gson.toJson(message)
            webSocketClient?.send(json)
            Timber.d("SignalingClient: Sent ${message.type}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to send signaling message")
        }
    }

    private fun handleMessage(json: String) {
        try {
            Timber.d("SignalingClient: Received message: $json")

            val message = SignalingMessage.fromJson(json)

            when (message) {
                is SignalingMessage.Offer -> {
                    listener.onOffer(message.sdp)
                }
                is SignalingMessage.Answer -> {
                    listener.onAnswer(message.sdp)
                }
                is SignalingMessage.IceCandidate -> {
                    listener.onIceCandidate(
                        message.candidate,
                        message.sdpMid,
                        message.sdpMLineIndex
                    )
                }
                is SignalingMessage.Error -> {
                    listener.onError(message.error)
                }
                is SignalingMessage.Ready -> {
                    Timber.d("SignalingClient: Peer ready")
                }
                is SignalingMessage.Bye -> {
                    Timber.d("SignalingClient: Peer disconnected - ${message.reason}")
                    listener.onDisconnected()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error handling signaling message")
            listener.onError("Failed to parse message: ${e.message}")
        }
    }

    fun isConnected(): Boolean {
        return webSocketClient?.isOpen == true
    }
}
