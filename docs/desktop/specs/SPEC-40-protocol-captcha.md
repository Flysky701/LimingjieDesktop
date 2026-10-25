# SPEC-40 协议设备画像、网关与人工验证码（WS-E）

## 1. 背景：语义从「本机」变成「目标模拟器」

在 Android 上，助手和游戏装在同一台设备上，协议画像（`Build.*`、`ANDROID_ID`、显示尺寸、游戏包的版本和签名）读的就是本机。
在桌面上，助手运行在 PC，游戏运行在模拟器里。**画像必须描述目标模拟器**：所有字段都从 ADB 读取（SPEC-10 §9），绝不使用 PC 的信息（例如 Windows 主机名、PC 分辨率）。

## 2. `DeviceIdentitySnapshot`

```kotlin
data class DeviceIdentitySnapshot(
    val deviceKey: String,            // serialno + android_id 的稳定组合
    val brand: String, val model: String,
    val release: String, val sdkInt: Int,
    val supportedAbis: List<String>,  // ro.product.cpu.abilist 按逗号拆分，保持顺序
    val androidId: String,            // 可能为空
    val displayWidth: Int, val displayHeight: Int,  // wm size，按横屏（宽 ≥ 高）记录
    val packages: Map<String, GamePackageIdentity>, // 包名 → versionName / versionCode / certificateMd5
    val capturedAtMillis: Long,
)
```

- 每次连接 Ready 后刷新；某个游戏包的 `lastUpdateTime` 变化时重新读取它的签名。持久化在 `<data>/device/identity-<deviceKey>.json`。
- 「当前快照」= 当前所连设备的快照。**没有连接设备时，使用上次连接的设备的快照**，并在账号页标明来源（设备名和读取时间）。完全没有快照时，登录类操作被阻止，提示：「尚未读取模拟器设备信息，请先在『设备』页连接模拟器」。
- `displayWidth × displayHeight` 按横屏记录。上游取的是助手应用自身的 `displayMetrics`，在模拟器横屏使用时也是横屏尺寸；这一取舍登记到 DECISIONS。

## 3. 画像工厂替身

两个替身的公共签名与上游逐字一致（SPEC-00 §7），构造参数里的 `Context` 是垫片 Context，不使用。

**`AndroidBilibiliSdkRequestProfileFactory.create()`**：
- 字段列表、`linkedMapOf` 的**键顺序**、常量（`sdk_ver`、`server_id`、`domain`、`app_id` 等）与上游完全一致。
- 来源对照：`support_abis` ← `supportedAbis.joinToString(",")`；`dp` ← `"${w}*${h}"`；`pf_ver` ← `release`；`brand` / `model` ← 快照；`version_code` / `ver` / `apk_sign` ← `packages["com.bilibili.priconne"]`；`android_id` ← 快照中的值，为空时用 installId（与上游 `ifBlank { installId }` 一致，FB-11）；`udid` / `buvid` 的派生公式与上游一致（`installId:androidId:certMd5` 的 SHA-256 / MD5）。
- installId：prefs `protocol-install-identity` 的 `install-id`，与上游逻辑相同（首次生成 UUID，`check(commit())`）。
- B 服包未安装在当前模拟器上时，抛出与上游语义一致的错误（「无法读取国服游戏签名」）。

**`AndroidGameProtocolProfileFactory.create()`**：headers 的键顺序和常量与上游一致；`APP-VER` ← 对应包的 versionName；`User-Agent`、`PLATFORM-OS-VERSION` 中的系统版本和 API 级别 ← 快照；`DEVICE-NAME` ← `brand + " " + model`（过滤空值，空时为 `"Android"`）。

**漂移检查（必须）**：上游原实现依赖 `Build` / `Settings`，无法在 JVM 上运行。因此测试从上游源文件中提取 `linkedMapOf(…)` 块的键列表（按顺序）和 `companion object` 中的常量，与替身用 fixture 快照生成的结果比对，断言**键集合、顺序和常量值完全相同**。上游增删字段时 CI 失败。

## 4. 网关

`BilibiliSdkGatewayFactory`、`BilibiliGameGatewayFactory` 原样保留（K 类）。它们内部各自创建 OkHttp 客户端，超时和重试设置不变。

