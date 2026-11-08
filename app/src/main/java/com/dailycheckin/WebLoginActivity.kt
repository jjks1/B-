package com.dailycheckin

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 内嵌网页登录：用户在 WebView 里用手机号或账号密码登录 B 站，
 * 登录成功后从 CookieManager 里抓取 SESSDATA / bili_jct / DedeUserID 并保存。
 *
 * 之后所有打卡动作都用这三个 Cookie 走接口，无需再登录。
 */
class WebLoginActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private val ui = Handler(Looper.getMainLooper())
    private var stopped = false
    private var csrfHinted = false

    // 进页面时网页里可能还留着上一个账号的登录态。若不区分"新登录"和"旧登录态"，
    // 点「重新登录」会拿旧 Cookie 立刻保存并退出，看起来像页面闪一下就关了。
    private var initialSession = ""
    private var blockedReason = ""

    private val poller = object : Runnable {
        override fun run() {
            if (stopped) return
            if (trySaveCookie()) return
            ui.postDelayed(this, 1000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web)

        web = findViewById(R.id.web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            // 系统的真实 UA 里带 "; wv)" 标记，B 站风控会据此判定为「WebView」并拒掉
            // 短信验证码接口（页面表现为「网络不给力 undefined」）。
            // 只去掉 wv 与 Version/4.0 两段，其余保持设备与内核的真实版本，不做任何伪造。
            userAgentString = WebSettings.getDefaultUserAgent(this@WebLoginActivity)
                .replace("; wv)", ")")
                .replace("Version/4.0 ", "")
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            javaScriptCanOpenWindowsAutomatically = true
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        // 把页面里失败的请求摆到台面上：B 站只显示一句「网络不给力」，
        // 真实原因（风控 412 / CORS / 401）藏在接口响应里。
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                val text = msg.message()
                if (text.startsWith("BILINET ")) {
                    Toast.makeText(
                        this@WebLoginActivity,
                        text.removePrefix("BILINET "),
                        Toast.LENGTH_LONG
                    ).show()
                }
                return true
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.let { hookNetwork(it) }
                if (!trySaveCookie()) {
                    // 页面多次回调时避免挂上多条轮询链
                    ui.removeCallbacks(poller)
                    ui.postDelayed(poller, 1000)
                }
            }

            /**
             * 只让 http/https 在网页内打开。B站登录完成、点协议、点"打开App"时
             * 会跳到 bilibili:// 这类自定义 scheme，WebView 处理不了，会直接
             * 报 ERR_UNKNOWN_URL_SCHEME 并把页面刷成一片空白，看着就像登录失败。
             * 这里把非 http(s) 的跳转接住，页面不动，Cookie 仍由轮询照常抓取。
             */
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString().orEmpty()
                return !(url.startsWith("http://") || url.startsWith("https://"))
            }

            // 主框架 4xx/5xx 也报出来
            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame != true) return
                Toast.makeText(
                    this@WebLoginActivity,
                    "网页返回 HTTP ${errorResponse?.statusCode ?: 0}：${request.url}",
                    Toast.LENGTH_LONG
                ).show()
            }

            // 把真实错误码摆到台面上，避免只看到页面上一句「网络错误」
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame != true) return
                Toast.makeText(
                    this@WebLoginActivity,
                    "网页加载失败（${error?.errorCode ?: 0}：${error?.description ?: "未知"}）",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // 基准必须取网页自己的登录态。若拿 Prefs 里保存的 Cookie 当基准，
        // 而网页里其实登着另一个账号（比如上次登录缺 bili_jct 没存下来），
        // 比较结果会"不同"→ 立刻把网页里的账号存下并关闭，又变成一闪就退。
        initialSession = Prefs.cookieValue(webCookies(), "SESSDATA")
        web.loadUrl("https://passport.bilibili.com/login")

        findViewById<Button>(R.id.btnDone).setOnClickListener {
            if (!trySaveCookie(force = true)) {
                Toast.makeText(
                    this,
                    blockedReason.ifBlank { "还没检测到登录状态，请先完成登录" },
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * 包一层 fetch，把失败请求的 URL 与状态码打到 console，
     * 由上面的 WebChromeClient 转成界面提示。不改页面行为。
     */
    private fun hookNetwork(view: WebView) {
        view.evaluateJavascript(
            """(function(){
              if (window.__biliNetHooked) return; window.__biliNetHooked = 1;
              var of = window.fetch;
              window.fetch = function(){
                var url = (arguments[0] && arguments[0].url) || String(arguments[0]);
                return of.apply(this, arguments).then(function(r){
                  if (!r.ok) console.error('BILINET HTTP ' + r.status + ' ' + url);
                  return r;
                }).catch(function(e){
                  console.error('BILINET FAIL ' + url + ' ' + e); throw e;
                });
              };
            })();""",
            null
        )
    }

    /**
     * 网页当前三个域下的 Cookie，按 key 合并后的结果。
     * 刻意不在这里 flush()：flush 是同步落盘，而这个方法每秒被轮询调用一次，
     * 放在主线程会造成每秒一次磁盘写；读取当前进程内的 Cookie 本来就不需要落盘。
     */
    private fun webCookies(): String {
        val cm = CookieManager.getInstance()
        return mergeCookies(
            listOf(
                "https://www.bilibili.com",
                "https://passport.bilibili.com",
                "https://api.bilibili.com"
            ).map { cm.getCookie(it).orEmpty() }
        )
    }

    /** 按 key 合并多段 Cookie，同名键取后出现的值，输出的键不重复 */
    private fun mergeCookies(sources: List<String>): String {
        val map = LinkedHashMap<String, String>()
        for (raw in sources) {
            for (part in raw.split(';')) {
                val seg = part.trim()
                if (seg.isEmpty()) continue
                val eq = seg.indexOf('=')
                if (eq <= 0) continue
                val key = seg.substring(0, eq).trim()
                if (key.isEmpty()) continue
                map[key] = seg.substring(eq + 1).trim()
            }
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * 从 CookieManager 合并各域下的 Cookie，判断是否已登录。
     * @return true 表示已保存并结束页面
     */
    private fun trySaveCookie(force: Boolean = false): Boolean {
        if (stopped || isFinishing) return false
        // 三个域各自可能下发同名 Cookie，直接拼接会得到 "SESSDATA=a; SESSDATA=b"，
        // 请求头带两个同名 Cookie 会让 B 站判定登录态异常。按 key 合并，同名取后者。
        val merged = webCookies()
        val session = Prefs.cookieValue(merged, "SESSDATA")
        val hasSession = session.isNotBlank() &&
            Prefs.cookieValue(merged, "DedeUserID").isNotBlank()
        if (!hasSession) {
            blockedReason = "还没检测到登录状态，请先完成登录"
            return false
        }

        // 网页里仍是进页面时那个账号的登录态，说明用户还没真正登录新账号，
        // 此时不能自动保存退出，否则「重新登录」会一点就闪回。
        if (!force && initialSession.isNotBlank() && session == initialSession) {
            blockedReason = "当前网页里还是原来那个账号的登录态；登录新账号后会自动保存"
            return false
        }

        // 三项缺一不可：SESSDATA 是登录态，bili_jct 是所有写操作的 CSRF。
        // 缺 bili_jct 时若照样保存并退出，loggedIn() 会是 false、开机闸门永不放行，
        // 界面上只显示「未登录」，用户完全看不出发生了什么。
        if (Prefs.cookieValue(merged, "bili_jct").isBlank()) {
            blockedReason =
                "已取到登录态（SESSDATA、DedeUserID），但还缺 bili_jct：打卡的分享、投币、直播签到都要用它。请在网页里再点一下（比如打开个人空间）后返回本页"
            if (!csrfHinted) {
                csrfHinted = true
                Toast.makeText(this, blockedReason, Toast.LENGTH_LONG).show()
            }
            return false
        }

        Prefs.setCookie(this, merged)
        // 只在真正保存成功后落盘一次，让 Cookie 能跨进程存活
        CookieManager.getInstance().flush()
        blockedReason = ""
        Toast.makeText(this, "登录成功，Cookie 已保存", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
        return true
    }

    /**
     * 切到后台（比如去短信 App 抄验证码）时停掉轮询：
     * 后台每秒读一次 Cookie 没有意义，还会让界面在用户看不见的时候自行保存并关闭。
     */
    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(poller)
    }

    override fun onResume() {
        super.onResume()
        if (!stopped && !isFinishing) {
            ui.removeCallbacks(poller)
            ui.postDelayed(poller, 1000)
        }
    }

    /** 网页里退回上一页，而不是直接关掉登录界面 */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        stopped = true
        ui.removeCallbacksAndMessages(null)
        try {
            web.stopLoading()
            web.destroy()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
