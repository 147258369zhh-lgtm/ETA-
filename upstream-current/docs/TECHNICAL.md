# 技术实现

模块按进程与功能域安装针对性的 Hook。入口只负责生命周期、进程筛选、配置注入与安装结果汇总；具体目标定位和拦截逻辑留在各自功能域中。

## Hook 安装与诊断

- 每个功能域通过 `HookRegistrar` 注册 Hook，并使用稳定 ID、`PROTECTIVE` 异常模式和统一优先级策略。
- 安装结果区分 `INSTALLED`、`MISSING`、`FAILED`、`SKIPPED`，保留 `HookHandle`，便于定位 ROM 或目标 App 升级后的签名漂移。
- 普通目标缺失与反射异常按功能域失败开放；`HookFailedError` 等框架级 `Error` 不会被普通异常隔离层吞掉。
- `ModuleMain` 会尽早过滤无关进程并调用 `detach()`，避免在不需要的进程中继续保留生命周期回调。
- 小布和双指识屏的混淆目标在安装期通过 DexKit 的字符串、签名和调用关系定位；签名足够唯一的接口使用结构反射。零候选或多个候选均放弃接管，不选择第一个结果。描述符缓存随 APK 的 DEX 校验值和查询规则版本失效，缓存命中后仍校验反射签名；解析桥在安装结束时关闭，运行回调不扫描 DEX。
- DexKit 原生库先使用框架标准加载及模块安装目录；两者不可用时，从模块 APK 提取与当前进程位数匹配的原生库到宿主私有缓存。缓存按库内容的 SHA-256 隔离，复用前与 APK 字节校验，写入后设为只读并原子替换。库始终由模块 ClassLoader 加载，兼容压缩打包；日志分别报告原生库加载和 JNI 初始化失败，不记录 linker 消息中的文件路径。

## 日志与 Release 裁剪

Eta 使用同一组四级日志语义，并按运行环境选择后端：App 与 Agent Runtime 通过 `AndroidAgentLogger` 写入 logcat，Hook 进程通过 `ModuleLogger` 写入 Xposed 日志。业务代码不得直接调用 `android.util.Log` 或 `XposedModule.log`。

| 级别 | 使用范围 | Release |
| --- | --- | --- |
| `DEBUG` | 高频正常流程、目标匹配、重试细节、尺寸与计数 | 全部裁剪 |
| `INFO` | 低频生命周期、Hook 安装汇总、特权动作的结构化摘要 | 保留 |
| `WARN` | 可恢复降级、fallback、目标签名漂移、重试耗尽 | 保留；高频事件必须节流 |
| `ERROR` | 当前请求或功能确定无法完成、关键不变量被破坏 | 保留 |

取消、功能关闭和可选目标缺失不记为 `ERROR`。`debug` 只接受惰性 supplier；supplier 必须是纯观察代码，不能执行 Hook、反射写入、状态变更或其他业务副作用，因为 Release 的 R8 会删除整次调用。

任何级别都禁止记录 Prompt、请求或响应正文、API Key、认证 Header、Cookie、工具参数与结果、原始命令、stdout/stderr、URI、文件路径、图片内容、应用清单和原始运行标识。异常默认只记录类型，不拼接 `Throwable.message`；外部或模型生成的名称必须先转成受长度和字符集约束的安全 token。只有确认不承载用户数据的框架或反射异常才允许附带完整堆栈。

Release 裁剪以 `app/proguard-rules.pro` 为唯一可执行事实来源，规则边界如下：

- `-maximumremovedandroidloglevel 3 class io.github.mangi.eta.** { *; }` 只删除 Eta 自有代码中的 Android `VERBOSE/DEBUG`，不影响依赖库。
- 对 `AgentLogger.debug(Function0)`、`AndroidAgentLogger.debug(Function0)` 和 `ModuleLogger.debug(Function0)` 使用精确的 `-assumenosideeffects`，覆盖 R8 无法识别的 Xposed 日志后端。
- 不为 `INFO/WARN/ERROR` 声明无副作用，不使用 `*Logger` 或全局 `android.util.Log` 通配裁剪规则。
- 每次修改规则后同时构建 Debug 与 Release，并检查 R8 configuration/usage、DEX 日志调用、代表性日志字符串和 Xposed 入口元数据。

