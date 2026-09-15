package com.voicecontrol.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlin.concurrent.thread
import java.io.File

/**
 * 前台服务：会话期间占用麦克风，做【离线】语音识别。
 *
 * 安全设计（本项目最高优先级约束，不可妥协）：
 *  1. 识别出「退出」 → 立刻释放麦克风并停止；
 *  2. [WATCHDOG_MILLIS] 看门狗强制释放——时间制独挑大梁：到点前 20 秒预警，
 *     预警期说「继续」续期（仅预警期有效），不续期到点释放（2026-09-14 用户拍板
 *     删除静音自动释放——60s 安静就断太激进，交给看门狗时间制即可）；
 *  3. 锁屏立即释放，保证紧急通道随叫随到。
 */
class VoiceService : Service() {

    companion object {
        private const val TAG = "VoiceControl"
        const val CHANNEL_ID = "voice_session"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.voicecontrol.app.action.STOP"
        // 主页状态卡「已完成」停留时长：给足视觉确认，再自动回聆听态
        private const val PHASE_DONE_MILLIS = 1200L
        // 测试注入（开发期专用）：不经过麦克风，直接重放「识别→匹配→执行」全链路
        const val ACTION_SIMULATE = "com.voicecontrol.app.action.SIMULATE"
        const val EXTRA_TEXT = "com.voicecontrol.app.extra.TEXT"

        // 飞行记录仪（v0.51.0）：会话出生在偏好里留档、正常结束销档——
        // 下次启动若档还在 = 上次会话没落地（进程被杀/崩溃，当时无法记录），事后追认
        private const val KEY_SESSION_ACTIVE_SINCE = "session_active_since"

        // 自定义说法录入（v0.39.0）：绑定页请求捕获下一句识别原文——复用正常会话链路
        // （前台服务/看门狗/静音释放全在），听到第一句自动还麦。绑定页轮询 lastCaptured 取结果
        @Volatile
        var captureArmed: Boolean = false
        @Volatile
        var lastCaptured: String? = null

        // 听写武装标志（v0.40.0）：companion 存储——单发注入会 stopSelf 重启服务实例，
        // 实例字段会在两条注入之间丢状态（2026-09-12 实测踩坑）；截止时间=elapsedRealtime 毫秒
        @Volatile
        var dictationArmed: Boolean = false
        @Volatile
        var dictationDeadline: Long = 0L

        // 回声测试台（开发期专用）：用 USAGE_MEDIA 外放 assets/echo_cmd.wav
        // ——与抖音/视频 App 完全同一条音频通道，1:1 复刻「外放声音被麦克风拾取」场景。
        // 用途：修复前复现 bug（基线）、修复后验证回声是否真被消除。商用前与 SIMULATE 一并移除。
        //
        // 安全约束（2026-09-08 深夜噪音事故后强制）：绝不循环播放，播完自动停；
        // 并设硬性超时上限，即使 STOP 命令丢失也必定自动停，不可能无限念。
        const val ACTION_ECHO_TEST = "com.voicecontrol.app.action.ECHO_TEST"
        const val ACTION_ECHO_STOP = "com.voicecontrol.app.action.ECHO_STOP"
        private const val ECHO_TEST_FILE = "echo_cmd.wav"
        // 外放硬性超时上限（毫秒）：到点强制停，绝不依赖外部 STOP 命令
        private const val ECHO_TEST_MAX_MS = 12_000L

        // 离线 ASR 测试台（开发期专用，商用前移除）：把 assets 里的 wav 直接喂给识别器，
        // 不经过麦克风、不发出任何声音——用于静音诊断「点击十八→点击八」这类丢字问题。
        // 自建临时 recognizer/vad 实例（不复用会话中的），避开与识别线程的 native 并发。
        // 可传 EXTRA_SCORE 对比不同热词权重的准确率，一个 build 内完成 A/B。
        const val ACTION_ASR_TEST = "com.voicecontrol.app.action.ASR_TEST"
        const val EXTRA_WAV = "com.voicecontrol.app.extra.WAV"
        const val EXTRA_SCORE = "com.voicecontrol.app.extra.SCORE"

        // 输入框文本操作探针（开发期诊断，商用前移除）
        const val ACTION_TEXT_PROBE = "com.voicecontrol.app.action.TEXT_PROBE"

        // 崩溃触发后门（开发期诊断，商用前移除）
        const val ACTION_CRASH_TEST = "com.voicecontrol.app.action.CRASH_TEST"

        // 热词权重（contextual biasing）。保持 8.0：2026-09-08 曾用离线 ASR 测试台做 8.0 vs 2.0 A/B，
        // 两者对「点击十八/十三/二十五」识别结果完全一致——「权重过高导致丢字」假设被证伪，
        // 无证据不改；8.0 是实测能改善「抖婴→抖音」这类听岔的值。
        private const val HOTWORDS_SCORE = 8.0f

        /** 实时语音振幅（0~1）：识别循环每 100ms 按当前缓冲 RMS 更新，
         *  供顶部胶囊声波指示实时起伏（同一进程直接读共享字段） */
        @Volatile
        var liveAmplitude: Float = 0f
        const val SAMPLE_RATE = 16000

        // 看门狗：占用麦克风的基础时长（毫秒），到点强制释放
        // 2026-09-11 用户拍板：2 分钟 → 3 分钟（用着更从容，不必频繁喊「继续」）
        // 2026-09-14 用户拍板：3 分钟 → 5 分钟（每段更长，喊「继续」更少；硬顶 15 分钟不变）
        private const val WATCHDOG_MILLIS = 300_000L

        // 看门狗延期：每次「继续」延长的时长（毫秒）
        private const val EXTEND_MILLIS = 300_000L

        // 看门狗最多延期次数（基础 5 分钟 + 4 次 × 5 分钟 = 最多 25 分钟）
        // 2026-09-15 用户拍板：×2 → ×4（"四五二十"），总量 5+20=25 分钟
        private const val MAX_EXTENSIONS = 4

        // 胶囊文案统一由上面三个常量推导（2026-09-14 教训：常量改成 3 分钟、写死的「2 分钟」文案没跟上）
        private val EXTEND_MINUTES = EXTEND_MILLIS / 60_000L
        private val SESSION_MAX_MINUTES = (WATCHDOG_MILLIS + MAX_EXTENSIONS * EXTEND_MILLIS) / 60_000L

        // 休眠预警：看门狗到点前多久提示「即将休眠」（毫秒）
        private const val WARN_BEFORE_MILLIS = 20_000L

        // 静音自动释放已于 2026-09-14 用户拍板删除：会话时长完全由看门狗时间制管理

        // 退出命令词
        private const val CMD_EXIT = "退出"

        // 延期命令词（看门狗预警时说「继续」延长占用时长）
        private const val CMD_CONTINUE = "继续"

        // 纯语气词：ASR 常把口癖/残音识别成这些（如「返回」被听成「喂」）。
        // 商用体验原则（2026-09-08 用户定）：没识别对就保持安静继续聆听，不把误识别展示给用户。
        private val NOISE_WORDS = setOf(
            "喂", "喂喂", "嗯", "嗯嗯", "呃", "啊", "啊啊", "哦", "噢",
            "哎", "唉", "呀", "哈", "哈哈", "嘿", "诶", "欸"
        )

        // 命令冷却：同一动作执行后多久内不重复执行（防误触发连击）
        private const val COOLDOWN_MS = 1500L

        // 带附加提示的动作（执行器会写 actionNote，识别层优先展示提示而非通用文案）
        private val NOTE_ACTIONS = setOf(
            "volume_up", "volume_down", "volume_mute",
            "nudge_up", "nudge_down", "nudge_left", "nudge_right",
            "zoom_in", "zoom_out",
            "text_cursor_left", "text_cursor_right", "text_delete", "text_clear",
            "take_screenshot",
        )

        // 熔断：时间窗与阈值——窗口内执行次数过多即判定误循环，释放麦克风
        private const val CIRCUIT_WINDOW_MS = 8000L
        private const val CIRCUIT_MAX_EXEC = 6

        // 重复执行：最大次数（超过拒绝，防止失控/崩溃）
        private const val MAX_REPEAT = 10

        // 重复执行：两次动作之间的间隔（滑动拖动 550ms + 缓冲，避免打断上一段拖动手势）
        private const val REPEAT_INTERVAL_MS = 620L

        // 长按待命模式：进入后多久没收到数字/「中间」就自动退出
        private const val LONG_PRESS_MODE_TIMEOUT_MS = 5000L

        // 数字词热词（十一～九十九）：2026-09-08 真人实测发现「点击十八→点击二十/点击八」丢字。
        // 实测规律：单独念的完整数字词（如「十六」）识别极稳，动词+数字粘连时丢「十」——
        // 把全部两位数整词加入热词表，让解码时倾向输出完整数字词，不做两步式（保留一步直给的快）。
        private val NUMBER_HOTWORDS = buildList {
            val digits = listOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九")
            add("十")
            for (n in 11..99) {
                if (n % 10 == 0) continue // 整十数口语不常用，减词表体积
                add(digits[n / 10] + "十" + digits[n % 10])
            }
        }

        // 重复家族热词（v0.53.1）：此前整个家族缺席——解码器无偏置时偏爱常见搭配，
        // 用户实测「重复五次/六次」被听成「重复一次」、「重复一次」本身也时灵时不灵。
        // 「一次」~「十次」「两次」与「重复 ×」一并覆盖，「再来一次」为口语变体
        private val REPEAT_HOTWORDS = buildList {
            val ci = listOf("一次", "两次", "三次", "四次", "五次", "六次", "七次", "八次", "九次", "十次")
            ci.forEach { add(it); add("重复$it") }
            add("再来一次")
        }

        // App 名热词（纯汉字）：让 ASR 优先识别成正确 App 名（否则「抖音」易被听成「面嗯」等）
        private val APP_HOTWORDS = listOf(
            "抖音", "微信", "支付宝", "淘宝", "京东", "微博", "小红书", "知乎",
            "哔哩哔哩", "百度", "美团", "快手", "钉钉", "飞书", "拼多多", "闲鱼",
            "今日头条", "夸克", "高德地图", "酷狗音乐", "爱奇艺", "优酷",
            "腾讯视频", "滴滴出行", "饿了么", "携程旅行", "豆瓣", "贴吧",
            "喜马拉雅", "网易云音乐", "西瓜视频", "番茄小说"
        )
    }

    private var audioRecord: AudioRecord? = null
    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null

    // 音频前处理效果器（回声消除/降噪/自动增益）：挂在 AudioRecord 的会话上，释放麦克风时一并释放
    private var aecEffect: AcousticEchoCanceler? = null
    private var nsEffect: NoiseSuppressor? = null
    private var agcEffect: AutomaticGainControl? = null

    @Volatile
    private var recording = false

    private var recordThread: Thread? = null
    private val handler = Handler(Looper.getMainLooper())

