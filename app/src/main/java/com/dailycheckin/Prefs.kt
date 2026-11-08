package com.dailycheckin

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地配置与状态。
 * Cookie 存在私有 SharedPreferences 里（应用沙盒，不对外可读）。
 */
object Prefs {

    private const val FILE = "auto_checkin_config"

    const val KEY_COOKIE = "cookie"
    const val KEY_UNAME = "uname"
    const val KEY_LAST_DATE = "last_date"
    const val KEY_LAST_REPORT = "last_report"

    const val KEY_OPEN_ON_BOOT = "open_on_boot"
    const val KEY_AUTO_COIN = "auto_coin"
    const val KEY_OPEN_BILI_APP = "open_bili_app"
    const val KEY_LIVE_SIGN = "live_sign"

    /** 是否已阅读并同意免责条款（首次启动时强制确认一次） */
    const val KEY_DISCLAIMER = "disclaimer_accepted"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun cookie(ctx: Context): String = sp(ctx).getString(KEY_COOKIE, "").orEmpty()
    fun setCookie(ctx: Context, value: String) {
        // 换了账号必须清掉上一位账号留下的昵称、当天标记和报告，
        // 否则新账号会因为「今天已打卡」被直接跳过，报告里还是旧账号的数据。
        val oldSession = cookieValue(ctx, "SESSDATA")
        val newSession = cookieValue(value, "SESSDATA")
        val editor = sp(ctx).edit().putString(KEY_COOKIE, value)
        if (oldSession.isNotBlank() && oldSession != newSession) {
            editor.putString(KEY_UNAME, "")
            editor.putString(KEY_LAST_DATE, "")
            editor.putString(KEY_LAST_REPORT, "")
        }
        editor.apply()
    }

    fun uname(ctx: Context): String = sp(ctx).getString(KEY_UNAME, "").orEmpty()
    fun setUname(ctx: Context, value: String) =
        sp(ctx).edit().putString(KEY_UNAME, value).apply()

    fun loggedIn(ctx: Context): Boolean {
        val c = cookie(ctx)
        return c.contains("SESSDATA=") && c.contains("bili_jct=") && c.contains("DedeUserID=")
    }

    fun lastDate(ctx: Context): String = sp(ctx).getString(KEY_LAST_DATE, "").orEmpty()
    fun setLastDate(ctx: Context, value: String) =
        sp(ctx).edit().putString(KEY_LAST_DATE, value).apply()

    fun lastReport(ctx: Context): String = sp(ctx).getString(KEY_LAST_REPORT, "").orEmpty()
    fun setLastReport(ctx: Context, value: String) =
        sp(ctx).edit().putString(KEY_LAST_REPORT, value).apply()

    fun openOnBoot(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_OPEN_ON_BOOT, true)
    fun autoCoin(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_AUTO_COIN, false)
    fun openBiliApp(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_OPEN_BILI_APP, true)
    fun liveSign(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_LIVE_SIGN, true)

    fun setBool(ctx: Context, key: String, value: Boolean) =
        sp(ctx).edit().putBoolean(key, value).apply()

    fun disclaimerAccepted(ctx: Context): Boolean =
        sp(ctx).getBoolean(KEY_DISCLAIMER, false)

    /**
     * 免责同意的落盘用 commit 而不是 apply：
     * 这是"使用前提"级别的状态，用户点完同意很可能立刻锁屏/退出，
     * 异步写入在进程被回收前来不及落盘时会重弹一次，虽然无害但没必要。
     */
    fun setDisclaimerAccepted(ctx: Context, value: Boolean) =
        sp(ctx).edit().putBoolean(KEY_DISCLAIMER, value).commit()

    fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** 从 Cookie 串里取某个键的值，例如 SESSDATA / bili_jct / DedeUserID */
    /** 从 Cookie 串里取指定键（带 = 边界，避免 DedeUserID 命中 DedeUserID__ckMd5） */
    fun cookieValue(merged: String, key: String): String {
        for (part in merged.split(';')) {
            val seg = part.trim()
            val eq = seg.indexOf('=')
            if (eq <= 0) continue
            if (seg.substring(0, eq).trim() == key) return seg.substring(eq + 1).trim()
        }
        return ""
    }

    fun cookieValue(ctx: Context, key: String): String {
        val raw = cookie(ctx)
        val m = Regex("(^|;)\\s*$key=([^;]*)").find(raw) ?: return ""
        return m.groupValues[2].trim()
    }
}