上述策略依据 Android 官方的 [R8 附加规则](https://developer.android.com/topic/performance/app-optimization/additional-rule-types)、[日志信息泄露防护](https://developer.android.com/privacy-and-security/risks/log-info-disclosure)、AOSP [日志级别约定](https://source.android.com/docs/core/tests/debug/understanding-logging)、OWASP [运行时日志测试](https://mas.owasp.org/MASTG/tests/android/MASVS-STORAGE/MASTG-TEST-0203/) 与 [CWE-532](https://cwe.mitre.org/data/definitions/532.html)。

## Eta 原生数字助理

Manifest 注册 `VoiceInteractionService`、与浮窗同进程的 `VoiceInteractionSessionService`、全屏 `TYPE_APPLICATION_OVERLAY` 助理浮窗以及 Android 助理角色资格要求的 `RecognitionService`。设置页只负责打开系统数字助理选择界面；浮窗使用已授予的麦克风权限；未授权时提供应用权限设置入口并保留键盘输入。

`VoiceInteractionSession` 承接系统入口及系统提供的 Assist 数据，关闭自身 UI；`EtaAssistantOverlayService` 持有全屏窗口、语音序列动画、语音输入和键盘输入。窗口通过 `setFitInsetsTypes(0)` 绘制到状态栏、导航栏与显示开孔后方，可交互内容再通过状态栏、导航栏与 IME Insets 保持可触达，避免给根容器增加 Insets 后截断 edge-to-edge 背景。用户提交的文本交给 `AgentRuntimeClient`；请求、流式结果、前台工具收起、取消与归档沿用既有 Runtime 协议。前台工具执行前，Eta 自有入口在主线程定向移除窗口并通知系统会话 `hide()`，Runtime 等待该 View 真正 detach 后才继续；外部助手入口仍使用返回动作和目标包窗口确认。唤醒时已获麦克风权限且启用语音识别就启动一次云端语音识别，最终识别结果直接提交；流式预览只显示在输入区。失败时按凭据、网络、服务或麦克风原因提示并提供语音设置入口与键盘输入。切换键盘、关闭、再次唤醒和前台工具收起都会终止录音并隔离迟到结果。

`:voice` 与 `:recognition` 进程只初始化本地偏好，不预热数据库、Skills 或 Xposed UI 服务。`RecognitionService` 仅保留 Android 数字助理角色资格所需声明，不由当前浮窗调用；HyperOS 按键适配不在当前实现范围内。

助理上下文通过 `SHOW_WITH_ASSIST`、`SHOW_WITH_SCREENSHOT` 请求，Android 17 同时请求结构化屏幕内容并声明对应权限及服务 XML 属性。Session 与浮窗在主进程共享单次唤醒对象，Intent 只携带关联 ID；新唤醒替换旧对象，关闭窗口或系统会话隐藏后释放上下文，迟到的异步结果不写回新入口。截图与应用文本独立处理；提交时最多等待剩余的两秒采集窗口，缺失部分不阻塞普通提问。同一次浮窗内的追问沿用该次唤醒快照，并明确标注采集时间；前台工具改变界面后不把旧快照当作实时屏幕。

应用内容按可见窗口、节点数、深度和文本容量限制，跳过密码输入节点；截断会附带标记。屏幕正文以独立可选字段进入 Runtime，仅投影到当前模型请求，不改写用户原话、不写入持久上下文或日志；截图继续通过既有文件描述符链路进入 Runtime。系统共享开关、受保护窗口或应用未提供内容时允许部分数据或空结果，GUI 操作本身仍遵循无障碍工具的能力要求。

GUI、Root、数字助理与内置浏览器的模型截图保持原尺寸与像素。已有编码的截图直接上传原始字节；Bitmap 只在采集端编码一次 PNG，Runtime 不再重复转码。浏览器只在主线程绘制视口，编码在执行线程完成。聊天卡片的小预览单独生成，不参与模型输入；普通附件和文件图片继续使用 JPEG 兼容编码。

## system_server

- **电源键接管**：Hook `PhoneWindowManagerExtImpl$OplusSpeechHandler.handleMessage()` 处理系统分发给小布的唤醒消息（`what == 0x3F3`）。目标为小布时直接执行原方法；目标为 Gemini 或 Eta 时才拦截并分发到对应入口。
- **兼容配置**：三态目标写入字符串键；键不存在或值非法时读取旧 `POWER_KEY_TAKEOVER` 布尔协议，`true` 继续表示 Gemini，`false` 表示小布。新安装默认保持小布，旧用户不会因新增 Eta 被改写目标。
- **数字助理配置修复**：独立自动设置开关开启后，开机、解锁、切用户及启动失败恢复时，通过 `AssistantManager` 异步校正当前 Gemini/Eta 目标的 `android.app.role.ASSISTANT` 与 secure settings。小布模式和开关关闭时不写系统配置；校验缓存及异步回调同时核对用户与目标，旧目标任务不会继续覆盖新选择。
- **唤起逻辑优化**：Gemini 恢复原有 `VoiceInteractionManagerService`、`ACTION_ASSIST`、`ACTION_VOICE_COMMAND` 顺序；Eta 优先使用活动 `voiceinteraction` 会话，再在已经配置为默认助理时尝试同包 `ACTION_ASSIST` 桥。所有路径失败后立即执行小布原逻辑，不阻塞系统回调。
- **息屏后维持 Hey Google 可用**：Hook `PhoneWindowManager.screenTurnedOff()`，在默认显示息屏后短延迟检查 Google 的 `SoftwareTrustedHotwordDetectorSession`。只有已有 `mSoftwareCallback` 且当前未 running 时，才恢复 `startListeningFromMicLocked()`；亮屏或恢复成功后会取消未执行任务。
- **一圈即搜支持**：强制启用 `ContextualSearchManagerService`，将包名指向 Google App，并放行 `SystemUI` 与 ColorDirectService 的调用权限。作为一圈即搜的底层依赖始终执行，不可关闭。
- **无障碍保护**：复用已验证的 `SystemServer.startOtherServices(TimingsTraceAndSlog)` 生命周期点，在系统服务启动完成后接入事件驱动保护。后台工作复用 Android `BackgroundThread`，不开模块线程、不轮询；开关默认关闭，开启请求需同时通过 signature 权限、真实发送 UID、服务声明与 APK signer 钉扎校验。保护只维护 owner 用户中的 Eta 组件和总开关，保留其他服务；断连时通过仅允许 `system` UID 调用的健康 Provider 确认，并对 Eta 做带次数上限和冷却的定向重绑。

## 无障碍保护

无障碍保护要求 Android 14+，控制广播依赖系统提供的真实发送 UID；Android 13 不安装保护控制入口，也不通过弱化身份校验提供回退。

「强制保持无障碍」默认关闭。开启后，注入 `system_server` 的保护后端会校验 Eta 的服务声明、调用 UID 与 APK 签名，并在无障碍服务列表、总开关、Eta 安装包或 owner 用户解锁状态变化时校正配置。它保留其他无障碍服务，不依赖 App 自启动，也不执行周期轮询。

如果服务仍在启用列表中但没有真实连接，保护后端只重启 Eta 自身，并最多逐步尝试三轮；持续失败后冷却一分钟。ColorOS 持续反删设置时，写回间隔会从 300 ms 退避到 30 秒，稳定一分钟后恢复。关闭开关只停止保护，不会替用户关闭当前服务。

GUI 工具执行前仍会确认真实服务连接。保护未开启、system 作用域未生效或重绑超时时，本次动作会明确失败，不会改用 Root 或 Shell 偷偷修改无障碍设置。

若 App 控制入口不可用，可用 ADB 停止保护后再从系统设置关闭服务：

```bash
adb shell settings put global eta_accessibility_protection_enabled 0
```

删除该设置会恢复默认关闭状态。开发阶段主动更换签名且确认 APK 来源可信时，还需清除旧 signer 钉扎值：

```bash
adb shell settings delete global eta_app_signer_sha256
```

## SystemUI

拦截底部手势条长按触发的 OPPO OCR 识屏，通过 binder 直接调用 `contextual_search` 服务触发一圈即搜。

## ColorDirectService

在 `com.coloros.directui.ui.CollectInfoActivity` 中按 `Intent → void` 签名和输入诊断字符串定位识屏入口，不依赖混淆方法名。读取 `startInfo.directExt` 中的 `fingerTrigger` 与 `touchInfo.fingerCount`，确认是双指识屏后，直接调用 `contextual_search` 服务触发一圈即搜，并关闭小布识屏页面；调用失败才回退小布原逻辑。

## 小布助手

消息处理、文本入口、聊天派发、当前房间、Agent、快速模式和历史序列化等目标按语义定位；历史读取、写入及指令派发按唯一签名定位。快速模式与深度思考保持取反关系，不把其他布尔状态当作思考开关。交付结果所需接口不完整时，不认领请求，避免请求被接管后无法回写小布。

## 小布记忆

ColorOS 系统记忆存在 `com.oplus.aimemory` 的 `ai_memory` 数据库中。Eta 只在小布记忆默认进程保留模块生命周期，Hook 其 `DataShareProvider.call(String, String, Bundle)` 安装内部查询桥。Runtime 通过 Root 以固定 method 调用该 Provider，Hook 在拥有数据库权限的目标进程内执行固定只读查询。非 Eta method 会原样进入小布记忆自身逻辑；内部 method 只接受 UID 0，校验后以宿主身份查询，不向模型暴露任意 URI、表名或 SQL。

ColorOS 17 的小布记忆使用加密数据库时，Eta 优先校验已知 Room 数据库类型，按类型获取唯一的静态单例字段和现存连接字段；已知类型不再匹配时，才通过 DexKit 的数据库工厂特征重新定位。因此当前已知版本的记忆查询不依赖 DexKit 原生库加载成功。Eta 不调用工厂创建数据库，不提取密钥，也不关闭宿主持有的连接。未初始化时提示打开小布记忆后重试。普通 SQLite 文件继续以只读方式打开，两种连接共用固定查询引擎。读取异常与确实缺少表或字段分别报告，避免把加密读取失败误报成不支持的表结构。

查询协议只允许系统记忆、个人订单和已保存地点三种操作，请求与结果都有 UTF-8 字节上限。数据库层只查询预定义的表和字段，SQLite 标识符统一引用，以兼容 `shipments.order` 等与 SQL 保留字重名的字段。结果继续按敏感工具处理，不写入持久会话。

进程内查询桥不可用时，Runtime 才回退到 Root 快照路径：将主数据库及存在的 WAL、SHM 或 journal 边车文件限大复制到 Eta 缓存，用同一查询引擎只读打开，并在查询结束后立即删除。快照不是普通 SQLite 格式时，明确提示需要小布记忆 Hook，不尝试将加密文件作为普通数据库读取。

## 超级小爱

超级小爱入口当前锁定包名 `com.miui.voiceassist`、版本 `7.13.32.0016`（versionCode `507013032`），并且只在主进程与 `:core` 进程保留模块生命周期。版本不匹配或无法读取版本时不安装业务 Hook。

适配器在 `OperationManager.setQueryInfo(String, String, JSONObject)` 原方法执行前暂存对话 ID、查询文本和可选的 `extra_image_file_id`，同时从 `z10.a.processed(Instruction)` 记录终态 `SpeechRecognizer.RecognizeResult`。`y00.r0.C0(Event): boolean` 收到 `Nlp.Request` 时，小爱已经通过 `APIUtils.buildEvent` 生成了新的 Event ID，因此适配器按查询文本关联输入上下文，不能要求它与 `setQueryInfo` 的对话 ID 相等。只有配置、前缀、图片引用和后台队列全部通过检查后才认领请求并返回发送成功；否则只调用一次原方法，让超级小爱继续原生处理。

终态 ASR 会在 `setQueryInfo` 之前建立短时轮次状态。当前轮次确定由 Eta 接管时，`kh0.s0` 的 `execute` / `executeActionsAsync` 原生 Agent Action 会被跳过，避免本地动作链在模型请求认领前抢先打开设置或执行其他动作。轮次状态有时效并在小爱会话清理时释放；文档输入不会进入这条接管链路。

图片 ID 只解析为当前小爱进程可读的单个本地图片文件，认领前校验存在性、大小和文件头，不扫描目录，也不记录文件路径。图片正文继续通过 Eta 现有的文件描述符传输链路进入 Agent Runtime。文档理解、多图片以及无法解析的图片不在当前接管范围内。

结果使用超级小爱的 `FlowTemplateToastCard` 在主线程流式更新，完成后通过其原生 TTS 入口朗读。卡片、取消、结果恢复和 Runtime handoff 都绑定已认领的对话 ID 与独立的 `xiaoai` source；不会全局屏蔽