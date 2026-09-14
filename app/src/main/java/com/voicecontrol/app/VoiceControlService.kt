package com.voicecontrol.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.animation.ValueAnimator
import android.content.Context
import android.content.ComponentName
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.io.File
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 无障碍服务：执行语音命令对应的屏幕动作（滑动、全局导航），并提供跨应用悬浮反馈：
 *   1. 顶部状态横条 —— 显示「正在聆听 / 识别文字 / 正在执行」；
 *   2. 蓝色圆球动画 —— 滑动时沿滑动方向移动，让用户实时看到手势正在发生。
 *
 * 悬浮层用 TYPE_ACCESSIBILITY_OVERLAY：无需额外「悬浮窗」授权，无障碍服务开启即生效。
 */
open class VoiceControlService : AccessibilityService() {

    companion object {
        private const val TAG = "VoiceControlService"

        // 滑动手势总时长（毫秒）：420ms 扫过短边 42% → 抬指速度≈1350px/s，触发系统惯性滑行（iOS 轻扫手感）
        private const val SWIPE_GESTURE_MS = 380L

        // 系统页（桌面等）滑动时长：压在长按阈值之下，防误入桌面编辑模式
        private const val SYSTEM_SWIPE_MS = 280L

        // 摇移手势总时长（毫秒）：520ms 慢拖短边 16% → 抬指速度≈370px/s，几乎无惯性，精准停住
        private const val NUDGE_GESTURE_MS = 520L

        // 双指捏合手势总时长（毫秒）：看图片/网页的捏合缩放，匀速不惯性
        private const val PINCH_GESTURE_MS = 500L

        // 流光胶囊到达终点后的原地余晖时长（毫秒）；前段与真实手势完全同步
        private const val AFTERGLOW_MS = 140L

        // 无障碍树遍历防御上限：避免异常深/大的树导致遍历过慢
        private const val MAX_TREE_DEPTH = 64
        private const val MAX_TREE_NODES = 2000

        // 点击手势时长（毫秒）
        private const val TAP_DURATION_MS = 60L

        // 长按手势时长（毫秒）：同一点按住不动，系统识别为长按
        private const val LONG_PRESS_DURATION_MS = 650L

        // 双击两次点击之间的间隔（毫秒）
        private const val DOUBLE_TAP_GAP_MS = 80L

        // 网格：列数 × 行数（竖屏 3×4 = 12 格）
        const val GRID_COLS = 3
        const val GRID_ROWS = 4

        // 网格缩放最大层级（全屏算第 1 层，最多缩到第 6 层 = 缩 5 次，到手指精度为止）
        const val MAX_GRID_LEVEL = 6

        // 媒体音量安全上限（百分比）。用户亲自定的安全设计，与看门狗同级，不可动：
        // 真实事故（2026 年）：抖音外放被误识别成「增加音量」循环加满 100%，
        // 声音更大→误识别更多→彻底失控，无法唤醒小爱也无法退出，只能等家人。
        // 80% 硬顶让这个失控循环有天花板；配合命令冷却与熔断，循环最多几秒就被掐断。
        const val VOLUME_CAP_PERCENT = 80

        @Volatile
        private var instance: VoiceControlService? = null

        /** 供识别服务调用：执行某个动作标识，返回是否成功派发。无障碍服务未开启时返回 false。 */
        fun execute(action: String): Boolean {
            val svc = instance ?: return false
            return svc.performAction(action)
        }

        /** 无障碍服务是否已连接（已开启） */
        fun isReady(): Boolean = instance != null

        /** 显示顶部横条（会话开始） */
        fun showBar() {
            sessionActive = true
            instance?.doShowBar()
        }

        /** 隐藏顶部横条（会话结束） */
        fun hideBar() {
            sessionActive = false
            instance?.doHideBar()
        }

        /** 会话是否进行中：横条可见性的唯一依据（横条是会话的镜子，不是无障碍服务的镜子） */
        @Volatile
        private var sessionActive = false

        /** 最近一次系统滚动事件时间戳（elapsedRealtime）：滚动校验的「系统级真相」来源 */
        @Volatile
        var lastScrollEventAt: Long = 0L
            private set

        /** 连续「拖动后无滚动事件」计数：达到 2 次才判定页面真拦截手势（防止 WebView 事件迟到造成误跳页） */
        @Volatile
        var gestureNoEventStreak = 0

        /** 更新顶部横条文字 */
        fun updateBar(text: String) = instance?.doUpdateBar(text)

        /** 摇移应答：胶囊朝拖动方向微移后弹回（v0.25.3，替代摇移的流光） */
        fun nudgeIslandToward(dx: Float, dy: Float) = instance?.doNudgeIsland(dx, dy)

        /** 显示编号网格（第①步：给屏幕上可点击元素标数字） */
        fun showLabels() = instance?.doShowLabels()

        /** 听写落笔（v0.40.0）：把识别原文插入当前输入框光标处（小米式短听写） */
        fun textInsert(content: String): Boolean {
            val svc = instance ?: return false
            return svc.textInsert(content)
        }

        /** 替换（v0.41.0）：把输入框中 [start,end) 段换成 replacement，光标落替换段末尾 */
        fun textReplaceRange(start: Int, end: Int, replacement: String): Boolean {
            val svc = instance ?: return false
            return svc.textReplaceRange(start, end, replacement)
        }

        /** 读当前输入框文本（替换找词用）；无输入框返回 null */
        fun currentEditableText(): String? {
            val svc = instance ?: return null
            return svc.editableNodeOrNull()?.text?.toString()
        }

        /** 输入框文本操作探针（开发期诊断，商用前移除） */
        fun textProbe(): Boolean {
            val svc = instance ?: return false
            Thread {
                runCatching { svc.runTextProbe() }
            }.start()
            return true
        }

        /** 隐藏编号网格 */
        fun hideLabels() = instance?.doHideLabels()

        /** 点击第 number 个可点击元素（编号点击） */
        fun tapLabel(number: Int): Boolean {
            val svc = instance ?: return false
            return svc.doTapLabel(number)
        }

        /** 根据屏幕文字点击：说「抖音」/「打开抖音」→ 点当前页面「抖音」那个元素（当前页面没有则不响应） */
        fun tapText(target: String): Boolean {
            val svc = instance ?: return false
            return svc.doTapText(target)
        }

        /** 长按第 number 个可点击元素（编号模式：显示编号后说「长按 1」） */
        fun longPressLabel(number: Int): Boolean {
            val svc = instance ?: return false
            return svc.doLongPressLabel(number)
        }

        /** 根据屏幕文字长按：说「长按抖音」→ 长按当前页面「抖音」那个元素（当前页面没有则不响应） */
        fun longPressText(target: String): Boolean {
            val svc = instance ?: return false
            return svc.doLongPressText(target)
        }

        /** 长按屏幕正中间（长按待命模式下说「中间」时调用） */
        fun longPressCenter(): Boolean {
            val svc = instance ?: return false
            return svc.doLongPressCenter()
        }

        /** 显示网格（从全屏开始，逐级缩小定位） */
        fun showGrid() = instance?.doShowGrid()

        /** 缩放到第 number 格（把该格放大为新的网格，不是点击） */
        fun zoomGrid(number: Int): Boolean {
            val svc = instance ?: return false
            return svc.doZoomGrid(number)
        }

        /** 点击当前网格中心（定位完成后真正点击） */
        fun tapGridCenter(): Boolean {
            val svc = instance ?: return false
            svc.doTapGridCenter()
            return true
        }

        /** 点击第 number 格（网格模式一步式点击，不是缩放） */
        fun tapGridCell(number: Int): Boolean {
            val svc = instance ?: return false
            return svc.doTapGridCell(number)
        }

        /** 最近一次网格点击的屏幕落点（供「重复」回放同一点位） */
        @Volatile
        var lastGridTapPoint: Pair<Float, Float>? = null
            private set

        /** 在指定屏幕坐标点击（供「重复」回放网格点击） */
        fun tapAtPoint(x: Float, y: Float): Boolean {
            val svc = instance ?: return false
            return svc.tapAt(x, y)
        }

        /** 隐藏网格 */
        fun hideGrid() = instance?.doHideGrid()

        /** 网格是否正在显示（供识别层判断：显示中数字优先按网格处理） */
        fun isGridShowing(): Boolean = instance?.gridStack?.isNotEmpty() == true

        /** 动作附加提示（如「已达安全上限」「桌面不支持摇移」）；识别层取走即清空，null=无附加提示 */
        fun consumeActionNote(): String? {
            val svc = instance ?: return null
            val n = svc.actionNote
            svc.actionNote = null
            return n
        }

        /** 编号浮层是否正在显示（供识别层判断：显示中纯数字直接点编号） */
        fun isLabelsVisible(): Boolean = instance?.labelsVisible == true
    }

    private var windowManager: WindowManager? = null
    private var islandBar: IslandBar? = null
    private var labelsOverlayView: View? = null
    private var gridOverlayView: View? = null
    private val gridStack = mutableListOf<RectF>()  // 网格缩放栈（归一化比例 0~1），末位=当前区域
    private var labelsVisible = false

    // 「显示编号」快照：屏幕上画着的编号 → 元素屏幕矩形。点击/长按优先按它定位——
    // 所见即所点。2026-09-14 抖音真机实锤：信息流元素实时增删，点时重新遍历会错位/越界
    // （显示时 25+ 个、53 秒后只剩 8 个 → 明明画着 28 号却报「没有编号」）
    private var labelRects: List<Rect> = emptyList()

