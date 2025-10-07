package com.youngfeng.android.assistant.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.youngfeng.android.assistant.MainActivity
import com.youngfeng.android.assistant.R
import com.youngfeng.android.assistant.server.websocket.ScreenStreamManager
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.ByteArrayOutputStream

class ScreenCaptureService : Service() {
    companion object {
        const val CHANNEL_ID = "ScreenCaptureChannel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_CAPTURE = "com.youngfeng.android.assistant.START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.youngfeng.android.assistant.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private var isRunning = false

        fun isServiceRunning(): Boolean = isRunning
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job? = null

    // Audio capture variables
    private var audioRecord: AudioRecord? = null
    private var audioCaptureJob: Job? = null
    private var isAudioEnabled = true // Can be toggled by user
    private val audioSampleRate = 48000 // 48kHz
    private val audioChannelConfig = AudioFormat.CHANNEL_IN_STEREO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // Performance optimization variables
    private var targetFps = 30 // Default target FPS
    private var dynamicQuality = 70 // Dynamic JPEG quality
    private var lastFrameTime = System.currentTimeMillis()
    private var frameSkipCount = 0
    private var adaptiveScaleFactor = 2
    private val performanceMonitor = PerformanceMonitor()

    // Reusable objects to reduce memory allocation
    private var reusableBitmap: Bitmap? = null
    private val jpegOutputStream = ByteArrayOutputStream()

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var windowManager: WindowManager
    private lateinit var displayMetrics: DisplayMetrics

    private val accessibilityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                RemoteControlAccessibilityService.ACTION_ACCESSIBILITY_CONNECTED -> {
                    Timber.d("Accessibility service connected, updating input controller")
                    ScreenStreamManager.updateAccessibilityService()
                }
                RemoteControlAccessibilityService.ACTION_ACCESSIBILITY_DISCONNECTED -> {
                    Timber.d("Accessibility service disconnected")
                    ScreenStreamManager.getInputController()?.setAccessibilityService(null)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)

        createNotificationChannel()

        // Register accessibility service receiver
        val filter = IntentFilter().apply {
            addAction(RemoteControlAccessibilityService.ACTION_ACCESSIBILITY_CONNECTED)
            addAction(RemoteControlAccessibilityService.ACTION_ACCESSIBILITY_DISCONNECTED)
        }
        registerReceiver(accessibilityReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startForeground(NOTIFICATION_ID, createNotification())
                    startCapture(resultCode, resultData)
                } else {
                    Timber.e("Failed to start capture: Invalid result code or data")
                    stopSelf()
                }
            }
            ACTION_STOP_CAPTURE -> {
                stopCapture()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "正在共享屏幕"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP_CAPTURE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("屏幕共享中")
            .setContentText("正在共享您的屏幕内容")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        try {
            // Stop any existing capture
            stopCapture()

            // Create MediaProjection
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

            // Setup ImageReader for capturing frames
            val width = displayMetrics.widthPixels
            val height = displayMetrics.heightPixels
            val density = displayMetrics.densityDpi

            // Adaptive resolution scaling for better performance
            adaptiveScaleFactor = when {
                width > 2000 -> 3 // Very high resolution screens
                width > 1500 -> 2 // Normal high resolution
                else -> 1 // Lower resolution screens
            }
            val scaledWidth = width / adaptiveScaleFactor
            val scaledHeight = height / adaptiveScaleFactor

            // Increase buffer size for smoother capture
            imageReader = ImageReader.newInstance(
                scaledWidth,
                scaledHeight,
                PixelFormat.RGBA_8888,
                5 // Increased from 2 to 5 for better buffering
            )

            // Create VirtualDisplay
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                scaledWidth,
                scaledHeight,
                density / adaptiveScaleFactor,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                imageReader!!.surface,
                null,
                null
            )

            // Initialize ScreenStreamManager with input controller
            ScreenStreamManager.initInputController(this, width, height, adaptiveScaleFactor)
            Timber.d("ScreenStreamManager initialized with dimensions: ${width}x${height}, scale: $adaptiveScaleFactor, targetFPS: $targetFps")

            // Start capturing frames
            startFrameCapture()

            // Start audio capture if supported (Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startAudioCapture()
            } else {
                Timber.w("Audio capture not supported on Android < 10")
            }

