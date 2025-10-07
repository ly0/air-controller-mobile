package com.youngfeng.android.assistant.server.webrtc

import android.content.Context
import android.media.projection.MediaProjection
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.*
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * Server-side WebRTC peer connection
 * Acts as the offerer and sends video/audio streams to web client
 */
class ServerWebRTCPeer(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val sessionId: String,
    private val screenWidth: Int,
    private val screenHeight: Int
) {
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var videoCapturer: ManualVideoCapturer? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val gson = Gson()

    // EGL context for video encoding
    private var eglBase: EglBase? = null

    companion object {
        private const val VIDEO_TRACK_ID = "video_track"
        private const val STREAM_ID = "screen_stream"
        private const val VIDEO_FPS = 60
    }

    fun initialize() {
        try {
            Timber.d("ServerWebRTCPeer: Initializing for session $sessionId")

            // Initialize EGL context
            eglBase = EglBase.create()

            // Initialize PeerConnectionFactory
            val options = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(
                    DefaultVideoEncoderFactory(
                        eglBase!!.eglBaseContext,
                        true, // Enable hardware acceleration
                        true // Enable H264 high profile
                    )
                )
                .setVideoDecoderFactory(
                    DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)
                )
                .createPeerConnectionFactory()

            Timber.d("ServerWebRTCPeer: PeerConnectionFactory created")
        } catch (e: Exception) {
            Timber.e(e, "ServerWebRTCPeer: Failed to initialize")
        }
    }

    suspend fun createOffer(): String? {
        return try {
            Timber.d("ServerWebRTCPeer: Creating offer")

            // Create PeerConnection
            createPeerConnection()

            // Add media tracks
            addVideoTrack()

            // Create offer
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }

            val offer = suspendCancellableCoroutine<SessionDescription> { continuation ->
                peerConnection?.createOffer(
                    object : SdpObserver {
                        override fun onCreateSuccess(sdp: SessionDescription?) {
                            sdp?.let {
                                continuation.resume(it) {
                                    Timber.d("Offer creation cancelled")
                                }
                            } ?: run {
                                continuation.resumeWith(Result.failure(Exception("SDP is null")))
                            }
                        }

                        override fun onCreateFailure(error: String?) {
                            continuation.resumeWith(Result.failure(Exception(error ?: "Unknown error")))
                        }

                        override fun onSetSuccess() {}
                        override fun onSetFailure(error: String?) {}
                    },
                    constraints
                )
            }

            // Set local description
            suspendCancellableCoroutine<Unit> { continuation ->
                peerConnection?.setLocalDescription(
                    object : SdpObserver {
                        override fun onSetSuccess() {
                            continuation.resume(Unit) {
                                Timber.d("Set local description cancelled")
                            }
                        }

                        override fun onSetFailure(error: String?) {
                            continuation.resumeWith(Result.failure(Exception(error ?: "Unknown error")))
                        }

                        override fun onCreateSuccess(sdp: SessionDescription?) {}
                        override fun onCreateFailure(error: String?) {}
                    },
                    offer
                )
            }

            Timber.d("ServerWebRTCPeer: Offer created successfully")
            offer.description
        } catch (e: Exception) {
            Timber.e(e, "ServerWebRTCPeer: Failed to create offer")
            null
        }
    }

    suspend fun handleAnswer(answerSdp: String) {
        try {
            Timber.d("ServerWebRTCPeer: Handling answer")

            val answer = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)

            suspendCancellableCoroutine<Unit> { continuation ->
                peerConnection?.setRemoteDescription(
                    object : SdpObserver {
                        override fun onSetSuccess() {
                            Timber.d("ServerWebRTCPeer: Remote description set successfully")
                            continuation.resume(Unit) {
                                Timber.d("Set remote description cancelled")
                            }
                        }

                        override fun onSetFailure(error: String?) {
                            Timber.e("ServerWebRTCPeer: Failed to set remote description: $error")
                            continuation.resumeWith(Result.failure(Exception(error ?: "Unknown error")))
                        }

                        override fun onCreateSuccess(sdp: SessionDescription?) {}
                        override fun onCreateFailure(error: String?) {}
                    },
                    answer
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "ServerWebRTCPeer: Failed to handle answer")
        }
    }

    fun handleIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        try {
            val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex ?: 0, candidate)
            peerConnection?.addIceCandidate(iceCandidate)
            Timber.d("ServerWebRTCPeer: ICE candidate added")
        } catch (e: Exception) {
            Timber.e(e, "ServerWebRTCPeer: Failed to add ICE candidate")
        }
    }

    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    Timber.d("ServerWebRTCPeer: New ICE candidate")
                    // Send to web client via SignalingManager
                    scope.launch {
                        val message = SignalingMessage.IceCandidate(
                            sessionId = sessionId,
                            candidate = it.sdp,
                            sdpMid = it.sdpMid,
                            sdpMLineIndex = it.sdpMLineIndex
                        )
                        SignalingManager.forwardToWeb(sessionId, gson.toJson(message))
                    }
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                Timber.d("ServerWebRTCPeer: Connection state: $newState")
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                Timber.d("ServerWebRTCPeer: ICE connection state: $newState")
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                Timber.d("ServerWebRTCPeer: ICE receiving: $receiving")
            }

            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
                Timber.d("ServerWebRTCPeer: ICE gathering state: $newState")
            }

            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dataChannel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, observer)
        Timber.d("ServerWebRTCPeer: PeerConnection created")
    }

    private fun addVideoTrack() {
        try {
            Timber.d("ServerWebRTCPeer: Adding video track")

            // Create manual video capturer
            videoCapturer = ManualVideoCapturer()

            // Create video source
            videoSource = peerConnectionFactory?.createVideoSource(videoCapturer!!.isScreencast)

            // Initialize capturer
            val surfaceTextureHelper = SurfaceTextureHelper.create(
                "CaptureThread",
                eglBase!!.eglBaseContext
            )

            videoCapturer?.initialize(
                surfaceTextureHelper,
                context,
                videoSource?.capturerObserver
            )

            // Start capturing
            videoCapturer?.startCapture(screenWidth, screenHeight, VIDEO_FPS)

            // Create video track
            videoTrack = peerConnectionFactory?.createVideoTrack(VIDEO_TRACK_ID, videoSource)

            // Add track to peer connection
            videoTrack?.let {
                peerConnection?.addTrack(it, listOf(STREAM_ID))
                Timber.d("ServerWebRTCPeer: Video track added")
            }
        } catch (e: Exception) {
            Timber.e(e, "ServerWebRTCPeer: Failed to add video track")
        }
    }

    /**
     * Push a frame from ImageReader to WebRTC
     * Called by ScreenCaptureService
     */
    fun pushFrame(bitmap: android.graphics.Bitmap, timestampNs: Long) {
        videoCapturer?.pushFrame(bitmap, timestampNs)
    }

    fun cleanup() {
        Timber.d("ServerWebRTCPeer: Cleaning up")

        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoCapturer = null

        videoTrack?.dispose()
        videoTrack = null

        videoSource?.dispose()
        videoSource = null

        peerConnection?.dispose()
        peerConnection = null

        peerConnectionFactory?.dispose()
        peerConnectionFactory = null

        eglBase?.release()
        eglBase = null
    }
}
