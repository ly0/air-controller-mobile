package com.youngfeng.android.assistant.server.routing

import android.content.Context
import android.media.MediaScannerConnection
import android.text.TextUtils
import com.youngfeng.android.assistant.app.AirControllerApp
import com.youngfeng.android.assistant.server.HttpError
import com.youngfeng.android.assistant.server.HttpModule
import com.youngfeng.android.assistant.server.entity.*
import com.youngfeng.android.assistant.server.request.*
import com.youngfeng.android.assistant.server.util.ErrorBuilder
import com.youngfeng.android.assistant.util.PathHelper
import com.youngfeng.android.assistant.util.PhotoUtil
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import timber.log.Timber
import java.io.File
import java.util.Locale

fun Route.configureImageRoutes(context: Context) {
    val mContext = AirControllerApp.getInstance()

    route("/image") {
        post("/albums") {
            val albums = PhotoUtil.getAllAlbums(mContext)
            call.respond(HttpResponseEntity.success(albums))
        }

        post("/all") {
            val images = PhotoUtil.getAllImages(mContext)
            call.respond(HttpResponseEntity.success(images))
        }

        post("/daily") {
            call.respond(
                HttpStatusCode.NotImplemented,
                HttpResponseEntity<List<DailyImageEntity>>(
                    code = HttpStatusCode.NotImplemented.value,
                    data = null,
                    msg = "Not implemented"
                )
            )
        }

        post("/monthly") {
            call.respond(
                HttpStatusCode.NotImplemented,
                HttpResponseEntity<List<MonthlyImageEntity>>(
                    code = HttpStatusCode.NotImplemented.value,
                    data = null,
                    msg = "Not implemented"
                )
            )
        }

        post("/albumImages") {
            val images = PhotoUtil.getAlbumImages(mContext)
            call.respond(HttpResponseEntity.success(images))
        }

        // Unregistered delete route - code preserved
        /*
        post("/delete") {
            val request = call.receive<DeleteImageRequest>()
            val languageCode = call.request.header("languageCode")
            val locale = if (!TextUtils.isEmpty(languageCode)) Locale(languageCode!!) else Locale("en")

            try {
                val resultMap = HashMap<String, String>()
                val imageFiles = ArrayList<String>()
                var isAllSuccess = true

                request.paths.forEach { imgPath ->
                    val imageFile = File(imgPath)
                    imageFiles.add(imageFile.absolutePath)

                    if (!imageFile.exists()) {
                        isAllSuccess = false
                        resultMap[imgPath] = mContext.getString(locale, HttpError.ImageFileNotExist.value)
                    } else {
                        val isSuccess = imageFile.delete()
                        if (!isSuccess) {
                            isAllSuccess = false
                            resultMap[imgPath] = mContext.getString(locale, HttpError.DeleteImageFail.value)
                        }
                    }
                }

                // 通知媒体扫描器
                MediaScannerConnection.scanFile(
                    mContext,
                    imageFiles.toTypedArray(),
                    null
                ) { _, _ -> }

                if (isAllSuccess) {
                    call.respond(HttpResponseEntity.success<Any>())
                } else {
                    val response = ErrorBuilder().locale(locale).module(HttpModule.ImageModule)
                        .error(HttpError.DeleteImageFail).build<Any>()
                    call.respond(response)
                }
            } catch (e: Exception) {
                Timber.e("Delete image error: ${e.message}")
                val response = ErrorBuilder().locale(locale).module(HttpModule.ImageModule)
                    .error(HttpError.DeleteImageFail).build<Any>()
                call.respond(response)
            }
        }
        */

        // Unregistered deleteAlbums route - code preserved
        /*
        post("/deleteAlbums") {
            val request = call.receive<DeleteAlbumsRequest>()
            val languageCode = call.request.header("languageCode")
            val locale = if (!TextUtils.isEmpty(languageCode)) Locale(languageCode!!) else Locale("en")

            try {
                val resultMap = HashMap<String, String>()
                val imageFiles = ArrayList<String>()
                var isAllSuccess = true

                request.paths.forEach { path ->
                    val images = PhotoUtil.getImagesOfAlbum(mContext, path)
                    images.forEach { image ->
                        val imageFile = File(image.path)
                        imageFiles.add(imageFile.absolutePath)

                        if (!imageFile.exists()) {
                            isAllSuccess = false
                            resultMap[image.path] = mContext.getString(locale, HttpError.ImageFileNotExist.value)
                        } else {
                            val isSuccess = imageFile.delete()
                            if (!isSuccess) {
                                isAllSuccess = false
                                resultMap[image.path] = mContext.getString(locale, HttpError.DeleteImageFail.value)
                            }
                        }
                    }
                }

                // 通知媒体扫描器
                MediaScannerConnection.scanFile(
                    mContext,
                    imageFiles.toTypedArray(),
                    null
                ) { _, _ -> }

                if (isAllSuccess) {
                    call.respond(HttpResponseEntity.success<Any>())
                } else {
                    val response = ErrorBuilder().locale(locale).module(HttpModule.ImageModule)
                        .error(HttpError.DeleteAlbumFail).build<Any>()
                    call.respond(response)
                }
            } catch (e: Exception) {
                Timber.e("Delete albums error: ${e.message}")
                val response = ErrorBuilder().locale(locale).module(HttpModule.ImageModule)
                    .error(HttpError.DeleteAlbumFail).build<Any>()
                call.respond(response)
            }
        }
        */

        post("/imagesOfAlbum") {
            val request = call.receive<GetAlbumImagesRequest>()
            val images = PhotoUtil.getImagesOfAlbum(mContext, request.id)
            call.respond(HttpResponseEntity.success(images))
        }

        post("/uploadPhotos") {
            val multipart = call.receiveMultipart()
            val languageCode = call.request.header("languageCode")
            val locale = if (!TextUtils.isEmpty(languageCode)) Locale(languageCode!!) else Locale("en")

            try {
                val uploadedFiles = mutableListOf<String>()

                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FileItem -> {
                            val fileName = part.originalFileName ?: "${System.currentTimeMillis()}.jpg"
                            val uploadDir = PathHelper.imageUploadDir()
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
                Timber.e("Upload image error: ${e.message}")
                val response = ErrorBuilder().locale(locale).module(HttpModule.ImageModule)
                    .error(HttpError.GetPhotoDirFailure).build<Any>()
                call.respond(response)
            }
        }
    }
}
