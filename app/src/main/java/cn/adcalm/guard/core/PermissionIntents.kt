package cn.adcalm.guard.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 跳转到各项特殊权限的系统设置页。
 *
 * 有些 ROM 不认这些 action，统一在这里兜底退回应用详情页——
 * 至少让用户能手动找到入口，而不是点了没反应。
 */
object PermissionIntents {

    fun accessibility(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun usageAccess(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun notificationAccess(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    fun allFilesAccess(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                packageUri(context),
            )
        } else {
            appDetails(context)
        }
    }

    @SuppressLint("BatteryLife")
    fun batteryOptimization(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context))

    fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

    fun openOrFallback(context: Context, intent: Intent) {
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching { context.startActivity(appDetails(context)) }
        }
    }

    private fun packageUri(context: Context): Uri = Uri.parse("package:${context.packageName}")
}
