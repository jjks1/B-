package com.dailycheckin

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * 开机后自动弹出的全屏界面：
 *  1. 内嵌 WebView 打开哔哩哔哩（复用网页版 Cookie，进去就是已登录状态）
 *  2. 后台线程调用接口完成每日打卡
 *  3. 完成后按需跳转「哔哩哔哩」客户端或系统自带浏览器，然后自动关闭
 */
class AutoRunActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FROM_BOOT = "from_boot"
    }

    private lateinit var web: WebView
    private lateinit var statusView: TextView
    private lateinit var panel: View

    private val ui = Handler(Looper.getMainLooper())
    private var exitTask: Runnable? = null

    // 打卡只允许一个线程在跑：连点「重试」会并发发出两套请求，容易触发风控
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    // 本界面是 singleInstance：登录页在另一个任务里，返回结果不保证送达回调。
    // 所以额外记录"正在等登录"，由 onResume 自查兜底。
    private var awaitingLogin = false

    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
            awaitingLogin = false
            if (Prefs.loggedIn(this)) {
                syncCookiesToWebView()
                runCheckIn()
            } else {
                statusView.text = "未完成登录，打卡已跳过"
                scheduleExit(3_000, jump = false)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_autorun)

        web = findViewById(R.id.web)
        statusView = findViewById(R.id.status)
        panel = findViewById(R.id.panel)

        setupWebView()
        syncCookiesToWebView()
        web.loadUrl("https://www.bilibili.com")

        findViewById<Button>(R.id.btnRetry).setOnClickListener {
            cancelExit()
            runCheckIn()
        }
        findViewById<Button>(R.id.btnClose).setOnClickListener {
            cancelExit()
            finish()
        }
        findViewById<Button>(R.id.btnOpenBili).setOnClickListener {
            cancelExit()
            openBiliApp()
            finish()
        }

        if (!Prefs.loggedIn(this)) {
            statusView.text = "还没有登录哔哩哔哩，正在打开登录页…"
            awaitingLogin = true
            loginLauncher.launch(Intent(this, WebLoginActivity::class.java))
        } else {
            runCheckIn()
        }
    }

    /**
     * singleInstance 模式下 startActivityForResult 的结果可能直接以 RESULT_CANCELED 回来，
     * 甚至回调不触发。这里在每次回到本界面时自查一次：登录页确实写入了完整 Cookie 就继续打卡。
     */
    override fun onResume() {
        super.onResume()
        if (awaitingLogin && Prefs.loggedIn(this)) {
            awaitingLogin = false
            syncCookiesToWebView()
            runCheckIn()
        }
    }

    // ------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        web.settings.apply {
            // 与登录页一致：去掉 UA 里的 wv 标记，避免被 B 站按「WebView」风控
            userAgentString = android.webkit.WebSettings.getDefaultUserAgent(this@AutoRunActivity)
                .replace("; wv)", ")")
                .replace("Version/4.0 ", "")
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame != true) return
                statusView.text = "网页加载失败（${error?.errorCode ?: 0}：${error?.description ?: "未知"}）\n打卡仍在后台继续"
            }
        }
    }

    /** 把已保存的 Cookie 注入 WebView，让内置浏览器直接处于登录态 */
    private fun syncCookiesToWebView() {
        if (!Prefs.loggedIn(this)) return
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        for (key in listOf("SESSDATA", "bili_jct", "DedeUserID", "sid")) {
            val v = Prefs.cookieValue(this, key)
            if (v.isNotBlank()) {
                cm.setCookie("https://www.bilibili.com", "$key=$v; path=/")
            }
        }
        cm.flush()
    }

    // ------------------------------------------------------------ 打卡

    private fun runCheckIn() {
        if (!running.compareAndSet(false, true)) {
            statusView.text = "正在打卡中，请稍等…"
            return
        }
        statusView.text = "正在打卡…"
        val autoCoin = Prefs.autoCoin(this)
        val liveSign = Prefs.liveSign(this)

        Thread {
          try {
            var res = BiliApi.checkIn(this, autoCoin, liveSign)
            var attempt = 0
            // 开机瞬间 Wi-Fi / 移动数据常常还没就绪。这种情况以前会被当成"已完成"，
            // 然后把当天标记掉、一整天不再尝试；现在改成等网络、重试两次。
            while (!res.success && res.retryable && attempt < 2) {
                attempt++
                val waitSec = 10 * attempt
                val n = attempt
                ui.post { statusView.text = "网络还没就绪，${waitSec} 秒后重试（第 $n 次）…" }
                Thread.sleep(waitSec * 1000L)
                res = BiliApi.checkIn(this, autoCoin, liveSign)
            }
            ui.post { render(res) }
          } finally {
            running.set(false)
          }
        }.apply { isDaemon = true }.start()
    }

    private fun render(res: BiliApi.Result) {
        if (isFinishing || isDestroyed) return

        val report = buildString {
            appendLine(if (res.success) "✅ 今日打卡完成" else "❌ 打卡未完成")
            if (res.uname.isNotBlank()) appendLine("账号：${res.uname}")
            res.lines.forEach { appendLine(it) }
            append("日期：${Prefs.today()}")
        }
        statusView.text = report
        // 成功失败都落盘：以前只在成功时写报告，失败后设置页一直显示上一次的成功报告，
        // 用户会以为今天是成功的。
        Prefs.setLastReport(this, report)

        if (res.success) {
            Prefs.setLastDate(this, Prefs.today())
            // 打完成功后 2.5 秒自动跳转并关闭，避免占用屏幕
            scheduleExit(2_500, jump = true)
        } else {
            // 失败时多留一会儿让用户看清原因
            scheduleExit(8_000, jump = false)
        }
    }

    private fun scheduleExit(delayMs: Long, jump: Boolean) {
        cancelExit()
        val task = Runnable {
            if (isFinishing || isDestroyed) return@Runnable
            if (jump && Prefs.openBiliApp(this)) openBiliApp()
            finish()
        }
        exitTask = task
        ui.postDelayed(task, delayMs)
    }

    private fun cancelExit() {
        exitTask?.let { ui.removeCallbacks(it) }
        exitTask = null
    }

    // ------------------------------------------------------------ 跳转

    /** 优先打开哔哩哔哩客户端；没装则用系统自带浏览器打开网页版 */
    private fun openBiliApp() {
        try {
            packageManager.getPackageInfo("tv.danmaku.bili", 0)
            val i = packageManager.getLaunchIntentForPackage("tv.danmaku.bili")
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                return
            }
        } catch (_: Exception) {
            // 未安装，走浏览器
        }
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.bilibili.com"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {
            statusView.append("\n（未找到可用浏览器）")
            panel.visibility = View.VISIBLE
        }
    }

    // ------------------------------------------------------------ 生命周期

    override fun onDestroy() {
        cancelExit()
        try {
            web.stopLoading()
            // 销毁前换回空客户端，避免销毁过程中的回调再触碰已失效的界面
            web.webViewClient = WebViewClient()
            web.destroy()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
