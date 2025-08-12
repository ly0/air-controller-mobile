package com.youngfeng.android.assistant.server

import com.youngfeng.android.assistant.manager.LogManager
import com.youngfeng.android.assistant.model.LogType
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

object IpWhitelistManager {
    private val whitelist = ConcurrentHashMap<String, Boolean>()
    private var isEnabled = true // 默认启用白名单

    // 手动启用白名单
    fun manualEnable() {
        isEnabled = true
        Timber.d("IP whitelist manually enabled")
        LogManager.log("手动启用IP白名单访问控制", LogType.INFO)
    }

    // 手动禁用白名单 - 只有这个方法才能真正禁用白名单
    fun manualDisable() {
        isEnabled = false
        Timber.d("IP whitelist manually disabled")
        LogManager.log("手动禁用IP白名单访问控制", LogType.INFO)
    }

    fun isEnabled(): Boolean {
        return isEnabled
    }

    fun addIp(ip: String) {
        if (ip.isNotEmpty()) {
            val cleanIp = extractIp(ip)
            whitelist[cleanIp] = true
            Timber.d("Added IP to whitelist: $cleanIp")
            LogManager.log("添加IP到白名单: $cleanIp", LogType.INFO)
        }
    }

    fun removeIp(ip: String) {
        if (ip.isNotEmpty()) {
            val cleanIp = extractIp(ip)
            whitelist.remove(cleanIp)
            Timber.d("Removed IP from whitelist: $cleanIp")
            LogManager.log("从IP白名单移除: $cleanIp", LogType.INFO)
        }
    }

    fun clear() {
        whitelist.clear()
        Timber.d("Cleared IP whitelist")
    }

    fun isAllowed(ip: String?): Boolean {
        if (!isEnabled) return true
        if (ip == null) return false

        val cleanIp = extractIp(ip)
        val allowed = whitelist.containsKey(cleanIp) || cleanIp == "127.0.0.1" || cleanIp == "localhost"

        if (!allowed) {
            Timber.w("Access denied for IP: $cleanIp")
            LogManager.log("拒绝来自IP的访问: $cleanIp", LogType.WARNING)
        }

        return allowed
    }

    fun getWhitelistedIps(): Set<String> {
        return whitelist.keys.toSet()
    }

    private fun extractIp(ip: String): String {
        return if (ip.startsWith("/")) {
            ip.substring(1).substringBefore(":")
        } else {
            ip.substringBefore(":")
        }
    }
}