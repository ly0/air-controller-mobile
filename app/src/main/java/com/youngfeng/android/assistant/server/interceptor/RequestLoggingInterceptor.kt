package com.youngfeng.android.assistant.server.interceptor

import com.yanzhenjie.andserver.annotation.Interceptor
import com.yanzhenjie.andserver.framework.HandlerInterceptor
import com.yanzhenjie.andserver.framework.handler.RequestHandler
import com.yanzhenjie.andserver.http.HttpRequest
import com.yanzhenjie.andserver.http.HttpResponse
import com.youngfeng.android.assistant.manager.LogManager
import com.youngfeng.android.assistant.model.LogType
import timber.log.Timber

/**
 * 请求日志拦截器，记录所有访问服务器的 URL
 */
@Interceptor
class RequestLoggingInterceptor : HandlerInterceptor {

    override fun onIntercept(
        request: HttpRequest,
        response: HttpResponse,
        handler: RequestHandler
    ): Boolean {
        val method = request.method.value()
        val path = request.path

        // 获取客户端 IP 地址（从 Header 中获取）
        val clientIp = request.getHeader("X-Forwarded-For")
            ?: request.getHeader("X-Real-IP")
            ?: request.getHeader("Host")?.substringBefore(":")
            ?: "Unknown"

        // 构建日志消息（简化版，只记录路径）
        val logMessage = "请求: $method $path (来自: $clientIp)"

        // 记录到活动日志
        LogManager.log(logMessage, LogType.INFO)

        // 同时记录到 Timber 日志（用于调试）
        Timber.d("HTTP Request: $method $path from $clientIp")

        // 返回 false 继续处理请求链，true 会中断请求
        return false
    }
}
