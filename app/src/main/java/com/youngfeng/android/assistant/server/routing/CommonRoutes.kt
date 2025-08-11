package com.youngfeng.android.assistant.server.routing

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.text.TextUtils
import com.youngfeng.android.assistant.app.AirControllerApp
import com.youngfeng.android.assistant.db.RoomDatabaseHolder
import com.youngfeng.android.assistant.db.entity.UploadFileRecord
import com.youngfeng.android.assistant.event.BatchUninstallEvent
import com.youngfeng.android.assistant.model.MobileInfo
import com.youngfeng.android.assistant.server.HttpError
import com.youngfeng.android.assistant.server.HttpModule
import com.youngfeng.android.assistant.server.entity.HttpResponseEntity
import com.youngfeng.android.assistant.server.entity.InstalledAppEntity
import com.youngfeng.android.assistant.server.util.ErrorBuilder
import com.youngfeng.android.assistant.util.CommonUtil
import com.youngfeng.android.assistant.util.MD5Helper
import com.youngfeng.android.assistant.util.PathHelper
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.greenrobot.eventbus.EventBus
import timber.log.Timber
import java.io.File
import java.util.Locale

fun Route.configureCommonRoutes(context: Context) {
    val mContext = AirControllerApp.getInstance()

    route("/common") {
        post("/mobileInfo") {
            val batteryLevel = CommonUtil.getBatteryLevel(mContext)
            val storageSize = CommonUtil.getExternalStorageSize()
            val mobileInfo = MobileInfo(batteryLevel, storageSize)
            call.respond(HttpResponseEntity.success(mobileInfo))
        }

        post("/installedApps") {
            val packageManager = mContext.packageManager
            val packages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)

            val apps = mutableListOf<InstalledAppEntity>()
            packages.forEach {
                val appName = packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(it.packageName, 0)
                ).toString()
                val packageInfo = packageManager.getPackageInfo(it.packageName, 0)
                val versionName = packageInfo.versionName ?: "Unknown"
                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    packageInfo.versionCode.toLong()
                }
                val packageName = it.packageName
                val appFile = File(it.publicSourceDir)
                val size = appFile.length()
                val enable = it.enabled

                val app = InstalledAppEntity(
                    isSystemApp = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    name = appName,
                    versionName = versionName,
                    versionCode = versionCode,
                    packageName = packageName,
                    size = size,
                    enable = enable
                )
                apps.add(app)
            }

            call.respond(HttpResponseEntity.success(apps))
        }

        post("/install") {
            val multipart = call.receiveMultipart()
            var bundle: File? = null
            var md5: String? = null
            var fileName: String? = null

            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        if (part.name == "bundle") {
                            fileName = part.originalFileName ?: "${System.currentTimeMillis()}.apk"
                            val uploadDir = PathHelper.uploadFileDir()
                            bundle = File(uploadDir, fileName!!)
                            part.streamProvider().use { input ->
                                bundle!!.outputStream().buffered().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                    is PartData.FormItem -> {
                        if (part.name == "md5") {
                            md5 = part.value
                        }
                    }
                    else -> {}
                }
                part.dispose()
            }

            if (bundle != null) {
                try {
                    CommonUtil.install(mContext, bundle!!)

                    val db = RoomDatabaseHolder.getRoomDatabase(mContext)
                    val uploadFileRecordDao = db.uploadFileRecordDao()
                    val newUploadFileRecord = UploadFileRecord(
                        id = 0,
                        name = fileName!!,
                        path = bundle!!.absolutePath,
                        size = bundle!!.length(),
                        uploadTime = System.currentTimeMillis(),
                        md5 = MD5Helper.md5(bundle!!)
                    )
                    uploadFileRecordDao.insert(newUploadFileRecord)

                    call.respond(HttpResponseEntity.success<Any>())
                } catch (e: Exception) {
                    Timber.e("Upload install bundle failure, reason: ${e.message}")
                    val languageCode = call.request.header("languageCode")
                    val locale = if (!TextUtils.isEmpty(languageCode)) Locale(languageCode!!) else Locale("en")

                    val response = ErrorBuilder().locale(locale).module(HttpModule.CommonModule)
                        .error(HttpError.UploadInstallFileFailure).build<Any>()
                    response.msg = mContext.getString(HttpError.UploadInstallFileFailure.value)
                        .replace("%s", "${e.message}")
                    call.respond(response)
                }
            } else {
                call.respond(
                    HttpStatusCode.BadRequest,
                    HttpResponseEntity<Any>(
                        code = HttpStatusCode.BadRequest.value,
                        data = null,
                        msg = "Missing bundle file"
                    )
                )
            }
        }

        post("/tryToInstallFromCache") {
            val params = call.receiveParameters()
            val fileName = params["fileName"]
            val md5 = params["md5"]

            if (fileName != null && md5 != null) {
                val db = RoomDatabaseHolder.getRoomDatabase(mContext)
                val uploadFileRecords = db.uploadFileRecordDao().findWithMd5(md5)

                if (uploadFileRecords.isNotEmpty()) {
                    val uploadFileRecord = uploadFileRecords.single()
                    val installFile = File(uploadFileRecord.path)

                    if (installFile.exists() && MD5Helper.md5(installFile) == uploadFileRecord.md5) {
                        CommonUtil.install(mContext, installFile)
                        call.respond(HttpResponseEntity.success<Any>())
                        return@post
                    }
                }

                val languageCode = call.request.header("languageCode")
                val locale = if (!TextUtils.isEmpty(languageCode)) Locale(languageCode!!) else Locale("en")
                call.respond(
                    ErrorBuilder().locale(locale).module(HttpModule.CommonModule)
                        .error(HttpError.InstallationFileNotFound).build<Any>()
                )
            } else {
                call.respond(
                    HttpStatusCode.BadRequest,
                    HttpResponseEntity<Any>(
                        code = HttpStatusCode.BadRequest.value,
                        data = null,
                        msg = "Missing parameters"
                    )
                )
            }
        }

        post("/uninstall") {
            val packages = call.receive<List<String>>()
            EventBus.getDefault().post(BatchUninstallEvent(packages))
            call.respond(HttpResponseEntity.success<Any>())
        }
    }
}