    // 动作附加提示（音量上限、桌面不支持摇移等），由 performAction/nudgeCommand 写入、
    // 识别层经 consumeActionNote 取走并清空
    @Volatile
    var actionNote: String? = null
        private set

    // 主线程 Handler：所有悬浮层 UI 操作（addView/动画/移除）都必须切到主线程
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 记录滚动事件时间戳（滚动校验的真相来源），必须在 labelsVisible 早退之前
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            lastScrollEventAt = SystemClock.elapsedRealtime()
        }
        // 编号浮层显示时，屏幕内容变化 → 实时刷新编号
        if (!labelsVisible) return
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> scheduleLabelsRefresh()
        }
    }

    override fun onInterrupt() {
        // 无长任务需要中断
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // 订阅滚动事件：v0.23 起滚动校验依赖 TYPE_VIEW_SCROLLED 事件流（系统级真相），
        // 服务默认配置未包含该类型，需运行时合并订阅
        runCatching {
            serviceInfo = serviceInfo.apply {
                eventTypes = eventTypes or AccessibilityEvent.TYPE_VIEW_SCROLLED
            }
            Log.i(TAG, "已订阅 TYPE_VIEW_SCROLLED（滚动校验依赖）")
        }
        createBar()
        loadScrollCache() // B 线：加载滚动机制学习缓存
        // 服务（重）连接时按当前会话状态同步横条：无障碍被系统重启后，
        // 会话还在就恢复显示，会话不在就保持隐藏（绝不凭空挂一条死横条）
        doShowBarOrHide()
        Log.i(TAG, "无障碍服务已连接（会话中=${sessionActive}）")
    }

    /** 按会话状态同步横条可见性（服务连接/重连时调用） */
    private fun doShowBarOrHide() {
        if (sessionActive) doShowBar() else doHideBar()
    }

    override fun onDestroy() {
        removeBar()
        removeLabelsOverlay()
        removeGridOverlay()
        gridStack.clear()
        instance = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    // ===== 顶部状态横条 =====

    private fun createBar() {
        val wm = windowManager ?: return
        val island = IslandBar(this)
        val params = island.windowParams(statusBarHeight())
        // 可见性只跟随会话状态，不跟随无障碍服务：
        // 无障碍服务是常开的（几天不掉线），若默认显示就会变成永久挂在桌面上的死横条
        // ——会话没开始时不可操作、也不会消失（2026-09-08 用户实测发现的 bug）。
        // 用 sessionActive 同时解决启动时序竞态：会话先于服务连接开始时，建条即按会话状态显示。
        island.root.visibility = if (sessionActive) View.VISIBLE else View.GONE
        runCatching { wm.addView(island.root, params) }
        islandBar = island
    }

    private fun removeBar() {
        islandBar?.let { runCatching { windowManager?.removeView(it.root) } }
        islandBar = null
    }

    /** 获取系统状态栏高度（横条要下移到它下方） */
    private fun statusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    private fun doShowBar() {
        islandBar?.show()
    }

    private fun doHideBar() {
        islandBar?.hide()
    }

    private fun doUpdateBar(text: String) {
        islandBar?.update(text)
    }

    private fun doNudgeIsland(dx: Float, dy: Float) {
        islandBar?.nudgeToward(dx, dy)
    }

    // ===== 蓝色圆球动画 =====

    /**
     * 滑动时在屏幕中央显示「流光胶囊」：视觉与底层手势 1:1 融合——
     * 光带的起点/终点/时长与 dispatchGesture 的手指路径**完全同源**
     * （同一 SwipeSegment、同一 SWIPE_GESTURE_MS）：同时出发、同时到达、走同一条线。
     * 到达后原地余晖消散 140ms（iOS 式 dissolve），不再边跑边散。
     *
     * 性能：单视图单动画一次绘制（ValueAnimator 只驱动 progress），零每帧分配以外的开销，
     * 与 iOS 系统动画同级，不影响帧率。
     *
     * 注意：本方法可能在识别线程被调用，所有 UI 操作必须 post 到主线程；
     * 结束用「主线程兜底移除」保证视图一定被清掉，不依赖回调。
     */
    /**
     * 启动流光胶囊（必须在主线程调用，且紧跟在 dispatchGesture 之前）。
     *
     * v0.25.2 同步绑定修复：旧实现把动画 post 到主线程队列（晚一拍起跑），
     * 且用减速插值而系统手势是线性播放（中途越走越脱节）——用户实测「动作先出现，光效才出现」。
     * 现改为：同步加视图 + 线性插值，与 dispatchGesture 同一起跑线、同一路径、同一时长——
     * 光头在任一时刻的位置 = 真实手指的位置。余晖段（手势结束后）才允许原地消散。
     */
    private fun startGestureStreak(seg: SwipeSegment, motionMs: Long) {
        val wm = windowManager ?: return
        val streak = GestureStreakView(this, seg, motionMs)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        runCatching { wm.addView(streak, params) }
        Log.i(TAG, "流光启动 dur=${motionMs}ms dist=${seg.dist.toInt()}px")
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = motionMs + AFTERGLOW_MS
            interpolator = LinearInterpolator()   // 与系统手势的线性播放严格一致，中途不脱节
            addUpdateListener { streak.progress = it.animatedValue as Float }
        }
        anim.start()
        // 兜底：无论动画是否正常结束，稍后强制移除，绝不留残影
        mainHandler.postDelayed(
            {
                runCatching { wm.removeView(streak) }
                anim.cancel()
            },
            motionMs + AFTERGLOW_MS + 80L
        )
    }

    /**
     * 流光胶囊 v2：光头沿真实手指路径滑行（同一 SwipeSegment），两节拖尾沿同一条路径回退，
     * 到达终点后原地拉长消散。方向只分横竖两态，几何全部来自 SwipeSegment，无任何独立魔法数。
     */
    private class GestureStreakView(
        context: Context,
        private val seg: SwipeSegment,
        motionMs: Long,
    ) : View(context) {

        var progress = 0f   // 0..1（总时长归一化；≤MOTION_FRACTION 为同步滑行段）
            set(value) { field = value.coerceIn(0f, 1f); invalidate() }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val density = resources.displayMetrics.density
        private val horizontal = seg.startY == seg.endY
        private val len = (seg.dist * 0.42f).coerceIn(density * 64f, density * 150f)
        private val thick = density * 13f
        private val motionFraction = motionMs.toFloat() / (motionMs + AFTERGLOW_MS)

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val motion = (progress / motionFraction).coerceIn(0f, 1f)
            val overall = when {                     // 前 8% 快速渐显，余晖段整体消散
                progress < 0.08f -> progress / 0.08f
                progress > motionFraction -> 1f - (progress - motionFraction) / (1f - motionFraction)
                else -> 1f
            }.coerceIn(0f, 1f)
            if (overall <= 0.01f) return

            val dissolve = ((progress - motionFraction) / (1f - motionFraction))
                .coerceIn(0f, 1f) * 0.18f             // 余晖期轻微拉长=消散感
            val headLen = len * (0.9f + 0.1f * motion + dissolve)

            // 拖尾两节：沿同一条路径向起点方向回退（与真实路径完全重合）
            drawAt(canvas, (motion - 0.16f).coerceIn(0f, 1f), overall * 0.55f, thick * 0.92f, 0x590A84FF, false)
            drawAt(canvas, (motion - 0.32f).coerceIn(0f, 1f), overall * 0.35f, thick * 0.8f, 0x330A84FF, false)
            // 光头：全不透明（用户反馈 v0.20 太淡），头亮尾透
            drawAt(canvas, motion, overall, thick, 0xFF0A84FF, true, headLen)
            // 高光芯
            drawAt(canvas, motion, overall * 0.6f, thick * 0.34f, 0xCCFFFFFF, false, headLen * 0.72f)
        }

        /** 在路径进度 t 处画一枚沿轴胶囊；gradient=true 时头亮尾透（头部=运动方向一侧） */
        private fun drawAt(
            canvas: Canvas, t: Float, alpha: Float, thick: Float,
            color: Long, gradient: Boolean, lenOverride: Float = len,
        ) {
            if (alpha <= 0.01f) return
            val x = seg.startX + (seg.endX - seg.startX) * t
            val y = seg.startY + (seg.endY - seg.startY) * t
            paint.shader = null
            paint.color = color.toInt()
            paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
            val left: Float; val top: Float; val right: Float; val bottom: Float
            if (horizontal) {
                left = x - lenOverride / 2f; right = x + lenOverride / 2f
                top = y - thick / 2f; bottom = y + thick / 2f
                if (gradient) {
                    val headX = if (seg.endX > seg.startX) right else left
                    val tailX = if (seg.endX > seg.startX) left else right
                    paint.shader = LinearGradient(headX, 0f, tailX, 0f, color.toInt(), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                }
            } else {
                left = x - thick / 2f; right = x + thick / 2f
                top = y - lenOverride / 2f; bottom = y + lenOverride / 2f
                if (gradient) {
                    val headY = if (seg.endY > seg.startY) bottom else top
                    val tailY = if (seg.endY > seg.startY) top else bottom
                    paint.shader = LinearGradient(0f, headY, 0f, tailY, color.toInt(), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                }
            }
            canvas.drawRoundRect(left, top, right, bottom, thick / 2f, thick / 2f, paint)
        }
    }

    // ===== 媒体音量控制（含安全上限）=====

    private fun audioManager(): AudioManager =
        getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * 增大媒体音量一格，但绝不越过安全上限（[VOLUME_CAP_PERCENT]%）。
     * 到顶时命令仍算「成功执行」（用户意图被尊重，只是安全上不再加大），
     * 并写入 [actionNote] 让横条告知用户为什么不再加。
     */
    private fun volumeUp(): Boolean {
        val am = audioManager()
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cap = max * VOLUME_CAP_PERCENT / 100
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (cur >= cap) {
            actionNote = "⚠️ 已达安全音量上限（${VOLUME_CAP_PERCENT}%），不再增大"
            Log.i(TAG, "VOLUME_UP 到顶：$cur/$max（安全上限 $cap 格）")
            // 已到顶也弹系统面板：让用户亲眼看见滑条顶在上限位置（与手按音量键一致）
            am.setStreamVolume(AudioManager.STREAM_MUSIC, cur, AudioManager.FLAG_SHOW_UI)
            true
        } else {
            val target = minOf(cur + 1, cap)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
            actionNote = null
            Log.i(TAG, "VOLUME_UP $cur->$target/$max（安全上限 $cap 格）")
            true
        }
    }

    /** 降低媒体音量一格，最低到 0（等于静音） */
    private fun volumeDown(): Boolean {
        val am = audioManager()
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (cur <= 0) {
            actionNote = "⚠️ 已是最低音量"
            Log.i(TAG, "VOLUME_DOWN 到底：0/$max")
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
            true
        } else {
            val target = cur - 1
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
            actionNote = null
            Log.i(TAG, "VOLUME_DOWN $cur->$target/$max")
            true
        }
    }

    /** 媒体静音（音量归零；之后说「增加音量」从 0 重新加起，仍受安全上限约束） */
    private fun volumeMute(): Boolean {
        val am = audioManager()
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
        actionNote = null
        Log.i(TAG, "VOLUME_MUTE 静音（原音量档位清零/$max）")
        return true
    }

    // ===== 输入框文本操作探针（开发期诊断，v0.40-Spike，商用前移除）=====
    // 依次验证微信等输入框的可行性并出结论表：
    // 找可编辑节点 → 聚焦 → 读原文 → 设光标到 0 → SET_TEXT 写入 → 回读 → 剪贴板粘贴 → 回读 → 清空恢复。
    // 只在输入框写测试词并清空，绝不发送任何消息。

    private fun findEditableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        if (root.isEditable) return root
        val all = mutableListOf<AccessibilityNodeInfo>()
        runCatching { collectAllNodes(root, all) }
        var found: AccessibilityNodeInfo? = null
        for (n in all) {
            if (n.isEditable) {
                found = n
                break
            }
        }
        // 未命中的节点也要释放，命中的返回给调用方使用
        all.forEach { if (it !== found) runCatching { it.recycle() } }
        return found
    }

    private fun runTextProbe() {
        val node = findEditableNode()
        if (node == null) {
            Log.w(TAG, "TEXT_PROBE 未找到可编辑节点（是否已聚焦聊天输入框？）")
            return
        }
        Log.i(TAG, "TEXT_PROBE 节点：class=${node.className} editable=${node.isEditable} 原文=[${node.text}]")

        // 1) 聚焦
        val focused = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        Log.i(TAG, "TEXT_PROBE 聚焦=$focused")

        // 2) 光标：SET_SELECTION 到 0
        val selArgs = android.os.Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 0)
        }
        val sel = node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
        val selPos = runCatching { node.textSelectionStart }.getOrDefault(-1)
        Log.i(TAG, "TEXT_PROBE 设光标 performAction=$sel 实际光标位=$selPos")

        // 3) SET_TEXT 写入
        val setArgs = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "听写测试A")
        }
        val set = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
        val fresh = findEditableNode()
        val afterSet = fresh?.text?.toString() ?: ""
        Log.i(TAG, "TEXT_PROBE SET_TEXT performAction=$set 回读=[$afterSet]")

        // 4) 剪贴板粘贴兜底
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("probe", "粘贴测试B"))
        val fresh2 = findEditableNode()
        val pasted = fresh2?.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        val fresh3 = findEditableNode()
        val afterPaste = fresh3?.text?.toString() ?: ""
        Log.i(TAG, "TEXT_PROBE 粘贴 performAction=$pasted 回读=[$afterPaste]")

        // 5) 光标移到末尾（模拟「移回光标」类操作）
        val endArgs = android.os.Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, afterPaste.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, afterPaste.length)
        }
        val selEnd = (fresh3 ?: fresh2 ?: node).performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, endArgs)
        Log.i(TAG, "TEXT_PROBE 光标移末尾 performAction=$selEnd")

        // 6) 清空恢复
        val clearArgs = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        val cleared = (fresh3 ?: fresh2 ?: node).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
        val fresh4 = findEditableNode()
        Log.i(TAG, "TEXT_PROBE 清空 performAction=$cleared 终态=[${fresh4?.text ?: ""}]")
        Log.i(TAG, "TEXT_PROBE 完成")
    }

    // ===== 输入框文本编辑（v0.40.0）=====
    // 实现链已经 TEXT_PROBE 真机实证（微信聊天输入框=标准 EditText，随选朗读共存下树完全可见）：
    // 读文本 → 改写 → ACTION_SET_TEXT 回写 → ACTION_SET_SELECTION 恢复光标。
    // 全部动作失败时写 actionNote（「未找到输入框」等），经 NOTE_ACTIONS 通道告知用户，不静默。

    /** 当前屏幕上的可编辑节点（无则 null） */
    private fun editableNodeOrNull(): AccessibilityNodeInfo? = findEditableNode()

    /** 光标位置：无有效选区信息时视为文本末尾 */
    private fun editCursorOf(n: AccessibilityNodeInfo): Int {
        val len = n.text?.length ?: 0
        val s = runCatching { n.textSelectionStart }.getOrDefault(-1)
        val e = runCatching { n.textSelectionEnd }.getOrDefault(-1)
        return if (s in 0..len && e == s) s else len
    }

    /** 回写文本并把光标放到指定位置（一次 SET_TEXT + 一次 SET_SELECTION） */
    private fun editTextSet(n: AccessibilityNodeInfo, text: CharSequence, cursor: Int): Boolean {
        val setArgs = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
        if (ok) {
            val selArgs = android.os.Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            }
            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
        }
        return ok
    }

    /** 光标平移（负=左移，正=右移），钳制在 [0, 文本长度] */
    private fun textCursorMove(chars: Int): Boolean {
        val n = editableNodeOrNull()
        if (n == null) { actionNote = "未找到输入框"; return false }
        val len = n.text?.length ?: 0
        val cur = editCursorOf(n)
        val target = (cur + chars).coerceIn(0, len)
        val args = android.os.Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, target)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, target)
        }
        val ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        actionNote = null
        Log.i(TAG, "TEXT_CURSOR $cur->$target/$len ok=$ok")
        return ok
    }

    /** 删除光标前一个字符（无光标信息视为末尾）；空文本提示 */
    private fun textDeleteBackward(): Boolean {
        val n = editableNodeOrNull()
        if (n == null) { actionNote = "未找到输入框"; return false }
        val text = n.text?.toString() ?: ""
        if (text.isEmpty()) { actionNote = "输入框为空"; return false }
        val cur = editCursorOf(n).coerceIn(0, text.length)
        val delPos = (cur - 1).coerceAtLeast(0)
        val newText = text.removeRange(delPos, cur.coerceAtLeast(delPos + 1))
        val ok = editTextSet(n, newText, delPos)
        actionNote = null
        Log.i(TAG, "TEXT_DELETE 光标=$cur 余[$newText] ok=$ok")
        return ok
    }

    /** 清空输入框 */
    private fun textClear(): Boolean {
        val n = editableNodeOrNull()
        if (n == null) { actionNote = "未找到输入框"; return false }
        val ok = editTextSet(n, "", 0)
        actionNote = null
        Log.i(TAG, "TEXT_CLEAR ok=$ok")
        return ok
    }

    /** 听写插入：在光标处写入内容，光标移至插入末尾（小米式短听写的落笔动作） */
    private fun textInsert(content: String): Boolean {
        val n = editableNodeOrNull()
        if (n == null) { actionNote = "未找到输入框"; return false }
        val text = n.text?.toString() ?: ""
        val cur = editCursorOf(n).coerceIn(0, text.length)
        val newText = text.substring(0, cur) + content + text.substring(cur)
        val ok = editTextSet(n, newText, cur + content.length)
        actionNote = null
        Log.i(TAG, "TEXT_INSERT [$content]@$cur ok=$ok")
        return ok
    }

    /** 替换：把输入框中的 [start,end) 段换成 replacement，光标落在替换段末尾（iOS 口径） */
    private fun textReplaceRange(start: Int, end: Int, replacement: String): Boolean {
        val n = editableNodeOrNull()
        if (n == null) { actionNote = "未找到输入框"; return false }
        val text = n.text?.toString() ?: ""
        if (start < 0 || end > text.length || start > end) { actionNote = "替换位置无效"; return false }
        val newText = text.substring(0, start) + replacement + text.substring(end)
        val ok = editTextSet(n, newText, start + replacement.length)
        actionNote = null
        Log.i(TAG, "TEXT_REPLACE [$start,$end)->[${replacement}] 全文[$newText] ok=$ok")
        return ok
    }

    // ===== 动作执行 =====

    /** 执行动作标识，返回是否成功 */
    private fun performAction(action: String): Boolean {
        return when (action) {
            "swipe_up" -> {
                startGestureStreak(swipeSegment(0f, -1f), SWIPE_GESTURE_MS)
                scrollSmart(vertical = true, forward = true)
            }
            "swipe_down" -> {
                startGestureStreak(swipeSegment(0f, 1f), SWIPE_GESTURE_MS)
                scrollSmart(vertical = true, forward = false)
            }
            "swipe_left" -> {
                startGestureStreak(swipeSegment(-1f, 0f), SWIPE_GESTURE_MS)
                scrollSmart(vertical = false, forward = true)
            }
            "swipe_right" -> {
                startGestureStreak(swipeSegment(1f, 0f), SWIPE_GESTURE_MS)
                scrollSmart(vertical = false, forward = false)
            }
            "nudge_up" -> nudgeCommand(vertical = true, forward = true)
            "nudge_down" -> nudgeCommand(vertical = true, forward = false)
            "nudge_left" -> nudgeCommand(vertical = false, forward = true)
            "nudge_right" -> nudgeCommand(vertical = false, forward = false)
            "zoom_in" -> pinchZoom(zoomIn = true)
            "zoom_out" -> pinchZoom(zoomIn = false)
            "go_back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "go_home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "open_recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "volume_up" -> volumeUp()
            "volume_down" -> volumeDown()
            "volume_mute" -> volumeMute()
            "text_cursor_left" -> textCursorMove(-1)
            "text_cursor_right" -> textCursorMove(1)
            "text_delete" -> textDeleteBackward()
            "text_clear" -> textClear()
            "lock_screen" -> runCatching { performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) }.getOrDefault(false)
            "show_notifications" -> runCatching { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) }.getOrDefault(false)
            "show_quick_settings" -> runCatching { performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) }.getOrDefault(false)
            "show_labels" -> {
                doHideGrid() // 互斥：编号与网格不可同时显示（2026-09-08 用户实测发现重叠）
                doShowLabels()
                true
            }
            "hide_labels" -> {
                doHideLabels()
                true
            }
            "show_grid" -> {
                doHideLabels() // 互斥：网格与编号不可同时显示（同上）
                doShowGrid()
                true
            }
            "hide_grid" -> {
                doHideGrid()
                true
            }
            "hide_overlays" -> {
                doHideLabels()
                doHideGrid()
                true
            }
            "tap" -> {
                // 网格模式下点击当前网格中心；否则点击屏幕中心
                if (gridStack.isNotEmpty()) {
                    doTapGridCenter()
                } else {
                    val dm = resources.displayMetrics
                    tapAt(dm.widthPixels / 2f, dm.heightPixels / 2f)
                }
                true
            }
            "double_tap" -> {
                doDoubleTap()
                true
            }
            "long_press" -> {
                doLongPress()
                true
            }
            "grid_back" -> {
                doGridBack()
                true
            }
            else -> {
                Log.w(TAG, "未知动作：$action")
                false
            }
        }
    }

    /**
     * 从屏幕中心附近向指定方向滑动。
     * @param dx 水平方向系数：-1 左、0 不动、1 右
     * @param dy 垂直方向系数：-1 上、0 不动、1 下
     */
    /**
     * 滑动手势的几何定义：起点/终点。这是全 App 唯一的滑动几何来源——
     * 手势层（dispatchGesture）与视觉层（流光胶囊）共用同一份数据，保证「光在哪手指就在哪」。
     * 距离=短边 40%、中心对称、时长 SWIPE_GESTURE_MS，均为既定设计，本次未改动。
     */
    private data class SwipeSegment(
        val startX: Float, val startY: Float, val endX: Float, val endY: Float,
    ) {
        val dist: Float =
            kotlin.math.sqrt((endX - startX) * (endX - startX) + (endY - startY) * (endY - startY))
    }

    /** 由方向系数生成滑动段（dx/dy ∈ {-1,0,1}） */
    private fun swipeSegment(dx: Float, dy: Float): SwipeSegment {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val dist = minOf(w, h) * 0.4f
        val cx = w / 2f
        val cy = h / 2f
        return SwipeSegment(cx - dx * dist / 2f, cy - dy * dist / 2f, cx + dx * dist / 2f, cy + dy * dist / 2f)
    }

    /**
     * 平滑拖动（单笔划）：沿滑动段匀速拖动后抬指。
     * 2026-09-09 实证：多段 continueStroke 手势链在真机上被系统静默丢弃（RecyclerView 与
     * WebView 均拖不动），单笔划才是验证过可靠的机制，故废弃多段方案。
     * 丝滑感来自：拖动本身连续无级 + 抬指时的惯性（flick 快扫带惯性滑行，慢拖精准停）。
     */
    private fun smoothDrag(seg: SwipeSegment, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(seg.startX, seg.startY)
            lineTo(seg.endX, seg.endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
        Log.i(TAG, "平滑拖动 dispatched=$dispatched dur=${durationMs}ms dist=${seg.dist.toInt()}px")
        return dispatched
    }

    /** 「滑动」= 轻扫带惯性。quick=true 用于桌面/系统页：280ms 短扫，
     *  压在长按阈值之下——380ms 拖动实测会误触发 MIUI 桌面编辑模式（2026-09-10 截图实证） */
    private fun swipe(dx: Float, dy: Float, quick: Boolean = false): Boolean =
        smoothDrag(swipeSegment(dx, dy), if (quick) SYSTEM_SWIPE_MS else SWIPE_GESTURE_MS)

    /** 摇移的几何：短边 16%、中心对称——慢拖一小段，精准可控，与「滑动」尺度明确区分 */
    private fun nudgeSegment(dx: Float, dy: Float): SwipeSegment {
        val dm = resources.displayMetrics
        val dist = minOf(dm.widthPixels, dm.heightPixels) * 0.16f
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels / 2f
        return SwipeSegment(cx - dx * dist / 2f, cy - dy * dist / 2f, cx + dx * dist / 2f, cy + dy * dist / 2f)
    }

    /** 「摇移」= 慢拖精准（与滑动惯性感明确区分）：短边 16%、520ms 慢拖，几乎无惯性，挪多少停多少 */
    private fun nudge(dx: Float, dy: Float): Boolean =
        smoothDrag(nudgeSegment(dx, dy), NUDGE_GESTURE_MS)

    private fun dirOf(vertical: Boolean, forward: Boolean): Pair<Float, Float> =
        if (vertical) (if (forward) 0f to -1f else 0f to 1f)
        else (if (forward) -1f to 0f else 1f to 0f)

    /** 递归遍历无障碍树，收集所有节点（含自身），用于执行滚动与统一释放 */
    private fun collectAllNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int = 0
    ) {
        if (depth > MAX_TREE_DEPTH || out.size >= MAX_TREE_NODES) return
        out.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllNodes(child, out, depth + 1)
        }
    }

    /** 判断节点方向是否匹配本次滚动方向（横向容器优先用类名，其余用高宽比兜底） */
    private fun matchOrientation(n: AccessibilityNodeInfo, vertical: Boolean): Boolean {
        val cls = n.className?.toString() ?: ""
        val knownHorizontal = cls.contains("HorizontalScrollView") || cls.contains("ViewPager")
        if (knownHorizontal) return !vertical
        val r = Rect(); n.getBoundsInScreen(r)
        val looksVertical = r.height() > r.width()
        return looksVertical == vertical
    }

    /** 对「正确方向」的可滚动节点执行无障碍标准滚动动作；成功返回 true（节点统一释放） */
    private fun performScrollAction(vertical: Boolean, forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                     else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val all = mutableListOf<AccessibilityNodeInfo>()
        runCatching { collectAllNodes(root, all) }
        // 派发标准滚动（滚动校验由 standardScroll 的滚动事件监听完成）
        var ok = false
        try {
            // 反向遍历 = 深层优先，先滚动真正的内容列表（避免被外层空壳容器抢先）
            for (n in all.asReversed()) {
                if (!n.isScrollable) continue
                if (!matchOrientation(n, vertical)) continue
                if (runCatching { n.performAction(action) }.getOrDefault(false)) {
                    Log.i(TAG, "标准滚动成功(${if (vertical) "纵" else "横"}): ${n.className}")
                    ok = true
                    break
                }
            }
        } finally {
            // 释放遍历得到的全部节点，避免长期运行累积占用
            all.forEach { runCatching { it.recycle() } }
        }
        return ok
    }

    /**
     * 智能滚动（v0.22 重构：手势优先）：
     *
     * 第一性原理：标准无障碍滚动 = 翻页键，一次跳一屏，天生「一格一格」不丝滑；
     * 真实拖动手势 = 内容跟手的连续滚动，才是信息流的正确姿势。
     * 因此默认手势优先（三段变速平滑拖动），仅当手势被页面拦截（假成功家族：闲鱼吸顶栏等）
     * 才回退标准滚动兜底，并学习记忆该页面的有效机制，下次直达。
     *
     * 假成功校验不变：判断标准永远是「页面可见结果」（前后指纹对比），不是 API 返回值。
     */
    private fun scrollSmart(vertical: Boolean, forward: Boolean): Boolean {
        // 最近任务页（系统窗口）："上滑"是抛卡片清后台，不是滚动；直接手势（HyperOS 实测结论，保留）
        val pkg = rootInActiveWindow?.packageName?.toString()
        if (pkg == null || pkg == "com.android.systemui" || pkg == "android" || pkg == "com.miui.home") {
            Log.i(TAG, "系统页面($pkg)，跳过标准滚动，直接手势")
            val (dx0, dy0) = dirOf(vertical, forward)
            return swipe(dx0, dy0, quick = true)
        }

        // 已学习页面：按记忆的机制直达（缓存值沿用旧语义：true=标准滚动有效，false=手势有效）
        val cacheKey = scrollCacheKey(vertical)
        when (cacheKey?.let { scrollMechCache[it] }) {
            true -> {
                Log.i(TAG, "缓存命中：标准滚动（$cacheKey）")
                if (standardScroll(vertical, forward)) return true
                Log.w(TAG, "已缓存标准滚动失效（页面可能改版），回退手势")
                return dispatchGestureScroll(vertical, forward, learn = false)
            }
            false -> {
                Log.i(TAG, "缓存命中：手势（$cacheKey）")
                return dispatchGestureScroll(vertical, forward, learn = true)
            }
            else -> { /* 未知页面：手势优先，异步学习（见 dispatchGestureScroll） */ }
        }
        return dispatchGestureScroll(vertical, forward, learn = true)
    }

    /**
     * 派发平滑拖动；返回「已派发」（同步），验证与学习**异步**进行。
     *
     * 为什么验证必须异步：整个命令链跑在主线程，滚动事件的回调也排在主线程队列——
     * 如果当场 sleep 后检查，队列里的事件永远还没派发，必然误判「没滚」（v0.22/0.23 的一格一格根源）。
     * 改为 postDelayed 到队列排空后再读事件时间戳，命令本身零等待。
     */
    private fun dispatchGestureScroll(vertical: Boolean, forward: Boolean, learn: Boolean): Boolean {
        val (dx, dy) = dirOf(vertical, forward)
        val t0 = SystemClock.elapsedRealtime()
        val dispatched = smoothDrag(swipeSegment(dx, dy), SWIPE_GESTURE_MS)
        if (!dispatched) return false
        mainHandler.postDelayed({
            val gotEvent = lastScrollEventAt > t0
            if (gotEvent) {
                gestureNoEventStreak = 0
                Log.i(TAG, "手势滚动已确证（滚动事件）")
                if (learn) learnScrollMech(vertical, standardWorked = false)
            } else {
                gestureNoEventStreak++
                Log.i(TAG, "拖动后未收到滚动事件（streak=$gestureNoEventStreak）")
                if (gestureNoEventStreak >= 2 && learn) {
                    // 连续两次无事件：该页面真拦截手势，记住下次直接标准滚动（跳变但有效）
                    learnScrollMech(vertical, standardWorked = true)
                    Log.w(TAG, "页面判定拦截手势：已记为标准滚动优先")
                }
            }
        }, SWIPE_GESTURE_MS + 300L)
        return dispatched
    }

    /** 标准滚动：无障碍滚动动作（跳变式，仅作被拦截页面的兜底；结果以 API 返回为准） */
    private fun standardScroll(vertical: Boolean, forward: Boolean): Boolean =
        performScrollAction(vertical, forward)

    /** 摇移的反馈（v0.25.3 重设计）：**不做光效**——小步轻挪的内容运动本身就是反馈，
     *  叠加流光属于「反馈音量超过动作本身」（用户实测随意、一闪而过）。
     *  iOS 式做法：让系统元素轻轻「应答」——顶部胶囊朝拖动方向微移 8dp 再弹回。 */
    private fun nudgeCommand(vertical: Boolean, forward: Boolean): Boolean {
        val pkg = rootInActiveWindow?.packageName?.toString()
        if (pkg == null || pkg == "com.android.systemui" || pkg == "android" || pkg == "com.miui.home") {
            actionNote = "⚠️ 桌面/系统页不支持摇移，回到内容页面再用"
            return false
        }
        val (dx, dy) = dirOf(vertical, forward)
        val t0 = SystemClock.elapsedRealtime()
        val dispatched = nudge(dx, dy)
        if (!dispatched) return false
        nudgeIslandToward(dx, dy)
        // 摇移是精准命令，绝不跳页兜底：页面没动就让它安静（用户看得见），事件到达则清除拦截计数
        SystemClock.sleep(NUDGE_GESTURE_MS + 200)
        if (lastScrollEventAt > t0) gestureNoEventStreak = 0
        return true
    }

    /**
     * 双指捏合缩放（v0.34.0，看图片/网页）：两只手指水平对称布局，屏幕中心为轴
     * 同时向外张开（放大）或向内合拢（缩小），500ms 匀速——系统按标准双指捏合处理。
     * 桌面/系统页拒绝（双指捏合会触发桌面编辑态，安全第一，横条提示），与摇移同一保护。
     */
    private fun pinchZoom(zoomIn: Boolean): Boolean {
        val pkg = rootInActiveWindow?.packageName?.toString()
        if (pkg == null || pkg == "com.android.systemui" || pkg == "android" || pkg == "com.miui.home") {
            actionNote = "⚠️ 桌面/系统页不支持双指缩放，回到内容页面再用"
            return false
        }
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels / 2f
        // 手指横向对称：起点半距 → 终点半距（放大=张开，缩小=合拢）
        val halfStart = dm.widthPixels * (if (zoomIn) 0.13f else 0.32f)
        val halfEnd = dm.widthPixels * (if (zoomIn) 0.32f else 0.13f)
        val pathL = android.graphics.Path().apply {
            moveTo(cx - halfStart, cy); lineTo(cx - halfEnd, cy)
        }
        val pathR = android.graphics.Path().apply {
            moveTo(cx + halfStart, cy); lineTo(cx + halfEnd, cy)
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(pathL, 0, PINCH_GESTURE_MS))
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(pathR, 0, PINCH_GESTURE_MS))
            .build()
        val dispatched = runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
        Log.i(TAG, "双指${if (zoomIn) "放大" else "缩小"} dispatched=$dispatched")
        return dispatched
    }

    // ===== 滚动机制学习缓存（B 线）=====
    // 第一性原理：每个页面对「标准滚动/手势」的响应特性是稳定的（闲鱼拦截手势、抖音消息页吃掉标准滚动）。
    // 一次试探学会了就不用每次都付试探成本：记住「这个包名+页面签名+方向 用哪种机制」。
    // 页面签名用 Activity 名（同一 Activity 不同列表位置对滚动机制响应相同，无需更细）。
    // 校验方式：v0.23 起用 TYPE_VIEW_SCROLLED 事件流（系统级真相），废弃易误判的界面树指纹采样。

    /** 缓存键：包名|activity|方向 */
    private fun scrollCacheKey(vertical: Boolean): String? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: return null
        val act = try {
            val cls = root.className?.toString() ?: ""
            cls.substringAfterLast('.')
        } catch (_: Exception) { "" }
        return "${pkg}|${act}|${if (vertical) "v" else "h"}"
    }

    /** 已学习页面缓存（内存态；onCreate 加载，写穿到磁盘） */
    private val scrollMechCache = HashMap<String, Boolean>() // value: true=标准滚动有效, false=必须手势

    private fun scrollCacheFile(): File = File(filesDir, "scroll_mech_cache.json")

    // 缓存格式版本：v1 是「标准优先」时代学的，与手势优先策略语义不兼容（会把丝滑页面记成跳变页），加载时直接弃用
    private val SCROLL_CACHE_VERSION = 2

    private fun loadScrollCache() {
        runCatching {
            val f = scrollCacheFile()
            if (!f.exists()) return
            val obj = JSONObject(f.readText())
            if (obj.optInt("_v", 1) != SCROLL_CACHE_VERSION) {
                Log.w(TAG, "滚动机制缓存版本过旧（标准优先时代所学），已弃用重学")
                scrollMechCache.clear()
                f.delete()
                return
            }
            obj.keys().forEach { k -> if (k != "_v") scrollMechCache[k] = obj.getBoolean(k) }
            Log.i(TAG, "滚动机制缓存已加载：${scrollMechCache.size} 条")
        }
    }

    private fun saveScrollCache() {
        runCatching {
            val obj = JSONObject()
            obj.put("_v", SCROLL_CACHE_VERSION)
            scrollMechCache.forEach { (k, v) -> obj.put(k, v) }
            scrollCacheFile().writeText(obj.toString())
        }
    }

    /** 记住一次学习结果（标准滚动是否在这个页面真的有效） */
    private fun learnScrollMech(vertical: Boolean, standardWorked: Boolean) {
        val key = scrollCacheKey(vertical) ?: return
        scrollMechCache[key] = standardWorked
        saveScrollCache()
        Log.i(TAG, "滚动机制学习：$key -> ${if (standardWorked) "标准滚动" else "手势"}")
    }

    /** 在指定屏幕坐标模拟一次真实点击（对桌面小组件等 performAction 无效的元素同样生效） */
    private fun tapAt(x: Float, y: Float): Boolean {
        // 2026-09-14 裘晨阳三次崩溃实证：负坐标（目标在屏幕外，如桌面旁页/负一屏）会让
        // StrokeDescription 直接 FATAL 炸进程、语音会话陪葬——先 clamp 屏幕内，再兜 runCatching
        val dm = resources.displayMetrics
        val cx = x.coerceIn(0f, dm.widthPixels.toFloat())
        val cy = y.coerceIn(0f, dm.heightPixels.toFloat())
        val path = Path().apply {
            moveTo(cx, cy)
            lineTo(cx, cy)
        }
        return runCatching {
            val stroke = GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        }.getOrDefault(false)
    }

    /** 原地长按：同一点、时长拉长，系统识别为长按（如桌面图标长按出菜单） */
    private fun longPressAt(x: Float, y: Float): Boolean {
        // 同 tapAt：负坐标必炸进程，clamp + runCatching 双保险
        val dm = resources.displayMetrics
        val cx = x.coerceIn(0f, dm.widthPixels.toFloat())
        val cy = y.coerceIn(0f, dm.heightPixels.toFloat())
        val path = Path().apply {
            moveTo(cx, cy)
            lineTo(cx, cy)
        }
        return runCatching {
            val stroke = GestureDescription.StrokeDescription(path, 0, LONG_PRESS_DURATION_MS)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        }.getOrDefault(false)
    }

    /** 当前「点击目标」坐标：网格模式=网格中心，否则屏幕中心；网格浮层未就绪返回 null */
    private fun currentTapPoint(): Pair<Float, Float>? {
        val region = gridStack.lastOrNull()
        if (region != null) {
            val view = gridOverlayView ?: return null
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val cx = region.centerX() * view.width + loc[0]
            val cy = region.centerY() * view.height + loc[1]
            return cx to cy
        }
        val dm = resources.displayMetrics
        return (dm.widthPixels / 2f) to (dm.heightPixels / 2f)
    }

    /** 网格模式点击后收尾：清空缩放栈、移除网格浮层（非网格模式下是空操作） */
    private fun finishGridTap() {
        if (gridStack.isNotEmpty()) {
            gridStack.clear()
            removeGridOverlay()
        }
    }

    /** 双击：与「轻点」同位置，连续点两次 */
    private fun doDoubleTap() {
        val (x, y) = currentTapPoint() ?: return
        tapAt(x, y)
        mainHandler.postDelayed({ tapAt(x, y) }, DOUBLE_TAP_GAP_MS)
        finishGridTap()
    }

    /** 长按：与「轻点」同位置，按住不动约 0.65 秒 */
    private fun doLongPress() {
        val (x, y) = currentTapPoint() ?: return
        longPressAt(x, y)
        finishGridTap()
    }

    // ===== 编号网格（第①步：元素编号） =====

    /** 遍历无障碍树，收集可点击元素的节点（保留引用以便后续点击） */
    private fun collectClickable(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int = 0
    ) {
        // 与 collectAllNodes 同款防御上限：遇巨型页面（几千节点）绝不无限递归，避免主线程卡死 ANR
        if (depth > MAX_TREE_DEPTH || out.size >= MAX_TREE_NODES) return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (node.isClickable && rect.width() >= dp(24) && rect.height() >= dp(24)) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectClickable(child, out, depth + 1)
        }
    }

    /**
     * 编号收集（v0.37.0，对标苹果 Show numbers）：可点击节点 + 带文字的叶子节点。
     *
     * 背景（2026-09-11 用户报障：微信聊天页不显示编号）：微信聊天消息气泡在无障碍树里
     * 不标记 isClickable，纯 clickable 收集在聊天页结果为空。苹果的做法是给所有可见元素编号，
     * 点击走坐标手势——不依赖节点是否声明可点击。
     *
     * 规则：可点击节点优先；补充「有文字/内容描述且无子节点」的叶子节点，
     * 已被可点击节点边界包含的不重复编（列表行内的文字不另占号，原页面编号不漂移）；
     * 同边界去重（微信常把同一段文字暴露在父子两层）。
     */
    private fun collectLabelNodes(
        root: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        val clickable = mutableListOf<AccessibilityNodeInfo>()
        runCatching { collectClickable(root, clickable) }
        val texts = mutableListOf<AccessibilityNodeInfo>()
        runCatching { collectTextLeaf(root, texts) }
        val clickRects = clickable.map { c ->
            val r = Rect(); c.getBoundsInScreen(r); r
        }
        val seen = mutableSetOf<String>()
        texts.forEach { t ->
            val r = Rect(); t.getBoundsInScreen(r)
            if (r.width() < dp(24) || r.height() < dp(24)) return@forEach
            if (clickRects.any { it.contains(r) }) return@forEach
            val key = "${r.left},${r.top},${r.right},${r.bottom}"
            if (!seen.add(key)) return@forEach
            clickable.add(t)
        }
        out.addAll(clickable)
    }

    /** 收集带文字/内容描述的叶子节点（无子节点层） */
    private fun collectTextLeaf(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int = 0
    ) {
        if (depth > MAX_TREE_DEPTH || out.size >= MAX_TREE_NODES) return
        val hasText = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
        if (hasText && node.childCount == 0) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextLeaf(child, out, depth + 1)
        }
    }

    /** 按屏幕位置（先上到下、再左到右）排序，保证「看到的编号 = 点到的编号」 */
    private fun sortNodes(nodes: MutableList<AccessibilityNodeInfo>) {
        nodes.sortWith { a, b ->
            val ra = Rect(); a.getBoundsInScreen(ra)
            val rb = Rect(); b.getBoundsInScreen(rb)
            when {
                ra.top != rb.top -> ra.top - rb.top
                ra.left != rb.left -> ra.left - rb.left
                else -> 0
            }
        }
    }

    /** 显示编号网格：遍历当前窗口可点击元素，叠加数字标签 + 网格线（不拦截触摸） */
    private fun doShowLabels() {
        labelsVisible = true
        labelRetryCount = 0
        lastLabelSig = null
        mainHandler.post { doShowLabelsNow() }
    }
    /** 实际遍历并绘制编号（供首次显示与实时刷新共用） */
    private fun doShowLabelsNow() {
        if (!labelsVisible) {
            Log.w(TAG, "doShowLabelsNow：labelsVisible=false，跳过绘制")
            return
        }
        val root = rootInActiveWindow ?: run {
            // 多窗口状态下激活窗口可能暂时拿不到：从可检索窗口列表兜底找聚焦的窗口
            val w = runCatching { windows }.getOrNull()
            val fallback = w?.firstOrNull { it.isFocused } ?: w?.firstOrNull()
            val fr = fallback?.root
            if (fr == null) {
                Log.w(TAG, "显示编号失败：拿不到当前窗口，稍后重试")
                retryLabels()
                return
            }
            Log.w(TAG, "rootInActiveWindow 为空，使用窗口列表兜底")
            fr
        }
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        runCatching { collectLabelNodes(root, nodes) }
        if (nodes.isEmpty()) {
            // 关键：空结果绝不保留旧编号——残留的错位编号比没有编号更误导。
            // 页面过渡期树未就绪时限次重试；重试仍为空（真·无可编号页面）就摘掉覆盖层。
            Log.w(TAG, "显示编号：当前页面没有可编号元素，移除旧编号并重试")
            removeLabelsOverlay()
            retryLabels()
            return
        }
        sortNodes(nodes)
        val rects = nodes.map {
            val r = Rect(); it.getBoundsInScreen(r); r
        }
        labelRetryCount = 0
        // 指纹与上次相同（页面稳定）→ 不重画；不同（切页/滚动）→ 重画并安排一次落定复查
        val sig = rects.joinToString(",") { "${it.left},${it.top},${it.right},${it.bottom}" }
        if (sig != lastLabelSig) {
            lastLabelSig = sig
            // 注意：removeLabelsOverlay 会清空快照，labelRects 必须在重画之后回填——
            // 首版写在前面，抖音信息流持续翻腾→持续重画→快照刚存就被清，7 分钟后点击仍失败（真机实锤）
            removeLabelsOverlay()
            val view = LabelsOverlayView(this, rects)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            runCatching { windowManager?.addView(view, params) }
            labelsOverlayView = view
            Log.i(TAG, "编号网格已显示：${rects.size} 个元素")
            mainHandler.removeCallbacks(settleLabelsRunnable)
            mainHandler.postDelayed(settleLabelsRunnable, 600)
        }
        // 快照与屏幕所画严格一致：重画分支或稳定分支都在此回填（落定复查会再刷新）
        labelRects = rects
    }

    /** 落定复查：600ms 后再刷新一次，纠正切换过渡期可能画错的位置 */
    private val settleLabelsRunnable = Runnable { doShowLabelsNow() }

    private fun doHideLabels() {
        labelsVisible = false
        labelRetryCount = 0
        lastLabelSig = null
        mainHandler.removeCallbacks(refreshLabelsRunnable)
        mainHandler.removeCallbacks(settleLabelsRunnable)
        mainHandler.post { removeLabelsOverlay() }
    }

    /** 取第 number 个可编号元素的中心坐标。优先按「显示编号」快照（屏幕画着几号就点几号）；
     *  无快照（编号未显示时的兜底）按原逻辑现遍历。越界/屏幕外返回 null */
    private fun labelCenter(root: AccessibilityNodeInfo, number: Int): Pair<Float, Float>? {
        if (number < 1) return null
        val snap = labelRects
        if (snap.isNotEmpty()) {
            if (number > snap.size) {
                Log.w(TAG, "定位失败：编号 $number 超出快照范围（快照共 ${snap.size} 个）")
                return null
            }
            return onScreenCenter(snap[number - 1], number)
        }
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        runCatching { collectLabelNodes(root, nodes) }
        sortNodes(nodes)
        if (number > nodes.size) {
            Log.w(TAG, "定位失败：编号 $number 超出范围（共 ${nodes.size} 个）")
            return null
        }
        val rect = Rect()
        nodes[number - 1].getBoundsInScreen(rect)
        return onScreenCenter(rect, number)
    }

    /** 中心点必须在屏幕内（防旁页/负一屏负坐标炸手势，2026-09-14 崩溃修复的体检保留）；不在返回 null */
    private fun onScreenCenter(rect: Rect, number: Int): Pair<Float, Float>? {
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        val dm = resources.displayMetrics
        if (cx < 0f || cy < 0f || cx > dm.widthPixels || cy > dm.heightPixels) {
            Log.w(TAG, "定位失败：编号 $number 在屏幕外（bounds=$rect）")
            return null
        }
        return cx to cy
    }

    /** 点击第 number 个可点击元素（优先按「显示编号」快照定位：所见即所点） */
    private fun doTapLabel(number: Int): Boolean {
        if (number < 1) return false
        // 主线程（识别回调本就在主线程）直接执行并如实返回——旧版派发即返回 true，
        // 抖音现场实锤：使用记录出现「没点上却记 ✅ 已执行」的假成功
        if (android.os.Looper.myLooper() == mainLooper) return tapLabelInternal(number)
        mainHandler.post { tapLabelInternal(number) }
        return true   // 非主线程兜底：无法同步取结果（正常调用都走主线程分支）
    }

    private fun tapLabelInternal(number: Int): Boolean {
        // 与编号显示同一套窗口兜底：激活窗口拿不到时从窗口列表找
        var root = rootInActiveWindow
        if (root == null) {
            val w = runCatching { windows }.getOrNull()
            root = w?.firstOrNull { it.isFocused }?.root ?: w?.firstOrNull()?.root
        }
        if (root == null) {
            updateBar("⚠️ 拿不到当前窗口，请重试")
            Log.w(TAG, "点击编号 $number 失败：拿不到当前窗口")
            return false
        }
        val c = labelCenter(root, number)
        if (c == null) {
            // 假成功是欺骗：编号越界必须明确告知，而不是闪一下「已执行」
            updateBar("⚠️ 没有编号 $number（看清屏幕编号范围）")
            Log.w(TAG, "点击编号 $number 失败：越界/无窗口")
            return false
        }
        val ok = tapWithVerify(c.first, c.second, "编号 $number")
        Log.i(TAG, "点击编号 $number @(${c.first.toInt()},${c.second.toInt()}) -> $ok")
        return ok
    }

    /** 长按第 number 个可编号元素（编号模式：显示编号后说「长按 1」） */
    private fun doLongPressLabel(number: Int): Boolean {
        if (number < 1) return false
        mainHandler.post {
            // 与点击同款窗口兜底：激活窗口拿不到时从窗口列表找
            var root = rootInActiveWindow
            if (root == null) {
                val w = runCatching { windows }.getOrNull()
                root = w?.firstOrNull { it.isFocused }?.root ?: w?.firstOrNull()?.root
            }
            if (root == null) {
                updateBar("⚠️ 拿不到当前窗口，请重试")
                Log.w(TAG, "长按编号 $number 失败：拿不到当前窗口")
                return@post
            }
            val c = labelCenter(root, number)
            if (c == null) {
                updateBar("⚠️ 没有编号 $number（看清屏幕编号范围）")
                Log.w(TAG, "长按编号 $number 失败：越界/无窗口")
                return@post
            }
            val ok = longPressAt(c.first, c.second)
            Log.i(TAG, "长按编号 $number @(${c.first.toInt()},${c.second.toInt()}) -> $ok")
        }
        return true
    }

    /** 长按屏幕正中间（长按待命模式下说「中间」时调用） */
    private fun doLongPressCenter(): Boolean {
        val dm = resources.displayMetrics
        return longPressAt(dm.widthPixels / 2f, dm.heightPixels / 2f)
    }

    // ===== 点击闭环校验（A 线）=====
    // 同滚动假成功同源：节点 CLICK 动作返回 true ≠ 界面真有反应。点击后复抓窗口结构指纹，
    // 没变则补发一次真实坐标手势点击（模拟真手指，走完整触摸链）。
    // 指纹 = 窗口内所有可见节点的「类名短名+包名+bounds」抽样拼接（截前 64 个节点，够区分页面态）。

    /** 窗口结构指纹（用于点击效果校验；与滚动指纹不同，这个看全窗口） */
    private fun windowFingerprint(): String? {
        val root = rootInActiveWindow ?: return null
        val all = mutableListOf<AccessibilityNodeInfo>()
        runCatching { collectAllNodes(root, all) }
        val sb = StringBuilder()
        try {
            var count = 0
            for (n in all) {
                if (count >= 64) break
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.isEmpty) continue
                val cls = (n.className?.toString() ?: "").substringAfterLast('.')
                sb.append(cls).append('@').append(r.toString()).append(';')
                count++
            }
        } finally {
            all.forEach { runCatching { it.recycle() } }
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    /**
     * 点击 + 效果观察。v0.23.2：**移除自动补发第二击**。
     * 2026-09-09 抖音分享页实证：选中好友只是打个勾，不改变无障碍树结构——指纹比对误判「没点中」，
     * 补发的第二击把刚选中的又取消了（用户看到「点了自动取消」）；对开关/选择类控件，
     * 双击=打开再关闭，危害远大于偶发漏点。新原则：像人一样点一下就完，
     * 没点中用户看得到、再说一次编号即可。指纹仅记日志供诊断。
     */
    private fun tapWithVerify(x: Float, y: Float, what: String): Boolean {
        val before = windowFingerprint()
        val dispatched = tapAt(x, y)
        if (!dispatched) return false
        SystemClock.sleep(250)
        val after = windowFingerprint()
        if (before != null && after != null && before == after) {
            Log.i(TAG, "点击后界面未见结构变化（可能是选择/开关类控件）：$what，不补发")
        }
        return true
    }

    /** 取文字 target 对应元素的中心坐标（主线程内调用）；找不到返回 null */
    private fun textCenter(target: String): Pair<Float, Float>? {
        if (target.isBlank()) return null
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "文字定位失败：拿不到当前窗口")
            return null
        }
        // 精确匹配优先，再包含匹配（避免「抖音」误点「抖音火山版」）
        val exact = mutableListOf<AccessibilityNodeInfo>()
        val fuzzy = mutableListOf<AccessibilityNodeInfo>()
        runCatching { collectByText(root, target, exact, fuzzy) }
        val candidates = if (exact.isNotEmpty()) exact else fuzzy
        if (candidates.isEmpty()) {
            Log.w(TAG, "文字定位：当前页面没有「$target」")
            return null
        }
        // 优先用可点击节点（自己或祖先）；都没有就用文字节点中心。
        // 2026-09-14：目标在屏幕外（MIUI 桌面旁页/负一屏，getBoundsInScreen 可为负）时中心为负，
        // 交给 tapAt 会炸进程——中心不在屏幕内的候选直接跳过/返回 null（顺带治好「屏幕外假成功记 ✅」）
        val dm = resources.displayMetrics
        fun onScreen(cx: Float, cy: Float) =
            cx >= 0f && cy >= 0f && cx <= dm.widthPixels && cy <= dm.heightPixels
        for (node in candidates) {
            val clickable = findClickableAncestor(node)
            if (clickable != null) {
                val r = Rect(); clickable.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    val cx = r.exactCenterX()
                    val cy = r.exactCenterY()
                    if (onScreen(cx, cy)) return cx to cy
                }
            }
        }
        val r = Rect(); candidates[0].getBoundsInScreen(r)
        val cx = r.exactCenterX()
        val cy = r.exactCenterY()
        return if (onScreen(cx, cy)) cx to cy else null
    }

    /** 根据文字点击：遍历无障碍树找 text/内容描述 匹配的节点，点它（或最近可点击祖先）。返回是否找到并派发。 */
    private fun doTapText(target: String): Boolean {
        val c = textCenter(target) ?: return false
        mainHandler.post {
            val ok = tapWithVerify(c.first, c.second, "文字「$target」")
            Log.i(TAG, "文字点击「$target」@(${c.first.toInt()},${c.second.toInt()}) -> $ok")
        }
        return true
    }

    /** 根据文字长按：遍历无障碍树找 text 匹配节点，长按它（或最近可点击祖先）。返回是否找到并派发。 */
    private fun doLongPressText(target: String): Boolean {
        val c = textCenter(target) ?: return false
        mainHandler.post {
            val ok = longPressAt(c.first, c.second)
            Log.i(TAG, "文字长按「$target」@(${c.first.toInt()},${c.second.toInt()}) -> $ok")
        }
        return true
    }

    /** 找自己或最近的可点击祖先节点 */
    private fun findClickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = node
        while (cur != null) {
            if (cur.isClickable) return cur
            cur = cur.parent
        }
        return null
    }

    /** 遍历收集 text/内容描述 匹配的节点：精确命中进 exact，包含命中进 fuzzy */
    private fun collectByText(
        node: AccessibilityNodeInfo,
        target: String,
        exact: MutableList<AccessibilityNodeInfo>,
        fuzzy: MutableList<AccessibilityNodeInfo>,
        depth: Int = 0
    ) {
        // 同款防御上限：两个结果集合并计数，防巨型树主线程卡死
        if (depth > MAX_TREE_DEPTH || exact.size + fuzzy.size >= MAX_TREE_NODES) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (text.isNotEmpty() || desc.isNotEmpty()) {
            if (text == target || desc == target) exact.add(node)
            else if (text.contains(target) || desc.contains(target)) fuzzy.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectByText(child, target, exact, fuzzy, depth + 1)
        }
    }

    private fun removeLabelsOverlay() {
        labelsOverlayView?.let { runCatching { windowManager?.removeView(it) } }
        labelsOverlayView = null
        labelRects = emptyList()   // 覆盖层摘除，快照同步作废
    }

    // ===== 网格（元素识别不到的兜底，逐级缩小定位） =====

    /** 显示网格：从全屏开始切 GRID_COLS×GRID_ROWS 格（归一化比例，避免状态栏偏移错位） */
    private fun doShowGrid() {
        mainHandler.post {
            gridStack.clear()
            gridStack.add(RectF(0f, 0f, 1f, 1f))
            redrawGrid()
        }
    }

    /** 缩放到第 number 格：把该格放大为新的网格区域（不是点击） */
    private fun doZoomGrid(number: Int): Boolean {
        val region = gridStack.lastOrNull() ?: return false
        val total = GRID_COLS * GRID_ROWS
        if (number < 1 || number > total) {
            Log.w(TAG, "缩放失败：编号 $number 超出范围（1~$total）")
            return false
        }
        if (gridStack.size >= MAX_GRID_LEVEL) {
            Log.w(TAG, "已缩到最小（最多 ${MAX_GRID_LEVEL - 1} 次），无法继续缩小")
            return false
        }
        mainHandler.post {
            val cellW = region.width() / GRID_COLS
            val cellH = region.height() / GRID_ROWS
            val col = (number - 1) % GRID_COLS
            val row = (number - 1) / GRID_COLS
            gridStack.add(
                RectF(
                    region.left + col * cellW,
                    region.top + row * cellH,
                    region.left + (col + 1) * cellW,
                    region.top + (row + 1) * cellH
                )
            )
            redrawGrid()
            Log.i(TAG, "缩放到第 $number 格（第 ${gridStack.size} 层）")
        }
        return true
    }

    /** 点击当前网格区域中心（定位完成后真正点击），点击后清除网格 */
    private fun doTapGridCenter() {
        val region = gridStack.lastOrNull() ?: return
        val view = gridOverlayView ?: return
        mainHandler.post {
            // 归一化比例 × 浮层实际尺寸 + 浮层屏幕偏移 = 精确屏幕坐标
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val cx = region.centerX() * view.width + loc[0]
            val cy = region.centerY() * view.height + loc[1]
            val ok = tapAt(cx, cy)
            Log.i(TAG, "网格点击中心 @(${cx.toInt()},${cy.toInt()}) -> $ok")
            gridStack.clear()
            removeGridOverlay()
        }
    }

    /** 点击第 number 格中心（网格显示时一步式直接点该格，不用先缩到最小），点击后清除网格 */
    private fun doTapGridCell(number: Int): Boolean {
        val region = gridStack.lastOrNull() ?: return false
        val total = GRID_COLS * GRID_ROWS
        if (number < 1 || number > total) {
            Log.w(TAG, "点击失败：编号 $number 超出范围（1~$total）")
            return false
        }
        val view = gridOverlayView ?: return false
        // 坐标在主线程同步计算并记录（供「重复」回放同一点位），点击派发给系统后清除网格
        val cellW = region.width() / GRID_COLS
        val cellH = region.height() / GRID_ROWS
        val col = (number - 1) % GRID_COLS
        val row = (number - 1) / GRID_COLS
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val cx = (region.left + (col + 0.5f) * cellW) * view.width + loc[0]
        val cy = (region.top + (row + 0.5f) * cellH) * view.height + loc[1]
        lastGridTapPoint = cx to cy
        mainHandler.post {
            val ok = tapAt(cx, cy)
            Log.i(TAG, "点击第 $number 格 @(${cx.toInt()},${cy.toInt()}) -> $ok")
            gridStack.clear()
            removeGridOverlay()
        }
        return true
    }

    private fun doHideGrid() {
        gridStack.clear()
        mainHandler.post { removeGridOverlay() }
    }

    /** 撤销上一次缩放：返回上一级网格 */
    private fun doGridBack() {
        if (gridStack.size <= 1) {
            Log.w(TAG, "已是最外层网格，无法返回")
            return
        }
        mainHandler.post {
            gridStack.removeAt(gridStack.lastIndex)
            redrawGrid()
            Log.i(TAG, "返回上一级网格（第 ${gridStack.size} 层）")
        }
    }

    private fun removeGridOverlay() {
        gridOverlayView?.let { runCatching { windowManager?.removeView(it) } }
        gridOverlayView = null
    }

    /** 按当前网格栈顶区域重画网格浮层 */
    private fun redrawGrid() {
        val region = gridStack.lastOrNull() ?: return
        removeGridOverlay()
        val view = GridOverlayView(this, region, GRID_COLS, GRID_ROWS)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        runCatching { windowManager?.addView(view, params) }
        gridOverlayView = view
        Log.i(TAG, "网格已显示：${GRID_COLS}×${GRID_ROWS} = ${GRID_COLS * GRID_ROWS} 格")
    }

    /** 全屏浮层：在当前缩放区域内画网格线 + 每格编号（带半透明遮罩，让线清晰可见） */
    private class GridOverlayView(
        context: Context,
        private val region: RectF,
        private val cols: Int,
        private val rows: Int,
    ) : View(context) {
    private val gridPaint = Paint().apply {
        color = 0xB3FFFFFF.toInt()
        strokeWidth = 2f
    }
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // iOS 语音控制编号徽章：近黑实心圆（与文字标签同一套）
        color = 0xF21C1C1E.toInt()
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(0x14000000) // 极淡遮罩：1:1 贴近 iOS（不压暗内容），仅给细线一点托底
            // 归一化比例 × 浮层实际尺寸，彻底避免状态栏偏移导致的错位
            val w = width.toFloat()
            val h = height.toFloat()
            val left = region.left * w
            val top = region.top * h
            val rw = region.width() * w
            val rh = region.height() * h
            val cellW = rw / cols
            val cellH = rh / rows
            // 网格线（含外边框）
            for (i in 0..cols) canvas.drawLine(left + cellW * i, top, left + cellW * i, top + rh, gridPaint)
            for (j in 0..rows) canvas.drawLine(left, top + cellH * j, left + rw, top + cellH * j, gridPaint)
            val density = resources.displayMetrics.scaledDensity
            // 编号随格子大小自适应：全屏时适中，缩小后随之变小，避免堆叠
            val cellMinDim = minOf(cellW, cellH)
            val radius = (cellMinDim * 0.28f).coerceIn(6f * density, 16f * density)
            textPaint.textSize = (cellMinDim * 0.32f).coerceIn(9f * density, 15f * density)
            val fm = textPaint.fontMetrics
            var n = 1
            for (row in 0 until rows) {
                for (col in 0 until cols) {
                val cx = left + (col + 0.5f) * cellW
                val cy = top + (row + 0.5f) * cellH
                canvas.drawCircle(cx, cy, radius, circlePaint)
                val ty = cy - (fm.ascent + fm.descent) / 2f
                canvas.drawText(n.toString(), cx, ty, textPaint)
                n++
                }
            }
        }
    }

    /** 屏幕内容变化时防抖刷新编号（300ms 合并一次） */
    private val refreshLabelsRunnable = Runnable { doShowLabelsNow() }

    /** 编号刷新重试计数：页面切换过渡期可能拿到错窗口/空树，限次重试防误保留旧编号 */
    private var labelRetryCount = 0

    /** 上一次绘制的编号位置指纹：相同说明页面稳定，无需重画 */
    private var lastLabelSig: String? = null

    /** 上一次绘制的编号指纹（位置签名）：相同则说明页面已稳定，不再重画 */

    private fun retryLabels() {
        if (labelRetryCount >= 3) return
        labelRetryCount++
        mainHandler.removeCallbacks(refreshLabelsRunnable)
        mainHandler.postDelayed(refreshLabelsRunnable, 400L)
    }

    /**
     * 屏幕内容变化时防抖刷新编号（300ms 合并一次）。
     * 注：v0.23.1 曾因「面板被打回」嫌疑加长到 1200ms，后查明真凶是点击校验的自动补发第二击
     * （见 tapWithVerify），编号层不拦截触摸、打不回面板，故还原——保持滚动后编号快速跟新。
     */
    private fun scheduleLabelsRefresh() {
        mainHandler.removeCallbacks(refreshLabelsRunnable)
        mainHandler.postDelayed(refreshLabelsRunnable, 300L)
    }

    /** 全屏透明浮层：只画元素数字编号徽章（v0.25.4 移除旧的淡网格线——那与「显示网格」命令混淆，
     *  无线条）。v0.37.5 徽章按用户拍板恢复 v0.8 经典样式：黑底圆角方块，非圆形 */
    private class LabelsOverlayView(context: Context, items: List<Rect>) : View(context) {
        private val items = items
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // 经典黑底编号徽章：85% 黑，浅色深色页面都压得住
            color = 0xD9000000.toInt()
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // 浮层在屏幕上的实际起点（可能因状态栏产生偏移），据此补偿坐标
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val offX = loc[0]
            val offY = loc[1]
            val density = resources.displayMetrics.scaledDensity
            textPaint.textSize = 11f * density
            val fm = textPaint.fontMetrics
            // 黑底白字、小号圆角正方形标签，统一大小（v0.8 原始几何：20dp 方块 5dp 圆角）
            val size = 20f * density
            val corner = 5f * density
            for ((idx, r) in items.withIndex()) {
                val cx = r.exactCenterX() - offX
                val cy = r.exactCenterY() - offY
                val left = cx - size / 2f
                val top = cy - size / 2f
                canvas.drawRoundRect(left, top, left + size, top + size, corner, corner, bgPaint)
                val ty = cy - (fm.ascent + fm.descent) / 2f
                canvas.drawText((idx + 1).toString(), cx, ty, textPaint)
            }
        }
    }
}