    // 命令冷却：记录每个动作最后一次执行时间（elapsedRealtime 毫秒）
    private val lastExecTime = mutableMapOf<String, Long>()

    // 熔断：记录最近若干次执行时间，窗口内次数过多则熔断
    private val execHistory = ArrayDeque<Long>()

    // 横条文字恢复的定时任务：预警期间恢复预警文案（否则预警会被反馈文字顶掉，用户看不到就断），平时恢复「正在聆听」
    private val barResetRunnable = Runnable {
        SessionState.phase = SessionState.Phase.LISTENING   // 主页状态卡回到聆听态
        VoiceControlService.updateBar(
            if (sleepWarned) warnBarText() else "🎤 正在聆听…"
        )
    }

    /** 休眠预警横条文案：带剩余「继续」次数——预算看得见（2026-09-15 用户误以为 25 分钟自动给满） */
    private fun warnBarText() =
        "😴 即将休眠，说「继续」延长（剩余 ${MAX_EXTENSIONS - extensionCount} 次）"

    // 命令匹配层（v0.4：识别结果 → 词表纠错）。v0.39.0 起带用户自定义说法（语音绑定）：
    // 按绑定文件时间戳缓存重建——绑定保存后下一次识别即生效，无需重启会话/进程
    private var cachedMatcher: CommandMatcher? = null
    private var matcherStamp = Long.MIN_VALUE

