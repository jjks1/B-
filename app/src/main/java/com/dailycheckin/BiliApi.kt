package com.dailycheckin

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * 哔哩哔哩 Web 接口封装（纯 HttpURLConnection，无第三方依赖）。
 *
 * 全部接口都依赖网页版 Cookie：SESSDATA / bili_jct / DedeUserID
 *  - SESSDATA：登录态
 *  - bili_jct：CSRF token，写操作必带
 *  - DedeUserID：账号 uid
 *
 * 注意：这是网页端私有接口，官方随时可能改动；一旦失效只需要改这里的 URL / 参数。
 */
object BiliApi {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    private const val REFERER = "https://www.bilibili.com/"

    data class Result(
        val success: Boolean,
        val uname: String,
        val lines: List<String>,
        /** 失败原因是否属于「等会儿再试可能就好了」（网络还没就绪），用于开机时重试 */
        val retryable: Boolean = false
    )

    // ---------------------------------------------------------------- 网络底层

    private fun request(
        url: String,
        cookie: String,
        method: String = "GET",
        form: Map<String, String>? = null
    ): String = execute(url, cookie, method, form).first

    /** 回包体 + Set-Cookie 列表（扫码登录成功后 Cookie 只出现在响应头里） */
    private fun execute(
        url: String,
        cookie: String,
        method: String = "GET",
        form: Map<String, String>? = null
    ): Pair<String, List<String>> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Referer", REFERER)
            conn.setRequestProperty("Origin", "https://www.bilibili.com")
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            conn.setRequestProperty("Cookie", cookie)