            Timber.d("Screen capture started successfully (with audio: ${Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q})")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start screen capture")
            stopCapture()
        }
    }

    private fun startAudioCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Timber.w("Audio capture requires Android 10+")
            return
        }

        try {
            // Stop any existing audio capture
            stopAudioCapture()

            // Build AudioPlaybackCaptureConfiguration
            val audioConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            // Calculate buffer size
            val bufferSize = AudioRecord.getMinBufferSize(
                audioSampleRate,
                audioChannelConfig,
                audioFormat
            ).coerceAtLeast(4096)

            // Build AudioFormat
            val audioFormatBuilder = AudioFormat.Builder()
                .setEncoding(audioFormat)
                .setSampleRate(audioSampleRate)
                .setChannelMask(audioChannelConfig)
                .build()

            // Create AudioRecord with playback capture
            audioRecord = AudioRecord.Builder()
                .setAudioFormat(audioFormatBuilder)
                .setBufferSizeInBytes(bufferSize * 2) // Double buffer for safety
                .setAudioPlaybackCaptureConfig(audioConfig)
                .build()

            // Start recording
            audioRecord?.startRecording()

            // Start audio capture job
            audioCaptureJob = serviceScope.launch {
                captureAndSendAudio(bufferSize)
            }

            Timber.d("Audio capture started successfully (Sample rate: $audioSampleRate, Buffer: $bufferSize)")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start audio capture")
            stopAudioCapture()
        }
    }

    private suspend fun captureAndSendAudio(bufferSize: Int) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(bufferSize)
        var audioPacketCount = 0L

        try {
            while (isActive && isAudioEnabled) {
                val bytesRead = audioRecord?.read(buffer, 0, bufferSize) ?: -1

                if (bytesRead > 0) {
                    // Create audio packet with header
                    // Format: [type(1) | timestamp(8) | size(4) | data]
                    val timestamp = System.currentTimeMillis()
                    val packet = ByteArray(1 + 8 + 4 + bytesRead)

                    // Type: 0x01 for audio
                    packet[0] = 0x01

                    // Timestamp (8 bytes)
                    for (i in 0..7) {
                        packet[1 + i] = ((timestamp shr (56 - i * 8)) and 0xFF).toByte()
                    }

                    // Size (4 bytes)
                    for (i in 0..3) {
                        packet[9 + i] = ((bytesRead shr (24 - i * 8)) and 0xFF).toByte()
                    }

                    // Audio data
                    System.arraycopy(buffer, 0, packet, 13, bytesRead)

                    // Broadcast audio packet
                    ScreenStreamManager.broadcastAudio(packet)

                    audioPacketCount++

                    // Log every 100 packets
                    if (audioPacketCount % 100 == 0L) {
                        Timber.d("Audio packets sent: $audioPacketCount")
                    }
                } else if (bytesRead < 0) {
                    Timber.e("AudioRecord read error: $bytesRead")
                    delay(10) // Wait before retry
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Timber.e(e, "Error capturing audio")
            }
        }
    }

    private fun stopAudioCapture() {
        audioCaptureJob?.cancel()
        audioCaptureJob = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        Timber.d("Audio capture stopped")
    }

    private fun startFrameCapture() {
        captureJob?.cancel()
        captureJob = serviceScope.launch {
            while (isActive) {
                try {
                    val startTime = System.currentTimeMillis()

                    // Capture and send frame
                    captureAndSendFrame()

                    // Calculate dynamic delay based on target FPS
                    val processingTime = System.currentTimeMillis() - startTime
                    val targetFrameTime = 1000L / targetFps
                    val delayTime = (targetFrameTime - processingTime).coerceAtLeast(1)

                    // Adaptive FPS adjustment based on performance
                    if (processingTime > targetFrameTime) {
                        // If processing takes too long, reduce quality or skip frames
                        performanceMonitor.recordSlowFrame()
                        adjustPerformanceSettings()
                    } else {
                        performanceMonitor.recordNormalFrame()
                    }

                    delay(delayTime)
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Timber.e(e, "Error capturing frame")
                    }
                }
            }
        }
    }

    private suspend fun captureAndSendFrame() = withContext(Dispatchers.IO) {
        imageReader?.acquireLatestImage()?.use { image ->
            try {
                val bitmap = imageToBitmap(image)
                val jpegData = bitmapToJpeg(bitmap, dynamicQuality)

                // Broadcast frame to all connected WebSocket clients via ScreenStreamManager
                ScreenStreamManager.broadcastFrame(jpegData)

                // Update statistics
                performanceMonitor.recordFrameSize(jpegData.size)
            } catch (e: Exception) {
                Timber.e(e, "Failed to process frame")
            }
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmapWidth = image.width + rowPadding / pixelStride
        val bitmapHeight = image.height

        // Reuse existing bitmap if possible
        var bitmap = reusableBitmap
        if (bitmap == null || bitmap.width != bitmapWidth || bitmap.height != bitmapHeight) {
            // Create new bitmap only if dimensions changed or doesn't exist
            bitmap = Bitmap.createBitmap(
                bitmapWidth,
                bitmapHeight,
                Bitmap.Config.ARGB_8888
            )
            reusableBitmap = bitmap
        }

        // Rewind buffer to the beginning
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)

        // Crop the bitmap to remove padding if necessary
        return if (rowPadding > 0) {
            // Note: This creates a view, not a copy, so it's efficient
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        } else {
            bitmap
        }
    }

    private fun bitmapToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        // Reset the reusable output stream
        jpegOutputStream.reset()

        // Compress with optimal settings for screen sharing
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, jpegOutputStream)

        return jpegOutputStream.toByteArray()
    }

    private fun stopCapture() {
        captureJob?.cancel()
        captureJob = null

        // Stop audio capture
        stopAudioCapture()

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        mediaProjection?.stop()
        mediaProjection = null

        // Clean up reusable objects
        reusableBitmap?.recycle()
        reusableBitmap = null
        jpegOutputStream.reset()

        // Clean up ScreenStreamManager
        ScreenStreamManager.cleanup()

        Timber.d("Screen capture stopped")
    }

    fun setAudioEnabled(enabled: Boolean) {
        isAudioEnabled = enabled
        Timber.d("Audio ${if (enabled) "enabled" else "disabled"}")
    }

    fun isAudioEnabled(): Boolean = isAudioEnabled

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopCapture()
        serviceScope.cancel()

        // Unregister accessibility receiver
        try {
            unregisterReceiver(accessibilityReceiver)
        } catch (e: Exception) {
            Timber.e(e, "Error unregistering accessibility receiver")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun adjustPerformanceSettings() {
        // Adjust quality based on performance
        if (performanceMonitor.getAverageSlowFrameRate() > 0.3f) {
            // More than 30% slow frames, reduce quality
            if (dynamicQuality > 40) {
                dynamicQuality = (dynamicQuality - 10).coerceAtLeast(40)
                Timber.d("Reducing JPEG quality to $dynamicQuality due to performance issues")
            } else if (targetFps > 15) {
                // If quality is already low, reduce FPS
                targetFps = (targetFps - 5).coerceAtLeast(15)
                Timber.d("Reducing target FPS to $targetFps due to performance issues")
            }
        } else if (performanceMonitor.getAverageSlowFrameRate() < 0.1f) {
            // Less than 10% slow frames, can increase quality
            if (targetFps < 30) {
                targetFps = (targetFps + 5).coerceAtMost(30)
                Timber.d("Increasing target FPS to $targetFps due to good performance")
            } else if (dynamicQuality < 85) {
                dynamicQuality = (dynamicQuality + 5).coerceAtMost(85)
                Timber.d("Increasing JPEG quality to $dynamicQuality due to good performance")
            }
        }
    }

    fun updateQualitySetting(quality: Int) {
        dynamicQuality = quality.coerceIn(30, 100)
        Timber.d("Quality manually updated to $dynamicQuality")
    }

    fun updateTargetFps(fps: Int) {
        targetFps = fps.coerceIn(10, 60)
        Timber.d("Target FPS manually updated to $targetFps")
    }

    /**
     * Nested class to monitor performance metrics
     */
    private class PerformanceMonitor {
        private val frameHistory = mutableListOf<FrameMetrics>()
        private val maxHistorySize = 100

        data class FrameMetrics(
            val timestamp: Long,
            val isSlow: Boolean,
            val frameSize: Int
        )

        fun recordSlowFrame() {
            addFrame(FrameMetrics(System.currentTimeMillis(), true, 0))
        }

        fun recordNormalFrame() {
            addFrame(FrameMetrics(System.currentTimeMillis(), false, 0))
        }

        fun recordFrameSize(size: Int) {
            if (frameHistory.isNotEmpty()) {
                val lastFrame = frameHistory.last()
                frameHistory[frameHistory.size - 1] = lastFrame.copy(frameSize = size)
            }
        }

        private fun addFrame(metrics: FrameMetrics) {
            frameHistory.add(metrics)
            if (frameHistory.size > maxHistorySize) {
                frameHistory.removeAt(0)
            }
        }

        fun getAverageSlowFrameRate(): Float {
            if (frameHistory.isEmpty()) return 0f
            val recentFrames = frameHistory.takeLast(30)
            val slowCount = recentFrames.count { it.isSlow }
            return slowCount.toFloat() / recentFrames.size
        }

        fun getAverageFrameSize(): Int {
            if (frameHistory.isEmpty()) return 0
            val recentFrames = frameHistory.takeLast(30).filter { it.frameSize > 0 }
            if (recentFrames.isEmpty()) return 0
            return recentFrames.sumOf { it.frameSize } / recentFrames.size
        }

        fun reset() {
            frameHistory.clear()
        }
    }
}