    private fun currentMatcher(): CommandMatcher {
        val stamp = CustomBindings.stamp(applicationContext)
        cachedMatcher?.let { if (stamp == matcherStamp) return it }
        val json = assets.open("commands.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val bindings = CustomBindings.all(applicationContext).map { it.phrase to it.action }
        val built = CommandMatcher.fromJson(json, bindings)
        Log.i(TAG, "匹配器已构建：标准词表 + 自定义说法 ${bindings.size} 条")
        cachedMatcher = built
        matcherStamp = stamp
        return built
    }

    // 上一次动作（供「重复」命令回放）
    private sealed class LastAction {
        data class Command(val action: String) : LastAction()
        data class TapLabel(val number: Int) : LastAction()
        data class TapPoint(val x: Float, val y: Float) : LastAction()
    }
    private var lastAction: LastAction? = null
    private var repeatRunnable: Runnable? = null

    // 长按待命模式（两步式：先「长按」进入，再报数字/「中间」，提升「动词+数字」识别率）
    private var longPressMode = false
    private val longPressModeRunnable = Runnable {
        exitLongPressMode()
        VoiceControlService.updateBar("🎤 正在聆听…")
    }

    // 听写模式（v0.40.0，小米式一次性短听写，用户拍板）：说「输入/听写」立即进入，
    // 下一句识别原文直接写入输入框，说完停顿（VAD 分句）即结束——不做「退出听写」往返。
    // 武装标志存 companion（服务实例可能在两条注入间被 stopSelf 重启）；实例 runnable 只负责到点恢复横条文案
    private var dictationMode
        get() = dictationArmed && SystemClock.elapsedRealtime() < dictationDeadline
        set(value) {
            dictationArmed = value
            if (value) dictationDeadline = SystemClock.elapsedRealtime() + 12_000L
        }
    private val dictationTimeoutRunnable = Runnable {
        if (!dictationMode) {
            VoiceControlService.updateBar("🎤 正在聆听…")
            SessionState.lastMatch = "→ 听写超时已退出"
        }
    }

    // 「重复」回放（v0.39.1 治本）：不再维护白名单——任何派发成功的动作都记入 lastAction，
    // 「重复一次/N 次」回放的就是上一个动作本身，新增功能天然可重复、无需登记。
    // 历史教训：v0.4 白名单早于 v0.17 设备控制组，音量动作从未登记 →「增加音量后重复一次」失效。
    // 安全边界：exit 走红线不经派发（双保险见 dispatchCommand）；重复上限 10 次；音量受 80% 帽约束

    private val watchdogRunnable = Runnable {
        Log.i(TAG, "看门狗到点，强制释放")
        DiagnosticsHelper.log("看门狗到点，强制释放")
        releaseAndStop("看门狗强制释放")
    }
    // 静音自动释放 runnable 已删（2026-09-14 用户拍板）：安静不再提前断会话，看门狗时间制独挑大梁
    // 锁屏自动释放：黑屏（SCREEN_OFF）立即还麦，保证锁屏状态下随时能唤起小爱
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                Log.i(TAG, "锁屏，自动释放麦克风")
                releaseAndStop("锁屏自动释放")
            }
        }
    }
    // 休眠预警是否已显示：静音释放不得在预警出现前触发，否则用户永远等不到「即将休眠」提示
    @Volatile
    private var sleepWarned = false

    // 休眠预警：看门狗到点前提示「即将休眠，说「继续」延长（剩余 N 次）」
    private val warnRunnable = Runnable {
        sleepWarned = true
        Log.i(TAG, "WARN_SHOWN 休眠预警已显示")
        VoiceControlService.updateBar(warnBarText())
    }
    // 已延期次数（0 ~ MAX_EXTENSIONS）
    private var extensionCount = 0

    // 30 秒滚动窗口内的"近似退出被拦"时间戳（守卫升级阶梯用，见 dispatchMatched）
    private val fuzzyExitRejects = ArrayDeque<Long>()

    // 本次会话开始的 elapsedRealtime（0 = 无真实会话上下文，如 SIMULATE 注入）
    private var sessionStartElapsed = 0L

    // 当前段（基础段/延期段）开始的 elapsedRealtime：算「继续」早说时距预警还有多久
    private var segmentStartElapsed = 0L

    // 停止请求：释放后置 true，后台初始化线程逐阶段检查，防止「停止后初始化完成又抢麦」的竞态
    @Volatile
    private var stopRequested = false

    // 会话代际：每次启动新会话 +1，初始化线程捕获自己的代际，阶段间核对待办——
    // 加载期间被「退出再重开」顶替时，旧线程就地释放已分配资源，防双初始化 / native 内存泄漏
    @Volatile
    private var sessionGeneration = 0

    // 初始化互斥锁：串行化模型创建（每个 SenseVoice 约 200MB，并发创建会 OOM）
    private val initLock = Any()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        UsageLog.init(applicationContext)   // 幂等：加载使用记录
        CrashCatcher.register(applicationContext)   // v0.43.0：崩溃记录器（幂等）
        // 飞行记录仪（v0.51.0）黑匣子终检：上次会话的出生档还在 = 它没落地（进程被杀/崩溃，当时无法记录）。
        // 在此事后追认一条使用记录（任何启动形式必经：真实会话/SIMULATE/探针）；检测即销档防重复补记。
        // 活体检查：本进程会话还在录（如会话中收到停止/重开/注入）时档属于活会话，不是孤儿——
        // 真机验收 A 组实锤的误报（会话 21:36:44 出生、21:36:59 注入退出，终检把活会话当失踪）
        val bb = getSharedPreferences("app", MODE_PRIVATE)
        val orphan = bb.getLong(KEY_SESSION_ACTIVE_SINCE, 0L)
        if (orphan > 0L && !recording) {
            val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
            SessionState.lastMatch = "→ ⚠️ 上次会话异常终止（${fmt.format(java.util.Date(orphan))} 开始，无结束记录——进程被杀或崩溃，详见导出反馈）"
            Log.w(TAG, "BLACKBOX 上次会话无结束记录（开始于 $orphan），已补记异常终止")
            bb.edit().remove(KEY_SESSION_ACTIVE_SINCE).apply()
        }
        if (intent?.action == ACTION_STOP) {
            releaseAndStop("用户退出")
            return START_NOT_STICKY
        }

        // 输入框文本操作探针（开发期诊断，商用前移除）：验证微信等输入框的改文本/光标/粘贴可行性。
        // 不占麦不进会话；前置条件=目标聊天页已打开且输入框可见
        if (intent?.action == ACTION_TEXT_PROBE) {
            createChannelIfNeeded()
            startForeground(NOTIFICATION_ID, buildNotification("🔬 文本探针"))
            Thread {
                runCatching { VoiceControlService.textProbe() }
                runCatching {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }.start()
            return START_NOT_STICKY
        }

        // 崩溃触发后门（开发期诊断，商用前移除）：主动抛空指针验证 CrashCatcher 记录→导出链路。
        // 必须不在录音线程：崩溃由未捕获异常处理器记录后原样交还系统（行为=真实闪退）
        if (intent?.action == ACTION_CRASH_TEST) {
            createChannelIfNeeded()
            startForeground(NOTIFICATION_ID, buildNotification("💥 崩溃测试"))
            Thread {
                Thread.sleep(500)   // 等 startForeground 落地，避免被系统当成 FGS 超时而非测试崩溃
                throw NullPointerException("CRASH_TEST 主动测试崩溃")
            }.start()
            return START_NOT_STICKY
        }

        // 回声测试台（开发期专用）：外放命令词音频，复现/验证「抖音视频声音被误识别」
        if (intent?.action == ACTION_ECHO_TEST) {
            startEchoTest()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ECHO_STOP) {
            stopEchoTest()
            return START_NOT_STICKY
        }

        // 离线 ASR 测试台：静音、不占麦，用 wav 文件直接测识别准确率
        if (intent?.action == ACTION_ASR_TEST) {
            val wav = intent.getStringExtra(EXTRA_WAV) ?: "numbers.wav"
            val score = intent.getFloatExtra(EXTRA_SCORE, HOTWORDS_SCORE)
            runOfflineAsrTest(wav, score)
            return START_NOT_STICKY
        }

        // 测试注入（仅开发期；服务未导出，第三方 App 无法调用，商用前移除）：
        // 修复验证闭环的关键——bug 必须先在真机重放通过，才允许宣称「修好」
        if (intent?.action == ACTION_SIMULATE) {
            val text = intent.getStringExtra(EXTRA_TEXT)?.replace(" ", "")
            if (!text.isNullOrBlank()) {
                Log.i(TAG, "SIMULATE 注入: $text")
                val inSession = recording
                if (!inSession) {
                    // startForegroundService 启动的服务必须调 startForeground，否则 Android 14+
                    // 直接崩整个进程——无障碍服务同进程陪葬，系统标「此服务出现故障」、胶囊弹不出
                    // （2026-09-11 深夜事故根因，用户实拍）。测试注入也必须合法前台化再走。
                    createChannelIfNeeded()
                    startForeground(NOTIFICATION_ID, buildNotification("🔔 测试注入"))
                    recording = true // 放行 onRecognized 的会话守卫
                }
                try {
                    onRecognized(text)
                } finally {
                    if (!inSession) {
                        recording = false
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
            return START_NOT_STICKY
        }

        // 已在会话中 → 不重复启动（防止重复占麦、重复开识别线程）
        if (recording) return START_NOT_STICKY

        createChannelIfNeeded()
        startForeground(NOTIFICATION_ID, buildNotification("🔴 会话中 · 正在听"))

        // 模型加载秒级耗时，放后台线程：不阻塞主线程（ANR 风险），加载完成前不占麦。
        // 用「会话代际 + 初始化互斥锁」防并发初始化：
        //   代际——加载期间退出再立刻重开，旧线程发现被顶替就地释放资源，绝不双占麦 / 泄漏；
        //   互斥锁——同一时刻只允许一个线程创建重模型（每个 SenseVoice 约 200MB，并发创建会 OOM）。
        stopRequested = false
        val myGen = ++sessionGeneration
        thread(name = "voice-init") {
            var commit = false
            synchronized(initLock) {
                // 锁内重新核对待办：前面已有线程在创建时，本次可能已作废
                if (isSessionStale(myGen)) return@thread
                val rec = createRecognizer()
                if (rec == null) {
                    Log.e(TAG, "模型初始化失败，不占用麦克风")
                    if (isSessionCurrent(myGen)) stopSelf()
                    return@thread
                }
                if (isSessionStale(myGen)) { runCatching { rec.release() }; return@thread }
                val v = createVad()
                if (v == null) {
                    Log.e(TAG, "VAD 初始化失败，不占用麦克风")
                    runCatching { rec.release() }
                    if (isSessionCurrent(myGen)) stopSelf()
                    return@thread
                }
                if (isSessionStale(myGen)) {
                    runCatching { rec.release() }; runCatching { v.release() }
                    return@thread
                }
                if (!startMicrophone()) {
                    Log.e(TAG, "麦克风初始化失败")
                    runCatching { rec.release() }; runCatching { v.release() }
                    if (isSessionCurrent(myGen)) stopSelf()
                    return@thread
                }
                if (isSessionStale(myGen)) {
                    // 麦克风已启动却被新会话顶替：还麦 + 清理本地资源
                    releaseMicrophone()
                    runCatching { rec.release() }; runCatching { v.release() }
                    return@thread
                }
                // 全部成功且仍是当前会话 → 锁内提交字段（防锁外提交被插队），置 commit 到锁外启动识别线程
                recognizer = rec
                vad = v
                recording = true
                commit = true
            }
            if (commit) {
                recordThread = thread(name = "voice-recognition") { recognitionLoop() }
                // 显示顶部识别状态横条
                VoiceControlService.showBar()
                VoiceControlService.updateBar("🎤 正在聆听…")
                // 启动看门狗（时间制独挑大梁）与休眠预警
                extensionCount = 0
                // 飞行记录仪（v0.51.0）：会话出生留档（异常终检在 onStartCommand 顶部，任何启动形式都先过一遍）
                sessionStartElapsed = SystemClock.elapsedRealtime()
                getSharedPreferences("app", MODE_PRIVATE)
                    .edit().putLong(KEY_SESSION_ACTIVE_SINCE, System.currentTimeMillis()).apply()
                sleepWarned = false
                segmentStartElapsed = SystemClock.elapsedRealtime()   // 本段起点（「继续」早说检测用）
                handler.postDelayed(watchdogRunnable, WATCHDOG_MILLIS)
                handler.postDelayed(warnRunnable, WATCHDOG_MILLIS - WARN_BEFORE_MILLIS)
                // 锁屏自动释放：注册黑屏广播，锁屏即把麦克风还给系统
                registerScreenOffReceiver()
                SessionState.phase = SessionState.Phase.LISTENING
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRequested = true
        recording = false
        runCatching { unregisterReceiver(screenOffReceiver) }
        handler.removeCallbacks(watchdogRunnable)
        handler.removeCallbacks(warnRunnable)
        handler.removeCallbacks(barResetRunnable)
        repeatRunnable?.let { handler.removeCallbacks(it) }
        repeatRunnable = null
        VoiceControlService.hideBar()
        releaseMicrophone()   // 兜底（正常释放路径在 releaseAndStop 里已先执行）
        micReleaseReceipt(100L)
        teardownNativeAsync() // recognizer/vad 等识别线程退出后再释放，防 use-after-free
        super.onDestroy()
    }

    /** 注册锁屏广播（SCREEN_OFF）：锁屏立即释放麦克风，保证黑屏时随时能唤起小爱 */
    private fun registerScreenOffReceiver() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
            }
        }
    }

    /** 创建离线识别器（SenseVoice，非流式整句识别）；返回本地对象，由调用方决定提交或释放 */
    private fun createRecognizer(hotwordsScore: Float = HOTWORDS_SCORE): OfflineRecognizer? {
        return try {
            val hotwordsFile = writeHotwords()
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = "sensevoice.int8.onnx",
                        language = "zh",
                        useInverseTextNormalization = false,
                    ),
                    tokens = "tokens.txt",
                    numThreads = 4, // 2→4（2026-09-09 提速）：整句解码更快，代价仅会话期略增功耗
                    debug = false,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search", // 禁区：beam search 实测 native 崩进程（2026-09-12 复现，热词实验后回退）
                // 热词（contextual biasing）：让解码时偏向命令词表，从源头减少「向右→鲜花」这类听岔
                hotwordsFile = hotwordsFile,
                hotwordsScore = hotwordsScore,
            )
            val r = OfflineRecognizer(assets, config)
            Log.i(TAG, "识别器初始化成功（SenseVoice）")
            r
        } catch (e: Exception) {
            Log.e(TAG, "识别器初始化失败", e)
            null
        }
    }

    /** 创建 VAD（silero 深度学习语音活动检测，判断一句话的起止）；返回本地对象 */
    private fun createVad(): Vad? {
        return try {
            val config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "silero_vad.onnx",
                    // v0.38.0 起按用户灵敏度滑块读取（格 5 = 0.60f 恰为历史常量，默认零变化）：
                    // 越高门槛越低，小声/口齿不清才"开得了闸"；映射见 RecognitionSensitivity
                    threshold = RecognitionSensitivity.vadThreshold(
                        RecognitionSensitivity.level(applicationContext)
                    ),
                    // 0.4s：说完到动手延迟的大头，正常换气停顿 (<0.3s) 不会被斩断。
                    // v0.54.0 弱声专档（9~10 格）放宽到 0.55s：构音障碍者字间停顿长，0.4s 会拦腰斩句
                    minSilenceDuration = RecognitionSensitivity.minSilence(
                        RecognitionSensitivity.level(applicationContext)
                    ),
                    minSpeechDuration = RecognitionSensitivity.minSpeech(
                        RecognitionSensitivity.level(applicationContext)
                    ),
                    windowSize = 512,
                    // v0.54.0 弱声专档放宽到 5s：慢语速长句不被腰斩；其余格 3s 不变
                    maxSpeechDuration = RecognitionSensitivity.maxSpeech(
                        RecognitionSensitivity.level(applicationContext)
                    ),
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                debug = false,
                provider = "cpu",
            )
            val v = Vad(assets, config)
            Log.i(TAG, "VAD 初始化成功（minSilence=${config.sileroVadModelConfig.minSilenceDuration}s threshold=${config.sileroVadModelConfig.threshold}）")
            v
        } catch (e: Exception) {
            Log.e(TAG, "VAD 初始化失败", e)
            null
        }
    }

    /** 本 init 线程是否已被新会话顶替（代际过期或已请求停止） */
    private fun isSessionStale(myGen: Int): Boolean = stopRequested || sessionGeneration != myGen

    /** 是否仍是当前活跃会话（未被顶替且未停止） */
    private fun isSessionCurrent(myGen: Int): Boolean = !isSessionStale(myGen)

    /** 把命令词表写成热词文件，返回文件绝对路径（词表为空则返回空串、跳过热词） */
    private fun writeHotwords(): String {
        // v0.42.0：并入用户自定义常用词（人名/地名）——来源只增不减，指令词 8.0 分照旧；
        // 撞车词已在准入时被 CustomVocab.validateWord 拦截（commandCollision），不会到这里
        val words = (currentMatcher().hotwords() + APP_HOTWORDS + NUMBER_HOTWORDS +
            REPEAT_HOTWORDS + CustomVocab.all(applicationContext)).distinct()
        if (words.isEmpty()) {
            Log.w(TAG, "命令词表为空，跳过热词")
            return ""
        }
        // 本模型 tokens 是「字符级」的（每个汉字一个 token），
        // 热词必须写成「字与字之间用空格分隔」，否则会被当成一个不存在的整词而失效。
        val lines = words
            .filter { it.all { c -> c.code in 0x4E00..0x9FFF } }   // 只保留纯汉字词
            .map { it.toCharArray().joinToString(" ") }
        if (lines.isEmpty()) {
            Log.w(TAG, "没有可用的中文热词")
            return ""
        }
        val file = File(filesDir, "hotwords.txt")
        file.writeText(lines.joinToString("\n"), Charsets.UTF_8)
        Log.i(TAG, "热词文件已生成：${lines.size} 个 -> ${file.absolutePath}")
        return file.absolutePath
    }

    /**
     * 启动麦克风。
     *
     * 音频源用 VOICE_COMMUNICATION 而非 VOICE_RECOGNITION —— 这是「外放声音被误识别成命令」的根治点：
     * 实测本机 /vendor/etc/audio_effects.xml 只给 voice_communication 挂了 aec/ns/agc 前处理链，
     * VOICE_RECOGNITION 一个都没有，所以抖音外放的人声被原样拾取、精确识别成「向上滑动」（已真机复现）。
     * VOICE_COMMUNICATION 是 Android 官方为通话/语音助手设计的源，系统自动做回声消除。
     * 再显式 attach AEC/NS/AGC 作双保险（部分 ROM 的 preprocess 配置不全）。
     */
    private fun startMicrophone(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            if (minBuf > 0) minBuf * 2 else 8192
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }
        audioRecord = recorder
        attachAudioEffects(recorder.audioSessionId)   // 先挂效果器，再开始录音
        return try {
            recorder.startRecording()
            Log.i(TAG, "麦克风已启动：VOICE_COMMUNICATION + AEC=${aecEffect?.enabled == true} NS=${nsEffect?.enabled == true} AGC=${agcEffect?.enabled == true}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "startRecording 失败", e)
            detachAudioEffects()
            recorder.release()
            audioRecord = null
            false
        }
    }

    /**
     * 挂载音频前处理效果器：回声消除(AEC) / 噪声抑制(NS) / 自动增益(AGC)。
     *
     * AEC 是「刷抖音时不被视频声音误触发」的关键：它拿系统正在外放的音频作参考信号，
     * 从麦克风输入里减掉——外放声音被消掉，你近讲的人声（直达声）保留。
     * 每个效果器都先 isAvailable 判断（机型能力不同），不可用就跳过，绝不影响录音主链路。
     */
    private fun attachAudioEffects(sessionId: Int) {
        detachAudioEffects()
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.apply {
                    if (enabled) { aecEffect = this; Log.i(TAG, "AEC 回声消除已启用") }
                    else runCatching { release() }
                }
            } else Log.w(TAG, "AEC 不可用（机型不支持）")
        }
        runCatching {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.apply {
                    if (enabled) { nsEffect = this; Log.i(TAG, "NS 噪声抑制已启用") }
                    else runCatching { release() }
                }
            } else Log.w(TAG, "NS 不可用（机型不支持）")
        }
        runCatching {
            // v0.54.0 弱声专档（9~10 格）强制开启 AGC：系统层把小声/含糊嗓音拉到正常音量。
            // 机型相关行为：有的厂商不提供/不允许第三方开 AGC——失败时明确记日志（导出反馈可见），
            // 靠弱声档其余参数（低门槛/停顿容忍）兜底；实测不佳就降回 8 格（一行回退）
            val weak = RecognitionSensitivity.weakVoiceMode(
                RecognitionSensitivity.level(applicationContext)
            )
            if (!AutomaticGainControl.isAvailable()) {
                if (weak) Log.w(TAG, "AGC 不可用（机型不提供该效果器），弱声专档靠低门槛+停顿容忍兜底")
                return@runCatching
            }
            AutomaticGainControl.create(sessionId)?.apply {
                if (enabled) {
                    agcEffect = this
                    Log.i(TAG, "AGC 自动增益已启用")
                } else if (weak && runCatching { this.enabled = true }.isSuccess) {
                    agcEffect = this
                    Log.i(TAG, "AGC 自动增益已启用（弱声专档强制开启）")
                } else {
                    if (weak) Log.w(TAG, "AGC 无法开启（弱声专档）：机型不允许，靠低门槛+停顿容忍兜底")
                    runCatching { release() }
                }
            }
        }
    }

    private fun recognitionLoop() {
        val v = vad ?: return
        val rec = recognizer ?: return
        val bufferSize = (SAMPLE_RATE * 0.1).toInt() // 每次读 100ms 音频
        val buffer = ShortArray(bufferSize)
        // 一阶高通滤波（IIR）：滤掉 ~100Hz 以下的低频鼓点/轰鸣/空调声，
        // 保留人声共振峰，让 VAD 和识别器少受背景低频干扰。状态跨帧延续。
        val alpha = 0.962f // 截止频率约 100Hz @ 16kHz
        var prevIn = 0f
        var prevOut = 0f
        // 灵敏度软件增益（v0.38.0）：会话开始时读一次，格 5=1.0×（历史零变化）。
        // 增益乘在滤波输入端（滤波器线性，先增益后滤波数学等价），钳制 ±1 防爆音；
        // 胶囊声波 RMS 随之放大——小声说话的用户能直接看到"波浪起来了"的正反馈。
        val gain = RecognitionSensitivity.gain(RecognitionSensitivity.level(applicationContext))
        Log.i(TAG, "识别灵敏度增益：${gain}×")
        try {
            while (recording) {
                val n = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (n <= 0) continue
                val samples = FloatArray(n)
                var sumSq = 0.0
                for (i in 0 until n) {
                    val x = buffer[i] / 32768f * gain
                    val y = alpha * (prevOut + x - prevIn)
                    prevIn = x
                    prevOut = y
                    samples[i] = y.coerceIn(-1f, 1f)
                    sumSq += samples[i] * samples[i]
                }
                // 实时振幅（RMS 归一化）→ 顶部胶囊声波（每 100ms 一帧，视觉上即实时）
                VoiceService.liveAmplitude =
                    kotlin.math.sqrt(sumSq / n).toFloat().times(7f).coerceIn(0f, 1f)
                // VAD 检测语音段（一句话）；检测到完整一段就交给 SenseVoice 整句识别。
                // 每句都新建 stream、用完即释放，没有状态累积，不会越跑越慢。
                v.acceptWaveform(samples)
                while (!v.empty()) {
                    val segment = v.front()
                    v.pop()
                    val seg = segment.samples
                    if (seg.isEmpty()) continue
                    val stream = rec.createStream()
                    stream.acceptWaveform(seg, SAMPLE_RATE)
                    rec.decode(stream)
                    val text = rec.getResult(stream).text
                    stream.release()
                    if (text.isNotBlank()) {
                        val clean = text.replace(" ", "")
                        handler.post { onRecognized(clean) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "识别循环异常", e)
        }
    }

    private fun onRecognized(text: String) {
        // 会话已结束（看门狗/静音/退出已释放）→ 忽略识别线程队列里残留的结果，避免退出后还触发动作
        if (!recording) return
        SessionState.lastText = text
        // 自定义说法录入（v0.39.0）：第一句原文到手立即还麦，绑定页轮询取走确认绑定
        if (captureArmed) {
            captureArmed = false
            lastCaptured = text
            Log.i(TAG, "CAPTURE 说法已捕获：[$text]")
            VoiceControlService.updateBar("已听到：$text，回页面确认绑定")
            releaseAndStop("说法录入完成")
            return
        }
        SessionState.phase = SessionState.Phase.EXECUTING   // 主页状态卡：正在执行
        val before = SessionState.lastMatch
        handleRecognized(text)
        // lastMatch 没动（语气词等静默忽略）→ 直接回聆听，不拿旧结果重演「已完成」
        if (!recording) return
        if (SessionState.lastMatch != before) settlePhase()
        else SessionState.phase = SessionState.Phase.LISTENING
    }

    /**
     * 执行落地四态（主页状态卡）：lastMatch 带 ✅ → 已完成，停留 1.2s 自动回聆听态；
     * 其余（模式切换/延期等提示，或未成功）不冒充失败，直接回聆听态——
     * 顶部胶囊已有实时反馈，状态卡只报喜不报吓人的红叉。
     * 退出/看门狗路径已把 phase 置 IDLE，这里检测到会话结束就不覆盖。
     */
    private fun settlePhase() {
        if (!recording) return
        if (SessionState.lastMatch.contains("✅")) {
            SessionState.phase = SessionState.Phase.DONE
            handler.postDelayed({
                if (recording) SessionState.phase = SessionState.Phase.LISTENING
            }, PHASE_DONE_MILLIS)
        } else {
            SessionState.phase = SessionState.Phase.LISTENING
        }
    }

    private fun handleRecognized(text: String) {
        Log.i(TAG, "识别结果: $text")
        updateNotification("🔴 会话中 · 你说：${text.take(15)}")

        // 长按待命模式优先：数字→长按编号 / 中间→长按屏幕 / 退出→取消长按（不结束会话）
        if (longPressMode) {
            handleLongPressMode(text)
            return
        }

        // 安全红线 1：原文直接含「退出」→ 无条件释放，不依赖匹配层
        if (text.contains(CMD_EXIT)) {
            releaseAndStop("识别到「退出」")
            return
        }

        // 听写模式（v0.40.0）：本句为听写内容——写进输入框后自动回到普通命令聆听。
        // 放在语气词过滤之前：用户说的内容原样落笔（含语气词），只让路给「退出」红线
        if (dictationMode) {
            handler.removeCallbacks(dictationTimeoutRunnable)
            dictationMode = false
            if (text.contains("取消")) {
                VoiceControlService.updateBar("🎤 已取消输入")
                SessionState.lastMatch = "→ 已取消输入"
            } else {
                // 常用词纠错（v0.42.0）：识别原文里与常用词拼音相近的片段改写为常用词（只作用听写内容）
                val corrected = CustomVocab.correctText(text, CustomVocab.all(applicationContext))
                if (corrected != text) Log.i(TAG, "听写纠错: [$text] -> [$corrected]")
                val ok = VoiceControlService.textInsert(corrected)
                VoiceControlService.updateBar(if (ok) "✍️ 已输入：${corrected.take(12)}" else "⚠️ 未找到输入框")
                SessionState.lastMatch = if (ok) "→ 输入「$corrected」✅" else "→ 未找到输入框"
                if (ok) vibrateFeedback()
            }
            return
        }

        // 纯语气词：静默忽略，横条保持当前状态继续聆听（商用原则：不把误识别展示给用户）
        if (text in NOISE_WORDS) {
            Log.i(TAG, "忽略语气词: [$text]")
            return
        }

        // 「继续」：看门狗延期——**仅预警期有效**（2026-09-14 裘晨阳报 bug：没到预警说继续
        // 也会扣延期名额并重置 5 分钟计时=提前浪费。修复：预警未出现时明确拒绝并告知还差多久）
        if (text.contains(CMD_CONTINUE)) {
            if (!sleepWarned) {
                val waitText = if (segmentStartElapsed != 0L) {
                    val remainSec = ((segmentStartElapsed + WATCHDOG_MILLIS - WARN_BEFORE_MILLIS -
                        SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)
                    val m = remainSec / 60
                    if (m > 0) "${m} 分 ${remainSec % 60} 秒" else "${remainSec} 秒"
                } else null
                Log.i(TAG, "CONTINUE_EARLY 「继续」被拒：预警未出现${waitText?.let { "（距预警还有 $it）" } ?: ""}")
                VoiceControlService.updateBar(
                    if (waitText != null) "⏳ 还没到续期时间（$waitText 后提醒）" else "⏳ 还没到续期时间"
                )
                SessionState.lastMatch = "→ 「继续」太早，预警出现后再说${waitText?.let { "（还有 $it）" } ?: ""}"
                handler.removeCallbacks(barResetRunnable)
                handler.postDelayed(barResetRunnable, 1500L)
                return
            }
            handleExtendSession()
            // 横条稍后恢复为「正在聆听」
            handler.removeCallbacks(barResetRunnable)
            handler.postDelayed(barResetRunnable, 1500L)
            return
        }

        // 听写触发（v0.40.0）：说「输入/听写」→ 下一句识别原文直接写入输入框（小米式短听写）
        if (text == "输入" || text == "听写") {
            dictationMode = true
            handler.removeCallbacks(dictationTimeoutRunnable)
            handler.postDelayed(dictationTimeoutRunnable, 12_000L)
            VoiceControlService.updateBar("✍️ 请说出内容，停顿即填入")
            SessionState.lastMatch = "→ 听写中（说完停顿即填入）"
            return
        }

        // 替换命令（v0.41.0）：「把X替换成Y/换成Y/改成Y」——精确找词 → 拼音模糊滑窗
        // （治照读屏幕错字却被听岔：拼音同即命中）。找不到原词不静默，明确提示
        REPLACE_REGEX.find(text)?.let { m ->
            val find = m.groupValues[1].replace(" ", "")
            val repl = m.groupValues[2].replace(" ", "")
            val cur = VoiceControlService.currentEditableText()
            if (cur == null) {
                VoiceControlService.updateBar("⚠️ 未找到输入框")
                SessionState.lastMatch = "→ 未找到输入框"
            } else {
                val range = if (cur.contains(find)) {
                    IntRange(cur.indexOf(find), cur.indexOf(find) + find.length - 1)
                } else {
                    CommandMatcher.findFuzzyRange(cur, find)
                }
                if (range == null) {
                    VoiceControlService.updateBar("⚠️ 输入框中没有「$find」")
                    SessionState.lastMatch = "→ 没有找到「$find」"
                } else {
                    val ok = VoiceControlService.textReplaceRange(range.first, range.last + 1, repl)
                    VoiceControlService.updateBar(if (ok) "🔁 已替换为「$repl」" else "🎤 识别：$text")
                    SessionState.lastMatch = if (ok) "→ 把「$find」替换为「$repl」✅" else "→ 替换失败"
                    if (ok) vibrateFeedback()
                }
            }
            return
        }

        // 「重复一次」常被 ASR 听成「过一次/不一次/试一次」（音节丢失），宽松兜底按重复处理
        val repeatCount = extractRepeatCount(text)
            ?: extractLooseRepeat(text)
        if (repeatCount != null) {
            handleRepeat(repeatCount)
        } else {
            // 网格：先「点击 N」= 一步式点击第 N 格；否则「第 N 格/网格 N/纯数字」= 缩放
            val gridShowing = VoiceControlService.isGridShowing()
            val gridTap = if (gridShowing) extractGridTapCell(text) else null
            val gridNum = if (gridTap == null) extractGridNumber(text, gridShowing) else null
            if (gridTap != null) {
                val ok = VoiceControlService.tapGridCell(gridTap)
                if (ok) {
                    // 记住网格点击的落点，「重复一次」可在同一位置再点（用户明确指令，非自动重试）
                    VoiceControlService.lastGridTapPoint?.let { p ->
                        lastAction = LastAction.TapPoint(p.first, p.second)
                    }
                }
                VoiceControlService.updateBar(if (ok) "⚡ 点击第 $gridTap 格" else "🎤 识别：$text")
                SessionState.lastMatch = if (ok) "→ 点击第 $gridTap 格 ✅" else "→ 点击第 $gridTap 格"
            } else if (gridNum != null) {
                val ok = VoiceControlService.zoomGrid(gridNum)
                VoiceControlService.updateBar(if (ok) "⚡ 缩放到第 $gridNum 格" else "⚠️ 已到最小格，无法再缩")
                SessionState.lastMatch = if (ok) "→ 缩放到第 $gridNum 格 ✅" else "→ 已到最小格"
            } else {
                // 编号点击：点击 N / 点第 N 个 / 第 N 个；编号显示时纯数字 N 也直接点（快捷模式）
                // 编号显示时对短音节误识别（如「四是」=4+10）也按数字意图处理——
                // 否则会掉进文字点击通道去页面搜「四是」（搜不到被忽略，用户看起来像没反应）
                val tapNum = extractTapNumber(text)
                    ?: if (VoiceControlService.isLabelsVisible())
                        extractBareNumber(text) ?: extractBareNumberLoose(text) else null
                if (tapNum != null) {
                    val ok = VoiceControlService.tapLabel(tapNum)
                    if (ok) lastAction = LastAction.TapLabel(tapNum)
                    VoiceControlService.updateBar(if (ok) "⚡ 点击编号 $tapNum" else "🎤 识别：$text")
                    SessionState.lastMatch = if (ok) "→ 点击编号 $tapNum ✅ 已执行" else "→ 点击编号 $tapNum"
                } else {
                    // 长按编号：长按 N / 长按 12 / 长按第 N 个（编号模式）
                    val lpNum = extractLongPressNumber(text)
                    if (lpNum != null) {
                        val ok = VoiceControlService.longPressLabel(lpNum)
                        VoiceControlService.updateBar(if (ok) "⚡ 长按编号 $lpNum" else "🎤 识别：$text")
                        SessionState.lastMatch = if (ok) "→ 长按编号 $lpNum ✅" else "→ 长按编号 $lpNum"
                    } else {
                        // 长按文字：长按抖音 / 按住抖音
                        val lpText = extractLongPressText(text)
                        if (lpText != null) {
                            val target = currentMatcher().resolveClosest(lpText, APP_HOTWORDS) ?: lpText
                            if (VoiceControlService.longPressText(target)) {
                                VoiceControlService.updateBar("⚡ 长按「$target」")
                                SessionState.lastMatch = "→ 长按「$target」 ✅"
                            } else {
                                // 长按文字失败 → 回退精确命令（「按住不动」这类无参长按）
                                val strict = currentMatcher().matchStrict(text)
                                if (strict != null) {
                                    Log.i(TAG, "匹配: [$text] -> ${strict.matchedWord} (${strict.method})")
                                    dispatchMatched(strict)
                                } else {
                                    val fuzzy = currentMatcher().matchFuzzy(text)
                                    if (fuzzy != null) {
                                        Log.i(TAG, "匹配: [$text] -> ${fuzzy.matchedWord} (${fuzzy.method})")
                                        dispatchMatched(fuzzy)
                                    } else {
                                        // 长按文字没找到：同样静默忽略（商用原则同上）
                                        Log.i(TAG, "长按未命中，忽略: [$text]")
                                    }
                                }
                            }
                        } else {
                            // 分层匹配：精确命令 → 文字点击（说屏幕文字/App 名）→ 拼音兜底纠错命令
                            val strict = currentMatcher().matchStrict(text)
                            if (strict != null) {
                                Log.i(TAG, "匹配: [$text] -> ${strict.matchedWord} (${strict.method})")
                                dispatchMatched(strict)
                            } else {
                                val rawTarget = extractTextToTap(text)
                                if (rawTarget != null) {
                                    // 目标先经 App 名热词纠错（「抖婴」→「抖音」），再按屏幕文字点击
                                    val target = currentMatcher().resolveClosest(rawTarget, APP_HOTWORDS) ?: rawTarget
                                    if (VoiceControlService.tapText(target)) {
                                        VoiceControlService.updateBar("⚡ 点击「$target」")
                                        SessionState.lastMatch = "→ 点击「$target」 ✅"
                                        // 文字点击成功也登记为可重复（v0.55）：媒体播放器里
                                        // 「暂停」就是文字点击，「重复一次」复点同一位置即可切回
                                        VoiceControlService.lastTextTapPoint?.let { p ->
                                            lastAction = LastAction.TapPoint(p.first, p.second)
                                        }
                                    } else {
                                        val fuzzy = currentMatcher().matchFuzzy(text)
                                        if (fuzzy != null) {
                                            Log.i(TAG, "匹配: [$text] -> ${fuzzy.matchedWord} (${fuzzy.method})")
                                            dispatchMatched(fuzzy)
                                        } else {
                                            // 屏幕文字没找到：不操作也不提示，安静继续聆听（只记日志供诊断）。
                                            // 商用原则：误识别不展示给用户，否则用户会怀疑自己普通话不标准
                                            Log.i(TAG, "未命中，忽略: [$text]")
                                        }
                                    }
                                } else {
                                    val fuzzy = currentMatcher().matchFuzzy(text)
                                    if (fuzzy != null) {
                                        Log.i(TAG, "匹配: [$text] -> ${fuzzy.matchedWord} (${fuzzy.method})")
                                        dispatchMatched(fuzzy)
                                    } else {
                                        // 未命中任何命令：静默继续聆听，不回显 ASR 原文（商用原则同上）
                                        Log.i(TAG, "未匹配，忽略: [$text]")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 横条稍后恢复为「正在聆听」
        handler.removeCallbacks(barResetRunnable)
        handler.postDelayed(barResetRunnable, 1500L)
    }

    /** 「继续」：看门狗延期（调用方已保证预警期）。带次数上限，防 bug 自动无限延期导致彻底占麦。 */
    private fun handleExtendSession() {
        if (extensionCount >= MAX_EXTENSIONS) {
            VoiceControlService.updateBar("⚠️ 已达最长 $SESSION_MAX_MINUTES 分钟，无法再延长")
            SessionState.lastMatch = "→ 延期已达上限（最多 $MAX_EXTENSIONS 次）"
            return
        }
        extensionCount++
        sleepWarned = false
        segmentStartElapsed = SystemClock.elapsedRealtime()
        Log.i(TAG, "EXTEND 已延期 $extensionCount/$MAX_EXTENSIONS 次")
        handler.removeCallbacks(watchdogRunnable)
        handler.removeCallbacks(warnRunnable)
        handler.postDelayed(watchdogRunnable, EXTEND_MILLIS)
        handler.postDelayed(warnRunnable, EXTEND_MILLIS - WARN_BEFORE_MILLIS)
        val remaining = MAX_EXTENSIONS - extensionCount
        VoiceControlService.updateBar("✅ 已延长 $EXTEND_MINUTES 分钟（还可延长 $remaining 次）")
        SessionState.lastMatch = "→ 已延长，剩余可延期 $remaining 次"
        updateNotification("🔴 会话中 · 已延长（剩余 $remaining 次）")
    }

    /** 编号点击匹配：点击 5 / 点第 5 个 / 第 5 个（排除「点一下」这类无参数点击） */
    private val TAP_LABEL_REGEX = Regex("""(?:点击|点|第)\s*([0-9零一二两三四五六七八九十百]+)\s*(?:个)?(?!下)""")

    /** 网格点击匹配：网格 5 / 格子 5 / 第 5 格（与元素编号「第 5 个」区分） */
    private val GRID_TAP_REGEX = Regex("""(?:网格|格子)\s*([0-9零一二两三四五六七八九十百]+)|第\s*([0-9零一二两三四五六七八九十百]+)\s*格""")

    /** 网格一步式点击：点击 5 / 点击第 5 格 / 点第 5 格 → 直接点第 5 格（任意层级；排除「点一下」长按误触）。
     *  v0.25.5 放宽：旧正则要求数字紧跟「点击」，「点击第5格」会掉进缩放通道（用户只能在第二层点击）。
     *  现在第/格均可选——点击永远是点击，缩放只归「缩放到第N格 / 第N格 / 纯数字」管 */
    private val GRID_TAP_CELL_REGEX = Regex("""(?:点击|点)\s*第?\s*([0-9零一二两三四五六七八九十百]+)\s*格?(?!下)""")

    /** 网格显示时的宽容匹配：提取任意数字（"第5个"/"5"/"第五"都算），降低"格"字误识别影响 */
    private val LOOSE_GRID_REGEX = Regex("""([0-9]+|[零一二两三四五六七八九十百]+)""")

    /** 网格撤销命令词（均不含数字，从根源避免被数字匹配误吞） */
    private val GRID_BACK_KEYWORDS = listOf("退回", "回退", "取消缩放")

    /** 识别「点击 N」网格一步式点击（网格显示时）；不是点击命令返回 null */
    private fun extractGridTapCell(text: String): Int? {
        val m = lastMatch(GRID_TAP_CELL_REGEX, normalizeDigitHomophones(text)) ?: return null
        return parseChineseNumber(m.groupValues[1])
    }

    /** 识别网格缩放数字；loose=true 时宽容提取任意数字（网格显示中） */
    private fun extractGridNumber(text: String, loose: Boolean): Int? {
        // 撤销类命令（不含数字）优先走命令匹配，双保险排除
        if (GRID_BACK_KEYWORDS.any { text.contains(it) }) return null
        val norm = normalizeDigitHomophones(text)
        val m = lastMatch(GRID_TAP_REGEX, norm)
        if (m != null) {
            val numStr = m.groupValues[1].ifBlank { m.groupValues[2] }
            return parseChineseNumber(numStr)
        }
        if (!loose) return null
        val lm = lastMatch(LOOSE_GRID_REGEX, norm) ?: return null
        return parseChineseNumber(lm.groupValues[1])
    }

    /** 识别「点击 N / 点第 N 个 / 第 N 个」并返回数字；否则 null */
    private fun extractTapNumber(text: String): Int? {
        val m = lastMatch(TAP_LABEL_REGEX, normalizeDigitHomophones(text)) ?: return null
        return parseChineseNumber(m.groupValues[1])
    }

    /**
     * 取「最后一个」正则匹配（而不是第一个）。
     *
     * 为什么：说话音量偏低时 VAD 会把多条命令合并成一段——实测「点击二十五点击十八」。
     * 取第一个会去执行 25（用户早就不想点的旧编号），而用户真正想要的是最后说的 18。
     * 第一性原理：一句话里出现多条同类命令时，最近说出的才是当前意图。
     */
    private fun lastMatch(regex: Regex, text: String): MatchResult? =
        regex.findAll(text).lastOrNull()

    /** 纯数字快捷匹配：整句就是一个数字（编号显示时直接报数字点编号，省掉「点击」前缀） */
    private val BARE_NUMBER_REGEX = Regex("""^[0-9零一二两三四五六七八九十百]+$""")
    private fun extractBareNumber(text: String): Int? {
        val t = normalizeDigitHomophones(text.trim())
        if (!BARE_NUMBER_REGEX.matches(t)) return null
        return parseChineseNumber(t)
    }

    /**
     * 编号模式下的宽松数字提取：整句只由「数字音节的常见同音字」组成（如「四是」=四十）。
     * 只在编号显示时兜底使用——此时用户几乎必然在报数字，误判成本远低于掉进文字点击通道。
     */
    private val LOOSE_DIGIT_SYLLABLES = setOf(
        '零', '一', '衣', '依', '二', '两', '尔', '而', '三', '伞', '散',
        '四', '是', '似', '寺', '事', '五', '午', '舞', '无', '六', '陆', '路',
        '七', '期', '妻', '气', '八', '吧', '把', '爸', '九', '久', '酒', '就',
        '十', '是', '时', '石', '事', '百', '白', '点', '栋', '动'
    )
    private fun extractBareNumberLoose(text: String): Int? {
        val t = text.trim()
        if (t.length !in 1..3) return null
        if (t.any { it !in LOOSE_DIGIT_SYLLABLES }) return null
        return parseChineseNumber(normalizeDigitHomophones(t))
    }

    /** 长按编号匹配：长按 5 / 长按 12 / 长按第 5 个（(?!下) 排除「长按一下」这类无参长按） */
    private val LONG_PRESS_LABEL_REGEX = Regex("""(?:长按|按住)\s*(?:第)?\s*([0-9零一二两三四五六七八九十百]+)\s*(?:个)?(?!下)""")

    /** 长按文字匹配：长按抖音 / 按住抖音 → 提取目标文字 */
    private val LONG_PRESS_TEXT_REGEX = Regex("""^(?:长按|按住)\s*(.+)$""")

    /** 识别「长按 N」并返回编号；不是长按编号命令返回 null */
    private fun extractLongPressNumber(text: String): Int? {
        val m = lastMatch(LONG_PRESS_LABEL_REGEX, normalizeDigitHomophones(text)) ?: return null
        return parseChineseNumber(m.groupValues[1])
    }

    /** 识别「长按 X」并返回目标文字；不是长按文字命令返回 null */
    private fun extractLongPressText(text: String): String? {
        val m = LONG_PRESS_TEXT_REGEX.find(text) ?: return null
        val t = m.groupValues[1].trim()
        return t.ifEmpty { null }
    }

    /** 文字点击匹配：打开 X / 点 X / 点击 X / 进入 X → 提取目标文字 X；否则短词(2~8字)直接当屏幕文字 */
    private val TEXT_TAP_REGEX = Regex("""^(?:打开|点|点击|按|进入|启动)\s*(.+)$""")
    private fun extractTextToTap(text: String): String? {
        val trimmed = text.trim()
        val m = TEXT_TAP_REGEX.find(trimmed)
        if (m != null) {
            val t = m.groupValues[1].trim()
            return t.ifEmpty { null }
        }
        return if (trimmed.length in 2..8) trimmed else null
    }

    /** 进入长按待命模式：顶部横条提示，等待报数字或「中间」 */
    private fun enterLongPressMode() {
        longPressMode = true
        VoiceControlService.updateBar("🔵 长按模式：说数字编号，或「中间」")
        handler.removeCallbacks(longPressModeRunnable)
        handler.postDelayed(longPressModeRunnable, LONG_PRESS_MODE_TIMEOUT_MS)
    }

    /** 退出长按待命模式（收到数字/「中间」/「退出」或超时） */
    private fun exitLongPressMode() {
        if (!longPressMode) return
        longPressMode = false
        handler.removeCallbacks(longPressModeRunnable)
        // 退出长按模式后，横条稍后恢复「聆听中」（与普通命令一致）
        handler.removeCallbacks(barResetRunnable)
        handler.postDelayed(barResetRunnable, 1500L)
    }

    /** 长按待命模式下处理下一句：数字→长按编号；中间→长按屏幕；退出→取消 */
    private fun handleLongPressMode(text: String) {
        // 「退出/取消」→ 退出长按模式（不结束整个会话）
        if (text.contains("退出") || text.contains("取消")) {
            exitLongPressMode()
            VoiceControlService.updateBar("已退出长按模式")
            SessionState.lastMatch = "→ 退出长按模式"
            return
        }
        // 「中间/屏幕」→ 长按屏幕正中间
        if (text.contains("中间") || text.contains("屏幕")) {
            val ok = VoiceControlService.longPressCenter()
            VoiceControlService.updateBar(if (ok) "⚡ 长按屏幕中间" else "🎤 识别：$text")
            SessionState.lastMatch = if (ok) "→ 长按屏幕中间 ✅" else "→ 长按中间"
            exitLongPressMode()
            return
        }
        // 数字 → 长按对应编号
        val num = extractBareNumber(text)
        if (num != null) {
            val ok = VoiceControlService.longPressLabel(num)
            VoiceControlService.updateBar(if (ok) "⚡ 长按编号 $num" else "🎤 识别：$text")
            SessionState.lastMatch = if (ok) "→ 长按编号 $num ✅" else "→ 长按编号 $num"
            exitLongPressMode()
            return
        }
        // 其他：保持长按模式，重新计时
        handler.removeCallbacks(longPressModeRunnable)
        handler.postDelayed(longPressModeRunnable, LONG_PRESS_MODE_TIMEOUT_MS)
        VoiceControlService.updateBar("🎤 长按模式：说数字，或「中间」")
    }

    /** 执行匹配到的命令；含「退出」安全红线。 */
    private fun dispatchMatched(matched: CommandMatcher.Match) {
        // 2026-09-14 用户日志实锤：闲话「走出」被 pinyin_fuzzy 掰成「退出」→ 会话无辜断开
        // （"用一半自动退出聆听"）。会话终结类命令（退出/锁屏）不接受模糊命中——
        // 同音字仍可退（pinyin_exact），只有"差一个音"这种最易误触的档位对危险命令闭嘴
        if (matched.method == "pinyin_fuzzy" &&
            (matched.action == "exit_session" || matched.action == "lock_screen")
        ) {
            // 2026-09-15 用户日志实锤反面：真「退出」被 ASR 连听成「走出」四次、守卫全拦
            // = 用户被反锁（麦克风占着退不掉，最恶性场景）。升级阶梯：30 秒内近似退出
            // 被拦 3 次判定为真实退出请求、第 3 次放行——闲话不会连说三遍，被困者一定会
            val now = SystemClock.elapsedRealtime()
            fuzzyExitRejects.addLast(now)
            while (fuzzyExitRejects.isNotEmpty() && now - fuzzyExitRejects.first() > 30_000L) {
                fuzzyExitRejects.removeFirst()
            }
            if (matched.action == "exit_session" && fuzzyExitRejects.size >= 3) {
                fuzzyExitRejects.clear()
                Log.w(TAG, "FUZZY_GUARD 升级：30 秒内第 3 次近似退出，按真实退出放行")
                DiagnosticsHelper.log("近似退出连说 3 次，守卫升级放行")
                VoiceControlService.updateBar("🔓 听到连续三次近似退出，已为您退出")
                SessionState.lastMatch = "→ 退出（近似说法连说三次） ✅"
                releaseAndStop("近似退出第 3 次（守卫升级）")
                return
            }
            Log.i(TAG, "FUZZY_GUARD 拒绝模糊命中危险命令：[${SessionState.lastText}] -> ${matched.matchedWord}")
            DiagnosticsHelper.log("模糊命中危险命令已忽略：${SessionState.lastText} ≈ ${matched.matchedWord}")
            if (fuzzyExitRejects.size == 2) {
                // 第二次给出明确指引：被识别困住的用户需要知道出口
                VoiceControlService.updateBar("想退出请说「退出」，或把刚才的说法连说三遍")
            }
            return
        }
        if (matched.action == "exit_session") {
            releaseAndStop("识别到「退出」")
            return
        }
        // 「长按/按住」（无参数）→ 进入长按待命模式（两步式，提升「动词+数字」识别率）
        if (matched.action == "long_press") {
            enterLongPressMode()
            SessionState.lastMatch = "→ 长按模式（说数字或「中间」）"
            return
        }
        // 数字绑定动作（v0.50.0）：执行路径与原生「点击编号 N / 点击第 N 格」完全一致，
        // 含「重复一次」的 lastAction 记录；横条/使用记录口径也对齐原生
        if (matched.action.startsWith("tap_number_")) {
            val n = matched.action.removePrefix("tap_number_").toIntOrNull() ?: return
            val ok = VoiceControlService.tapLabel(n)
            if (ok) lastAction = LastAction.TapLabel(n)
            VoiceControlService.updateBar(if (ok) "⚡ 点击编号 $n" else "🎤 识别：${SessionState.lastText}")
            SessionState.lastMatch = if (ok) "→ 点击编号 $n ✅ 已执行" else "→ 点击编号 $n"
            if (ok) vibrateFeedback()
            return
        }
        if (matched.action.startsWith("grid_tap_")) {
            val n = matched.action.removePrefix("grid_tap_").toIntOrNull() ?: return
            // 网格没显示时不能点：currentTapPoint 无网格会兜底屏幕中心（原生路径有 gridShowing 前置，此处对齐）
            if (!VoiceControlService.isGridShowing()) {
                VoiceControlService.updateBar("⚠️ 网格未显示，说「显示网格」")
                SessionState.lastMatch = "→ 点击第 $n 格（网格未显示）"
                return
            }
            val ok = VoiceControlService.tapGridCell(n)
            if (ok) {
                VoiceControlService.lastGridTapPoint?.let { p ->
                    lastAction = LastAction.TapPoint(p.first, p.second)
                }
            }
            VoiceControlService.updateBar(if (ok) "⚡ 点击第 $n 格" else "🎤 识别：${SessionState.lastText}")
            SessionState.lastMatch = if (ok) "→ 点击第 $n 格 ✅" else "→ 点击第 $n 格"
            if (ok) vibrateFeedback()
            return
        }
        // 音量/摇移命令：执行器可能带回附加提示（如「已达安全上限」「桌面不支持摇移」），
        // 优先展示提示，不被通用文案顶掉
        if (matched.action in NOTE_ACTIONS) {
            val ok = dispatchCommand(matched.action)
            val note = VoiceControlService.consumeActionNote()
            if (ok) {
                VoiceControlService.updateBar(note ?: "⚡ 执行：${matched.matchedWord}")
                SessionState.lastMatch = note ?: "→ ${matched.matchedWord} ✅ 已执行"
            } else {
                VoiceControlService.updateBar(note ?: "🎤 识别：${SessionState.lastText}")
                SessionState.lastMatch = note ?: "→ ${matched.matchedWord}"
            }
            return
        }
        val ok = dispatchCommand(matched.action)
        VoiceControlService.updateBar(if (ok) "⚡ 执行：${matched.matchedWord}" else "🎤 识别：${SessionState.lastText}")
        SessionState.lastMatch = if (ok) "→ ${matched.matchedWord} ✅ 已执行" else "→ ${matched.matchedWord}"
        if (ok) vibrateFeedback()
    }

    /** 震动反馈（设置页开关，默认关）：命令执行成功短震 30ms——触觉确认对无障碍用户有价值 */
    private fun vibrateFeedback() {
        if (!getSharedPreferences("app", MODE_PRIVATE).getBoolean("vibrate_feedback", false)) return
        val vib = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(android.os.VibrationEffect.createOneShot(30, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(30)
            }
        }
    }

    /** 数字同音字：ASR 常把数字听成同音汉字（8→「吧」、1→「衣」、4→「似」），数字命令解析时纠正回数字 */
    private val digitHomophones = mapOf(
        '吧' to '八', '扒' to '八', '疤' to '八',
        '衣' to '一', '依' to '一', '伊' to '一',
        '尔' to '二', '而' to '二',
        '似' to '四', '寺' to '四', '肆' to '四',
        '午' to '五', '舞' to '五', '武' to '五', '伍' to '五',
        '陆' to '六',
        '妻' to '七', '期' to '七', '柒' to '七',
        '久' to '九', '酒' to '九', '玖' to '九',
        '时' to '十', '石' to '十', '拾' to '十',
        '词' to '次', '此' to '次',   // v0.53.1：「五词/五此」→「五次」（重复家族量词被听岔）
    )

    /** 把数字同音字替换回数字汉字（数字命令解析前调用） */
    private fun normalizeDigitHomophones(s: String): String =
        s.map { digitHomophones[it] ?: it }.joinToString("")

    /** 中文/阿拉伯数字 → Int（支持 0~99） */
    private fun parseChineseNumber(s: String): Int? {
        val t = s.trim()
        t.toIntOrNull()?.let { return it }
        if (t.isEmpty()) return null
        val digits = mapOf(
            '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )
        if (t == "十") return 10
        if (t.length == 1) return digits[t[0]]
        if (t.contains('十')) {
            val parts = t.split('十')
            val tens = if (parts[0].isEmpty()) 1 else (digits[parts[0][0]] ?: return null)
            val ones = if (parts.size > 1 && parts[1].isNotEmpty()) (digits[parts[1][0]] ?: return null) else 0
            return tens * 10 + ones
        }
        // 逐位读法兑底：「一四」=14、「一三」=13（ASR 常输出「一四」而非「十四」）
        var result = 0
        for (c in t) {
            val d = digits[c] ?: return null
            result = result * 10 + d
        }
        return result
    }

    /** 重复命令匹配：重复 / 重复 N 次 / 再来一次 */
    private val REPEAT_REGEX = Regex("""(?:重复|再来)\s*([0-9零一二两三四五六七八九十百]+)?\s*(?:次|遍)?""")

    /** 替换命令匹配（v0.41.0）：把X替换成Y / 把X换成Y / 把X改成Y（X、Y 各 1~10 字，非贪婪） */
    private val REPLACE_REGEX = Regex("""^把(.{1,10}?)(?:替换成|换成|改成)(.{1,10})$""")

    /** 识别「重复 / 重复 N 次」并返回次数（默认 1）；不是重复命令返回 null */
    private fun extractRepeatCount(text: String): Int? {
        val m = lastMatch(REPEAT_REGEX, normalizeDigitHomophones(text)) ?: return null
        val numStr = m.groupValues[1]
        if (numStr.isBlank()) return 1
        return parseChineseNumber(numStr)
    }

    /**
     * 宽松重复兜底：ASR 常把「重复一次」听成「过一次/不一次/试一次」（音节丢失或替代）。
     * 口径收得极窄防误触——需同时满足：短句(2~4字)、以 次/遍/是 结尾、不含任何动作动词、
     * 编号/网格未显示（那些模式下短句是数字意图）、且确有可重复动作。
     */
    private fun extractLooseRepeat(text: String): Int? {
        if (lastAction == null) return null
        if (VoiceControlService.isLabelsVisible() || VoiceControlService.isGridShowing()) return null
        val t = text.trim()
        if (t.length !in 2..4) return null
        if (!(t.endsWith("次") || t.endsWith("遍") || t.endsWith("是"))) return null
        val actionVerbs = listOf("点", "按", "滑", "摇", "打", "退", "长", "显", "网格", "编号", "音量", "锁", "通知", "控制", "继续")
        if (actionVerbs.any { t.contains(it) }) return null
        return 1
    }

    /** 处理「重复 N 次」：校验上限、回放上一次动作 */
    private fun handleRepeat(times: Int) {
        when {
            times > MAX_REPEAT -> {
                VoiceControlService.updateBar("⚠️ 重复最多 $MAX_REPEAT 次")
                SessionState.lastMatch = "→ 重复次数超过上限（最多 $MAX_REPEAT 次）"
            }
            lastAction == null -> {
                VoiceControlService.updateBar("🎤 还没有可重复的动作")
                SessionState.lastMatch = "→ 还没有可重复的动作"
            }
            else -> {
                repeatLastAction(times)
                VoiceControlService.updateBar("⚡ 重复 $times 次")
                SessionState.lastMatch = "→ 重复 $times 次 ✅"
            }
        }
    }

    /** 串行回放上一次动作 times 次（每次间隔，避免手势冲突；绕过熔断/冷却因为是明确指令） */
    private fun repeatLastAction(times: Int) {
        val la = lastAction ?: return
        var remaining = times
        val runnable = object : Runnable {
            override fun run() {
                if (remaining <= 0 || !recording) {
                    Log.i(TAG, "重复回放中止：剩余 $remaining，recording=$recording")
                    return
                }
                Log.i(TAG, "重复回放：${la}（recording=$recording）")
                when (la) {
                    is LastAction.Command -> VoiceControlService.execute(la.action)
                    is LastAction.TapLabel -> VoiceControlService.tapLabel(la.number)
                    is LastAction.TapPoint -> {
                        Log.i(TAG, "重复：原坐标再点 (${la.x.toInt()},${la.y.toInt()})")
                        VoiceControlService.tapAtPoint(la.x, la.y)
                    }
                }
                remaining--
                if (remaining > 0) handler.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        repeatRunnable = runnable
        handler.post(runnable)
    }

    /**
     * 把动作派发到无障碍服务执行，带冷却与熔断保护。
     * @return 是否真正派发执行
     */
    private fun dispatchCommand(action: String): Boolean {
        // 无障碍服务未开启 → 无法执行；横条同步告知原因（商用级：失效必须可感知，不能静默）
        if (!VoiceControlService.isReady()) {
            Log.w(TAG, "无障碍服务未开启，无法执行：$action")
            SessionState.status = "无障碍服务被关闭，请回到应用重新开启"
            VoiceControlService.updateBar("⚠️ 无障碍已关闭，动作无法执行")
            return false
        }

        val now = SystemClock.elapsedRealtime()

        // 冷却：同一命令冷却期内不重复执行（先判断；被冷却跳过的动作不计入熔断，避免正常快速操作被误判成循环）
        val last = lastExecTime[action] ?: 0L
        if (now - last < COOLDOWN_MS) {
            Log.i(TAG, "命令冷却中，跳过：$action")
            return false
        }

        // 熔断：时间窗内「真正执行」次数过多 → 判定误循环，释放麦克风
        while (execHistory.isNotEmpty() && now - execHistory.first() > CIRCUIT_WINDOW_MS) {
            execHistory.removeFirst()
        }
        execHistory.addLast(now)
        if (execHistory.size >= CIRCUIT_MAX_EXEC) {
            DiagnosticsHelper.log("熔断触发：${CIRCUIT_WINDOW_MS / 1000}s 内 ${execHistory.size} 次")
            releaseAndStop("连续误触发，熔断保护")
            return false
        }
        lastExecTime[action] = now

        val ok = VoiceControlService.execute(action)
        Log.i(TAG, "执行动作：$action -> $ok")
        if (ok && action != "exit_session") {
            // v0.39.1：全部派发动作可被「重复」回放（白名单已废止，防"新功能忘登记"复发）。
            // exit_session 防御性排除：退出走红线直达，本处本就到不了，双保险
            lastAction = LastAction.Command(action)
            Log.i(TAG, "可重复动作已记录：$action")
        }
        return ok
    }

    private fun releaseAndStop(reason: String) {
        DiagnosticsHelper.log("会话结束: $reason")
        // 飞行记录仪（v0.51.0）：每次退出严格留痕。底层日志永远记；
        // 使用记录（用户可见+随导出走）只记真实会话——带时长与延期数，「时间没到就断」一眼可辨
        if (sessionStartElapsed != 0L) {
            val durSec = (SystemClock.elapsedRealtime() - sessionStartElapsed) / 1000
            val durText = if (durSec >= 60) "${durSec / 60} 分 ${durSec % 60} 秒" else "$durSec 秒"
            Log.i(TAG, "SESSION_END reason=$reason dur=${durSec}s ext=$extensionCount/$MAX_EXTENSIONS")
            SessionState.lastMatch = "→ 会话结束：$reason（本次 $durText · 延期 $extensionCount/$MAX_EXTENSIONS 次）"
            sessionStartElapsed = 0L
            getSharedPreferences("app", MODE_PRIVATE).edit().remove(KEY_SESSION_ACTIVE_SINCE).apply()
        } else {
            Log.i(TAG, "SESSION_END reason=$reason（无真实会话上下文，不记使用记录）")
        }
        SessionState.phase = SessionState.Phase.IDLE   // 主页状态卡回到未启动态
        // 安全红线：麦克风立即释放，不依赖 stopSelf() → onDestroy 的异步时序。
        // recognizer/vad 的 native 释放交给 onDestroy 里的后台 teardown——
        // 必须等识别线程真正退出（decode 可能数百 ms），否则主线程此刻 release 会 use-after-free 崩溃。
        stopRequested = true
        recording = false
        releaseMicrophone()
        micReleaseReceipt()   // 释放回执：系统级确认无残留（用户恐惧点闭环，见函数注释）
        handler.removeCallbacks(watchdogRunnable)
        handler.removeCallbacks(warnRunnable)
        repeatRunnable?.let { handler.removeCallbacks(it) }
        repeatRunnable = null
        longPressMode = false
        handler.removeCallbacks(longPressModeRunnable)
        dictationMode = false
        handler.removeCallbacks(dictationTimeoutRunnable)
        VoiceControlService.hideBar()
        VoiceControlService.hideLabels()
        VoiceControlService.hideGrid()
        SessionState.status = "已释放（$reason）"
        updateNotification("已释放（$reason）")
        stopSelf()
    }

    /** 后台等待识别线程退出后释放 recognizer/vad，防 native use-after-free（Service 销毁后仍短暂存活几秒） */
    private fun teardownNativeAsync() {
        val t = recordThread
        recordThread = null
        thread(name = "voice-teardown") {
            try { t?.join(3000) } catch (_: InterruptedException) {}
            releaseVad()
            releaseRecognizer()
        }
    }

    private fun releaseMicrophone() {
        // 2026-09-15 顺序修正（用户对照实验实锤"释放后小爱仍唤不醒直到清后台"）：
        // 先卸效果器、再停麦克风——部分底层实现中"挂着 AEC 效果的输入通道"会保持打开，
        // 原顺序（先 release 录音再卸效果器）可能让输入通道在效果器层面残留
        detachAudioEffects()   // 前处理效果器先卸载
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    /**
     * 麦克风释放回执（v0.55.1，用户恐惧点闭环）：释放后向系统查询「本应用名下是否仍有活跃录音」
     * （AudioManager.getActiveRecordingConfigurations，系统级视角、非自证）。
     * 结果写入诊断事件（随导出反馈带走）——"是否百分百释放干净"从口头承诺变成带时间戳的凭据；
     * 万一查到残留，立刻强制再清扫一遍并复查。触发点：每次会话结束（看门狗/退出/锁屏/用户停止）。
     */
    private fun micReleaseReceipt(delayMs: Long = 300L) {
        handler.postDelayed({
            val am = runCatching { getSystemService(android.media.AudioManager::class.java) }.getOrNull()
            val active = runCatching { am?.activeRecordingConfigurations?.size ?: -1 }.getOrDefault(-1)
            when {
                active == 0 -> {
                    Log.i(TAG, "MIC_RELEASE_RECEIPT 本应用已无任何活跃录音（系统级确认）")
                    DiagnosticsHelper.log("麦克风释放回执：系统确认本应用已无活跃录音")
                }
                active > 0 -> {
                    Log.w(TAG, "MIC_RELEASE_RECEIPT 仍有 $active 个活跃录音！强制再清扫")
                    DiagnosticsHelper.log("麦克风释放回执异常：仍有 $active 个活跃录音，强制再清扫")
                    releaseMicrophone()
                    handler.postDelayed({
                        val again = runCatching {
                            am?.activeRecordingConfigurations?.size ?: -1
                        }.getOrDefault(-1)
                        DiagnosticsHelper.log("麦克风二次清扫回执：剩余 $again 个活跃录音")
                        if (again != 0) Log.e(TAG, "MIC_RELEASE_RECEIPT 二次清扫后仍剩 $again")
                    }, 300L)
                }
                else -> DiagnosticsHelper.log("麦克风释放回执：系统查询不可用，已执行 stop+release+效果器分离")
            }
        }, delayMs)
    }

    /** 释放音频前处理效果器（AEC/NS/AGC） */
    private fun detachAudioEffects() {
        runCatching { aecEffect?.release() }; aecEffect = null
        runCatching { nsEffect?.release() }; nsEffect = null
        runCatching { agcEffect?.release() }; agcEffect = null
    }

    /** 兜底清扫（v0.55.1 用户对照实验后加固）：把可能存在的全部音频引用一并释放，幂等可重复调 */
    private fun forceAudioCleanup() {
        detachAudioEffects()
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    // ===== 回声测试台（开发期专用，商用前移除）=====

    private var echoPlayer: MediaPlayer? = null
    // 硬性超时兜底：即使外部 STOP 丢失，到点也强制停，绝不无限念（深夜噪音事故教训）
    private val echoTimeoutRunnable = Runnable {
        Log.w(TAG, "ECHO_TEST 超时兜底触发，强制停止外放")
        stopEchoTest()
    }

    /** 外放 assets 里的命令词音频（USAGE_MEDIA = 与抖音同通道）；播完自动停 + 超时硬兜底 */
    private fun startEchoTest() {
        if (echoPlayer != null) return
        try {
            val afd = assets.openFd(ECHO_TEST_FILE)
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                isLooping = false   // 绝不循环：播完即停（深夜噪音事故的根本修复）
                setOnCompletionListener { stopEchoTest() }   // 自然播完也自动停
                prepare()
                start()
            }
            afd.close()
            echoPlayer = mp
            // 双重保险：超时硬兜底，即使完成回调异常也必定停
            handler.removeCallbacks(echoTimeoutRunnable)
            handler.postDelayed(echoTimeoutRunnable, ECHO_TEST_MAX_MS)
            Log.i(TAG, "ECHO_TEST_START 外放开始（单次播放，最多 ${ECHO_TEST_MAX_MS / 1000}s 自动停）")
        } catch (e: Exception) {
            Log.e(TAG, "ECHO_TEST 启动失败", e)
        }
    }

    private fun stopEchoTest() {
        handler.removeCallbacks(echoTimeoutRunnable)
        echoPlayer?.let { runCatching { it.stop() }; runCatching { it.release() } }
        echoPlayer = null
        Log.i(TAG, "ECHO_TEST_STOP 外放已停止")
    }

    // ===== 离线 ASR 测试台（开发期专用，静音，商用前移除）=====

    /**
     * 把 assets 里的 wav 直接喂给识别器，对比热词权重对数字识别的影响。
     * 完全静音、不碰麦克风、不复用会话实例（自建自释，无 native 并发风险）。
     */
    private fun runOfflineAsrTest(wavFile: String, score: Float) {
        thread(name = "asr-test") {
            Log.i(TAG, "ASR_TEST_START file=$wavFile hotwordsScore=$score")
            val rec = createRecognizer(score)
            if (rec == null) { Log.e(TAG, "ASR_TEST_FAIL 识别器创建失败"); return@thread }
            val v = createVad()
            if (v == null) { Log.e(TAG, "ASR_TEST_FAIL VAD创建失败"); runCatching { rec.release() }; return@thread }
            try {
                val samples = readWavFromAssets(wavFile)
                if (samples == null) { Log.e(TAG, "ASR_TEST_FAIL 读不到 $wavFile"); return@thread }
                Log.i(TAG, "ASR_TEST 音频已读取：${samples.size / SAMPLE_RATE}s")
                // v0.38.0 与真实识别链路同源：按当前灵敏度等级施加软件增益，离线台才能当验证器用
                val gain = RecognitionSensitivity.gain(RecognitionSensitivity.level(applicationContext))
                Log.i(TAG, "ASR_TEST 灵敏度增益=${gain}×")
                if (gain != 1f) {
                    for (i in samples.indices) samples[i] = (samples[i] * gain).coerceIn(-1f, 1f)
                }
                // 模拟真实链路：按 100ms 帧喂给 VAD，VAD 切句后整句识别
                val frameSize = SAMPLE_RATE / 10
                var idx = 0
                while (idx < samples.size) {
                    val end = minOf(idx + frameSize, samples.size)
                    v.acceptWaveform(samples.copyOfRange(idx, end))
                    idx = end
                    drainSegments(v, rec)
                }
                v.flush()
                drainSegments(v, rec)
                Log.i(TAG, "ASR_TEST_END 识别完毕")
            } catch (e: Exception) {
                Log.e(TAG, "ASR_TEST_FAIL 异常", e)
            } finally {
                runCatching { v.release() }
                runCatching { rec.release() }
            }
        }
    }

    private fun drainSegments(v: Vad, rec: OfflineRecognizer) {
        while (!v.empty()) {
            val segment = v.front()
            v.pop()
            val seg = segment.samples
            if (seg.isEmpty()) continue
            val stream = rec.createStream()
            stream.acceptWaveform(seg, SAMPLE_RATE)
            rec.decode(stream)
            val text = rec.getResult(stream).text.replace(" ", "")
            stream.release()
            Log.i(TAG, "ASR_TEST_RESULT: [$text]")
        }
    }

    /** 读 assets 里的 16-bit PCM 单声道 wav（跳过 RIFF 头），归一化为 float 采样 */
    private fun readWavFromAssets(name: String): FloatArray? {
        return try {
            val bytes = assets.open(name).use { it.readBytes() }
            // 定位 data chunk（标准 44 字节头后；有的 wav 头带额外 chunk，按标记搜）
            var dataOffset = -1
            for (i in 0 until bytes.size - 4) {
                if (bytes[i] == 'd'.code.toByte() && bytes[i+1] == 'a'.code.toByte() &&
                    bytes[i+2] == 't'.code.toByte() && bytes[i+3] == 'a'.code.toByte()) {
                    dataOffset = i + 8   // 跳过 "data" + 4 字节长度
                    break
                }
            }
            if (dataOffset < 0) return null
            val pcmLen = bytes.size - dataOffset
            val out = FloatArray(pcmLen / 2)
            for (i in out.indices) {
                val lo = bytes[dataOffset + i * 2].toInt() and 0xFF
                val hi = bytes[dataOffset + i * 2 + 1].toInt()
                out[i] = ((hi shl 8) or lo).toShort() / 32768f
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "读 wav 失败: $name", e)
            null
        }
    }

    private fun releaseRecognizer() {
        try { recognizer?.release() } catch (_: Exception) {}
        recognizer = null
    }

    private fun releaseVad() {
        try { vad?.release() } catch (_: Exception) {}
        vad = null
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "语音会话", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("言出法随 · 会话")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
