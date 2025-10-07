// Screen Viewer JavaScript
class ScreenViewer {
    constructor() {
        this.canvas = document.getElementById('screen-canvas');
        this.videoElement = document.getElementById('screen-video');
        this.ctx = this.canvas.getContext('2d', {
            alpha: false, // Disable alpha for better performance
            desynchronized: true // Reduce latency
        });
        this.ws = null;
        this.isConnected = false;
        this.touchMode = 'tap'; // tap or swipe
        this.swipeStartPoint = null;
        this.frameCount = 0;
        this.lastFpsUpdate = Date.now();
        this.currentFps = 0;
        this.screenInfo = null;
        this.serverHost = window.location.hostname;
        this.httpPort = window.location.port || 9527;
        this.wsPort = 9527; // WebSocket now on same port as HTTP

        // Transport abstraction
        this.negotiator = new TransportNegotiator(this.serverHost, this.httpPort);
        this.transport = null; // Will be WebRTCTransport or WebSocket
        this.transportType = null; // 'webrtc' or 'websocket'

        // Performance optimization: Frame queue and object pool
        this.frameQueue = [];
        this.maxQueueSize = 3;
        this.isRendering = false;
        this.currentImage = new Image();
        this.nextImage = new Image();
        this.imagePool = [];
        this.maxImagePoolSize = 5;

        // Performance monitoring
        this.performanceMonitor = {
            frameTimestamps: [],
            droppedFrames: 0,
            totalFrames: 0,
            lastReportTime: Date.now()
        };

        // Initialize image pool
        for (let i = 0; i < this.maxImagePoolSize; i++) {
            this.imagePool.push(new Image());
        }

        // Audio playback setup
        this.audioContext = null;
        this.audioEnabled = true;
        this.audioQueue = [];
        this.nextPlayTime = 0;
        this.audioSampleRate = 48000;
        this.audioChannels = 2;

        this.init();
    }

    init() {
        this.setupEventListeners();
        this.setupCanvasEvents();
        this.updateFpsCounter();
        this.checkServerStatus();

        // Set initial touch mode (without showing toast)
        this.setTouchMode('tap', false);
    }

    setupEventListeners() {
        // Control buttons
        document.getElementById('start-btn').addEventListener('click', () => this.startScreenShare());
        document.getElementById('stop-btn').addEventListener('click', () => this.stopScreenShare());
        document.getElementById('fullscreen-btn').addEventListener('click', () => this.toggleFullscreen());

        // Navigation buttons
        document.querySelectorAll('.nav-btn').forEach(btn => {
            btn.addEventListener('click', (e) => {
                const action = e.target.dataset.action;
                this.sendNavigationEvent(action);
            });
        });

        // Touch mode buttons
        document.getElementById('touch-mode').addEventListener('click', () => {
            this.setTouchMode('tap');
        });

        document.getElementById('swipe-mode').addEventListener('click', () => {
            this.setTouchMode('swipe');
        });

        // Quality slider
        const qualitySlider = document.getElementById('quality-slider');
        qualitySlider.addEventListener('input', (e) => {
            document.getElementById('quality-value').textContent = e.target.value;
            this.sendQualityUpdate(e.target.value);
        });

        // Audio toggle button
        const audioToggleBtn = document.getElementById('audio-toggle');
        audioToggleBtn.addEventListener('click', () => {
            const enabled = this.toggleAudio();
            audioToggleBtn.textContent = enabled ? '🔊 音频已开启' : '🔇 音频已关闭';
            audioToggleBtn.classList.toggle('primary', enabled);
            this.showToast(enabled ? '音频已开启' : '音频已关闭');
        });
    }

    setupCanvasEvents() {
        this.canvas.addEventListener('mousedown', (e) => this.handleMouseDown(e));
        this.canvas.addEventListener('mousemove', (e) => this.handleMouseMove(e));
        this.canvas.addEventListener('mouseup', (e) => this.handleMouseUp(e));

        // Touch events for mobile
        this.canvas.addEventListener('touchstart', (e) => this.handleTouchStart(e), { passive: false });
        this.canvas.addEventListener('touchmove', (e) => this.handleTouchMove(e), { passive: false });
        this.canvas.addEventListener('touchend', (e) => this.handleTouchEnd(e), { passive: false });

        // Context menu prevention
        this.canvas.addEventListener('contextmenu', (e) => {
            e.preventDefault();
            // Right click as back button
            this.sendNavigationEvent('back');
        });
    }

