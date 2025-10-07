/**
 * WebRTC-based transport for high-performance screen sharing
 */
class WebRTCTransport {
    constructor() {
        this.peerConnection = null;
        this.signalingWs = null;
        this.sessionId = null;
        this.videoElement = null;
        this.audioContext = null;
        this.isConnected = false;

        // Callbacks
        this.onVideoCallback = null;
        this.onAudioCallback = null;
        this.onConnectionStateChange = null;
    }

    async connect(signalingUrl, iceServers) {
        console.log('WebRTCTransport: Connecting to', signalingUrl);

        try {
            // Create peer connection
            this.peerConnection = new RTCPeerConnection({
                iceServers: iceServers || [
                    { urls: 'stun:stun.l.google.com:19302' }
                ]
            });

            // Setup peer connection handlers
            this.setupPeerConnectionHandlers();

            // Connect to signaling server
            await this.connectSignaling(signalingUrl);

            console.log('WebRTCTransport: Connected successfully');
        } catch (error) {
            console.error('WebRTCTransport: Connection failed:', error);
            throw error;
        }
    }

    setupPeerConnectionHandlers() {
        // Handle incoming media tracks
        this.peerConnection.ontrack = (event) => {
            console.log('WebRTCTransport: Received track:', event.track.kind);

            if (event.track.kind === 'video') {
                // Create MediaStream with video track
                const stream = new MediaStream([event.track]);

                if (this.onVideoCallback) {
                    this.onVideoCallback(stream);
                }
            } else if (event.track.kind === 'audio') {
                // Create MediaStream with audio track
                const stream = new MediaStream([event.track]);

                if (this.onAudioCallback) {
                    this.onAudioCallback(stream);
                }
            }
        };

        // Handle connection state changes
        this.peerConnection.onconnectionstatechange = () => {
            const state = this.peerConnection.connectionState;
            console.log('WebRTCTransport: Connection state:', state);

            this.isConnected = (state === 'connected');

            if (this.onConnectionStateChange) {
                this.onConnectionStateChange(state);
            }

            if (state === 'failed') {
                console.error('WebRTCTransport: Connection failed');
                this.disconnect();
            }
        };

        // Handle ICE connection state
        this.peerConnection.oniceconnectionstatechange = () => {
            console.log('WebRTCTransport: ICE connection state:',
                        this.peerConnection.iceConnectionState);
        };

        // Handle ICE gathering state
        this.peerConnection.onicegatheringstatechange = () => {
            console.log('WebRTCTransport: ICE gathering state:',
                        this.peerConnection.iceGatheringState);
        };

        // Handle ICE candidates
        this.peerConnection.onicecandidate = (event) => {
            if (event.candidate) {
                console.log('WebRTCTransport: New ICE candidate');
                this.sendSignalingMessage({
                    type: 'ice-candidate',
                    session_id: this.sessionId,
                    candidate: event.candidate.candidate,
                    sdp_mid: event.candidate.sdpMid,
                    sdp_m_line_index: event.candidate.sdpMLineIndex
                });
            }
        };
    }

    async connectSignaling(signalingUrl) {
        return new Promise((resolve, reject) => {
            this.signalingWs = new WebSocket(signalingUrl);

            this.signalingWs.onopen = () => {
                console.log('WebRTCTransport: Signaling connected');
                resolve();
            };

            this.signalingWs.onmessage = async (event) => {
                try {
                    const message = JSON.parse(event.data);
                    await this.handleSignalingMessage(message);
                } catch (error) {
                    console.error('WebRTCTransport: Error handling signaling message:', error);
                }
            };

            this.signalingWs.onerror = (error) => {
                console.error('WebRTCTransport: Signaling error:', error);
                reject(error);
            };

            this.signalingWs.onclose = () => {
                console.log('WebRTCTransport: Signaling disconnected');
            };
        });
    }

