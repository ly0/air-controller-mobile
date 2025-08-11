package com.youngfeng.android.assistant.server

import android.content.Context
import com.youngfeng.android.assistant.manager.LogManager
import com.youngfeng.android.assistant.model.LogType
import com.youngfeng.android.assistant.server.entity.HttpResponseEntity
import com.youngfeng.android.assistant.server.routing.*
import io.ktor.http.*
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.partialcontent.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import timber.log.Timber
import java.io.File

fun Application.configureKtorServer(context: Context) {
    install(ContentNegotiation) {
        gson {
            setPrettyPrinting()
            disableHtmlEscaping()
        }
    }

    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader("languageCode")
        allowHeader("X-Forwarded-For")
        allowHeader("X-Real-IP")
        anyHost()
    }

    install(PartialContent)
    install(AutoHeadResponse)

    install(CallLogging) {
        format { call ->
            val method = call.request.httpMethod.value
            val path = call.request.path()
            val clientIp = call.request.header("X-Forwarded-For")
                ?: call.request.header("X-Real-IP")
                ?: call.request.header("Host")?.substringBefore(":")
                ?: "Unknown"

            val logMessage = "请求: $method $path (来自: $clientIp)"
            LogManager.log(logMessage, LogType.INFO)
            Timber.d("HTTP Request: $method $path from $clientIp")

            logMessage
        }
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            Timber.e(cause, "Unhandled exception")
            call.respond(
                HttpStatusCode.InternalServerError,
                HttpResponseEntity<Any>(
                    code = HttpStatusCode.InternalServerError.value,
                    msg = cause.message ?: "Internal Server Error",
                    data = null
                )
            )
        }
    }

    routing {
        // 静态文件服务
        staticFiles("/", File(context.cacheDir, "web"))

        // 配置路由
        configureCommonRoutes(context)
        configureFileRoutes(context)
        configureStreamRoutes(context)
        configureImageRoutes(context)
        configureVideoRoutes(context)
        configureAudioRoutes(context)
        configureContactRoutes(context)
    }
}
