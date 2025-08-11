package com.youngfeng.android.assistant.server.routing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Environment
import android.text.TextUtils
import androidx.core.content.ContextCompat
import com.youngfeng.android.assistant.app.AirControllerApp
import com.youngfeng.android.assistant.db.RoomDatabaseHolder
import com.youngfeng.android.assistant.db.entity.UploadFileRecord
import com.youngfeng.android.assistant.server.HttpError
import com.youngfeng.android.assistant.server.HttpModule
import com.youngfeng.android.assistant.server.entity.FileEntity
import com.youngfeng.android.assistant.server.entity.HttpResponseEntity
import com.youngfeng.android.assistant.server.request.*
import com.youngfeng.android.assistant.server.util.ErrorBuilder
import com.youngfeng.android.assistant.util.MD5Helper
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import timber.log.Timber
import java.io.File
import java.util.Locale

fun Route.configureFileRoutes(context: Context) {
    val mContext = AirControllerApp.getInstance()

    route("/file") {
        post("/list") {
            val languageCode = call.request.header("languageCode")
            val locale = if (!TextUtils.isEmpty(languageCode)) Locale(languageCode!!) else Locale("en")

            // 检查存储权限
            if (ContextCompat.checkSelfPermission(
                    mContext,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                call.respond(
                    ErrorBuilder().locale(locale).module(HttpModule.FileModule)
                        .error(HttpError.NoReadExternalStoragePerm).build<List<FileEntity>>()
                )
                return@post
            }

            val requestBody = call.receive<GetFileListRequest>()
            var path = requestBody.path
            if (TextUtils.isEmpty(path)) {
                path = Environment.getExternalStorageDirectory().absolutePath
            }

            val dir = File(path)

            if (!dir.isDirectory) {
                call.respond(
                    ErrorBuilder().locale(locale).module(HttpModule.FileModule)
                        .error(HttpError.FileIsNotADir).build<List<FileEntity>>()
                )
                return@post
            }

            val files = dir.listFiles()

            var data = mutableListOf<FileEntity>()
            files?.forEach {
                val fileEntity = FileEntity(
                    it.name,
                    it.parentFile?.absolutePath ?: "",
                    if (it.isFile) it.length() else 0,
                    it.isDirectory,
                    it.lastModified(),
                    it.listFiles()?.isEmpty() ?: false
                )
                data.add(fileEntity)
            }

            data = data.sortedWith(object : Comparator<FileEntity> {
                override fun compare(a: FileEntity, b: FileEntity): Int {
                    if (a.isDir && !b.isDir) {
                        return -1
                    }
                    if (!a.isDir && b.isDir) {
                        return 1
                    }
                    return a.name.lowercase().compareTo(b.name.lowercase())
                }
            }).toMutableList()

            call.respond(HttpResponseEntity.success(data))
        }

        post("/create") {
            val requestBody = call.receive<CreateFileRequest>()
            val languageCode = call.request.header("languageCode")
            val locale = if (!TextUtils.isEmpty(languageCode)) Locale(languageCode!!) else Locale("en")

            val folder: String = requestBody.folder
            val name: String = requestBody.name
            val file = File(folder, name)

            try {
                if (requestBody.type == CreateFileRequest.TYPE_DIR) {
                    if (file.exists()) {
                        call.respond(
                            ErrorBuilder().locale(locale).module(HttpModule.FileModule)
                                .error(HttpError.FolderCantEmpty).build<Any>()
                        )
                        return@post
                    }
                    file.mkdirs()
                } else {
                    if (file.exists()) {
                        call.respond(
                            ErrorBuilder().locale(locale).module(HttpModule.FileModule)
                                .error(HttpError.CreateFileFail).build<Any>()
                        )
                        return@post
                    }
                    file.createNewFile()
                }
                call.respond(HttpResponseEntity.success<Any>())
            } catch (e: Exception) {
                Timber.e("Create file/dir failure: ${e.message}")
                call.respond(
                    ErrorBuilder().locale(locale).module(HttpModule.FileModule)
                        .error(HttpError.CreateFileFail).build<Any>()
                )
            }
        }

        post("/delete") {
            val requestBody = call.receive<DeleteFileRequest>()
            val languageCode = call.request.header("languageCode")
            val locale = if (!TextUtils.isEmpty(languageCode)) Locale(languageCode!!) else Locale("en")

            try {
                val file = File(requestBody.file)
                if (file.exists()) {
                    file.deleteRecursively()
                }
                call.respond(HttpResponseEntity.success<Any>())
            } catch (e: Exception) {
                Timber.e("Delete files failure: ${e.message}")
                call.respond(
                    ErrorBuilder().locale(locale).module(HttpModule.FileModule)
                        .error(HttpError.DeleteFileFail).build<Any>()
                )
            }
        }

        post("/rename") {
            val requestBody = call.receive<RenameFileRequest>()
            val languageCode = call.request.header("languageCode")
            val locale = if (!TextUtils.isEmpty(languageCode)) Locale(languageCode!!) else Locale("en")

            val folder: String = requestBody.folder
            val fileName: String = requestBody.file
            val oldFile = File(folder, fileName)
            val newFile = File(folder, requestBody.newName)

            try {
                if (newFile.exists()) {
                    call.respond(
                        ErrorBuilder().locale(locale).module(HttpModule.FileModule)
                            .error(HttpError.CreateFileFail).build<Any>()
                    )
                    return@post
                }

                if (oldFile.renameTo(newFile)) {
                    // 通知媒体扫描器
                    MediaScannerConnection.scanFile(
                        mContext,
                        arrayOf(oldFile.absolutePath, newFile.absolutePath),
                        null,
                        null
                    )
                    call.respond(HttpResponseEntity.success<Any>())
                } else {
                    call.respond(
                        ErrorBuilder().locale(locale).module(HttpModule.FileModule)
                            .error(HttpError.RenameFileFail).build<Any>()
                    )
                }
            } catch (e: Exception) {
                Timber.e("Rename file failure: ${e.message}")
                call.respond(
                    ErrorBuilder().locale(locale).module(HttpModule.FileModule)
                        .error(HttpError.RenameFileFail).build<Any>()
                )
            }
        }

        post("/move") {
            val requestBody = call.receive<MoveFileRequest>()
            val languageCode = call.request.header("languageCode")
            val locale = if (!TextUtils.isEmpty(languageCode)) Locale(languageCode!!) else Locale("en")

            try {
                val srcFile = File(requestBody.oldFolder, requestBody.fileName)
                val destFile = File(requestBody.newFolder, requestBody.fileName)

                if (srcFile.isDirectory) {
                    srcFile.copyRecursively(destFile, overwrite = false)
                    srcFile.deleteRecursively()
                } else {
                    srcFile.copyTo(destFile, overwrite = false)
                    srcFile.delete()
                }
                call.respond(HttpResponseEntity.success<Any>())
            } catch (e: Exception) {
                Timber.e("Move files failure: ${e.message}")
                call.respond(
                    ErrorBuilder().locale(locale).module(HttpModule.FileModule)
                        .error(HttpError.MoveFileFail).build<Any>()
                )
            }
        }

        post("/deleteMulti") {
            val requestBody = call.receive<DeleteMultiFileRequest>()
            val languageCode = call.request.header("languageCode")
            val locale = if (!TextUtils.isEmpty(languageCode)) Locale(languageCode!!) else Locale("en")

            try {
                val resultMap = HashMap<String, String>()
                val fileList = ArrayList<String>()
                var isAllSuccess = true

                requestBody.paths.forEach { filePath ->
                    val file = File(filePath)
                    fileList.add(file.absolutePath)

                    if (!file.exists()) {
                        isAllSuccess = false
                        resultMap[filePath] = mContext.getString(HttpError.FileNotExist.value)
                    } else {
                        val isSuccess = file.deleteRecursively()
                        if (!isSuccess) {
                            isAllSuccess = false
                            resultMap[filePath] = mContext.getString(HttpError.DeleteFileFail.value)
                        }
                    }
                }

                // 通知媒体扫描器
                MediaScannerConnection.scanFile(
                    mContext,
                    fileList.toTypedArray(),
                    null,
                    null
                )

                if (isAllSuccess) {
                    call.respond(HttpResponseEntity.success<Any>())
                } else {
                    val response = ErrorBuilder().locale(locale).module(HttpModule.FileModule)
                        .error(HttpError.DeleteFileFail).build<Any>()
                    call.respond(response)
                }
            } catch (e: Exception) {
                Timber.e("Delete multi files failure: ${e.message}")
                call.respond(
                    ErrorBuilder().locale(locale).module(HttpModule.FileModule)
                        .error(HttpError.DeleteFileFail).build<Any>()
                )
            }
        }

        post("/downloadedFiles") {
            val languageCode = call.request.header("languageCode")
            val locale = if (!TextUtils.isEmpty(languageCode)) Locale(languageCode!!) else Locale("en")

            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

            if (downloadDir == null) {
                call.respond(
                    ErrorBuilder().locale(locale).module(HttpModule.Download)
                        .error(HttpError.GetDownloadDirFail).build<List<FileEntity>>()
                )
                return@post
            }

            if (!downloadDir.exists()) {
                call.respond(
                    ErrorBuilder().locale(locale).module(HttpModule.Download)
                        .error(HttpError.DownloadDirNotExist).build<List<FileEntity>>()
                )
                return@post
            }

            var data = downloadDir.listFiles()?.map {
                FileEntity(
                    isDir = it.isDirectory,
                    name = it.name,
                    folder = downloadDir.absolutePath,
                    size = if (it.isFile) it.length() else it.totalSpace,
                    changeDate = it.lastModified(),
                    isEmpty = if (it.isDirectory) it.listFiles()?.isEmpty() ?: true else true
                )
            }

            data = data?.sortedWith(object : Comparator<FileEntity> {
                override fun compare(a: FileEntity, b: FileEntity): Int {
                    if (a.isDir && !b.isDir) {
                        return -1
                    }
                    if (!a.isDir && b.isDir) {
                        return 1
                    }
                    return a.name.lowercase().compareTo(b.name.lowercase())
                }
            })

            call.respond(HttpResponseEntity.success(data))
        }

        post("/uploadFiles") {
            val multipart = call.receiveMultipart()
            val languageCode = call.request.header("languageCode")
            val locale = if (!TextUtils.isEmpty(languageCode)) Locale(languageCode!!) else Locale("en")

            try {
                var targetPath: String? = null
                val uploadedFiles = mutableListOf<String>()
                val uploadFileRecords = mutableListOf<UploadFileRecord>()

                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FileItem -> {
                            if (targetPath != null) {
                                val fileName = part.originalFileName ?: "uploaded_file_${System.currentTimeMillis()}"
                                val file = File(targetPath, fileName)
                                part.streamProvider().use { input ->
                                    file.outputStream().buffered().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                uploadedFiles.add(file.absolutePath)

                                // 创建上传文件记录
                                val uploadFileRecord = UploadFileRecord(
                                    id = 0,
                                    name = fileName,
                                    path = file.absolutePath,
                                    size = file.length(),
                                    uploadTime = System.currentTimeMillis(),
                                    md5 = MD5Helper.md5(file)
                                )
                                uploadFileRecords.add(uploadFileRecord)
                            }
                        }
                        is PartData.FormItem -> {
                            if (part.name == "path") {
                                targetPath = part.value
                            }
                        }
                        else -> {}
                    }
                    part.dispose()
                }

                // 保存上传文件记录到数据库
                if (uploadFileRecords.isNotEmpty()) {
                    val db = RoomDatabaseHolder.getRoomDatabase(mContext)
                    val uploadFileRecordDao = db.uploadFileRecordDao()
                    uploadFileRecords.forEach { record ->
                        uploadFileRecordDao.insert(record)
                    }
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
                Timber.e("Upload files failure: ${e.message}")
                val response = ErrorBuilder().locale(locale).module(HttpModule.FileModule)
                    .error(HttpError.UploadFilesFailure).build<Any>()
                call.respond(response)
            }
        }

        post("/upload") {
            val multipart = call.receiveMultipart()
            var targetPath: String? = null
            val uploadedFiles = mutableListOf<String>()

            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        if (targetPath != null) {
                            val fileName = part.originalFileName ?: "uploaded_file"
                            val file = File(targetPath, fileName)
                            part.streamProvider().use { input ->
                                file.outputStream().buffered().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            uploadedFiles.add(file.absolutePath)
                        }
                    }
                    is PartData.FormItem -> {
                        if (part.name == "path") {
                            targetPath = part.value
                        }
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
        }
    }
}
