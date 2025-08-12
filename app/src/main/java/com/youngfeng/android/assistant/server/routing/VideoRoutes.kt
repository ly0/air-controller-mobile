package com.youngfeng.android.assistant.server.routing

import android.content.Context
import android.media.MediaScannerConnection
import android.text.TextUtils
import com.youngfeng.android.assistant.R
import com.youngfeng.android.assistant.app.AirControllerApp
import com.youngfeng.android.assistant.ext.getString
import com.youngfeng.android.assistant.server.HttpError
import com.youngfeng.android.assistant.server.HttpModule
import com.youngfeng.android.assistant.server.entity.HttpResponseEntity
import com.youngfeng.android.assistant.server.request.DeleteVideosRequest
import com.youngfeng.android.assistant.server.request.GetVideosRequest
import com.youngfeng.android.assistant.server.util.ErrorBuilder
import com.youngfeng.android.assistant.util.PathHelper
import com.youngfeng.android.assistant.util.VideoUtil
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import timber.log.Timber
import java.io.File
import java.util.Locale

fun Route.configureVideoRoutes(context: Context) {
    val mContext = AirControllerApp.getInstance()

    route("/video") {
        post("/folders") {
            val videoFolders = VideoUtil.getAllVideoFolders(mContext)
            call.respond(HttpResponseEntity.success(videoFolders))
        }

        post("/videosInFolder") {
            val request = call.receive<GetVideosRequest>()
            val videos = VideoUtil.getVideosByFolderId(mContext, request.folderId)
            call.respond(HttpResponseEntity.success(videos))
        }

        post("/videos") {
            val videos = VideoUtil.getAllVideos(mContext)
            call.respond(HttpResponseEntity.success(videos))
        }

        // Unregistered delete route - code preserved
        /*
        post("/delete") {
            val request = call.receive<DeleteVideosRequest>()
            val languageCode = call.request.header("languageCode")
            val locale = if (!TextUtils.isEmpty(languageCode)) Locale(languageCode!!) else Locale("en")

            try {
                val paths = request.paths
                val deletedFiles = mutableListOf<String>()
                var deleteItemNum = 0

                paths.forEach { path ->
                    val file = File(path)
                    if (!file.exists()) {
                        val response = ErrorBuilder().locale(locale).module(HttpModule.VideoModule)
                            .error(HttpError.DeleteVideoFail).build<Any>()
                        response.msg = convertToDeleteVideoError(locale, paths.size, deleteItemNum, mContext)
                        call.respond(response)
                        return@post
                    } else {
                        val isSuccess = file.delete()
                        if (!isSuccess) {
                            val response = ErrorBuilder().locale(locale).module(HttpModule.VideoModule)
                                .error(HttpError.DeleteVideoFail).build<Any>()
                            response.msg = convertToDeleteVideoError(locale, paths.size, deleteItemNum, mContext)
                            call.respond(response)
                            return@post
                        }
                        deleteItemNum++
                        deletedFiles.add(file.absolutePath)
                    }
                }

                // 通知媒体扫描器
                MediaScannerConnection.scanFile(
                    mContext,
                    deletedFiles.toTypedArray(),
                    null
                ) { _, _ -> }

                call.respond(HttpResponseEntity.success<Any>())
            } catch (e: Exception) {
                Timber.e("Delete videos error: ${e.message}")
                val response = ErrorBuilder().locale(locale).module(HttpModule.VideoModule)
                    .error(HttpError.DeleteVideoFail).build<Any>()
                call.respond(response)
            }
        }
        */

        post("/upload") {
            val multipart = call.receiveMultipart()
            val languageCode = call.request.header("languageCode")
            val locale = if (!TextUtils.isEmpty(languageCode)) Locale(languageCode!!) else Locale("en")

            try {
                val uploadedFiles = mutableListOf<String>()

                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FileItem -> {
                            val fileName = part.originalFileName ?: "${System.currentTimeMillis()}.mp4"
                            val uploadDir = PathHelper.videoUploadDir()
                            val file = File(uploadDir, fileName)

                            part.streamProvider().use { input ->
                                file.outputStream().buffered().use { output ->
                                    input.copyTo(output)
                                }
                            }

                            uploadedFiles.add(file.absolutePath)
                        }
                        else -> {}
                    }
                    part.dispose()
                }

                // 通知媒体扫描器
                MediaScannerConnection.scanFile(
                    mContext,
                    uploadedFiles.toTypedArray(),
                    null
                ) { _, _ -> }

                call.respond(HttpResponseEntity.success<Any>())
            } catch (e: Exception) {
                Timber.e("Upload video error: ${e.message}")
                val response = ErrorBuilder().locale(locale).module(HttpModule.VideoModule)
                    .error(HttpError.UploadVideoFailure).build<Any>()
                call.respond(response)
            }
        }

        get("/stream/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest)

            val video = VideoUtil.getVideoById(mContext, id)
            if (video != null) {
                val file = File(video.path)
                if (file.exists()) {
                    call.response.header(HttpHeaders.ContentType, ContentType.Video.Any.toString())
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")

                    val range = call.request.header(HttpHeaders.Range)
                    if (range != null) {
                        handleRangeRequest(call, file, range)
                    } else {
                        call.respondFile(file)
                    }
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        // Alias route: /video/item/{id} - same implementation as /video/stream/{id}
        get("/item/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest)

            val video = VideoUtil.getVideoById(mContext, id)
            if (video != null) {
                val file = File(video.path)
                if (file.exists()) {
                    call.response.header(HttpHeaders.ContentType, ContentType.Video.Any.toString())
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")

                    val range = call.request.header(HttpHeaders.Range)
                    if (range != null) {
                        handleRangeRequest(call, file, range)
                    } else {
                        call.respondFile(file)
                    }
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        // Alias route: /video/uploadVideos - same implementation as /video/upload
        post("/uploadVideos") {
            val multipart = call.receiveMultipart()
            val languageCode = call.request.header("languageCode")
            val locale = if (!TextUtils.isEmpty(languageCode)) Locale(languageCode!!) else Locale("en")

            try {
                val uploadedFiles = mutableListOf<String>()

                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FileItem -> {
                            val fileName = part.originalFileName ?: "${System.currentTimeMillis()}.mp4"
                            val uploadDir = PathHelper.videoUploadDir()
                            val file = File(uploadDir, fileName)

                            part.streamProvider().use { input ->
                                file.outputStream().buffered().use { output ->
                                    input.copyTo(output)
                                }
                            }

                            uploadedFiles.add(file.absolutePath)
                        }
                        else -> {}
                    }
                    part.dispose()
                }

                // 通知媒体扫描器
                MediaScannerConnection.scanFile(
                    mContext,
                    uploadedFiles.toTypedArray(),
                    null
                ) { _, _ -> }

                call.respond(HttpResponseEntity.success<Any>())
            } catch (e: Exception) {
                Timber.e("Upload video error: ${e.message}")
                val response = ErrorBuilder().locale(locale).module(HttpModule.VideoModule)
                    .error(HttpError.UploadVideoFailure).build<Any>()
                call.respond(response)
            }
        }
    }
}

private fun convertToDeleteVideoError(locale: Locale, totalCount: Int, deletedCount: Int, context: Context): String {
    return if (totalCount == 1) {
        context.getString(locale, R.string.delete_video_file_fail)
    } else {
        val failedCount = totalCount - deletedCount
        context.getString(locale, R.string.delete_video_file_fail)
    }
}

// The handleRangeRequest function is already defined in StreamRoutes.kt
// We can make it internal and reuse it
private suspend fun handleRangeRequest(call: ApplicationCall, file: File, rangeHeader: String) {
    // This function is already implemented in StreamRoutes.kt
    // In a real implementation, we would extract this to a shared utility
    // For now, we'll duplicate the logic
    val fileLength = file.length()
    val rangeRegex = "bytes=(\\d*)-(\\d*)".toRegex()
    val matchResult = rangeRegex.find(rangeHeader)

    if (matchResult == null) {
        call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
        return
    }

    val (startStr, endStr) = matchResult.destructured
    val start = if (startStr.isEmpty()) 0L else startStr.toLongOrNull() ?: 0L
    val end = if (endStr.isEmpty()) fileLength - 1 else endStr.toLongOrNull() ?: (fileLength - 1)

    if (start < 0 || end >= fileLength || start > end) {
        call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
        return
    }

    val contentLength = end - start + 1

    call.response.status(HttpStatusCode.PartialContent)
    call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$fileLength")
    call.response.header(HttpHeaders.ContentLength, contentLength.toString())

    call.respondFile(file)
}
