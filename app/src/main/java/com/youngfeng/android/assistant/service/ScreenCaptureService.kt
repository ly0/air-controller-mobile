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

            // Reduce resolution for better performance
            val scaleFactor = 2
            val scaledWidth = width / scaleFactor
            val scaledHeight = height / scaleFactor

            imageReader = ImageReader.newInstance(
                scaledWidth,
                scaledHeight,
                PixelFormat.RGBA_8888,
                2
            )

            // Create VirtualDisplay
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                scaledWidth,
                scaledHeight,
                density / scaleFactor,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                imageReader!!.surface,
                null,
                null
            )

            // Initialize ScreenStreamManager with input controller
            ScreenStreamManager.initInputController(this, width, height, scaleFactor)
            Timber.d("ScreenStreamManager initialized with dimensions: ${width}x${height}, scale: $scaleFactor")

            // Start capturing frames
            startFrameCapture()

            Timber.d("Screen capture started successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start screen capture")
            stopCapture()
        }
    }

    private fun startFrameCapture() {
        captureJob?.cancel()
        captureJob = serviceScope.launch {
            while (isActive) {
                try {
                    captureAndSendFrame()
                    delay(50) // ~20 FPS
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
                val jpegData = bitmapToJpeg(bitmap, 70)

                // Broadcast frame to all connected WebSocket clients via ScreenStreamManager
                ScreenStreamManager.broadcastFrame(jpegData)
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

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        // Crop the bitmap to remove padding
        return if (rowPadding > 0) {
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        } else {
            bitmap
        }
    }

    private fun bitmapToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        return outputStream.toByteArray()
    }

    private fun stopCapture() {
        captureJob?.cancel()
        captureJob = null

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        mediaProjection?.stop()
        mediaProjection = null

        // Clean up ScreenStreamManager
        ScreenStreamManager.cleanup()

        Timber.d("Screen capture stopped")
    }

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
}