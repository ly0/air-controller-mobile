package com.youngfeng.android.assistant.server.config

import com.yanzhenjie.andserver.annotation.Config
import com.yanzhenjie.andserver.framework.config.WebConfig
import com.yanzhenjie.andserver.framework.website.StorageWebsite
import java.io.File

/**
 * AndServer 配置类
 * 用于配置服务器的全局设置，包括拦截器、静态资源等
 */
@Config
class ServerConfig : WebConfig {

    override fun onConfig(
        context: android.content.Context,
        delegate: WebConfig.Delegate
    ) {
        // 配置静态资源目录（可选）
        // 如果需要提供静态文件服务，可以在这里配置
        delegate.addWebsite(
            StorageWebsite(
                File(context.cacheDir, "web").absolutePath
            )
        )

        // 拦截器会通过 @Interceptor 注解自动注册
        // RequestLoggingInterceptor 会自动被添加到拦截器链中
    }
}
