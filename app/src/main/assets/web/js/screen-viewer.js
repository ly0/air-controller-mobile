// Screen Viewer JavaScript
class ScreenViewer {
    constructor() {
        this.canvas = document.getElementById('screen-canvas');
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
                this.connectWebSocket();
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

                // Wait a bit then connect to WebSocket
                setTimeout(() => {
                    this.connectWebSocket();
                }, 2000);
            } else {
                this.showToast('启动失败: ' + data.msg);
            }
        } catch (error) {
            console.error('Failed to start screen share:', error);
            this.showToast('启动屏幕共享失败');
        }
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
                this.disconnectWebSocket();
                this.updateButtonStates(false);
                this.clearCanvas();
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
                // Binary data - screen frame
                this.handleScreenFrame(event.data);
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

    handleScreenFrame(arrayBuffer) {
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