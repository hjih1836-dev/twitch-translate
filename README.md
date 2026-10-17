# Twitch 弹幕 + 主播语音实时翻译 — 英语 → 中文 · 悬浮覆盖

一个 Android App：实时拉取任意 Twitch 公开频道的聊天（弹幕），用 on-device 翻译成中文，再以悬浮窗弹幕条形式直接覆盖在屏幕上。

**新功能：主播语音翻译** — 用麦克风收手机外放 / 电脑音箱的声音，用系统语音识别把主播说的话识别成英文，再翻译成中文，同样以悬浮条 `🎙️ 主播: 中文翻译` 显示。弹幕和主播语音可以同时开。

默认测试频道：`shroud`。通用：App 启动有频道名输入框，改任意频道名即重新连接，所有公开频道都兼容。

> 本项目不使用截屏 OCR。Twitch 聊天本身就是文本，直接走 Twitch IRC 更快、更准、更省电，也真正跟得上弹幕速度。

## 功能

- **通用频道**：输入框默认 `shroud`，可改任意频道。切换频道：先点停止，再改名重新开始。
- **实时拉取**：Twitch IRC `irc.chat.twitch.tv:6697` SSL，匿名 `justinfan` 方式读公开频道聊天，不需用户 OAuth、不需 API Key。
- **翻译**：英语 → 中文，默认 ML Kit on-device 翻译（离线、免费、快）。见下方“切换到云端翻译”。
- **主播语音翻译（新）**：麦克风收音 + 系统 SpeechRecognizer 识别主播英文 → ML Kit 翻成中文 → 悬浮条显示。同一台手机外放看直播能用；电脑看直播、手机放旁边收电脑音箱也能用。主界面有开关 `识别并翻译主播说的话`，需要录音权限，点开始翻译后生效。
- **去重 + 限速**：同用户 15 秒内重复、同文案全局 5 秒内重复会被丢弃；翻译节流约 4 条/秒；队列上限 60 条，超了丢最旧的，避免刷屏卡死。
- **悬浮覆盖**：前台 Service + `SYSTEM_ALERT_WINDOW` 悬浮窗，翻译条以圆角卡片显示，原文小字附在下面。支持开关、清空、透明度（30%–100%）、字体大小（12–24sp）、位置（顶部 / 中间 / 底部）。
- **历史记录**：主界面有实时翻译记录列表，方便调试和回看。

## 项目结构

```
twitch-translate/
├── settings.gradle.kts
├── build.gradle.kts            # AGP 8.2.0 / Kotlin 1.9.20
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── gradlew                      # 存根：优先用 Android Studio 编译
├── app/
│   ├── build.gradle.kts         # Compose BOM 2023.10.01, ML Kit Translate 17.0.2
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml  # INTERNET / SYSTEM_ALERT_WINDOW / FOREGROUND_SERVICE / POST_NOTIFICATIONS
│       ├── java/com/example/twitchtranslate/
│       │   ├── MainActivity.kt          # 主界面：频道输入、权限引导、悬浮设置、历史
│       │   ├── OverlayService.kt        # 前台服务：IRC + 翻译 + 悬浮窗
│       │   ├── TwitchChatClient.kt      # Twitch IRC 客户端（SSL 6697，匿名 justinfan）
│       │   ├── Translator.kt            # ML Kit 翻译 + CloudTranslator 占位 + 俚语表
│       │   └── DanmakuRepository.kt     # 弹幕状态流 + 去重/限速
│       └── res/values/
│           ├── strings.xml
│           └── themes.xml
└── README.md
```

技术：Kotlin + Jetpack Compose（Material3）+ Coroutines + ML Kit Translate。
没有用 XML 布局，全部 Compose，便于悬浮窗复用同一套 UI。

## 如何在 Android Studio 打开

1. 打开 Android Studio（建议 Hedgehog / Iguana 及以上，内置 JDK 17）。
2. `File → Open`，选择 `twitch-translate/` 文件夹。
3. 等待 Gradle Sync 完成。首次 Sync 会下载 AGP / Kotlin / Compose / ML Kit 依赖，需要联网。
4. 如果提示缺少 SDK，在 `File → Settings → Appearance & Behavior → System Settings → Android SDK` 安装 Android 14（API 34）SDK Platform + Build-Tools。

