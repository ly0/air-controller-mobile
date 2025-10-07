package com.youngfeng.android.assistant.server.webrtc

import android.content.Context
import android.media.projection.MediaProjection
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages WebRTC sessions
 * Coordinates between signaling connections and WebRTC peers
 */
object WebRTCSessionManager {
    private var context: Context? = null
    private var mediaProjection: MediaProjection? = null
    private var screenWidth: Int = 1080
    private var screenHeight: Int = 1920

    private val activePeers = ConcurrentHashMap<String, ServerWebRTCPeer>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val gson = Gson()

    fun initialize(context: Context, mediaProjection: MediaProjection, width: Int, height: Int) {
        this.context = context
        this.mediaProjection = mediaProjection
        this.screenWidth = width
        this.screenHeight = height
        Timber.d("WebRTCSessionManager: Initialized ($width x $height)")
    }

    /**
     * Called when a web client connects to signaling
     * Creates a WebRTC peer and sends offer
     */
    fun onSignalingConnected(sessionId: String, signalingUrl: String) {
        val ctx = context
        val projection = mediaProjection

        if (ctx == null || projection == null) {
            Timber.e("WebRTCSessionManager: Not initialized")
            return
        }

        scope.launch {
            try {
                Timber.d("WebRTCSessionManager: Creating peer for session $sessionId")

                // Create and initialize peer
                val peer = ServerWebRTCPeer(
                    context = ctx,
                    mediaProjection = projection,
                    sessionId = sessionId,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight
                )

                peer.initialize()
                activePeers[sessionId] = peer

                // Register signaling callback to receive messages from web
                SignalingManager.registerAndroidCallback(
                    sessionId,
                    object : SignalingManager.SignalingCallback {
                        override suspend fun sendMessage(message: String) {
                            handleSignalingMessage(sessionId, message)
                        }
                    }
                )

                // Create and send offer
                val offerSdp = peer.createOffer()
                if (offerSdp != null) {
                    val offerMessage = SignalingMessage.Offer(sessionId, offerSdp)
                    SignalingManager.forwardToWeb(sessionId, gson.toJson(offerMessage))
                    Timber.d("WebRTCSessionManager: Offer sent to session $sessionId")
                } else {
                    Timber.e("WebRTCSessionManager: Failed to create offer")
                    cleanup(sessionId)
                }
            } catch (e: Exception) {
                Timber.e(e, "WebRTCSessionManager: Failed to create peer")
                cleanup(sessionId)
            }
        }
    }

    private suspend fun handleSignalingMessage(sessionId: String, messageJson: String) {
        try {
            val message = SignalingMessage.fromJson(messageJson)
            val peer = activePeers[sessionId]

            if (peer == null) {
                Timber.w("WebRTCSessionManager: No peer for session $sessionId")
                return
            }

            when (message) {
                is SignalingMessage.Answer -> {
                    Timber.d("WebRTCSessionManager: Received answer")
                    peer.handleAnswer(message.sdp)
                }
                is SignalingMessage.IceCandidate -> {
                    Timber.d("WebRTCSessionManager: Received ICE candidate")
                    peer.handleIceCandidate(message.candidate, message.sdpMid, message.sdpMLineIndex)
                }
                else -> {
                    Timber.d("WebRTCSessionManager: Ignoring message type: ${message.type}")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "WebRTCSessionManager: Error handling signaling message")
        }
    }

    /**
     * Called when signaling connection is closed
     */
    fun onSignalingDisconnected(sessionId: String) {
        cleanup(sessionId)
    }

    private fun cleanup(sessionId: String) {
        val peer = activePeers.remove(sessionId)
        peer?.cleanup()
        SignalingManager.unregisterAndroidCallback(sessionId)
        Timber.d("WebRTCSessionManager: Cleaned up session $sessionId")
    }

    /**
     * Push a captured frame to all active WebRTC peers
     * Called by ScreenCaptureService for each captured frame
     */
    fun pushFrame(bitmap: android.graphics.Bitmap) {
        val timestampNs = System.nanoTime()
        activePeers.values.forEach { peer ->
            peer.pushFrame(bitmap, timestampNs)
        }
    }

    fun getActivePeerCount(): Int = activePeers.size

    fun cleanup() {
        activePeers.keys.toList().forEach { cleanup(it) }
        activePeers.clear()
        context = null
        mediaProjection = null
        Timber.d("WebRTCSessionManager: Cleaned up all sessions")
    }
}
