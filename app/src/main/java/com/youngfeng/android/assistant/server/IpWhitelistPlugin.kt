package com.youngfeng.android.assistant.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.*
import timber.log.Timber

class IpWhitelistPlugin {
    companion object Plugin : BaseApplicationPlugin<Application, Unit, IpWhitelistPlugin> {
        override val key = AttributeKey<IpWhitelistPlugin>("IpWhitelistPlugin")

        override fun install(pipeline: Application, configure: Unit.() -> Unit): IpWhitelistPlugin {
            val plugin = IpWhitelistPlugin()

            pipeline.intercept(ApplicationCallPipeline.Features) {
                val clientIp = extractClientIp(call)

                if (!IpWhitelistManager.isAllowed(clientIp)) {
                    Timber.w("Access denied for IP: $clientIp")
                    call.respond(HttpStatusCode.Forbidden, "Access denied")
                    finish()
                }
            }

            return plugin
        }

        private fun extractClientIp(call: ApplicationCall): String? {
            // First try to get the real client IP from headers (if behind proxy)
            val forwardedFor = call.request.header("X-Forwarded-For")?.substringBefore(",")
            if (!forwardedFor.isNullOrBlank()) {
                return forwardedFor.trim()
            }

            val realIp = call.request.header("X-Real-IP")
            if (!realIp.isNullOrBlank()) {
                return realIp.trim()
            }

            // Try to get from local property (Ktor 2.x way)
            return try {
                val local = call.request.local
                // Use remoteAddress to get IP, not remoteHost which may return hostname
                val remoteAddress = local.remoteAddress
                // remoteAddress is the IP address as string
                remoteAddress
            } catch (e: Exception) {
                Timber.e(e, "Failed to get client IP, using fallback")
                "127.0.0.1"
            }
        }
    }
}