> 本仓库的 `gradlew` 是存根（当前环境无 Android SDK，无法在此完整编译验证）。在有 Android SDK 的机器上，推荐直接用 Android Studio 的 Build 菜单编译；也可以安装 Gradle 8.2 后用 `gradle assembleDebug`。

## 如何编译 APK

在 Android Studio 里：

- Debug 包：`Build → Build App Bundle(s) / APK(s) → Build APK(s)`，完成后点 `locate` 找到 `app-debug.apk`。
- Release 包：`Build → Generate Signed App Bundle / APK`，按向导签名（不要把 keystore 密码写进代码或提交到仓库）。

命令行（需已安装 Gradle 8.2 + Android SDK，`ANDROID_HOME` 已配置）：

```bash
cd twitch-translate
gradle assembleDebug
# 输出：app/build/outputs/apk/debug/app-debug.apk
```

## 如何在真机上测试（以 shroud 为例）

1. 手机打开开发者选项 + USB 调试，用 USB 连电脑，在 Android Studio 顶部选你的真机，点 Run 安装。
   - 或把上面编译的 `app-debug.apk` 传到手机，直接点击安装。
2. 打开 App **Twitch Translate**：
   - 点 **悬浮窗权限**，在系统设置里允许“在其他应用上层显示”。
   - Android 13+ 再点 **通知权限**，允许通知（前台服务通知用）。
3. 频道名保持默认 `shroud`，点 **开始翻译**。
   - 首次启动会下载 ML Kit 英→中翻译模型（约 30–40 MB），请保持联网，状态栏会显示“正在连接”。
   - 模型下载完后，状态变为“已连接，等待弹幕”。
4. 切换到 Twitch App，打开 `shroud` 的直播（或任意正在直播的频道）。
   - 如果该频道当前没直播 / 没人发弹幕，悬浮窗不会有内容，这是正常的。可以在主界面历史列表里确认是否已连接。
   - 有英文弹幕时，悬浮条会实时出现在屏幕顶部（默认），格式为 `用户名: 中文翻译` + 下一行小字原文。
5. 测试通用频道：
   - 回到本 App，点 **停止**，把频道名改成另一个正在直播的频道（例如 `xqc`、`pokimane` 等任意公开频道），再点 **开始翻译**。
6. 调整悬浮窗：
   - 在主界面改透明度、字体大小、位置（顶部/中间/底部）。注意：改位置后需重新开始一次才会重新计算悬浮窗位置（当前版本限制，见下方）。
   - 点 **清空** 可清空历史和悬浮条。

### 快速自测（不用等直播）

如果 `shroud` 当时没直播，可以先用 Twitch 的聊天测试：
- 在 Twitch 网页版打开任意热门直播间的聊天，手动发一条英文消息（例如 `hello world, nice shot!`），App 应在 1–2 秒内显示中文翻译。
- 或在 App 里观察状态：从“正在连接”变为“已连接，等待弹幕”即表示 IRC 已通。

## 权限说明