    async handleSignalingMessage(message) {
        console.log('WebRTCTransport: Received signaling message:', message.type);

        switch (message.type) {
            case 'ready':
                this.sessionId = message.session_id;
                console.log('WebRTCTransport: Session ID:', this.sessionId);
                break;

            case 'offer':
                await this.handleOffer(message.sdp);
                break;

            case 'ice-candidate':
                await this.handleIceCandidate(message);
                break;

            case 'error':
                console.error('WebRTCTransport: Signaling error:', message.error);
                break;

            default:
                console.warn('WebRTCTransport: Unknown message type:', message.type);
        }
    }

    async handleOffer(sdp) {
        try {
            console.log('WebRTCTransport: Handling offer');

            // Set remote description
            await this.peerConnection.setRemoteDescription(
                new RTCSessionDescription({ type: 'offer', sdp })
            );

            // Create answer
            const answer = await this.peerConnection.createAnswer();

            // Set local description
            await this.peerConnection.setLocalDescription(answer);

            // Send answer
            this.sendSignalingMessage({
                type: 'answer',
                session_id: this.sessionId,
                sdp: answer.sdp
            });

            console.log('WebRTCTransport: Answer sent');
        } catch (error) {
            console.error('WebRTCTransport: Error handling offer:', error);
        }
    }

    async handleIceCandidate(message) {
        try {
            const candidate = new RTCIceCandidate({
                candidate: message.candidate,
                sdpMid: message.sdp_mid,
                sdpMLineIndex: message.sdp_m_line_index
            });

            await this.peerConnection.addIceCandidate(candidate);
            console.log('WebRTCTransport: ICE candidate added');
        } catch (error) {
            console.error('WebRTCTransport: Error adding ICE candidate:', error);
        }
    }

    sendSignalingMessage(message) {
        if (this.signalingWs && this.signalingWs.readyState === WebSocket.OPEN) {
            this.signalingWs.send(JSON.stringify(message));
        } else {
            console.warn('WebRTCTransport: Signaling WebSocket not ready');
        }
    }

    disconnect() {
        console.log('WebRTCTransport: Disconnecting');

        // Send bye message
        if (this.signalingWs && this.signalingWs.readyState === WebSocket.OPEN) {
            this.sendSignalingMessage({
                type: 'bye',
                session_id: this.sessionId,
                reason: 'user_disconnect'
            });
        }

        // Close signaling
        if (this.signalingWs) {
            this.signalingWs.close();
            this.signalingWs = null;
        }

        // Close peer connection
        if (this.peerConnection) {
            this.peerConnection.close();
            this.peerConnection = null;
        }

        this.isConnected = false;
        console.log('WebRTCTransport: Disconnected');
    }

    renderVideo(videoElement) {
        this.videoElement = videoElement;

        this.onVideoCallback = (stream) => {
            videoElement.srcObject = stream;
            videoElement.play().catch(e => {
                console.error('WebRTCTransport: Error playing video:', e);
            });
        };

        this.onAudioCallback = (stream) => {
            // Audio is automatically played through the video element
            // or can be processed separately if needed
            console.log('WebRTCTransport: Audio stream received');
        };
    }

    async getStats() {
        if (!this.peerConnection) return null;

        const stats = await this.peerConnection.getStats();
        const result = {
            video: {},
            audio: {},
            connection: {}
        };

        stats.forEach(report => {
            if (report.type === 'inbound-rtp' && report.kind === 'video') {
                result.video = {
                    fps: report.framesPerSecond || 0,
                    bitrate: report.bytesReceived ?
                             (report.bytesReceived * 8 / report.timestamp) : 0,
                    packetsLost: report.packetsLost || 0,
                    jitter: report.jitter || 0
                };
            } else if (report.type === 'inbound-rtp' && report.kind === 'audio') {
                result.audio = {
                    bitrate: report.bytesReceived ?
                             (report.bytesReceived * 8 / report.timestamp) : 0,
                    packetsLost: report.packetsLost || 0
                };
            } else if (report.type === 'candidate-pair' && report.state === 'succeeded') {
                result.connection = {
                    currentRtt: report.currentRoundTripTime || 0,
                    availableOutgoingBitrate: report.availableOutgoingBitrate || 0
                };
            }
        });

        return result;
    }
}
