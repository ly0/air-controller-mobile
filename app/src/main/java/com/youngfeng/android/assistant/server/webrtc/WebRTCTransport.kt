package com.youngfeng.android.assistant.server.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import com.youngfeng.android.assistant.server.transport.StreamTransport
import kotlinx.coroutines.*
import org.webrtc.*
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * WebRTC-based transport implementation
 * Provides high-performance, low-latency screen sharing with audio
 */
class WebRTCTransport(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val signalingUrl: String
) : StreamTransport {

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null
    private var videoCapturer: ScreenCapturerAndroid? = null

    private var signalingClient: SignalingClient? = null
    private val sessionId = java.util.UUID.randomUUID().toString()

    private var state: StreamTransport.ConnectionState = StreamTransport.ConnectionState.DISCONNECTED
    private var stateListener: ((StreamTransport.ConnectionState) -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // EGL context for hardware encoding
    private var eglBase: EglBase? = null

    // Screen dimensions
    private var screenWidth = 1080
    private var screenHeight = 1920

    companion object {
        private const val VIDEO_TRACK_ID = "video_track"
        private const val AUDIO_TRACK_ID = "audio_track"
        private const val STREAM_ID = "screen_stream"

        // Video encoding parameters
        private const val VIDEO_FPS = 60
        private const val VIDEO_RESOLUTION_WIDTH = 1080
        private const val VIDEO_RESOLUTION_HEIGHT = 1920
        private const val VIDEO_BITRATE_KBPS = 5000
    }

    override fun getType(): StreamTransport.Type = StreamTransport.Type.WEBRTC

    override suspend fun start() = withContext(Dispatchers.Main) {
        try {
            Timber.d("WebRTCTransport: Starting")
            updateState(StreamTransport.ConnectionState.CONNECTING)

            // Initialize WebRTC
            initializeWebRTC()

            // Create peer connection
            createPeerConnection()

            // Add media tracks
            addMediaTracks()

            // Connect to signaling server
            connectSignaling()

            Timber.d("WebRTCTransport: Started successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start WebRTCTransport")
            updateState(StreamTransport.ConnectionState.FAILED)
            throw e
        }
    }

    override fun stop() {
        Timber.d("WebRTCTransport: Stopping")

        // Disconnect signaling
        signalingClient?.disconnect()
        signalingClient = null

        // Stop video capturer
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null
        } catch (e: Exception) {
            Timber.e(e, "Error stopping video capturer")
        }

        // Close peer connection
        peerConnection?.close()
        peerConnection = null

        // Dispose tracks
        videoTrack?.dispose()
        videoTrack = null
        audioTrack?.dispose()
        audioTrack = null

        // Dispose factory
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null

        // Release EGL
        eglBase?.release()
        eglBase = null

        scope.cancel()

        updateState(StreamTransport.ConnectionState.DISCONNECTED)
        Timber.d("WebRTCTransport: Stopped")
    }

    override suspend fun sendVideoFrame(frameData: ByteArray) {
        // Not used in WebRTC - video is captured directly by VideoCapturer
    }

    override suspend fun sendAudioData(audioData: ByteArray) {
        // Not used in WebRTC - audio is captured directly by AudioTrack
    }

    override fun getConnectionState(): StreamTransport.ConnectionState = state

    override fun getConnectionCount(): Int {
        return if (peerConnection?.connectionState() == PeerConnection.PeerConnectionState.CONNECTED) 1 else 0
    }

    override fun hasConnections(): Boolean {
        return peerConnection?.connectionState() == PeerConnection.PeerConnectionState.CONNECTED
    }

    override fun setStateListener(listener: (StreamTransport.ConnectionState) -> Unit) {
        this.stateListener = listener
    }

    private fun initializeWebRTC() {
        Timber.d("WebRTCTransport: Initializing WebRTC")

        // Initialize PeerConnectionFactory
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()

        PeerConnectionFactory.initialize(options)

        // Create EGL context
        eglBase = EglBase.create()

        // Build PeerConnectionFactory
        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase!!.eglBaseContext,
            true, // enableIntelVp8Encoder
            true // enableH264HighProfile
        )

        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()

        Timber.d("WebRTCTransport: PeerConnectionFactory created")
    }

    private fun createPeerConnection() {
        Timber.d("WebRTCTransport: Creating PeerConnection")

        // ICE servers configuration
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
                    Timber.d("WebRTCTransport: New ICE candidate")
                    signalingClient?.send(
                        SignalingMessage.IceCandidate(
                            sessionId = sessionId,
                            candidate = it.sdp,
                            sdpMid = it.sdpMid,
                            sdpMLineIndex = it.sdpMLineIndex
                        )
                    )
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                Timber.d("WebRTCTransport: Connection state changed to $newState")
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        updateState(StreamTransport.ConnectionState.CONNECTED)
                    }
                    PeerConnection.PeerConnectionState.FAILED -> {
                        updateState(StreamTransport.ConnectionState.FAILED)
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED -> {
                        updateState(StreamTransport.ConnectionState.DISCONNECTED)
                    }
                    else -> {}
                }
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                Timber.d("WebRTCTransport: ICE connection state: $newState")
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                Timber.d("WebRTCTransport: ICE connection receiving: $receiving")
            }

            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
                Timber.d("WebRTCTransport: ICE gathering state: $newState")
            }

            override fun onAddStream(stream: MediaStream?) {
                Timber.d("WebRTCTransport: Stream added")
            }

            override fun onRemoveStream(stream: MediaStream?) {
                Timber.d("WebRTCTransport: Stream removed")
            }

            override fun onDataChannel(dataChannel: DataChannel?) {
                Timber.d("WebRTCTransport: Data channel received")
            }

            override fun onRenegotiationNeeded() {
                Timber.d("WebRTCTransport: Renegotiation needed")
            }

            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {
                Timber.d("WebRTCTransport: Signaling state: $newState")
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                Timber.d("WebRTCTransport: ICE candidates removed")
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                Timber.d("WebRTCTransport: Track added")
            }
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, observer)

        Timber.d("WebRTCTransport: PeerConnection created")
    }

    private fun addMediaTracks() {
        Timber.d("WebRTCTransport: Adding media tracks")

        // Create video track
        videoTrack = createVideoTrack()
        videoTrack?.let {
            peerConnection?.addTrack(it, listOf(STREAM_ID))
            Timber.d("WebRTCTransport: Video track added")
        }

        // Create audio track (if Android 10+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            audioTrack = createAudioTrack()
            audioTrack?.let {
                peerConnection?.addTrack(it, listOf(STREAM_ID))
                Timber.d("WebRTCTransport: Audio track added")
            }
        } else {
            Timber.w("WebRTCTransport: Audio capture not supported on Android < 10")
        }
    }

    private fun createVideoTrack(): VideoTrack? {
        try {
            // Create screen capturer
            videoCapturer = ScreenCapturerAndroid(
                Intent(), // Will be set by MediaProjection
                object : MediaProjection.Callback() {}
            )

            // Create video source
            val surfaceTextureHelper = SurfaceTextureHelper.create(
                "CaptureThread",
                eglBase!!.eglBaseContext
            )

            val videoSource = peerConnectionFactory?.createVideoSource(false)
            videoCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)

            // Start capture
            videoCapturer?.startCapture(VIDEO_RESOLUTION_WIDTH, VIDEO_RESOLUTION_HEIGHT, VIDEO_FPS)

            // Create video track
            return peerConnectionFactory?.createVideoTrack(VIDEO_TRACK_ID, videoSource)
        } catch (e: Exception) {
            Timber.e(e, "Failed to create video track")
            return null
        }
    }

    private fun createAudioTrack(): AudioTrack? {
        try {
            val audioConstraints = MediaConstraints()
            val audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
            return peerConnectionFactory?.createAudioTrack(AUDIO_TRACK_ID, audioSource)
        } catch (e: Exception) {
            Timber.e(e, "Failed to create audio track")
            return null
        }
    }

    private fun connectSignaling() {
        Timber.d("WebRTCTransport: Connecting to signaling server")

        val listener = object : SignalingClient.SignalingListener {
            override fun onConnected() {
                Timber.d("WebRTCTransport: Signaling connected")
                scope.launch {
                    createOffer()
                }
            }

            override fun onDisconnected() {
                Timber.d("WebRTCTransport: Signaling disconnected")
            }

            override fun onOffer(sdp: String) {
                Timber.d("WebRTCTransport: Received offer (unexpected for offerer)")
            }

            override fun onAnswer(sdp: String) {
                Timber.d("WebRTCTransport: Received answer")
                scope.launch {
                    handleAnswer(sdp)
                }
            }

            override fun onIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
                Timber.d("WebRTCTransport: Received ICE candidate")
                scope.launch {
                    handleIceCandidate(candidate, sdpMid, sdpMLineIndex)
                }
            }

            override fun onError(error: String) {
                Timber.e("WebRTCTransport: Signaling error: $error")
                updateState(StreamTransport.ConnectionState.FAILED)
            }
        }

        signalingClient = SignalingClient(sessionId, signalingUrl, listener)
        signalingClient?.connect()
    }

    private suspend fun createOffer() = withContext(Dispatchers.Main) {
        try {
            Timber.d("WebRTCTransport: Creating offer")

            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }

            val offer = suspendCancellableCoroutine<SessionDescription> { continuation ->
                peerConnection?.createOffer(
                    object : SdpObserver {
                        override fun onCreateSuccess(sdp: SessionDescription?) {
                            sdp?.let { continuation.resume(it, null) }
                                ?: continuation.resumeWithException(Exception("SDP is null"))
                        }

                        override fun onCreateFailure(error: String?) {
                            continuation.resumeWithException(Exception(error ?: "Unknown error"))
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
                            continuation.resume(Unit, null)
                        }

                        override fun onSetFailure(error: String?) {
                            continuation.resumeWithException(Exception(error ?: "Unknown error"))
                        }

                        override fun onCreateSuccess(sdp: SessionDescription?) {}
                        override fun onCreateFailure(error: String?) {}
                    },
                    offer
                )
            }

            // Send offer to signaling server
            signalingClient?.send(
                SignalingMessage.Offer(
                    sessionId = sessionId,
                    sdp = offer.description
                )
            )

            Timber.d("WebRTCTransport: Offer created and sent")
        } catch (e: Exception) {
            Timber.e(e, "Failed to create offer")
            updateState(StreamTransport.ConnectionState.FAILED)
        }
    }

    private suspend fun handleAnswer(sdp: String) = withContext(Dispatchers.Main) {
        try {
            Timber.d("WebRTCTransport: Setting remote description")

            val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)

            suspendCancellableCoroutine<Unit> { continuation ->
                peerConnection?.setRemoteDescription(
                    object : SdpObserver {
                        override fun onSetSuccess() {
                            Timber.d("WebRTCTransport: Remote description set successfully")
                            continuation.resume(Unit, null)
                        }

                        override fun onSetFailure(error: String?) {
                            Timber.e("WebRTCTransport: Failed to set remote description: $error")
                            continuation.resumeWithException(Exception(error ?: "Unknown error"))
                        }

                        override fun onCreateSuccess(sdp: SessionDescription?) {}
                        override fun onCreateFailure(error: String?) {}
                    },
                    answer
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to handle answer")
        }
    }

    private suspend fun handleIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) =
        withContext(Dispatchers.Main) {
            try {
                val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
                peerConnection?.addIceCandidate(iceCandidate)
                Timber.d("WebRTCTransport: ICE candidate added")
            } catch (e: Exception) {
                Timber.e(e, "Failed to add ICE candidate")
            }
        }

    private fun updateState(newState: StreamTransport.ConnectionState) {
        state = newState
        stateListener?.invoke(newState)
    }
}
