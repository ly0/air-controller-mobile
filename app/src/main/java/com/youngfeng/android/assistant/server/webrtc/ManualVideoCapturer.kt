package com.youngfeng.android.assistant.server.webrtc

import android.graphics.Bitmap
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import timber.log.Timber

/**
 * Manual video capturer that accepts frames from ImageReader
 * Converts Bitmap to VideoFrame and pushes to WebRTC
 */
class ManualVideoCapturer : VideoCapturer {
    private var capturerObserver: CapturerObserver? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var isRunning = false

    // Reusable buffer to avoid allocation on every frame
    private var pixelBuffer: IntArray? = null

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        context: android.content.Context?,
        capturerObserver: CapturerObserver?
    ) {
        this.surfaceTextureHelper = surfaceTextureHelper
        this.capturerObserver = capturerObserver
        Timber.d("ManualVideoCapturer: Initialized")
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        isRunning = true
        Timber.d("ManualVideoCapturer: Started ($width x $height @ ${framerate}fps)")
    }

    override fun stopCapture() {
        isRunning = false
        Timber.d("ManualVideoCapturer: Stopped")
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        Timber.d("ManualVideoCapturer: Format changed to $width x $height @ ${framerate}fps")
    }

    override fun dispose() {
        isRunning = false
        capturerObserver = null
        surfaceTextureHelper = null
        Timber.d("ManualVideoCapturer: Disposed")
    }

    override fun isScreencast(): Boolean = true

    /**
     * Push a frame to WebRTC
     * Called from ImageReader callback with captured bitmap
     */
    fun pushFrame(bitmap: Bitmap, timestampNs: Long) {
        if (!isRunning || capturerObserver == null) {
            return
        }

        try {
            // Downscale for better performance (50% resolution = 4x faster)
            val targetWidth = bitmap.width / 2
            val targetHeight = bitmap.height / 2

            // Scale down bitmap for WebRTC
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, false)

            try {
                // Reuse pixel buffer to avoid allocation
                val pixelCount = targetWidth * targetHeight
                if (pixelBuffer == null || pixelBuffer!!.size != pixelCount) {
                    pixelBuffer = IntArray(pixelCount)
                }
                val pixels = pixelBuffer!!

                // Get ARGB pixels from scaled bitmap
                scaledBitmap.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)

                // Create I420 buffer
                val i420Buffer = org.webrtc.JavaI420Buffer.allocate(targetWidth, targetHeight)
                val dataY = i420Buffer.dataY
                val dataU = i420Buffer.dataU
                val dataV = i420Buffer.dataV
                val strideY = i420Buffer.strideY
                val strideU = i420Buffer.strideU
                val strideV = i420Buffer.strideV

                // Optimized ARGB to I420 conversion
                // Process in 2x2 blocks for chroma subsampling
                for (y in 0 until targetHeight step 2) {
                    val y1Offset = y * targetWidth
                    val y2Offset = (y + 1) * targetWidth

                    for (x in 0 until targetWidth step 2) {
                        // Process 2x2 block
                        val argb1 = pixels[y1Offset + x]
                        val argb2 = pixels[y1Offset + x + 1]
                        val argb3 = pixels[y2Offset + x]
                        val argb4 = pixels[y2Offset + x + 1]

                        // Extract RGB components for all 4 pixels
                        val r1 = (argb1 shr 16) and 0xFF
                        val g1 = (argb1 shr 8) and 0xFF
                        val b1 = argb1 and 0xFF

                        val r2 = (argb2 shr 16) and 0xFF
                        val g2 = (argb2 shr 8) and 0xFF
                        val b2 = argb2 and 0xFF

                        val r3 = (argb3 shr 16) and 0xFF
                        val g3 = (argb3 shr 8) and 0xFF
                        val b3 = argb3 and 0xFF

                        val r4 = (argb4 shr 16) and 0xFF
                        val g4 = (argb4 shr 8) and 0xFF
                        val b4 = argb4 and 0xFF

                        // Simplified RGB to Y conversion (faster approximation)
                        dataY.put(y * strideY + x, ((r1 + (g1 shl 1) + b1) shr 2).toByte())
                        dataY.put(y * strideY + x + 1, ((r2 + (g2 shl 1) + b2) shr 2).toByte())
                        dataY.put((y + 1) * strideY + x, ((r3 + (g3 shl 1) + b3) shr 2).toByte())
                        dataY.put((y + 1) * strideY + x + 1, ((r4 + (g4 shl 1) + b4) shr 2).toByte())

                        // Average chroma for 2x2 block
                        val avgR = (r1 + r2 + r3 + r4) shr 2
                        val avgG = (g1 + g2 + g3 + g4) shr 2
                        val avgB = (b1 + b2 + b3 + b4) shr 2

                        val uvY = y / 2
                        val uvX = x / 2
                        dataU.put(uvY * strideU + uvX, ((128 - avgR + avgB) shr 1).toByte())
                        dataV.put(uvY * strideV + uvX, ((128 + avgR - avgG) shr 1).toByte())
                    }
                }

                // Create VideoFrame
                val videoFrame = VideoFrame(i420Buffer, 0, timestampNs)

                // Push to observer
                capturerObserver?.onFrameCaptured(videoFrame)

                // Release resources
                videoFrame.release()
            } finally {
                // Recycle scaled bitmap
                scaledBitmap.recycle()
            }
        } catch (e: Exception) {
            Timber.e(e, "ManualVideoCapturer: Error pushing frame")
        }
    }
}
