# 每日自动打卡 · 哔哩哔哩（Android 原生工程）

一个**开机自启**的安卓小工具：每次开机自动弹出界面 → 内嵌 WebView 打开哔哩哔哩 → 后台用网页版 Cookie 调接口完成每日任务 → 跳转到 B 站客户端（未安装则用系统浏览器）→ 自动关闭。**每天只在第一次开机时执行一次。**

```
包名 com.dailycheckin  ·  Kotlin 1,468 行 / 45 文件  ·  minSdk 24 / targetSdk 34
第三方依赖 0  ·  APK 3.1 MB  ·  单元测试 22 例全通过  ·  versionCode 24
```

- 不模拟点击、不注入脚本、不逆向签名算法，全部动作走 B 站网页版公开接口
- 网络层用系统自带 `HttpURLConnection`，运行期依赖只有 `androidx.core` + `androidx.appcompat`
- Cookie 只存手机本地私有目录，不上传任何服务器
- 首次启动必须同意内置《免责声明》才能使用，开机链路同样受该前提约束

> 本项目是**源码工程**。想直接装：用同目录交付的 APK；想自己改：见「构建」，Android Studio 打开即可。

---

**目录**

[速览](#速览) · [功能清单](#1-功能清单) · [工作原理](#2-工作原理) · [设计取舍](#3-为什么这么实现) · [可靠性设计](#4-为什么它的报告可信) · [验证状态](#5-验证到什么程度) · [构建](#6-构建) · [首次使用](#7-首次使用约-2-分钟只做一次) · [自启动设置](#8-各品牌自启动路径) · [权限](#9-权限说明) · [工程结构](#10-工程结构) · [接口](#11-接口清单) · [已知限制](#12-已知限制) · [常见问题](#13-常见问题) · [版本演进](#14-版本演进) · [免责与许可](#15-免责声明与许可)

---

## 速览

| 问题 | 回答 |
|---|---|
| 它是什么 | 一个只做一件事的安卓原生工具：开机时替你做完 B 站每日任务，然后退出 |
| 它不是什么 | 不是外挂、不做多账号批量、不抓取你的数据、不需要服务器 |
| 适合谁 | 自己一个人用、手机每天关机再开、懒得每天点一遍任务的人 |
| 不适合谁 | 想批量养号的人；只想要现成商店应用、不愿自己编译签名的人 |
| 技术上特殊在哪 | 全部任务结果以**服务端回查**为准，不把接口返回 `code:0` 当真 |
| 最大的风险 | B 站用户协议不欢迎第三方自动化，账号风险自担（应用内有强制免责确认） |

---

## 1. 功能清单

| 能力 | 说明 |
|---|---|
| 开机自动执行 | 监听 `BOOT_COMPLETED`、`QUICKBOOT_POWERON`（两个厂商 action）、`MY_PACKAGE_REPLACED` |
| 每天只跑一次 | 登录态 / 开关 / 当天标记三道闸门，任一不满足即静默退出 |
| 内嵌网页登录 | WebView 打开 `passport.bilibili.com/login`，自动抓 `SESSDATA`、`bili_jct`、`DedeUserID` |
| 观看任务 | 排行榜取视频 → 换 `cid` → 按 3s/9s/18s 发三次心跳，两次之间间隔 3 秒 |
| 分享任务 | `share/add` 提交后**回查**任务状态，不看返回码（该接口用假 Cookie 也返回 0） |
| 投币任务 | 默认关闭。开启后消耗真实硬币（1 枚 = 10 经验），同一 UP 主只投一次 |
| 直播签到 | `DoSign`，失败不影响其它任务 |
| 结果可信 | 成功判据是「登录态有效 + 确实产生了经验或任务已完成」，四项全空判为未完成并重试 |
| 完成后跳转 | 优先拉起哔哩哔哩客户端，未安装则用系统浏览器打开网页版，然后自动关闭 |
| 设置页 | 4 个开关、立即打卡、网络自检、三项权限引导、免责条款入口 |
| 免责条款 | 首次启动强制确认（同意才可用，不同意直接退出），底部可随时重看 |

三个界面：`MainActivity`（设置页）、`WebLoginActivity`（登录页）、`AutoRunActivity`（开机打卡页，全屏 WebView 上浮一块进度面板）。

---

## 2. 工作原理

```
开机
 │
 ▼
BootReceiver          收到 4 种广播之一
 ├ 是伪造的 QUICKBOOT？ ── 距开机 > 10 分钟则忽略（该广播任何应用都能发）
 ├ 用户关了开机自启？   ── 静默退出
 ├ 没同意免责条款？     ── 静默退出
 ├ 没登录？             ── 静默退出（等用户手动打开 App）
 └ 今天已打过卡？       ── 静默退出
 │ 全部通过
 ▼
AutoRunActivity       全屏弹出（Android 10+ 需「显示在其他应用上层」权限）
 ├ WebView 打开 B 站   ← 复用的就是系统 WebView 内核
 └ 后台线程打卡
 │
 ▼
BiliApi.checkIn()
 ① nav          校验登录态，取昵称 / 等级 / 硬币数
 ② exp/reward   查今天四项任务进度与经验
 ③ share/add    分享（回查确认）
 ④ heartbeat    观看（3 次心跳，回查确认）
 ⑤ coin/add     投币（可选，默认关）
 ⑥ DoSign       直播签到
 ⑦ exp/reward   复检今日总经验
 │
 ▼
判定成功 → 写「今天已打卡」+ 报告 → 2.5 秒后跳转并关闭
判定失败 → 报告写明每个环节 → 8 秒后关闭（网络类失败会先重试 2 次，等 10s / 20s）
```

**Cookie 从哪来？** 首次在内置网页里登录一次，程序自动从 `CookieManager` 合并 `www / passport / api` 三个域，按 key 去重后取三项 Cookie 存进应用私有 `SharedPreferences`。之后每次打卡直接用它调接口，不需要每天登录。

---

## 3. 为什么这么实现

做完同一个目标有四种常见做法，选第一种是权衡后的结果：

| 方案 | 做法 | 代价 |
|---|---|---|
| **WebView + 网页接口**（本项目） | 用户在自己看得见的网页里登录，程序拿 Cookie 直接调网页端接口 | 接口可能被改；需要用户登录一次 |
| 无障碍模拟点击 | 常驻服务替用户在 B 站 App 里点按钮 | 手指位置随版本变、耗电、极易被系统杀，看不见在做啥 |
| 逆向 App 签名算法 | 破解客户端请求签名 | 违反协议且法律风险高，B 站一升级就全废 |
| 定时任务代替开机 | `AlarmManager` 每天定点跑 | 需常驻后台，国产 ROM 会掐掉，还得额外做保活 |

只用开机这一次窗口，是因为它天然满足「每天一次、不用保活」——开机广播是系统给的，不需要常驻。

---

## 4. 为什么它的报告可信

这个工具最容易骗人的地方是「接口返回 0 就报成功」。实测发现 B 站两个接口**用无效 Cookie 也返回 `code:0`**（`share/add`、`heartbeat`），所以判定层做了这些事：

| 机制 | 具体做法 |
|---|---|
| 不认返回码，只认回查 | 分享查 `reward.share`、观看查 `reward.watch`，没计入就显示「已提交，服务端暂未计入」 |
| 成功三元判据 | `navOk && (有实际动作 \|\| 今日经验 > 0 \|\| 任务本就已完成)`，四项全「—」且经验 +0 一律判失败 |
| 网络类失败重试 | 最多 2 次，等 10s / 20s，避免刚开机网络没就绪就判死 |
| 并发闸门 | 设置页与打卡页各有 `AtomicBoolean`，界面重建（旋转屏幕）也不会跑出第二套请求 |
| 防伪造广播 | `QUICKBOOT_POWERON` 不是受保护广播，任何应用都能发；用 `elapsedRealtime()` < 10 分钟校验挡掉 |
| 换号清理 | `SESSDATA` 变化时同事务清空昵称 / 今日标记 / 旧报告，避免新账号被判「今天已完成」 |
| 观看节奏真实化 | 三次心跳之间留 3 秒间隔，而不是瞬间把 `played_time` 从 3 报到 18 |

换句话说：**它宁可报「没完成」，也不会把没生效的任务报成成功。**

---

## 5. 验证到什么程度

如实标注，避免「全绿」误导：

| 项目 | 状态 | 依据 |
|---|---|---|
| 编译 | ✅ 实测 | `gradle test assembleDebug` BUILD SUCCESSFUL，0 error |
| 单元测试 22 例 | ✅ 实测 | 13 闸门 + 3 换号 + 2 防伪 + 4 免责界面，全通过 |
| 签名 / 4 字节对齐 | ✅ 实测 | `apksigner verify`、`zipalign -c -p 4` 通过 |
| 新代码确实进包 | ✅ 实测 | `strings classes*.dex` 能搜到新增字段名 |
| 9 个接口的真实响应 | ✅ 实测 | 用无效 Cookie 逐个打过，记录在案（含 `-101`、`-400`、假 `code:0`） |
| 开机广播是否被 ROM 拦截 | ⚠️ 未验证 | 沙箱无真机，各品牌差异大 |
| 后台能否拉起 `AutoRunActivity` | ⚠️ 未验证 | 依赖 `SYSTEM_ALERT_WINDOW` 真实授权 |
| WebView 能否抓到完整 `bili_jct` | ⚠️ 未验证 | 需真账号登录 |
| 任务是否真入账 | ⚠️ 未验证 | 测试账号 LV0，本身不发经验 |

---

## 6. 构建

### 前置

- JDK 17
- Android SDK Platform 34、Build-Tools 34.0.0
- Gradle 8.7（与 AGP 8.5.2 匹配）

### Android Studio（推荐）

1. `File → Open` 选择本目录，等待 Gradle 同步
2. `Build → Build Bundle(s) / APK(s) → Build APK(s)`
3. 产物在 `app/build/outputs/apk/debug/app-debug.apk`

### 命令行

```bash
echo "sdk.dir=/你的/Android/Sdk" > local.properties
gradle test assembleDebug        # 先跑测试再打包
```

> ⚠️ 本仓库**没有附带 `gradle-wrapper.jar`**（二进制不便入库）。两种补法：
> 1. 用本机已装的 `gradle`（≥ 8.5）直接执行上面的命令；
> 2. 在装了 Gradle 的机器上执行一次 `gradle wrapper --gradle-version 8.7`，之后可用 `./gradlew`。

跑测试要联网下载 Robolectric 的 `android-all` 运行时（约 150 MB），首次会慢；国内建议加镜像，见 `app/build.gradle.kts` 里已配好的阿里云仓库地址。

---

## 7. 首次使用（约 2 分钟，只做一次）

1. 安装 APK，打开应用
2. 在《免责声明》里点「**同意并继续**」——这是使用前提，不做的话开机不会自动打卡
3. 点「登录 B 站」，在内置网页里完成登录（推荐页面下方「**账号密码登录**」，不经过短信接口）
4. 回到设置页，确认账号显示「已登录」
5. 按页面提示依次授权：**显示在其他应用上层** → **忽略电池优化** → 系统设置里允许**自启动**

前两项在状态区有 `✓ / ✗` 可核对，第三项因机型而异需手动确认。

---

## 8. 各品牌自启动路径

| 品牌 | 路径 |
|---|---|
| 小米 / Redmi | 设置 → 应用设置 → 应用管理 → 每日自动打卡 → 自启动 + 省电策略「无限制」 |
| 华为 / 荣耀 | 设置 → 应用 → 应用启动管理 → 关闭「自动管理」，勾选允许自启动 / 关联启动 / 后台活动 |
| OPPO / 一加 / realme | 设置 → 电池 → 应用耗电管理 → 允许后台运行 / 自启动 |
| vivo / iQOO | 设置 → 电池 → 后台高耗电 → 允许；i管家 → 自启动管理 |
| 三星 | 设置 → 电池 → 后台使用限制 → 从「休眠应用」中移除 |
| 原生 Android | 一般无需额外设置 |

---

## 9. 权限说明

| 权限 | 是否真的用到 | 用途 |
|---|---|---|
| `INTERNET` | ✅ | 调接口、加载网页 |
| `RECEIVE_BOOT_COMPLETED` | ✅ | 接收开机广播 |
| `SYSTEM_ALERT_WINDOW` | ✅ | Android 10+ 后台启动界面必须，否则开机弹不出来 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | ✅ | 避免被冻结后收不到开机广播 |
| `ACCESS_NETWORK_STATE` | ⬜ 预留 | 未调用，为后续网络状态判断留位 |
| `POST_NOTIFICATIONS` | ⬜ 预留 | 未调用，为后续结果通知留位 |
| `WAKE_LOCK` | ⬜ 预留 | 未调用，为后续唤醒锁留位 |
| `<queries>` → `tv.danmaku.bili` | ✅ | Android 11+ 包可见性，否则查不到 B 站客户端是否安装 |

按本项目约定：**不为减小体积删减权限或依赖**，预留项保持声明，避免以后加功能还要改清单再发版。

---

## 10. 工程结构

```
BiliAutoCheckIn/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── README.md
└── app/
    ├── build.gradle.kts                  # minSdk 24 / targetSdk 34 / versionCode 24
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml       # 7 权限 + 4 组件 + 包可见性
        │   ├── java/com/dailycheckin/
        │   │   ├── Prefs.kt              # 配置 / Cookie / 报告存储，换号清理
        │   │   ├── BiliApi.kt            # 全部接口，无第三方依赖
        │   │   ├── BootReceiver.kt       # 开机广播 + 四道闸门 + 防伪造校验
        │   │   ├── AutoRunActivity.kt    # 全屏 WebView + 打卡 + 跳转 + 登录兜底
        │   │   ├── WebLoginActivity.kt   # 内置网页登录，抓 Cookie
        │   │   └── MainActivity.kt       # 设置页 / 权限引导 / 免责条款
        │   └── res/
        │       ├── layout/               # activity_main / activity_autorun / activity_web / dialog_disclaimer
        │       ├── drawable/             # 卡片与面板圆角背景
        │       ├── mipmap/ic_launcher.xml
        │       └── values/               # colors / strings / themes
        └── test/java/com/dailycheckin/
            ├── BootGateTest.kt           # 18 例：闸门、换号、防伪广播、免责前置
            └── DisclaimerTest.kt         # 4 例：真跑 MainActivity 断言弹窗行为
```

测试全部在 JVM 上用 Robolectric 跑**真实业务类**，不复制逻辑；测试依赖只在 `testImplementation`，不进 APK。

---

## 11. 接口清单

全部为 B 站网页端接口，固定写在 `BiliApi.kt`，接口变了只改这里：

| 用途 | 接口 |
|---|---|
| 登录态 / 昵称 / 等级 / 硬币 | `api.bilibili.com/x/web-interface/nav` |
| 今日任务进度与经验 | `api.bilibili.com/x/member/web/exp/reward` |
| 分享 | `api.bilibili.com/x/web-interface/share/add` |
| 投币 | `api.bilibili.com/x/web-interface/coin/add` |
| 直播签到 | `api.live.bilibili.com/xlive/web-ucenter/v1/sign/DoSign` |
| 取视频 | `api.bilibili.com/x/web-interface/ranking/v2` |
| 换 cid | `api.bilibili.com/x/player/pagelist` |
| 观看心跳 | `api.bilibili.com/x/click-interface/web/heartbeat` |
| 登录页 | `passport.bilibili.com/login` |

所有写操作都带 `bili_jct` 作 CSRF；请求统一带 `Referer`、`Origin` 与移动端 UA。

---

## 12. 已知限制

- **LV0 未转正账号拿不到经验**：B 站对 LV0 不发每日任务经验，每日登录硬币也要求 LV1 且绑定手机。此时打卡会如实报告「今日没有产生任何经验」，不会假装成功。
- **短信验证码受极验限制**：`sms/send` 需先过极验，在 WebView 里常起不来，页面表现为「网络不给力 undefined」。建议改用「账号密码登录」。
- **二维码登录已移除**：B 站二维码页在测过的机器上加载不出，此分支已删干净。
- **接口是网页端私有接口**：B 站随时可能调整参数或加风控，届时需同步改 `BiliApi.kt`。
- **交付的是 debug 签名 APK**：正式分发请自行配置 release 签名。
- **真机差异**：开机广播是否被 ROM 拦截、后台能否拉起界面，各品牌差异较大，见第 8 节。

---

## 13. 常见问题

**Q：开机没自动弹出？**
按顺序排查：免责条款是否同意过 → 悬浮窗权限（状态区应有 `✓`）→ 系统自启动设置 → 今天是否已打过卡。想重测直接点「立即打卡」。

**Q：提示「登录态已失效」？**
Cookie 过期或被异地登录踢掉。点「重新登录」重登一次即可，其它配置不变。

**Q：提示「分享已提交，服务端暂未计入」？**
`share/add` 用无效 Cookie 也返回 `code:0`，所以程序不认返回码，只认回查到的任务状态。这句说明接口收下了但 B 站没记经验，通常隔几分钟再验会有变化。

**Q：每天跑几次、消耗多少流量？**
一次开机跑一次，5~7 个请求，几十 KB。

**Q：会不会被封号？**
频率与手动开网页版接近，风险很低。但自动化本身不等同官方行为，**风险自担**，不要多账号批量跑。

---

## 14. 版本演进

| 版本 | 关键变化 |
|---|---|
| v1.0.0 | 首版：开机拉起 + 网页登录 + 四项任务 |
| fix1~2 | 改真实 UA、错误码可见；修「一律判成功」的假成功 |
| fix3~4 | 补观看任务；分享/心跳改回查确认 |
| fix5~8 | 逐行通读修 5 处；缺 `bili_jct` 显式提示；心跳判据改确认；LV0 说明 |
| fix9~13 | 曾加扫码登录、打卡并发闸门、权限与登录专项、登录返回兜底 |
| fix14~19 | **移除扫码**；登录页修 5 处（闪退、`bilibili://` 误报、登录态基准、每秒落盘、后台空转） |
| fix20~22 | 内置《免责声明》：首次强制同意 + 开机链路同样校验 + 4 个界面测试 |
| fix23 | 全量通读后再修 5 处：并发闸门、失败也存报告、心跳加真实间隔、网络检测防重入、报告格式统一 |

---

## 15. 免责声明与许可

本应用是**个人自用的辅助工具**，非哔哩哔哩官方产品，与其无任何隶属或合作关系。使用自动化可能带来的账号风险（任务不计入、触发风控、账号异常等）**由使用者自行承担**。完整条款见应用内「免责声明」。

若对外开源，建议：

- 许可证用 **MIT**（想强制衍生开源则用 GPL-3.0）
- 仓库名避开「哔哩哔哩 / bilibili」商标，用 `bili-daily-helper` 这类中性名
- 提交前确认 `.gitignore` 已排除 `local.properties`、`build/`、`.gradle/`，且仓库内无任何真实 Cookie 与账号截图
