package com.youngfeng.android.assistant.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 权限状态数据类
 */
data class PermissionStatus(
    val name: String,
    val isGranted: Boolean,
    val description: String,
    val action: String? = null
)

object PermissionChecker {

    /**
     * 检查所有需要的权限
     */
    fun checkAllPermissions(context: Context): List<PermissionStatus> {
        val permissions = mutableListOf<PermissionStatus>()

        // 存储权限
        permissions.add(
            PermissionStatus(
                name = "存储权限",
                isGranted = checkStoragePermission(context),
                description = "读写文件所需",
                action = "storage"
            )
        )

        // 通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(
                PermissionStatus(
                    name = "通知权限",
                    isGranted = checkNotificationPermission(context),
                    description = "显示服务通知所需",
                    action = "notification"
                )
            )
        }

        // 系统窗口权限
        permissions.add(
            PermissionStatus(
                name = "悬浮窗权限",
                isGranted = checkSystemAlertWindowPermission(context),
                description = "屏幕共享功能所需",
                action = "overlay"
            )
        )

        // 无障碍服务
        permissions.add(
            PermissionStatus(
                name = "无障碍服务",
                isGranted = AccessibilityUtil.isAccessibilityServiceEnabled(context),
                description = "远程控制功能所需",
                action = "accessibility"
            )
        )

        return permissions
    }

    /**
     * 检查存储权限
     */
    private fun checkStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 使用 MANAGE_EXTERNAL_STORAGE
            android.os.Environment.isExternalStorageManager()
        } else {
            // Android 10 及以下
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 检查通知权限 (Android 13+)
     */
    private fun checkNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            try {
                ContextCompat.checkSelfPermission(
                    context,
                    "android.permission.POST_NOTIFICATIONS"
                ) == PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) {
                true // 如果权限检查失败，认为已授予
            }
        } else {
            true // 旧版本不需要此权限
        }
    }

    /**
     * 检查系统窗口权限
     */
    private fun checkSystemAlertWindowPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true // 旧版本不需要此权限
        }
    }

    /**
     * 检查是否所有权限都已授予
     */
    fun areAllPermissionsGranted(context: Context): Boolean {
        return checkAllPermissions(context).all { it.isGranted }
    }

    /**
     * 获取未授予的权限数量
     */
    fun getUnGrantedPermissionCount(context: Context): Int {
        return checkAllPermissions(context).count { !it.isGranted }
    }
}