    async checkServerStatus() {
        try {
            const response = await fetch(`http://${this.serverHost}:${this.httpPort}/screen/status`);
            const data = await response.json();

            if (data.data && data.data.is_running) {
                this.updateButtonStates(true);
                await this.negotiateAndConnect();
            } else {
                this.updateButtonStates(false);
            }
        } catch (error) {
            console.error('Failed to check server status:', error);
            this.showToast('无法连接到服务器');
        }
    }

    async startScreenShare() {
        try {
            const response = await fetch(`http://${this.serverHost}:${this.httpPort}/screen/start`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' }
            });

            const data = await response.json();

            if (data.code === 0) {
                this.showToast('屏幕共享启动中，请在手机上授权');
                this.updateButtonStates(true);

                // Wait a bit then negotiate and connect
                setTimeout(async () => {
                    await this.negotiateAndConnect();
                }, 2000);
            } else {
                this.showToast('启动失败: ' + data.msg);
            }
        } catch (error) {
            console.error('Failed to start screen share:', error);
            this.showToast('启动屏幕共享失败');
        }
    }

    async negotiateAndConnect() {
        try {
            console.log('Negotiating transport...');
            document.getElementById('transport-type').textContent = '协商中...';

            // Negotiate transport
            const result = await this.negotiator.negotiate();
            this.transportType = result.transport;

            console.log('Negotiated transport:', this.transportType);

            // Update UI
            if (this.transportType === 'webrtc') {
                document.getElementById('transport-type').textContent = '🚀 WebRTC (高性能)';
                document.getElementById('transport-type').style.color = '#4caf50';
            } else {
                document.getElementById('transport-type').textContent = '📡 WebSocket (兼容)';
                document.getElementById('transport-type').style.color = '#2196f3';
            }

            // Connect with selected transport
            if (this.transportType === 'webrtc') {
                await this.connectWebRTC(result.config);
            } else {
                await this.connectWebSocket();
            }

        } catch (error) {
            console.error('Transport negotiation failed:', error);
            this.showToast('传输协商失败，使用兼容模式');

            // Fallback to WebSocket
            document.getElementById('transport-type').textContent = '📡 WebSocket (降级)';
            await this.connectWebSocket();
        }
    }

    async connectWebRTC(config) {
        try {
            console.log('Connecting via WebRTC...');

            const overlay = document.getElementById('connection-overlay');
            overlay.classList.remove('hidden');

            // Create WebRTC transport
            this.transport = new WebRTCTransport();

            // Handle connection state changes
            this.transport.onConnectionStateChange = async (state) => {
                console.log('WebRTC connection state:', state);

                if (state === 'connected') {
                    overlay.classList.add('hidden');
                    this.isConnected = true;
                    this.updateConnectionStatus(true);
                    this.showToast('WebRTC 连接成功');

                    // Show video, hide canvas
                    this.videoElement.style.display = 'block';
                    this.canvas.style.display = 'none';

                    // Start stats monitoring
                    this.startWebRTCStatsMonitoring();

                } else if (state === 'failed' || state === 'disconnected') {
                    overlay.classList.add('hidden');
                    this.isConnected = false;
                    this.updateConnectionStatus(false);

                    if (state === 'failed') {
                        this.showToast('WebRTC 连接失败，切换到兼容模式');
                        // Fallback to WebSocket
                        await this.fallbackToWebSocket();
                    }
                }
            };

            // Connect
            const signalingUrl = config.signaling_url ||
                                `ws://${this.serverHost}:${this.wsPort}/signaling`;

            // Set a timeout for WebRTC connection
            const connectionTimeout = setTimeout(async () => {
                console.warn('WebRTC connection timeout, falling back to WebSocket');
                this.showToast('WebRTC 连接超时，切换到兼容模式');
                await this.fallbackToWebSocket();
            }, 5000); // 5 second timeout

            // Clear timeout if connection succeeds
            const originalOnConnectionStateChange = this.transport.onConnectionStateChange;
            this.transport.onConnectionStateChange = async (state) => {
                if (state === 'connected' || state === 'failed') {
                    clearTimeout(connectionTimeout);
                }
                if (originalOnConnectionStateChange) {
                    await originalOnConnectionStateChange(state);
                }
            };

            await this.transport.connect(signalingUrl, config.ice_servers);

            // Render to video element
            this.transport.renderVideo(this.videoElement);

            console.log('WebRTC transport connected');

        } catch (error) {
            console.error('WebRTC connection error:', error);
            this.showToast('WebRTC 连接错误，切换到兼容模式');
            await this.fallbackToWebSocket();
        }
    }

    async fallbackToWebSocket() {
        console.log('Falling back to WebSocket...');

        // Disconnect WebRTC if exists
        if (this.transport) {
            this.transport.disconnect();
            this.transport = null;
        }

        // Update UI
        this.transportType = 'websocket';
        document.getElementById('transport-type').textContent = '📡 WebSocket (降级)';
        document.getElementById('transport-type').style.color = '#ff9800';

        // Show canvas, hide video
        this.videoElement.style.display = 'none';
        this.canvas.style.display = 'block';

        // Connect via WebSocket
        await this.connectWebSocket();
    }

    async stopScreenShare() {
        try {
            const response = await fetch(`http://${this.serverHost}:${this.httpPort}/screen/stop`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' }
            });

            const data = await response.json();

            if (data.code === 0) {
                this.showToast('屏幕共享已停止');
                this.disconnect(); // Use unified disconnect method
                this.updateButtonStates(false);
                this.clearCanvas();

                // Reset transport type display
                document.getElementById('transport-type').textContent = '未连接';
                document.getElementById('transport-type').style.color = '';
            } else {
                this.showToast('停止失败: ' + data.msg);
            }
        } catch (error) {
            console.error('Failed to stop screen share:', error);
            this.showToast('停止屏幕共享失败');
        }
    }

    connectWebSocket() {
        if (this.ws && this.ws.readyState === WebSocket.OPEN) {
            return;
        }

        const overlay = document.getElementById('connection-overlay');
        overlay.classList.remove('hidden');

        this.ws = new WebSocket(`ws://${this.serverHost}:${this.wsPort}/remote`);
        this.ws.binaryType = 'arraybuffer';

        this.ws.onopen = () => {
            console.log('WebSocket connected');
            this.isConnected = true;
            overlay.classList.add('hidden');
            this.updateConnectionStatus(true);
            this.showToast('已连接到设备');

            // Start ping interval
            this.startPingInterval();
        };

        this.ws.onmessage = (event) => {
            if (event.data instanceof ArrayBuffer) {
                // Binary data - could be video frame or audio data
                this.handleBinaryData(event.data);
            } else {
                // JSON message
                try {
                    const message = JSON.parse(event.data);
                    this.handleJsonMessage(message);
                } catch (e) {
                    console.error('Failed to parse message:', e);
                }
            }
        };

        this.ws.onerror = (error) => {
            console.error('WebSocket error:', error);
            this.showToast('连接错误');
        };

        this.ws.onclose = () => {
            console.log('WebSocket disconnected');
            this.isConnected = false;
            overlay.classList.add('hidden');
            this.updateConnectionStatus(false);
            this.showToast('连接已断开');
            this.stopPingInterval();
        };
    }

    disconnectWebSocket() {
        if (this.ws) {
            this.ws.close();
            this.ws = null;
        }
        this.isConnected = false;
        this.updateConnectionStatus(false);
        this.stopPingInterval();
    }

    disconnect() {
        // Disconnect based on transport type
        if (this.transportType === 'webrtc' && this.transport) {
            this.transport.disconnect();
            this.transport = null;
            this.stopWebRTCStatsMonitoring();
        } else {
            this.disconnectWebSocket();
        }

        this.isConnected = false;
        this.updateConnectionStatus(false);
    }

    startWebRTCStatsMonitoring() {
        // Stop any existing monitoring
        this.stopWebRTCStatsMonitoring();

        // Monitor stats every second
        this.statsInterval = setInterval(async () => {
            if (this.transport && this.transportType === 'webrtc') {
                try {
                    const stats = await this.transport.getStats();

                    if (stats && stats.video) {
                        // Update FPS
                        const fps = Math.round(stats.video.fps || 0);
                        document.getElementById('fps').textContent = fps;

                        // Update latency (RTT)
                        if (stats.connection && stats.connection.currentRtt) {
                            const latency = Math.round(stats.connection.currentRtt * 1000);
                            document.getElementById('latency').textContent = `${latency}ms`;
                        }

                        // Update frame count for overall FPS calculation
                        this.frameCount++;
                    }
                } catch (error) {
                    console.error('Error getting WebRTC stats:', error);
                }
            }
        }, 1000);
    }

    stopWebRTCStatsMonitoring() {
        if (this.statsInterval) {
            clearInterval(this.statsInterval);
            this.statsInterval = null;
        }
    }

    handleBinaryData(arrayBuffer) {
        // Parse packet header
        // Format: [type(1) | timestamp(8) | data...]
        const dataView = new DataView(arrayBuffer);

        if (arrayBuffer.byteLength < 9) {
            console.error('Invalid packet: too small');
            return;
        }

        const packetType = dataView.getUint8(0);

        // Extract timestamp (8 bytes, big-endian)
        let timestamp = 0;
        for (let i = 0; i < 8; i++) {
            timestamp = (timestamp * 256) + dataView.getUint8(1 + i);
        }

        if (packetType === 0x00) {
            // Video frame (type 0x00)
            // Data starts at byte 9
            const frameData = arrayBuffer.slice(9);
            this.handleScreenFrame(frameData, timestamp);
        } else if (packetType === 0x01) {
            // Audio packet (type 0x01)
            // Format: [type(1) | timestamp(8) | size(4) | pcm_data]
            if (arrayBuffer.byteLength < 13) {
                console.error('Invalid audio packet: too small');
                return;
            }

            const audioDataSize = dataView.getInt32(9, false); // big-endian
            const audioData = arrayBuffer.slice(13);

            if (audioData.byteLength >= audioDataSize) {
                this.handleAudioData(audioData.slice(0, audioDataSize), timestamp);
            } else {
                console.error(`Audio packet size mismatch: expected ${audioDataSize}, got ${audioData.byteLength}`);
            }
        } else {
            console.warn('Unknown packet type:', packetType);
        }
    }

    handleScreenFrame(arrayBuffer, timestamp) {
        // Track total frames received
        this.performanceMonitor.totalFrames++;

        // Add frame to queue
        if (this.frameQueue.length >= this.maxQueueSize) {
            // Drop oldest frame if queue is full
            const droppedFrame = this.frameQueue.shift();
            if (droppedFrame.url) {
                URL.revokeObjectURL(droppedFrame.url);
            }
            this.performanceMonitor.droppedFrames++;
        }

        const blob = new Blob([arrayBuffer], { type: 'image/jpeg' });
        const url = URL.createObjectURL(blob);

        this.frameQueue.push({ blob, url, timestamp: Date.now() });

        // Start rendering if not already running
        if (!this.isRendering) {
            this.renderNextFrame();
        }

        // Send performance report periodically
        this.checkAndSendPerformanceReport();
    }

    renderNextFrame() {
        if (this.frameQueue.length === 0) {
            this.isRendering = false;
            return;
        }

        this.isRendering = true;
        const frame = this.frameQueue.shift();

        // Get an image from the pool or create a new one
        const img = this.getImageFromPool();

        img.onload = () => {
            // Use requestAnimationFrame for smooth rendering
            requestAnimationFrame(() => {
                // Update canvas size if needed
                if (this.canvas.width !== img.width || this.canvas.height !== img.height) {
                    this.canvas.width = img.width;
                    this.canvas.height = img.height;
                    document.getElementById('resolution').textContent = `${img.width}x${img.height}`;
                }

                // Draw image to canvas with optimal settings
                this.ctx.imageSmoothingEnabled = false; // Disable smoothing for better performance
                this.ctx.drawImage(img, 0, 0);

                // Clean up
                URL.revokeObjectURL(frame.url);
                this.returnImageToPool(img);

                // Update FPS counter
                this.frameCount++;

                // Render next frame
                this.renderNextFrame();
            });
        };

        img.onerror = () => {
            URL.revokeObjectURL(frame.url);
            this.returnImageToPool(img);
            this.renderNextFrame();
        };

        img.src = frame.url;
    }

    getImageFromPool() {
        if (this.imagePool.length > 0) {
            return this.imagePool.pop();
        }
        return new Image();
    }

    returnImageToPool(img) {
        // Reset the image
        img.onload = null;
        img.onerror = null;
        img.src = '';

        if (this.imagePool.length < this.maxImagePoolSize) {
            this.imagePool.push(img);
        }
    }

    handleAudioData(audioDataBuffer, timestamp) {
        if (!this.audioEnabled) {
            return;
        }

        try {
            // Initialize audio context on first audio packet
            if (!this.audioContext) {
                this.initAudioContext();
            }

            // Convert PCM 16-bit stereo to Float32Array for Web Audio API
            const pcmData = new Int16Array(audioDataBuffer);
            const samplesPerChannel = pcmData.length / this.audioChannels;

            // Create audio buffer
            const audioBuffer = this.audioContext.createBuffer(
                this.audioChannels,
                samplesPerChannel,
                this.audioSampleRate
            );

            // Convert Int16 PCM to Float32 (-1.0 to 1.0)
            for (let channel = 0; channel < this.audioChannels; channel++) {
                const channelData = audioBuffer.getChannelData(channel);
                for (let i = 0; i < samplesPerChannel; i++) {
                    // Interleaved stereo: L, R, L, R, ...
                    const sample = pcmData[i * this.audioChannels + channel];
                    channelData[i] = sample / 32768.0; // Convert to -1.0 to 1.0
                }
            }

            // Schedule playback
            this.scheduleAudioPlayback(audioBuffer, timestamp);

        } catch (e) {
            console.error('Error handling audio data:', e);
        }
    }

    initAudioContext() {
        try {
            // Create audio context
            const AudioContext = window.AudioContext || window.webkitAudioContext;
            this.audioContext = new AudioContext({ sampleRate: this.audioSampleRate });

            // Initialize playback time
            this.nextPlayTime = this.audioContext.currentTime;

            console.log('Audio context initialized:', {
                sampleRate: this.audioContext.sampleRate,
                state: this.audioContext.state
            });

            // Resume context if suspended (some browsers require user interaction)
            if (this.audioContext.state === 'suspended') {
                this.audioContext.resume().then(() => {
                    console.log('Audio context resumed');
                });
            }
        } catch (e) {
            console.error('Failed to initialize audio context:', e);
        }
    }

    scheduleAudioPlayback(audioBuffer, timestamp) {
        if (!this.audioContext || !audioBuffer) {
            return;
        }

        try {
            // Create buffer source
            const source = this.audioContext.createBufferSource();
            source.buffer = audioBuffer;

            // Optional: Add gain control for volume
            const gainNode = this.audioContext.createGain();
            gainNode.gain.value = 1.0; // Full volume

            // Connect: source -> gain -> destination
            source.connect(gainNode);
            gainNode.connect(this.audioContext.destination);

            // Calculate when to play this buffer
            const currentTime = this.audioContext.currentTime;
            const bufferDuration = audioBuffer.duration;

            // If we're behind, catch up by playing immediately
            if (this.nextPlayTime < currentTime) {
                this.nextPlayTime = currentTime;
            }

            // Schedule playback
            source.start(this.nextPlayTime);

            // Update next play time
            this.nextPlayTime += bufferDuration;

            // Cleanup after playback
            source.onended = () => {
                source.disconnect();
                gainNode.disconnect();
            };

        } catch (e) {
            console.error('Error scheduling audio playback:', e);
        }
    }

    toggleAudio() {
        this.audioEnabled = !this.audioEnabled;
        console.log('Audio', this.audioEnabled ? 'enabled' : 'disabled');

        if (!this.audioEnabled && this.audioContext) {
            // Stop all scheduled audio
            this.audioContext.close().then(() => {
                this.audioContext = null;
                this.nextPlayTime = 0;
                console.log('Audio context closed');
            });
        }

        return this.audioEnabled;
    }

    handleJsonMessage(message) {
        switch (message.type) {
            case 'connected':
                console.log('Server confirmed connection:', message.message);
                break;
            case 'screen_info':
                this.screenInfo = message;
                console.log('Screen info received:', message);
                break;
            case 'pong':
                const latency = Date.now() - message.timestamp;
                document.getElementById('latency').textContent = `${latency}ms`;
                break;
            case 'error':
                this.showToast('错误: ' + message.message);
                break;
        }
    }

    handleMouseDown(e) {
        const point = this.getRelativePoint(e);

        if (this.touchMode === 'tap') {
            this.sendTouchEvent(point.x, point.y, 'down');
        } else if (this.touchMode === 'swipe') {
            this.swipeStartPoint = point;
        }
    }

    handleMouseMove(e) {
        if (this.touchMode === 'swipe' && this.swipeStartPoint) {
            // Draw swipe preview line
            const point = this.getRelativePoint(e);
            // You could add visual feedback here
        }
    }

    handleMouseUp(e) {
        const point = this.getRelativePoint(e);

        if (this.touchMode === 'tap') {
            this.sendTouchEvent(point.x, point.y, 'tap');
        } else if (this.touchMode === 'swipe' && this.swipeStartPoint) {
            this.sendSwipeEvent(
                this.swipeStartPoint.x,
                this.swipeStartPoint.y,
                point.x,
                point.y
            );
            this.swipeStartPoint = null;
        }
    }

    handleTouchStart(e) {
        e.preventDefault();
        const touch = e.touches[0];
        const rect = this.canvas.getBoundingClientRect();
        const point = {
            x: (touch.clientX - rect.left) / rect.width,
            y: (touch.clientY - rect.top) / rect.height
        };

        if (this.touchMode === 'tap') {
            this.sendTouchEvent(point.x, point.y, 'down');
        } else if (this.touchMode === 'swipe') {
            this.swipeStartPoint = point;
        }
    }

    handleTouchMove(e) {
        e.preventDefault();
        // Handle touch move if needed
    }

    handleTouchEnd(e) {
        e.preventDefault();

        if (this.touchMode === 'tap' && this.swipeStartPoint) {
            this.sendTouchEvent(this.swipeStartPoint.x, this.swipeStartPoint.y, 'tap');
            this.swipeStartPoint = null;
        } else if (this.touchMode === 'swipe' && this.swipeStartPoint && e.changedTouches.length > 0) {
            const touch = e.changedTouches[0];
            const rect = this.canvas.getBoundingClientRect();
            const point = {
                x: (touch.clientX - rect.left) / rect.width,
                y: (touch.clientY - rect.top) / rect.height
            };

            this.sendSwipeEvent(
                this.swipeStartPoint.x,
                this.swipeStartPoint.y,
                point.x,
                point.y
            );
            this.swipeStartPoint = null;
        }
    }

    getRelativePoint(e) {
        const rect = this.canvas.getBoundingClientRect();
        return {
            x: (e.clientX - rect.left) / rect.width,
            y: (e.clientY - rect.top) / rect.height
        };
    }

    sendTouchEvent(x, y, action) {
        if (!this.isConnected) return;

        const message = {
            type: 'touch',
            x: x,
            y: y,
            action: action
        };

        this.ws.send(JSON.stringify(message));
    }

    sendSwipeEvent(startX, startY, endX, endY) {
        if (!this.isConnected) return;

        const message = {
            type: 'swipe',
            startX: startX,
            startY: startY,
            endX: endX,
            endY: endY
        };

        this.ws.send(JSON.stringify(message));
    }

    sendNavigationEvent(button) {
        if (!this.isConnected) {
            this.showToast('请先连接到设备');
            return;
        }

        const message = {
            type: 'navigation',
            button: button
        };

        this.ws.send(JSON.stringify(message));
    }

    sendQualityUpdate(quality) {
        if (!this.isConnected) return;

        const message = {
            type: 'quality',
            value: parseInt(quality)
        };

        this.ws.send(JSON.stringify(message));
    }

    setTouchMode(mode, showToast = true) {
        // Don't switch if already in this mode
        if (this.touchMode === mode && showToast) {
            return;
        }

        this.touchMode = mode;

        const touchBtn = document.getElementById('touch-mode');
        const swipeBtn = document.getElementById('swipe-mode');
        const indicator = document.getElementById('current-mode-indicator');

        if (mode === 'tap') {
            touchBtn.classList.add('primary');
            swipeBtn.classList.remove('primary');
            if (indicator) {
                indicator.textContent = '（当前：点击）';
                indicator.style.color = '#4caf50';
            }
            if (showToast) {
                this.showToast('✅ 已切换到点击模式');
            }
        } else {
            swipeBtn.classList.add('primary');
            touchBtn.classList.remove('primary');
            if (indicator) {
                indicator.textContent = '（当前：滑动）';
                indicator.style.color = '#4caf50';
            }
            if (showToast) {
                this.showToast('✅ 已切换到滑动模式');
            }
        }
    }

    startPingInterval() {
        this.pingInterval = setInterval(() => {
            if (this.isConnected) {
                const message = {
                    type: 'ping',
                    timestamp: Date.now()
                };
                this.ws.send(JSON.stringify(message));
            }
        }, 5000);
    }

    stopPingInterval() {
        if (this.pingInterval) {
            clearInterval(this.pingInterval);
            this.pingInterval = null;
        }
    }

    updateFpsCounter() {
        setInterval(() => {
            const now = Date.now();
            const elapsed = (now - this.lastFpsUpdate) / 1000;
            this.currentFps = Math.round(this.frameCount / elapsed);

            document.getElementById('fps').textContent = this.currentFps;

            this.frameCount = 0;
            this.lastFpsUpdate = now;
        }, 1000);
    }

    updateConnectionStatus(connected) {
        const statusText = document.getElementById('ws-status');
        const indicator = document.getElementById('ws-indicator');

        if (connected) {
            statusText.textContent = '已连接';
            indicator.classList.remove('disconnected');
            indicator.classList.add('connected');
        } else {
            statusText.textContent = '未连接';
            indicator.classList.remove('connected');
            indicator.classList.add('disconnected');
            document.getElementById('latency').textContent = '--';
            document.getElementById('fps').textContent = '--';
        }
    }

    updateButtonStates(isRunning) {
        const startBtn = document.getElementById('start-btn');
        const stopBtn = document.getElementById('stop-btn');

        startBtn.disabled = isRunning;
        stopBtn.disabled = !isRunning;
    }

    toggleFullscreen() {
        if (!document.fullscreenElement) {
            this.canvas.requestFullscreen().catch(err => {
                console.error('Failed to enter fullscreen:', err);
                this.showToast('无法进入全屏模式');
            });
        } else {
            document.exitFullscreen();
        }
    }

    clearCanvas() {
        this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
        document.getElementById('resolution').textContent = '--';
    }

    showToast(message) {
        const toast = document.getElementById('toast');
        toast.textContent = message;
        toast.classList.add('show');

        setTimeout(() => {
            toast.classList.remove('show');
        }, 3000);
    }

    checkAndSendPerformanceReport() {
        const now = Date.now();
        const timeSinceLastReport = now - this.performanceMonitor.lastReportTime;

        // Send report every 5 seconds
        if (timeSinceLastReport > 5000 && this.isConnected) {
            const dropRate = this.performanceMonitor.droppedFrames /
                            Math.max(1, this.performanceMonitor.totalFrames);

            const performanceReport = {
                type: 'performance',
                fps: this.currentFps,
                dropRate: dropRate,
                latency: parseInt(document.getElementById('latency').textContent) || 0,
                queueSize: this.frameQueue.length
            };

            if (this.ws && this.ws.readyState === WebSocket.OPEN) {
                this.ws.send(JSON.stringify(performanceReport));
            }

            // Reset counters
            this.performanceMonitor.droppedFrames = 0;
            this.performanceMonitor.totalFrames = 0;
            this.performanceMonitor.lastReportTime = now;

            // Auto-adjust quality based on performance
            this.autoAdjustQuality(dropRate);
        }
    }

    autoAdjustQuality(dropRate) {
        const qualitySlider = document.getElementById('quality-slider');
        const currentQuality = parseInt(qualitySlider.value);

        if (dropRate > 0.2 && currentQuality > 40) {
            // High drop rate, reduce quality
            const newQuality = Math.max(40, currentQuality - 10);
            qualitySlider.value = newQuality;
            document.getElementById('quality-value').textContent = newQuality;
            this.sendQualityUpdate(newQuality);
            console.log(`Auto-adjusting quality down to ${newQuality} due to high drop rate`);
        } else if (dropRate < 0.05 && this.currentFps >= 25 && currentQuality < 80) {
            // Good performance, increase quality
            const newQuality = Math.min(80, currentQuality + 5);
            qualitySlider.value = newQuality;
            document.getElementById('quality-value').textContent = newQuality;
            this.sendQualityUpdate(newQuality);
            console.log(`Auto-adjusting quality up to ${newQuality} due to good performance`);
        }
    }
}

// Initialize the screen viewer when DOM is loaded
document.addEventListener('DOMContentLoaded', () => {
    window.screenViewer = new ScreenViewer();
});