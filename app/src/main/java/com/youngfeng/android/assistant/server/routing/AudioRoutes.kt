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
import com.youngfeng.android.assistant.server.request.DeleteAudioRequest
import com.youngfeng.android.assistant.server.util.ErrorBuilder
import com.youngfeng.android.assistant.util.AudioUtil
import com.youngfeng.android.assistant.util.PathHelper
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import timber.log.Timber
import java.io.File
import java.util.Locale

fun Route.configureAudioRoutes(context: Context) {
    val mContext = AirControllerApp.getInstance()

    route("/audio") {
        post("/all") {
            val audios = AudioUtil.getAllAudios(mContext)
            call.respond(HttpResponseEntity.success(audios))
        }

        // Unregistered delete route - code preserved
        /*
        post("/delete") {
            val request = call.receive<DeleteAudioRequest>()
            val languageCode = call.request.header("languageCode")
            val locale = if (!TextUtils.isEmpty(languageCode)) Locale(languageCode!!) else Locale("en")

            try {
                val paths = request.paths

                for (path in paths) {
                    val audioFile = File(path)

                    val isSuccess = audioFile.delete()
                    if (!isSuccess) {
                        val response = ErrorBuilder().locale(locale).module(HttpModule.AudioModule)
                            .error(HttpError.DeleteAudioFail).build<Any>()
                        response.msg = mContext.getString(locale, R.string.delete_audio_file_fail)
                            .replace("%s", audioFile.absolutePath)
                        call.respond(response)
                        return@post
                    } else {
                        MediaScannerConnection.scanFile(
                            mContext,
                            arrayOf(audioFile.absolutePath),
                            null,
                            null
                        )
                    }
                }

                call.respond(HttpResponseEntity.success<Any>())
            } catch (e: Exception) {
                Timber.e("Delete audio error: ${e.message}")
                val response = ErrorBuilder().locale(locale).module(HttpModule.AudioModule)
                    .error(HttpError.DeleteAudioFail).build<Any>()
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
                            val fileName = part.originalFileName ?: "${System.currentTimeMillis()}.mp3"
                            val uploadDir = PathHelper.audioUploadDir()
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
                    null,
                    null
                )

                call.respond(HttpResponseEntity.success<Any>())
            } catch (e: Exception) {
                Timber.e("Upload audio error: ${e.message}")
                val response = ErrorBuilder().locale(locale).module(HttpModule.AudioModule)
                    .error(HttpError.UploadAudioFail).build<Any>()
                call.respond(response)
            }
        }

        get("/stream/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest)

            val audio = AudioUtil.getAudioById(mContext, id)
            if (audio != null) {
                val file = File(audio.path)
                if (file.exists()) {
                    call.response.header(HttpHeaders.ContentType, ContentType.Audio.Any.toString())
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")

                    val range = call.request.header(HttpHeaders.Range)
                    if (range != null) {
                        // Range request handling
                        val fileLength = file.length()
                        val rangeRegex = "bytes=(\\d*)-(\\d*)".toRegex()
                        val matchResult = rangeRegex.find(range)

                        if (matchResult != null) {
                            val (startStr, endStr) = matchResult.destructured
                            val start = if (startStr.isEmpty()) 0L else startStr.toLongOrNull() ?: 0L
                            val end = if (endStr.isEmpty()) fileLength - 1 else endStr.toLongOrNull() ?: (fileLength - 1)

                            if (start >= 0 && end < fileLength && start <= end) {
                                val contentLength = end - start + 1

                                call.response.status(HttpStatusCode.PartialContent)
                                call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$fileLength")
                                call.response.header(HttpHeaders.ContentLength, contentLength.toString())

                                call.respondFile(file)
                            } else {
                                call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
                            }
                        } else {
                            call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
                        }
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

        // Alias route: /audio/item/{id} - same implementation as /audio/stream/{id}
        get("/item/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest)

            val audio = AudioUtil.getAudioById(mContext, id)
            if (audio != null) {
                val file = File(audio.path)
                if (file.exists()) {
                    call.response.header(HttpHeaders.ContentType, ContentType.Audio.Any.toString())
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")

                    val range = call.request.header(HttpHeaders.Range)
                    if (range != null) {
                        // Range request handling
                        val fileLength = file.length()
                        val rangeRegex = "bytes=(\\d*)-(\\d*)".toRegex()
                        val matchResult = rangeRegex.find(range)

                        if (matchResult != null) {
                            val (startStr, endStr) = matchResult.destructured
                            val start = if (startStr.isEmpty()) 0L else startStr.toLongOrNull() ?: 0L
                            val end = if (endStr.isEmpty()) fileLength - 1 else endStr.toLongOrNull() ?: (fileLength - 1)

                            if (start >= 0 && end < fileLength && start <= end) {
                                val contentLength = end - start + 1

                                call.response.status(HttpStatusCode.PartialContent)
                                call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$fileLength")
                                call.response.header(HttpHeaders.ContentLength, contentLength.toString())

                                call.respondFile(file)
                            } else {
                                call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
                            }
                        } else {
                            call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
                        }
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

        // Alias route: /audio/uploadAudios - same implementation as /audio/upload
        post("/uploadAudios") {
            val multipart = call.receiveMultipart()
            val languageCode = call.request.header("languageCode")
            val locale = if (!TextUtils.isEmpty(languageCode)) Locale(languageCode!!) else Locale("en")

            try {
                val uploadedFiles = mutableListOf<String>()

                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FileItem -> {
                            val fileName = part.originalFileName ?: "${System.currentTimeMillis()}.mp3"
                            val uploadDir = PathHelper.audioUploadDir()
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
                    null,
                    null
                )

                call.respond(HttpResponseEntity.success<Any>())
            } catch (e: Exception) {
                Timber.e("Upload audio error: ${e.message}")
                val response = ErrorBuilder().locale(locale).module(HttpModule.AudioModule)
                    .error(HttpError.UploadAudioFail).build<Any>()
                call.respond(response)
            }
        }
    }
}