**网络模式（D-19）**：JVM 参数 `-Djava.net.useSystemProxies=true`，跟随 Windows 系统代理。设置中提供「直连」：通过 `ProxySelector.setDefault` 安装一个始终返回 `Proxy.NO_PROXY` 的选择器（全局生效，不需要改上游代码）；切换后立即对新请求生效。已知限制：Java 不支持 PAC 自动配置脚本，只支持静态代理（写入 KNOWN_LIMITATIONS 和 README）。

## 5. Geetest 人工验证（D-10）

**替身** `GeetestCaptchaDialog(state, isWorking, onSolved, onError, onDismiss)`，签名与上游一致，是一个 Compose `Dialog`：

- 显示：标题「人工安全验证」、`账号：<别名>`、说明「验证由 Geetest 官方组件在系统浏览器中加载，请在浏览器中手动完成。登录账号和密码不会传入该页面。」、`state.error`（红色）、`isWorking` 时显示进度。
- 按钮：「在浏览器中打开」（再次打开）、「复制链接」、「取消验证」（`isWorking` 时禁用，与上游一致）。
- 对话框打开时（`state.challenge.challenge` 变化时重新创建会话）自动启动回环服务并打开系统浏览器；对话框关闭（`DisposableEffect` 的 onDispose）时注销该会话。

**回环服务** `CaptchaHost`（`DesktopBindings.captchaHost`，实现位于 `:app` 或 `:platform`，用 JDK 自带 `com.sun.net.httpserver` 即可）：

- 只绑定 `127.0.0.1`，端口随机（0）。每个会话一个随机路径令牌（≥ 128 位，URL 安全 Base64）：`GET /c/<token>` 返回页面；`POST /c/<token>/solved`、`POST /c/<token>/error` 接收结果。
- 页面内容 = 上游 `GeetestPageBuilder.build(challenge)` 的输出，在 `<head>` 的**最前面**注入：

  ```html
  <script>window.AndroidCaptcha={onSolved:function(v){…fetch POST…},onError:function(m){…fetch POST…}};</script>
  ```

  保证上游页面脚本调用 `AndroidCaptcha.onSolved(validate)` / `AndroidCaptcha.onError(message)` 时桥已经存在。注入点找不到 `<head>` 时直接失败（说明上游页面结构变了，需要更新）。
- 安全：校验 `Host` 为 `127.0.0.1:<port>`；POST 校验 `Origin` 为 `http://127.0.0.1:<port>`；请求体 ≤ 8 KB；令牌一次性：收到 solved 或 error 后会话关闭；会话 10 分钟超时，超时按 `onError("验证已超时，请重新发起登录")` 处理；响应带 `Cache-Control: no-store` 和 `Referrer-Policy: no-referrer`。
- 结果校验与上游桥一致：`validate` 为空白或长度 > 4096 → `onError("验证组件返回了无效结果")`；错误消息截断到 120 个字符。回调派发到 `Dispatchers.Main`（对应上游的 `webView.post`）。
- 系统浏览器用 `Desktop.browse`（不可用时调用 `rundll32 url.dll,FileProtocolHandler <url>`）；都失败时（FB-05）对话框直接显示可复制的链接和说明。
- 不注入任何账号信息；页面和日志里都不出现令牌以外的会话信息；日志中的 URL 需要把令牌打码。

**备选（R-02）**：如果 L3 实测证明 Geetest 拒绝 `127.0.0.1` 来源，改为 KCEF 内嵌浏览器，以 `https://line1-sdk-center-login-sh.biligame.net/` 作为 base URL 加载同一页面，其余逻辑不变。这是一项需要登记的决策，必须附证据。

## 6. 测试

- 画像：字段对照与漂移测试；`android_id` 为空的回落；B 服包缺失；键顺序。
- 回环服务：令牌错误返回 404；Host / Origin 错误返回 403；请求体过大返回 413；重复提交被拒绝；超时；注入后的页面中桥定义位于第一个 `<script>` 之前；无效 validate 走 `onError`；回调线程为 Main。
- 端到端（无需真实 Geetest）：用一个假的挑战页面替换 gt.js 的地址（仅测试），通过 HTTP 客户端模拟浏览器提交，验证 `onSolved` 被调用一次。
