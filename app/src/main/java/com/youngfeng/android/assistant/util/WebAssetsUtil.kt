package com.youngfeng.android.assistant.util

import android.content.Context
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

object WebAssetsUtil {

    /**
     * Copy web assets from assets folder to cache directory
     */
    fun copyWebAssetsToCache(context: Context) {
        try {
            val webCacheDir = File(context.cacheDir, "web")
            if (!webCacheDir.exists()) {
                webCacheDir.mkdirs()
            }

            // Copy all files from assets/web to cache/web
            copyAssetFolder(context, "web", webCacheDir)

            Timber.d("Web assets copied to cache successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to copy web assets to cache")
        }
    }

    private fun copyAssetFolder(
        context: Context,
        assetPath: String,
        targetDir: File
    ) {
        try {
            val assetManager = context.assets
            val assets = assetManager.list(assetPath)

            if (assets.isNullOrEmpty()) {
                // It's a file, not a directory
                copyAssetFile(context, assetPath, File(targetDir.parent, targetDir.name))
                return
            }

            // Make sure target directory exists
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            // Copy each file/folder
            for (asset in assets) {
                val assetFilePath = "$assetPath/$asset"
                val targetFile = File(targetDir, asset)

                // Check if it's a directory or file
                val subAssets = assetManager.list(assetFilePath)
                if (!subAssets.isNullOrEmpty()) {
                    // It's a directory, recursively copy
                    copyAssetFolder(context, assetFilePath, targetFile)
                } else {
                    // It's a file, copy it
                    copyAssetFile(context, assetFilePath, targetFile)
                }
            }
        } catch (e: IOException) {
            Timber.e(e, "Failed to copy asset folder: $assetPath")
        }
    }

    private fun copyAssetFile(
        context: Context,
        assetPath: String,
        targetFile: File
    ) {
        try {
            // Check if file already exists and has content
            if (targetFile.exists() && targetFile.length() > 0) {
                // File already copied, skip
                return
            }

            val inputStream: InputStream = context.assets.open(assetPath)
            val outputStream = FileOutputStream(targetFile)

            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output, bufferSize = 1024)
                }
            }

            Timber.d("Copied asset file: $assetPath to ${targetFile.absolutePath}")
        } catch (e: IOException) {
            Timber.e(e, "Failed to copy asset file: $assetPath")
        }
    }

    /**
     * Clear web cache directory
     */
    fun clearWebCache(context: Context) {
        try {
            val webCacheDir = File(context.cacheDir, "web")
            if (webCacheDir.exists()) {
                webCacheDir.deleteRecursively()
                Timber.d("Web cache cleared")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to clear web cache")
        }
    }
}