package com.dailycheckin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.widget.Toast

/**
 * 开机广播接收器：系统启动完成后，自动拉起 AutoRunActivity。
 *
 * 说明：
 *  - Android 10+ 禁止后台启动 Activity，但拥有「悬浮窗」(SYSTEM_ALERT_WINDOW) 权限的应用是例外。
 *    App 首次启动时会引导用户去授权，授权后开机自动弹出才能生效。
 *  - 国产 ROM（小米/华为/OPPO/vivo 等）还需在系统设置里允许「自启动 / 后台运行」。
 */
class BootReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "收到广播：$action")

        val isQuickBoot = action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        // QUICKBOOT_POWERON 不是受系统保护的广播，任何应用都能伪造一条，
        // 让本应用在非开机时凭空弹出全屏界面。开机广播只会在开机后很短时间内到达，
        // 所以这里再校验一次「设备启动至今不足 10 分钟」。
        if (isQuickBoot && SystemClock.elapsedRealtime() > 10 * 60 * 1000L) {
            Log.i(TAG, "QUICKBOOT 距离开机已超过 10 分钟，判定为伪造，忽略")
            return
        }

        val isBoot = action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        val isUpdate = action == Intent.ACTION_MY_PACKAGE_REPLACED

        if (!isBoot && !isUpdate) return
        if (!Prefs.openOnBoot(context)) {
            Log.i(TAG, "用户关闭了开机自启，跳过")
            return
        }
        // 免责条款是使用前提：没同意过就不该在开机时替用户执行任何自动化。
        // 升级到带免责声明的版本后，用户打开一次 App 并同意即可恢复自动打卡。
        if (!Prefs.disclaimerAccepted(context)) {
            Log.i(TAG, "用户尚未同意免责条款，跳过（打开 App 同意一次即恢复）")
            return
        }
        if (!Prefs.loggedIn(context)) {
            Log.i(TAG, "未登录，跳过（等用户手动打开 App）")
            return
        }
        if (Prefs.lastDate(context) == Prefs.today()) {
            Log.i(TAG, "今天已经打过卡，跳过")
            return
        }

        launch(context)
    }

    private fun launch(context: Context) {
        try {
            val i = Intent(context, AutoRunActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(AutoRunActivity.EXTRA_FROM_BOOT, true)
            }
            context.startActivity(i)
        } catch (e: Exception) {
            Log.e(TAG, "拉起失败", e)
            Toast.makeText(context, "开机自动打卡未能启动，请检查悬浮窗与自启动权限", Toast.LENGTH_LONG)
                .show()
        }
    }
}
