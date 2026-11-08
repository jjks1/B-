package com.dailycheckin

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import java.net.HttpURLConnection
import java.net.URL

/**
 * 设置页：登录、开关、手动打卡、权限引导。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvAccount: TextView
    private lateinit var tvLastDate: TextView
    private lateinit var tvReport: TextView
    private lateinit var tvPermState: TextView
    private lateinit var btnCheckNow: Button
    private lateinit var btnLogin: Button
    private lateinit var tvNet: TextView

    private val ui = Handler(Looper.getMainLooper())

    // 按钮禁用只能挡住"连点"，挡不住旋转后界面重建（按钮会重新可用）。
    // 用原子闸门保证任何时刻只有一套打卡请求在跑，避免并发触发风控。
    private val checkRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    // 网络检测同理：连点会并发开多个线程
    private val netTesting = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvAccount = findViewById(R.id.tvAccount)
        tvLastDate = findViewById(R.id.tvLastDate)
        tvReport = findViewById(R.id.tvReport)
        tvPermState = findViewById(R.id.tvPermState)
        btnCheckNow = findViewById(R.id.btnCheckNow)
        btnLogin = findViewById(R.id.btnLogin)
        tvNet = findViewById(R.id.tvNet)

        btnLogin.setOnClickListener {
            startActivity(Intent(this, WebLoginActivity::class.java))
        }

        findViewById<Button>(R.id.btnNetTest).setOnClickListener { netTest() }

        btnCheckNow.setOnClickListener {
            if (!Prefs.loggedIn(this)) {
                Toast.makeText(this, "请先登录哔哩哔哩", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            checkNow()
        }

        bindSwitch(R.id.swBoot, Prefs.openOnBoot(this)) {
            Prefs.setBool(this, Prefs.KEY_OPEN_ON_BOOT, it)
        }
        bindSwitch(R.id.swOpenApp, Prefs.openBiliApp(this)) {
            Prefs.setBool(this, Prefs.KEY_OPEN_BILI_APP, it)
        }
        bindSwitch(R.id.swCoin, Prefs.autoCoin(this)) {
            Prefs.setBool(this, Prefs.KEY_AUTO_COIN, it)
        }
        bindSwitch(R.id.swLive, Prefs.liveSign(this)) {
            Prefs.setBool(this, Prefs.KEY_LIVE_SIGN, it)
        }

        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            openOverlaySetting()
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            requestIgnoreBattery()
        }
        findViewById<Button>(R.id.btnAutoStart).setOnClickListener {
            openAppDetail()
        }

        // 免责条款：底部入口可随时重看；首次启动必须同意后才能继续使用
        findViewById<TextView>(R.id.tvDisclaimer).setOnClickListener {
            showDisclaimer(firstRun = false)
        }
        if (!Prefs.disclaimerAccepted(this)) {
            showDisclaimer(firstRun = true)
        }
    }

    /**
     * @param firstRun true 表示首次启动的强制确认：不同意即退出，不能返回；
     *                 false 表示从底部入口重看：只读，一个关闭按钮。
     */
    private fun showDisclaimer(firstRun: Boolean) {
        val content = layoutInflater.inflate(R.layout.dialog_disclaimer, null)
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.disclaimer_title)
            .setView(content)
            .setCancelable(!firstRun)
        if (firstRun) {
            builder.setPositiveButton(R.string.disclaimer_agree) { _, _ ->
                Prefs.setDisclaimerAccepted(this, true)
            }
            builder.setNegativeButton(R.string.disclaimer_exit) { _, _ -> finish() }
        } else {
            builder.setPositiveButton(R.string.disclaimer_close, null)
        }
        builder.show()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun bindSwitch(id: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
        val sw = findViewById<SwitchCompat>(id)
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, value -> onChange(value) }
    }

    private fun refresh() {
        val logged = Prefs.loggedIn(this)
        val name = Prefs.uname(this)
        // 登录成功了但没拿到 bili_jct 时，Cookie 会被判定为"未登录"，
        // 开机闸门因此永远不放行——这种状态必须显式提示，不能静默。
        val csrfOk = Prefs.cookieValue(this, "bili_jct").isNotBlank()
        tvAccount.text = when {
            !logged -> "账号：未登录"
            !csrfOk -> "账号：已登录但缺少 bili_jct（写操作会失败，请重新登录）"
            name.isNotBlank() -> "账号：$name  已登录"
            else -> "账号：已登录（昵称待打卡后同步）"
        }

        val last = Prefs.lastDate(this)
        tvLastDate.text = if (last.isBlank()) "上次打卡：—" else "上次打卡：$last"

        val report = Prefs.lastReport(this)
        if (report.isBlank()) {
            tvReport.visibility = View.GONE
        } else {
            tvReport.visibility = View.VISIBLE
            tvReport.text = report
        }

        val overlay = Settings.canDrawOverlays(this)
        val battery = (getSystemService(POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)

        tvPermState.text = buildString {
            appendLine("${if (overlay) "✓" else "✗"} 显示在其他应用上层（开机弹窗必需）")
            appendLine("${if (battery) "✓" else "✗"} 已忽略电池优化")
            append("• 自启动需在系统设置里手动确认（部分机型默认关闭）")
        }

        btnLogin.text = if (logged) "重新登录" else "登录B站"
    }

    private fun checkNow() {
        if (!checkRunning.compareAndSet(false, true)) {
            Toast.makeText(this, "正在打卡中，请稍等", Toast.LENGTH_SHORT).show()
            return
        }
        btnCheckNow.isEnabled = false
        btnCheckNow.text = "打卡中…"
        val autoCoin = Prefs.autoCoin(this)
        val live = Prefs.liveSign(this)

        Thread {
            val res = BiliApi.checkIn(this, autoCoin, live)
            ui.post {
                checkRunning.set(false)
                if (isFinishing || isDestroyed) return@post
                btnCheckNow.isEnabled = true
                btnCheckNow.text = "立即打卡"

                // 报告带上结论头，与开机弹窗里的格式保持一致
                val head = if (res.success) "✅ 今日打卡完成" else "❌ 打卡未完成"
                Prefs.setLastReport(
                    this,
                    (listOf(head) + res.lines + "日期：${Prefs.today()}").joinToString("\n")
                )
                if (res.success) Prefs.setLastDate(this, Prefs.today())
                refresh()
                Toast.makeText(
                    this,
                    if (res.success) "打卡完成" else "打卡失败，详见下方报告",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }.apply { isDaemon = true }.start()
    }

    // --------------------------------------------------------- 权限引导

    private fun openOverlaySetting() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "已拥有悬浮窗权限", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            Toast.makeText(this, "请到「设置 → 应用 → 特殊权限」手动开启", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestIgnoreBattery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "已忽略电池优化", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            Toast.makeText(this, "请到「设置 → 电池 → 应用耗电管理」手动设为不限制", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAppDetail() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
        }
    }

    /**
     * 网络自检：用应用自己的网络栈（不走 WebView）请求三个 B 站域名。
     * 目的是把「手机整体上不了网」和「只有内置浏览器上不了网」分开：
     *  - 这里也失败 → 手机网络 / 系统时间 / 代理 / 联网控制的问题，与网页无关；
     *  - 这里成功、网页仍报错 → WebView 组件本身的问题（可去应用商店更新 Android System WebView）。
     */
    private fun netTest() {
        if (!netTesting.compareAndSet(false, true)) {
            Toast.makeText(this, "检测进行中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        val btn = findViewById<Button>(R.id.btnNetTest)
        btn.isEnabled = false
        tvNet.visibility = View.VISIBLE
        tvNet.text = "检测中…"
        Thread {
            val sb = StringBuilder()
            for (url in listOf(
                "https://api.bilibili.com/x/web-interface/nav",
                "https://passport.bilibili.com/login",
                "https://www.bilibili.com"
            )) {
                val host = url.removePrefix("https://").substringBefore("/")
                val line = try {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 6000
                    c.readTimeout = 6000
                    c.instanceFollowRedirects = true
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)")
                    val code = c.responseCode
                    c.disconnect()
                    "$host  HTTP $code"
                } catch (e: Exception) {
                    val msg = e.message?.take(70) ?: ""
                    "$host  ${e.javaClass.simpleName}  $msg"
                }
                sb.append(line).append('\n')
            }
            sb.append("\n手机时间：").append(
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                    .format(java.util.Date())
            )
            val text = sb.toString()
            ui.post {
                netTesting.set(false)
                btn.isEnabled = true
                if (isFinishing || isDestroyed) return@post
                tvNet.text = text
            }
        }.start()
    }
}
