package com.voicecontrol.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.TextView

/** 会话状态：供前台服务与界面之间共享（同一进程内的简单单例） */
object SessionState {

    /** 会话阶段（主页状态卡四态驱动，v0.31.0） */
    enum class Phase { IDLE, LISTENING, EXECUTING, DONE, FAIL }

    @Volatile var lastText: String = ""

    /** 最近一次执行结果；setter 中央挂钩 → 使用记录（零散布点）+ phase 推进 */
    var lastMatch: String = ""
        set(value) {
            field = value
            if (value.isNotBlank()) {
                UsageLog.append(value)
                phase = if (value.contains("无障碍已关闭") || value.contains("被拒绝")) Phase.FAIL
                        else Phase.DONE
            }
        }

    @Volatile var status: String = "会话进行中…"
    @Volatile var phase: SessionState.Phase = SessionState.Phase.IDLE
}

class MainActivity : ThemedActivity() {

    companion object {
        const val PERMISSION_REQUEST = 100

        // 爱发电主页（用户真实链接，2026-09-09 提供；SettingsActivity 共用）
        const val AFDIAN_URL = "https://afdian.com/a/hugoqb"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingSessionStart = false

    // 从电池优化/自启动授权页返回后，是否要自动续接启动链（推进到下一环）
    private var pendingChainResume = false

    // 主页状态卡视图（onCreate 绑定后使用）；statusLogo 是自绘 LogoCircleView，按 View 持有
    private lateinit var heroIdle: View
    private lateinit var heroStatus: View
    private lateinit var statusLogo: View
    private lateinit var pbExec: View
    private lateinit var statusDone: ImageView
    private lateinit var statusFail: ImageView
    private lateinit var statusTitle: TextView
    private lateinit var statusHint: TextView
    private lateinit var heroLogo: LogoCircleView   // 未启动卡的圆形（v0.55.6 水波涟漪载体）

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UsageLog.init(applicationContext)
        CrashCatcher.register(applicationContext)   // 崩溃记录器（v0.43.0，幂等）
        setContentView(R.layout.activity_main)
        findViewById<TextView>(R.id.tv_version).text =
            "v${packageManager.getPackageInfo(packageName, 0).versionName}"

        // 英雄区双态
        heroIdle = findViewById(R.id.hero_idle)
        heroStatus = findViewById(R.id.hero_status)
        statusLogo = findViewById(R.id.iv_status_logo)
        statusLogo = findViewById(R.id.iv_status_logo)
        pbExec = findViewById(R.id.pb_exec)
        statusDone = findViewById(R.id.iv_status_done)
        statusFail = findViewById(R.id.iv_status_fail)
        statusTitle = findViewById(R.id.tv_status_title)
        statusHint = findViewById(R.id.tv_status_hint)

        // 未启动态：点击开始会话
        heroIdle.setOnClickListener {
            vibrateFeedback()   // v0.55.10：点下即触感确认（跟随设置页「震动反馈」开关，与执行指令震感同源）
            tryStartSession()
        }
        heroLogo = findViewById(R.id.logo_idle)
        // 会话中：结束按钮
        findViewById<View>(R.id.btn_end_session).setOnClickListener {
            val i = Intent(this, VoiceService::class.java)
            i.action = VoiceService.ACTION_STOP
            startService(i)
        }

        findViewById<View>(R.id.row_commands).setOnClickListener { showHelpDialog() }

        findViewById<View>(R.id.row_usage).setOnClickListener {
            startActivity(Intent(this, UsageActivity::class.java))
        }

        findViewById<View>(R.id.tv_about).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        findViewById<View>(R.id.tv_support).setOnClickListener { showSupportDialog() }

        // 设置齿轮 → 设置页
        findViewById<ImageView>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 测试期间保持亮屏，方便观察识别结果
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST)
        } else if (!isBatteryOptimizationIgnored()) {
            // 长期存活链第①环：电池优化豁免（防 MIUI 省电杀进程→无障碍被解绑）
            requestBatteryExemption()
        } else if (!isAutostartGuided()) {
            // 长期存活链第②环：MIUI 自启动授权引导（一次性）
            showAutostartGuide()
        } else if (!AccessibilityHelper.isServiceEnabled(this) &&
            // 已授权 WRITE_SECURE_SETTINGS 时静默自愈（写回开关），不再打扰用户手动开
            !AccessibilityHelper.trySelfHeal(this)
        ) {
            // 自愈不可用（未做过电脑授权）：退回原有手动引导弹窗（商用标准路径）
            showAccessibilityGuide()
        } else {
            // 全部就绪 → 自动开始（纯语音用户零点击依赖，必须保留）
            startSession("冷启动自动（应用启动）")
        }
    }

    // 主页状态卡轮询：500ms 读 SessionState.phase 刷新四态视图（仅前台运行）
    private val statusPoll = object : Runnable {
        override fun run() {
            renderPhase(SessionState.phase)
            handler.postDelayed(this, 500)
        }
    }

    override fun onStart() {
        super.onStart()
        handler.post(statusPoll)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(statusPoll)
    }

    /** 按会话阶段切换英雄区视图（IDLE=开始控制卡；其余四态在状态卡内切换） */
    private fun renderPhase(p: SessionState.Phase) {
        val idle = p == SessionState.Phase.IDLE
        heroIdle.visibility = if (idle) View.VISIBLE else View.GONE
        heroStatus.visibility = if (idle) View.GONE else View.VISIBLE
        if (idle) return
        // 非 IDLE = 会话已建立：涟漪使命完成（水波表示「等待启动」，IDLE 态的启停在 tryStartSession 管）
        heroLogo.stopWaitingRipple()
        statusLogo.visibility = if (p == SessionState.Phase.LISTENING) View.VISIBLE else View.GONE
        pbExec.visibility = if (p == SessionState.Phase.EXECUTING) View.VISIBLE else View.GONE
        statusDone.visibility = if (p == SessionState.Phase.DONE) View.VISIBLE else View.GONE
        statusFail.visibility = if (p == SessionState.Phase.FAIL) View.VISIBLE else View.GONE
        when (p) {
            SessionState.Phase.LISTENING -> {
                statusTitle.text = "正在聆听"
                statusHint.text = "请说出指令"
            }
            SessionState.Phase.EXECUTING -> {
                statusTitle.text = "正在执行"
                statusHint.text = "请稍候"
            }
            SessionState.Phase.DONE -> {
                statusTitle.text = "已完成"
                statusHint.text = SessionState.lastMatch.removePrefix("→ ").trim()
            }
            SessionState.Phase.FAIL -> {
                statusTitle.text = "未执行"
                statusHint.text = SessionState.lastMatch.removePrefix("→ ").trim()
            }
            SessionState.Phase.IDLE -> {}
        }
    }

    /** 触感反馈（v0.55.10）：点「开始控制」时短震一下，跟随设置页「震动反馈」开关（与 VoiceService 执行指令震感同参数） */
    private fun vibrateFeedback() {
        if (!getSharedPreferences("app", MODE_PRIVATE).getBoolean("vibrate_feedback", false)) return
        val vib = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(android.os.VibrationEffect.createOneShot(30, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(30)
        }
    }

    /** 点「开始会话」入口：录音权限 → 无障碍自检 → 静默自愈/弹引导 → 开会话 */
    private fun tryStartSession() {        heroLogo.startWaitingRipple()   // v0.55.6：水波涟漪=启动等待中，会话建立（renderPhase 切卡）即停
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST)
            heroLogo.stopWaitingRipple()
            return
        }
        if (!isBatteryOptimizationIgnored()) {
            requestBatteryExemption()
            heroLogo.stopWaitingRipple()
            return
        }
        if (!isAutostartGuided()) {
            showAutostartGuide()
            heroLogo.stopWaitingRipple()
            return
        }
        if (!AccessibilityHelper.isServiceEnabled(this) &&
            !AccessibilityHelper.trySelfHeal(this)
        ) {
            showAccessibilityGuide()
            heroLogo.stopWaitingRipple()
            return
        }
        startSession("手动点击/授权链续接")
    }

    // ===== 长期存活保障（商用核心诉求：无障碍几天不掉线）=====
    // 第一性原理：无障碍掉线只有两条路——①进程被杀（MIUI 省电/清理）②被系统或用户关闭。
    // ① 用「电池优化白名单 + 自启动授权」根治（一次授权，系统级豁免，几天几周不掉）；
    // ② 无法阻止（系统安全设计），用 v0.9 的检测+引导闭环兜底。
    // 不搞守护进程/双进程保活那套——复杂、耗电、被系统打击，简单的事不复杂化。

    /** 是否已豁免电池优化（Doze/省电策略不杀本进程） */
    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    /** 弹系统标准授权框：一键加入电池优化白名单（国内保活标准做法，一次授权永久生效） */
    private fun requestBatteryExemption() {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_battery)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        dialog.findViewById<View>(R.id.btn_battery_allow).setOnClickListener {
            dialog.dismiss()
            pendingChainResume = true
            runCatching {
                startActivity(Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                ))
            }.onFailure { showBatterySettingsFallback() }
        }
        dialog.findViewById<View>(R.id.btn_battery_skip).setOnClickListener {
            dialog.dismiss()
            android.widget.Toast.makeText(this, "已跳过：无障碍可能被系统自动关闭", android.widget.Toast.LENGTH_LONG).show()
        }

        dialog.show()
        val dm = resources.displayMetrics
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setLayout((dm.widthPixels * 0.82).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** 少数机型没有标准授权弹框：退回到电池优化设置列表页 */
    private fun showBatterySettingsFallback() {
        runCatching {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /** 自启动引导是否已展示过（一次性，不反复烦用户） */
    private fun isAutostartGuided(): Boolean =
        getSharedPreferences("app", MODE_PRIVATE).getBoolean("autostart_guided", false)

    /** MIUI 自启动授权引导：进程被杀后系统能否自动拉起无障碍服务，取决于这个开关 */
    private fun showAutostartGuide() {
        AlertDialog.Builder(this)
            .setTitle("最后一步：允许自启动")
            .setMessage(
                "建议允许「自启动」权限：进程万一被系统清理后，" +
                "只有允许自启动，无障碍服务才能被自动拉起，不用你手动重开。\n\n" +
                "在打开的页面里找到「言出法随」，把开关打开即可" +
                "（若页面中没有该选项，说明你的手机不需要此设置，点「已开过了」继续）。\n\n" +
                "（另外建议：在最近任务页长按本应用卡片 → 加锁，双保险）"
            )
            .setPositiveButton("去开启") { _, _ ->
                markAutostartGuided()
                pendingChainResume = true
                openAutostartSettings()
            }
            .setNegativeButton("已开过了") { _, _ -> markAutostartGuided() }
            .show()
    }

    private fun markAutostartGuided() {
        getSharedPreferences("app", MODE_PRIVATE).edit().putBoolean("autostart_guided", true).apply()
    }

    /** 打开 MIUI 自启动管理页；打不开（非 MIUI 或入口变更）退回应用详情页 */
    private fun openAutostartSettings() {
        val miui = Intent().setClassName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"
        )
        runCatching { startActivity(miui) }.onFailure {
            runCatching {
                startActivity(Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                ))
            }
        }
    }

    /** 商用级引导弹框：讲清「为什么需要 + 怎么开」，一键直达系统无障碍设置 */
    private fun showAccessibilityGuide() {
        AlertDialog.Builder(this)
            .setTitle("开启无障碍服务")
            .setMessage(
                "言出法随需要借助系统「无障碍服务」执行点按、滑动等语音指令。" +
                "该权限仅用于实现语音控制功能，不会收集或上传您的任何信息。\n\n" +
                "点「去开启」直达系统设置；也可在设置中搜索「无障碍」或「辅助功能」，" +
                "在「已下载的应用」中找到言出法随并开启（不同机型路径略有差异）。" +
                "开启后返回即自动继续。\n\n" +
                "建议在最近任务中锁定本应用，以保持后台长期运行。"
            )
            .setPositiveButton("去开启") { _, _ ->
                pendingSessionStart = true
                openAccessibilitySettings()
            }
            .setNegativeButton("暂不", null)
            .show()
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
            // 极少数机型没有标准入口，退回应用详情页（手动找无障碍）
            runCatching {
                startActivity(Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                ))
            }
        }
    }

    /** 从设置页回来：开着了就自动续接（用户不用再点开始）；没开则温和提示 */
    override fun onResume() {
        super.onResume()
        // 从电池优化/自启动授权页返回：重新跑一遍启动链，推进到下一环（已满足的自动跳过）
        if (pendingChainResume) {
            pendingChainResume = false
            tryStartSession()
            return
        }
        if (pendingSessionStart) {
            if (AccessibilityHelper.isServiceEnabled(this)) {
                pendingSessionStart = false
                android.widget.Toast.makeText(this, "无障碍已开启，自动开始会话…", android.widget.Toast.LENGTH_SHORT).show()
                startSession()
            } else {
                android.widget.Toast.makeText(this, "等待无障碍开启——开好后回到这里即可自动继续", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                // 权限刚批下来，继续走长期存活+无障碍自检链路（不是直接开会话）
                tryStartSession()
            } else {
                android.widget.Toast.makeText(this, "录音权限被拒绝，请到系统设置里允许麦克风权限", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startSession(source: String = "手动/未知") {
        // 会话启动来源留痕（2026-09-15 反锁案取证结论）：冷启动自动开始 vs 手动点击 vs 授权续接
        // 分不清时（如家人切回软件触发冷启动自动开会话）事后可从导出的诊断事件段回查
        com.voicecontrol.app.DiagnosticsHelper.log("SESSION_START 来源=$source")
        val i = Intent(this, VoiceService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i)
        } else {
            startService(i)
        }
    }

    // ===== 指令说明（iOS 卡片式弹窗，v0.25 重排）=====

    /** 帮助数据：分区 → (命令, 说明)。正式说法为标准命令，日常口语说法同样可以识别 */
    private val helpSections = listOf(
        "基础操作" to listOf(
            "向上滑动" to "屏幕内容向上滚动，浏览下方内容",
            "向下滑动" to "屏幕内容向下滚动，返回上方内容",
            "向左滑动" to "画面向左切换，查看右侧内容",
            "向右滑动" to "画面向右切换，查看左侧内容",
            "轻点" to "轻点一次屏幕中心位置",
            "双击" to "快速连续轻点两次屏幕中心",
        ),
        "滑动微调" to listOf(
            "向上摇移 / 向下摇移" to "屏幕内容小幅度上下移动，便于对准位置",
            "向左摇移 / 向右摇移" to "屏幕内容小幅度左右移动",
        ),
        "画面缩放" to listOf(
            "双指放大" to "模拟双指张开，放大图片或网页细节",
            "双指缩小" to "模拟双指合拢，将画面恢复原样",
        ),
        "页面导航" to listOf(
            "返回" to "返回上一级页面",
            "前往主屏幕" to "回到手机桌面",
            "打开 App 切换器" to "查看并切换最近运行的应用",
        ),
        "点击屏幕内容" to listOf(
            "点击 抖音" to "点击屏幕上显示「抖音」字样的元素",
            "点击 设置" to "直接说出屏幕上的文字即可点击对应内容",
        ),
        "文字输入" to listOf(
            "输入" to "说出接下来要写的内容，停顿后自动填入输入框",
            "把 A 替换成 B" to "将输入框中的 A 改为 B，如「把不错替换成很好」",
            "光标左移 / 光标右移" to "移动输入框中的光标位置",
            "删除" to "删除光标前的一个字",
            "清空输入框" to "清空输入框中的全部文字",
        ),
        "编号与网格" to listOf(
            "显示编号" to "为可点击元素标注数字，说「点击 5」即可点击对应元素",
            "显示网格" to "将屏幕划分为网格，说「点击 5」点击对应格子",
            "退回" to "网格放大后返回上一级网格",
            "隐藏显示" to "关闭编号或网格显示",
        ),
        "长按操作" to listOf(
            "长按" to "进入长按待命，再说数字或「中间」执行长按",
        ),
        "设备控制" to listOf(
            "增加音量 / 降低音量" to "调节媒体音量（上限 80%，保护听力）",
            "静音" to "关闭媒体声音",
            "锁屏" to "熄灭屏幕并结束当前会话",
            "通知中心 / 控制中心" to "打开对应的系统面板",
        ),
        "结束会话" to listOf(
            "退出" to "结束当前会话，释放麦克风",
        ),
        "使用说明" to listOf(
            "支持日常口语说法" to "如「上滑」「左滑」与正式命令均可识别",
            "同音字自动纠正" to "如说「华动」将自动识别为「滑动」",
            "安静环境识别更准确" to "媒体外放声音可能干扰识别效果",
            "自动休眠与继续" to "每 5 分钟需说「继续」续期（最多 4 次，单次会话最长 25 分钟）；到期前 20 秒预警，预警出现前说「继续」不算数",
        ),
    )

    /** 渲染 iOS 卡片式帮助弹窗：分区灰标题 + 命令(黑 medium)/说明(灰)行 + 胶囊完成 */
    private fun showHelpDialog() {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_help)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        val container = dialog.findViewById<LinearLayout>(R.id.help_container)
        val dp = { v: Int -> (v * resources.displayMetrics.density + 0.5f).toInt() }
        helpSections.forEach { (section, rows) ->
            container.addView(TextView(this).apply {
                text = section
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setPadding(dp(2), dp(14), dp(2), dp(4))
            })
            rows.forEach { (cmd, desc) ->
                container.addView(TextView(this).apply {
                    text = cmd
                    textSize = 15f
                    setTextColor(getColor(R.color.text_primary))
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                    setPadding(dp(2), dp(5), dp(2), 0)
                })
                container.addView(TextView(this).apply {
                    text = desc
                    textSize = 13.5f
                    setTextColor(getColor(R.color.text_secondary))
                    setPadding(dp(14), dp(1), dp(2), dp(2))
                })
            }
        }

        dialog.findViewById<View>(R.id.btn_help_done).setOnClickListener { dialog.dismiss() }

        dialog.show()
        val dm = resources.displayMetrics
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setLayout((dm.widthPixels * 0.88).toInt(), (dm.heightPixels * 0.72).toInt())
    }

    // ===== 支持作者（v0.55.14：爱发电 + 微信 + 支付宝 三通道卡片，主界面零新增元素） =====

    private fun showSupportDialog() {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_support)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.findViewById<View>(R.id.btn_afdian).setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AFDIAN_URL))) }
        }
        dialog.findViewById<View>(R.id.btn_support_done).setOnClickListener { dialog.dismiss() }
        // 长按收款码 → 保存到相册（Pictures/言出法随），供转发给朋友
        dialog.findViewById<ImageView>(R.id.qr_wechat).setOnLongClickListener {
            saveQrToGallery(R.drawable.support_qr_wechat, "言出法随_微信收款码.png"); true
        }
        dialog.findViewById<ImageView>(R.id.qr_alipay).setOnLongClickListener {
            saveQrToGallery(R.drawable.support_qr_alipay, "言出法随_支付宝收款码.png"); true
        }
        dialog.show()
        val dm = resources.displayMetrics
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setLayout((dm.widthPixels * 0.88).toInt(), (dm.heightPixels * 0.82).toInt())
    }

    /** 长按收款码保存到相册（Pictures/言出法随；Android 10+ MediaStore 免存储权限，失败弹提示不静默） */
    private fun saveQrToGallery(resId: Int, name: String) {
        runCatching {
            val bitmap = android.graphics.BitmapFactory.decodeResource(resources, resId)
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/言出法随")
                }
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            android.widget.Toast.makeText(this, "已保存到相册（Pictures/言出法随）", Toast.LENGTH_LONG).show()
        }.onFailure {
            android.widget.Toast.makeText(this, "保存失败：${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