            if (form != null) {
                val body = form.entries.joinToString("&") {
                    "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
                }
                conn.doOutput = true
                conn.setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8"
                )
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val headers = conn.headerFields["Set-Cookie"].orEmpty()
            return body to headers
        } finally {
            conn.disconnect()
        }
    }

    private fun json(text: String): JSONObject? =
        try {
            JSONObject(text)
        } catch (_: Exception) {
            null
        }

    // ---------------------------------------------------------------- 主流程

    /**
     * 执行每日打卡。所有步骤都有独立 try/catch，单步失败不影响整体。
     * @param autoCoin 是否自动投币（默认关闭：投币会消耗账号里真实的硬币）
     */
    fun checkIn(ctx: Context, autoCoin: Boolean, liveSign: Boolean): Result {
        val cookie = Prefs.cookie(ctx)
        val csrf = Prefs.cookieValue(ctx, "bili_jct")
        val lines = mutableListOf<String>()

        if (cookie.isBlank() || csrf.isBlank()) {
            return Result(false, "", listOf("× 还没有登录哔哩哔哩，请先在 App 内登录一次"))
        }

        // 本次是否真的做了事（用于判定成功，不能只看"跑完了流程"）
        var navOk = false
        var netError = false
        var didSomething = false

        // 1) 校验登录态，顺便拿昵称
        var uname = Prefs.uname(ctx)
        var level = 0
        var coinBalance = 0.0
        var mid = ""
        try {
            val data = json(request("https://api.bilibili.com/x/web-interface/nav", cookie))
                ?.optJSONObject("data")
            if (data == null || !data.optBoolean("isLogin", false)) {
                return Result(
                    false, uname,
                    listOf("× 登录态已失效，请在 App 内重新登录哔哩哔哩")
                )
            }
            navOk = true
            uname = data.optString("uname", uname)
            level = data.optJSONObject("level_info")?.optInt("current_level", 0) ?: 0
            coinBalance = data.optDouble("money", 0.0)
            mid = data.optString("mid", Prefs.cookieValue(ctx, "DedeUserID"))
            Prefs.setUname(ctx, uname)
            lines += "✓ 登录态有效：$uname（LV$level，硬币 ${fmt(coinBalance)}）"
            if (level <= 0) {
                lines += "• 账号等级 LV0（未转正）：B 站对未转正账号不发每日任务经验，" +
                    "也没有每日登录硬币（该奖励需 LV1 且绑定手机）"
            }
            if (autoCoin && coinBalance < 1.0) {
                lines += "• 投币已开启但硬币为 ${fmt(coinBalance)}，投币需要消耗硬币，本次跳过"
            }
        } catch (e: Exception) {
            // 开机瞬间网络常常还没连上，这类失败值得等一会儿重试
            netError = true
            lines += "× 登录态校验失败：${e.message}"
        }

        // 2) 查询今日任务完成情况（登录/观看/分享/投币）
        var shareDone = false
        var coinDone = false
        var watchDone = false
        var todayExp = 0
        try {
            val data = json(request("https://api.bilibili.com/x/member/web/exp/reward", cookie))
                ?.optJSONObject("data")
            if (data != null) {
                shareDone = data.optBoolean("share", false)
                coinDone = data.optBoolean("coin", false)
                watchDone = data.optBoolean("watch", false)
                todayExp = data.optInt("today_exp", 0)
                val loginDone = data.optBoolean("login", false)
                lines += "• 今日任务：登录 ${mark(loginDone)}｜观看 ${mark(watchDone)}｜" +
                    "分享 ${mark(shareDone)}｜投币 ${mark(coinDone)}｜今日经验 +$todayExp"
            }
        } catch (e: Exception) {
            lines += "× 今日任务查询失败：${e.message}"
        }

        // 3) 取一个排行榜视频，分享与观看共用（aid 分享用，bvid 观看心跳用）
        var aid = ""
        var bvid = ""
        try {
            val item = json(
                request("https://api.bilibili.com/x/web-interface/ranking/v2?rid=0&type=all", cookie)
            )?.optJSONObject("data")?.optJSONArray("list")?.optJSONObject(0)
            val a = item?.optLong("aid", 0L) ?: 0L
            if (a > 0L) aid = a.toString()
            bvid = item?.optString("bvid").orEmpty()
        } catch (_: Exception) {
        }

        if (!shareDone && aid.isNotBlank()) {
            try {
                val r = json(
                    request(
                        "https://api.bilibili.com/x/web-interface/share/add",
                        cookie,
                        "POST",
                        mapOf("aid" to aid, "csrf" to csrf)
                    )
                )
                val accepted = r?.optInt("code", -1) == 0
                if (accepted) {
                    // share/add 用无效 Cookie 也会返回 code 0（实测），所以不能凭返回码判定，
                    // 必须回查任务状态里的 share 字段才知道经验有没有真入账。
                    val confirmed = try {
                        json(request("https://api.bilibili.com/x/member/web/exp/reward", cookie))
                            ?.optJSONObject("data")?.optBoolean("share", false) ?: false
                    } catch (_: Exception) {
                        false
                    }
                    if (confirmed) {
                        didSomething = true
                        lines += "✓ 分享任务完成（服务端已确认，+5 经验）"
                    } else {
                        lines += "• 分享已提交，服务端暂未计入（接口返回 0 不等于生效）"
                    }
                } else {
                    lines += "× 分享失败：${r?.optString("message") ?: "未知"}"
                }
            } catch (e: Exception) {
                lines += "× 分享失败：${e.message}"
            }
        }

        // 4) 观看任务：网页播放器上报心跳（第一版缺的就是这一步）
        if (!watchDone) {
            try {
                if (watchVideo(cookie, csrf, aid, bvid, mid, lines)) {
                    didSomething = true
                    watchDone = true
                }
            } catch (e: Exception) {
                lines += "× 观看任务失败：${e.message}"
            }
        } else {
            lines += "• 今日观看任务已完成，跳过"
        }

        // 5) 投币任务（默认关闭）
        if (autoCoin && !coinDone) {
            try {
                val aids = pickVideoAids(cookie, 2)
                var given = 0
                for (a in aids) {
                    if (coinBalance - given < 1) break
                    val r = json(
                        request(
                            "https://api.bilibili.com/x/web-interface/coin/add",
                            cookie,
                            "POST",
                            mapOf(
                                "aid" to a,
                                "multiply" to "1",
                                "select_like" to "0",
                                "cross_domain" to "true",
                                "csrf" to csrf
                            )
                        )
                    )
                    if (r?.optInt("code", -1) == 0) given++ else break
                }
                if (given > 0) didSomething = true
                lines += if (given > 0) "✓ 已投币 $given 枚（+${given * 10} 经验，剩余硬币 ${fmt(coinBalance - given)}）"
                else "× 投币未成功（可能硬币不足或已达上限）"
            } catch (e: Exception) {
                lines += "× 投币失败：${e.message}"
            }
        } else if (autoCoin && coinDone) {
            lines += "• 今日投币任务已完成，跳过"
        }

        // 6) 直播间每日签到
        if (liveSign) {
            try {
                val r = json(
                    request(
                        "https://api.live.bilibili.com/xlive/web-ucenter/v1/sign/DoSign" +
                            "?csrf=$csrf&visit_id=",
                        cookie
                    )
                )
                val ok = r?.optInt("code", -1) == 0
                if (ok) didSomething = true
                lines += if (ok) "✓ 直播签到完成" else "• 直播签到：${r?.optString("message") ?: "已签到"}"
            } catch (e: Exception) {
                lines += "• 直播签到跳过：${e.message}"
            }
        }

        // 7) 复检今日总经验
        try {
            val data = json(request("https://api.bilibili.com/x/member/web/exp/reward", cookie))
                ?.optJSONObject("data")
            if (data != null) {
                lines += "• 打卡后今日经验：+${data.optInt("today_exp", 0)}"
            }
        } catch (_: Exception) {
        }

        val tasksAlreadyDone = shareDone || coinDone || watchDone
        val success = navOk && (didSomething || todayExp > 0 || tasksAlreadyDone)
        if (!success && navOk) {
            lines += "× 今日没有产生任何经验，判定为未完成（稍后会重试）"
        }
        return Result(success, uname, lines, retryable = netError)
    }

    // ---------------------------------------------------------------- 工具

    private fun mark(done: Boolean) = if (done) "✓" else "—"

    private fun fmt(v: Double) =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(Locale.US, "%.1f", v)

    /** 取排行榜里的视频 aid，用作分享/投币的载体 */

    /**
     * 观看任务。走的是网页播放器同一套心跳接口：
     *   排行榜取视频 → /x/player/pagelist 换 cid → /x/click-interface/web/heartbeat
     *   按 played_time = 3s / 9s / 18s 连发三次，服务端按累计播放时长记进度。
     * @return 是否至少有一次心跳被接受
     */
    private fun watchVideo(
        cookie: String,
        csrf: String,
        aid: String,
        bvid: String,
        mid: String,
        lines: MutableList<String>
    ): Boolean {
        if (aid.isBlank()) {
            lines += "× 观看任务：没取到可用视频"
            return false
        }
        val cid = try {
            json(
                request("https://api.bilibili.com/x/player/pagelist?aid=$aid&jsonp=jsonp", cookie)
            )?.optJSONArray("data")?.optJSONObject(0)?.optLong("cid", 0L) ?: 0L
        } catch (_: Exception) {
            0L
        }
        if (cid <= 0L) {
            lines += "× 观看任务：没取到该视频的 cid"
            return false
        }

        val startTs = System.currentTimeMillis() / 1000
        var accepted = 0
        var lastMsg = ""
        // 三秒内把 played_time 从 3 直接报到 18，等于自报"一秒钟看完了 15 秒"，
        // 服务端很容易判成刷量而丢弃。这里按真实观看节奏留出间隔。
        for ((idx, played) in listOf(3, 9, 18).withIndex()) {
            if (idx > 0) {
                try {
                    Thread.sleep(3_000L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            val r = try {
                json(
                    request(
                        "https://api.bilibili.com/x/click-interface/web/heartbeat",
                        cookie,
                        "POST",
                        mapOf(
                            "aid" to aid,
                            "cid" to cid.toString(),
                            "bvid" to bvid,
                            "played_time" to played.toString(),
                            "realtime" to played.toString(),
                            "start_ts" to startTs.toString(),
                            "type" to "3",
                            "sub_type" to "0",
                            "dt" to "2",
                            "play_type" to "0",
                            "mid" to mid,
                            "jsonp" to "jsonp",
                            "csrf" to csrf
                        )
                    )
                )
            } catch (_: Exception) {
                null
            }
            if (r?.optInt("code", -1) == 0) accepted++ else lastMsg = r?.optString("message").orEmpty()
        }

        var confirmed = false
        if (accepted > 0) {
            // 心跳接口即使未登录也会返回 code 0，所以必须回查任务状态才知道有没有真被计入
            confirmed = try {
                json(
                    request("https://api.bilibili.com/x/member/web/exp/reward", cookie)
                )?.optJSONObject("data")?.optBoolean("watch", false) ?: false
            } catch (_: Exception) {
                false
            }
            lines += if (confirmed) {
                "✓ 观看任务完成（服务端已确认${if (bvid.isNotBlank()) "，视频 $bvid" else ""}）"
            } else {
                "• 观看已上报 $accepted/3 次心跳，服务端未计入（心跳返回 0 不等于加分）"
            }
        } else {
            lines += "× 观看任务失败：" + (lastMsg.ifBlank { "心跳未被服务端接受" })
        }
        // 只有服务端复检确认才算完成：心跳接口即使未登录也返回 code 0，
        // 之前这里返回 accepted > 0，会让「全部未计入」的一天被判成打卡成功。
        return confirmed
    }

    private fun pickVideoAids(cookie: String, want: Int): List<String> {
        val text = request(
            "https://api.bilibili.com/x/web-interface/ranking/v2?rid=0&type=all",
            cookie
        )
        val list = json(text)?.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        val out = mutableListOf<String>()
        val owners = mutableListOf<Long>()
        for (i in 0 until list.length()) {
            if (out.size >= want) break
            val item = list.optJSONObject(i) ?: continue
            val aid = item.optLong("aid", 0L)
            val mid = item.optJSONObject("owner")?.optLong("mid", 0L) ?: 0L
            if (aid <= 0L || mid <= 0L) continue
            if (owners.contains(mid)) continue   // 同一 UP 主只投一次，避免触发限制
            owners += mid
            out += aid.toString()
        }
        return out
    }

    private fun pickVideoAid(cookie: String): String = pickVideoAids(cookie, 1).firstOrNull().orEmpty()
}
