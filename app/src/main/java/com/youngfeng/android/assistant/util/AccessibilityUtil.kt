package com.youngfeng.android.assistant.util

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import timber.log.Timber

object AccessibilityUtil {

    /**
     * Check if our accessibility service is enabled
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        // 尝试两种格式：完整类名和简写形式
        val fullServiceName = "${context.packageName}/${context.packageName}.service.RemoteControlAccessibilityService"
        val shortServiceName = "${context.packageName}/.service.RemoteControlAccessibilityService"

        try {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )

            if (!TextUtils.isEmpty(enabledServices)) {
                val colonSplitter = TextUtils.SimpleStringSplitter(':')
                colonSplitter.setString(enabledServices)

                while (colonSplitter.hasNext()) {
                    val componentName = colonSplitter.next()
                    if (componentName.equals(fullServiceName, ignoreCase = true) ||
                        componentName.equals(shortServiceName, ignoreCase = true)
                    ) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error checking accessibility service status")
        }

        return false
    }

    /**
     * Open accessibility settings page
     */
    fun openAccessibilitySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to open accessibility settings")
        }
    }
}