| 权限 | 用途 | 何时申请 |
|---|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` | Twitch IRC + ML Kit 模型下载 | 安装时（普通权限） |
| `SYSTEM_ALERT_WINDOW` | 悬浮窗覆盖 | 启动时引导用户去系统设置打开 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | 保持 IRC + 翻译在后台运行 | 安装时 |
| `POST_NOTIFICATIONS` (Android 13+) | 前台服务常驻通知 | 启动时申请 |
| `WAKE_LOCK` | 避免翻译中途被休眠打断 | 安装时 |

启动流程已处理引导：未授予悬浮窗权限时点“开始”会先跳转到系统设置。

## 切换到云端翻译

默认是 ML Kit 离线翻译，优点是免费、快、无 Key；缺点是对俚语、缩写、错别字、梗（`KEKW`、`Pog` 等）翻译较弱，代码里已用一个小俚语表做了启发式展开。

要换云端（例如 Google Cloud Translation / DeepL / Azure Translator）：

1. 在云厂商控制台创建一个翻译 API Key。**绝不要把 Key 硬编码进源码或提交到 Git。** 用 `local.properties` / `BuildConfig` / 加密存储注入。
2. 在 `Translator.kt` 里实现 `CloudTranslator.translate()`：用 `HttpsURLConnection` / OkHttp POST 到厂商 endpoint，解析返回的译文。
3. 在 `OverlayService.kt` 里把 `private var translator: Translator = MlKitTranslator()` 改成 `CloudTranslator()`，或加一个设置开关让用户选择离线/云端。
4. 注意云端按字符/请求计费，弹幕量大时费用会涨，建议保留去重 + 限速逻辑，并考虑只翻译长度 ≥ 3 且含英文的弹幕。

`CloudTranslator` 当前是一个占位实现（直接返回原文），就是为了避免误传“已接云端”。

## 已知限制（请先读）

- **Twitch 匿名 IRC 速率**：匿名 `justinfan` 连接不需 OAuth，但受 Twitch 限制：匿名 JOIN 频率约 20 个 / 30 秒，单个连接的消息接收也有全局限速。单个频道正常观看够用；超大流量爆发时 Twitch 侧本身就可能丢消息，不是 App 的 bug。需要更高配额时，需改用已认证的 Twitch OAuth + IRC（`oauth:xxx` 作为 PASS），并申请 Twitch 开发者应用。
- **翻译质量**：ML Kit 离线模型对短句、日常口语较好，对游戏黑话、拼写错误、大小写混用、全大写刷屏效果一般。已做俚语展开 + 短消息过滤（<2 字符跳过），仍会有误译。
- **悬浮窗系统限制**：部分厂商（小米 / 华为 / OPPO / vivo 等）的 MIUI / EMUI / ColorOS 等对悬浮窗有二次权限 / 省电策略，可能需要额外在厂商管家 / 电池优化里允许“后台运行”和“悬浮窗”，否则切后台后服务会被杀。Android 12+ 对前台服务启动也有后台启动限制，请从主界面点开始，不要从后台唤起。
- **位置热更新**：当前版本改悬浮窗位置后需重新开始一次才生效（`WindowManager.LayoutParams` 在 `addView` 后未重新 `updateViewLayout`）。如需热更新，可在 `OverlayService` 里监听 `settings.position` 变化并调用 `windowManager.updateViewLayout()`。
- **不适用于加密 / 需登录的聊天**：本 App 只读公开频道聊天。订阅者专属 / 仅关注者 / 慢速模式下的聊天仍能收到，但慢速模式下消息本身就少。
- **未用 OCR**：本 App 故意不用截屏 OCR，所以它只对 Twitch 聊天有效，不能翻译 Twitch 之外的 App 画面或视频内嵌文字。如需通用截屏翻译，需要另做 MediaProjection + OCR 版本（延迟和耗电会明显更高）。

## 常见问题

- **悬浮窗不显示**：检查系统设置 → 应用 → Twitch Translate → “在其他应用上层显示”是否已开；检查是否点了“开始翻译”且状态为已连接；检查主界面悬浮开关是否打开。
- **一直显示“正在连接”**：检查网络能否访问 `irc.chat.twitch.tv:6697`（部分网络 / 代理会拦截 IRC 端口）；检查频道名是否拼错；尝试换一个正在直播的频道。
- **翻译一直是英文原文**：可能是 ML Kit 模型还没下载完，等一会儿；或消息是纯表情/符号/链接，代码会直接返回原文（有意为之）。
- **手机发热 / 卡**：弹幕爆发时翻译 + 悬浮刷新压力大，可在主界面把字体调小、透明度调低、或在高峰时点停止休息一会儿。不要把限速调得更高。

## 安全

- 代码里没有任何密钥、Token、密码。Twitch 匿名登录用的是公开约定的 dummy 密码 `SCHMOOPIIE`（Twitch IRC 文档里的匿名占位，不是真实密钥）。
- 不要把云端翻译 Key 写进代码、日志或截图里。
- 悬浮窗只在本地显示翻译，不上传聊天内容到任何第三方服务器（ML Kit 离线翻译本身不联网，除模型下载外）。

## 许可

本示例代码仅供学习和个人使用。Twitch、Twitch IRC、ML Kit 分别属于各自的服务条款，使用时请遵守 Twitch 社区准则和相关服务条款。
