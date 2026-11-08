package com.dailycheckin

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 开机闸门的真实执行测试。
 *
 * 这段逻辑是整个需求的核心：**每天只在第一次开机时执行一次**。
 * 用的是工程里真实的 BootReceiver + Prefs，在 JVM 上跑（Robolectric），
 * 不是复制一份逻辑来判断。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootGateTest {

    private lateinit var app: Application

    private fun prefs() =
        app.getSharedPreferences("auto_checkin_config", Context.MODE_PRIVATE)

    private fun reset() {
        prefs().edit().clear().commit()
    }

    private fun login(agreeDisclaimer: Boolean = true) {
        val e = prefs().edit()
            .putString("cookie", "SESSDATA=s; bili_jct=c; DedeUserID=1")
            .putString("uname", "测试账号")
        if (agreeDisclaimer) e.putBoolean("disclaimer_accepted", true)
        e.commit()
    }

    private fun fire(action: String = Intent.ACTION_BOOT_COMPLETED) {
        BootReceiver().onReceive(app, Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** 被拉起的 Activity 类名，没有则为 null */
    private fun launched(): String? =
        shadowOf(app).nextStartedActivity?.component?.className

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        reset()
    }

    @Test
    fun boot_withoutLogin_doesNotLaunch() {
        fire()
        assertNull(launched())
    }

    @Test
    fun boot_withLogin_todayNotDone_launches() {
        login()
        fire()
        assertEquals(AutoRunActivity::class.java.name, launched())
    }

    @Test
    fun boot_withLogin_butAlreadyDoneToday_doesNotLaunch() {
        login()
        // 用真实实现写入"今天"
        Prefs.setCookie(app, "SESSDATA=s; bili_jct=c; DedeUserID=1")
        Prefs.setLastDate(app, Prefs.today())
        fire()
        assertNull(launched())
    }

    @Test
    fun boot_withLogin_butDoneYesterday_launches() {
        login()
        Prefs.setLastDate(app, "2020-01-01")
        fire()
        assertEquals(AutoRunActivity::class.java.name, launched())
    }

    @Test
    fun boot_whenSwitchOff_doesNotLaunch() {
        login()
        Prefs.setBool(app, Prefs.KEY_OPEN_ON_BOOT, false)
        fire()
        assertNull(launched())
    }

    @Test
    fun unrelatedAction_doesNotLaunch() {
        login()
        fire("android.intent.action.SCREEN_ON")
        assertNull(launched())
    }

    @Test
    fun quickBootAction_alsoWorks() {
        login()
        fire("android.intent.action.QUICKBOOT_POWERON")
        assertEquals(AutoRunActivity::class.java.name, launched())
    }

    @Test
    fun packageReplaced_alsoWorks() {
        login()
        fire(Intent.ACTION_MY_PACKAGE_REPLACED)
        assertEquals(AutoRunActivity::class.java.name, launched())
    }

    /** 日期格式必须稳定：同一天多次调用结果一致，且是公历 ASCII 数字 */
    @Test
    fun today_isStableAsciiDate() {
        val d = Prefs.today()
        assertEquals(d, Prefs.today())
        assertEquals(true, d.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    }

    /** Cookie 解析：前缀相同的键不能互相误命中 */
    @Test
    fun cookieValue_isExactKey() {
        Prefs.setCookie(app, "DedeUserID__ckMd5=md5; DedeUserID=960001")
        assertEquals("960001", Prefs.cookieValue(app, "DedeUserID"))
    }

    /** 没同意免责条款时，开机不得替用户执行 */
    @Test
    fun boot_withoutDisclaimer_doesNotLaunch() {
        login(agreeDisclaimer = false)
        fire()
        assertNull(launched())
    }

    /** 同意之后恢复正常 */
    @Test
    fun boot_afterDisclaimerAccepted_launches() {
        login(agreeDisclaimer = true)
        fire()
        assertEquals(AutoRunActivity::class.java.name, launched())
    }

    /** 缺 bili_jct 时不能算已登录（否则写操作必失败） */
    @Test
    fun loggedIn_requiresCsrf() {
        Prefs.setCookie(app, "SESSDATA=s; DedeUserID=1")
        assertEquals(false, Prefs.loggedIn(app))
    }
}

/** 换账号后旧状态必须清掉，否则新账号当天会被"已打卡"跳过 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountSwitchTest {
    private lateinit var app: android.app.Application

    @org.junit.Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("auto_checkin_config", Context.MODE_PRIVATE)
            .edit().clear().putBoolean("disclaimer_accepted", true).commit()
    }

    @org.junit.Test
    fun switchingAccount_clearsDayMarkAndName() {
        Prefs.setCookie(app, "SESSDATA=A; bili_jct=c1; DedeUserID=1")
        Prefs.setUname(app, "旧账号")
        Prefs.setLastDate(app, "2026-11-08")
        Prefs.setLastReport(app, "旧报告")

        Prefs.setCookie(app, "SESSDATA=B; bili_jct=c2; DedeUserID=2")

        assertEquals("", Prefs.lastDate(app))
        assertEquals("", Prefs.uname(app))
        assertEquals("", Prefs.lastReport(app))
        assertEquals(true, Prefs.loggedIn(app))
    }

    @org.junit.Test
    fun sameAccount_keepsTodayMark() {
        Prefs.setCookie(app, "SESSDATA=A; bili_jct=c1; DedeUserID=1")
        Prefs.setLastDate(app, "2026-11-08")
        Prefs.setCookie(app, "SESSDATA=A; bili_jct=c9; DedeUserID=1")
        assertEquals("2026-11-08", Prefs.lastDate(app))
    }

    @org.junit.Test
    fun cookieValue_doesNotMatchPrefixKey() {
        assertEquals("960001", Prefs.cookieValue("DedeUserID__ckMd5=md5; DedeUserID=960001", "DedeUserID"))
        assertEquals("", Prefs.cookieValue("DedeUserID__ckMd5=md5", "DedeUserID"))
    }
}

/** 伪造的 QUICKBOOT 广播：运行久了之后再发必须被忽略 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpoofBroadcastTest {
    private lateinit var app: android.app.Application

    @org.junit.Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("auto_checkin_config", Context.MODE_PRIVATE).edit()
            .clear().putBoolean("disclaimer_accepted", true).commit()
        Prefs.setCookie(app, "SESSDATA=s; bili_jct=c; DedeUserID=1")
    }

    private fun fire(action: String) {
        BootReceiver().onReceive(app, Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @org.junit.Test
    fun quickBoot_afterLongUptime_isIgnored() {
        org.robolectric.shadows.ShadowSystemClock.advanceBy(
            java.time.Duration.ofMinutes(11)
        )
        fire("android.intent.action.QUICKBOOT_POWERON")
        assertNull(shadowOf(app).nextStartedActivity?.component?.className)
    }

    @org.junit.Test
    fun systemBoot_alwaysTrusted() {
        org.robolectric.shadows.ShadowSystemClock.advanceBy(
            java.time.Duration.ofMinutes(11)
        )
        fire(Intent.ACTION_BOOT_COMPLETED)
        assertEquals(
            AutoRunActivity::class.java.name,
            shadowOf(app).nextStartedActivity?.component?.className
        )
    }
}
