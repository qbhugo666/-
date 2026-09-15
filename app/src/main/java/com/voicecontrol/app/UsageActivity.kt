package com.voicecontrol.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 使用记录页（v0.31.0）：显示最近执行的命令（时间+结果文本）。
 * 数据来自 UsageLog（SessionState.lastMatch 中央挂钩），零新依赖，代码动态生成行。
 */
class UsageActivity : ThemedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UsageLog.init(applicationContext)
        setContentView(R.layout.activity_usage)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        findViewById<View>(R.id.btn_clear).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空使用记录")
                .setMessage("确定要清空全部记录吗？")
                .setPositiveButton("清空") { _, _ ->
                    UsageLog.clear()
                    render()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 导出反馈（v0.36.0）：版本/设备/使用记录/应用日志 打包成文本，走系统分享发给开发者
        findViewById<View>(R.id.btn_export).setOnClickListener {
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_SUBJECT, "言出法随 问题反馈")
                putExtra(android.content.Intent.EXTRA_TEXT, buildReport())
            }
            runCatching {
                startActivity(android.content.Intent.createChooser(send, "把反馈信息发送给开发者"))
            }.onFailure {
                Toast.makeText(this, "打不开分享，请稍后再试", Toast.LENGTH_SHORT).show()
            }
        }

        render()
    }

    /** 组装问题反馈文本：设备环境 + 内存画像 + 崩溃记录 + 使用记录 + 应用日志 + 系统错误日志 */
    private fun buildReport(): String {
        val sb = StringBuilder()
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        sb.appendLine("===== 言出法随 · 问题反馈 =====")
        sb.appendLine("导出时间：${df.format(Date())}")
        runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            sb.appendLine("版本：v${info.versionName} (${info.longVersionCode})")
        }
        sb.appendLine("设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        sb.appendLine("系统：Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.VERSION.INCREMENTAL})")
        // 内存画像（v0.43.0）：低配机诊断一线信息
        runCatching { sb.appendLine("内存：${CrashCatcher.memoryLine(this)}") }
        sb.appendLine()
        sb.appendLine("---- 崩溃记录 ----")
        val crashes = CrashCatcher.dumpAll(this)
        sb.appendLine(crashes ?: "（无崩溃记录）")
        sb.appendLine()
        sb.appendLine("---- 使用记录（最近在前）----")
        val all = UsageLog.all()
        if (all.isEmpty()) {
            sb.appendLine("（无记录）")
        } else {
            val f = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
            all.asReversed().forEach { sb.appendLine("${f.format(Date(it.time))}  ${it.text}") }
        }
        sb.appendLine()
        sb.appendLine("---- 诊断事件（关键事件环形缓冲，logcat 被冲掉后的真相来源）----")
        sb.appendLine(DiagnosticsHelper.dumpEvents())
        sb.appendLine()
        sb.appendLine("---- 应用日志（最近 400 行）----")
        runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "400", "-s", "VoiceControl"))
            val out = p.inputStream.bufferedReader().readText()
            sb.append(if (out.isBlank()) "（暂无日志）" else out)
        }.onFailure {
            sb.appendLine("（日志读取失败：${it.message}）")
        }
        sb.appendLine()
        sb.appendLine("---- 系统错误日志（崩溃/错误，最近 200 行）----")
        runCatching {
            // -b crash = Java/native 崩溃专用缓冲；*:E = 全局错误级（含低内存杀进程记录）
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-b", "crash", "-t", "200"))
            val crash = p.inputStream.bufferedReader().readText()
            sb.append(if (crash.isBlank()) "（无）" else crash)
            sb.appendLine()
            val p2 = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "200", "*:E"))
            val errs = p2.inputStream.bufferedReader().readText()
            sb.append(if (errs.isBlank()) "（无错误级日志）" else errs)
        }.onFailure {
            sb.appendLine("（系统日志读取失败：${it.message}）")
        }
        return sb.toString()
    }

    private fun render() {
        val container = findViewById<LinearLayout>(R.id.usage_container)
        container.removeAllViews()
        val dp = { v: Int -> (v * resources.displayMetrics.density + 0.5f).toInt() }
        val entries = UsageLog.all()

        if (entries.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "还没有使用记录\n开个会话说几句话，这里就会显示最近执行的操作"
                textSize = 14f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(dp(4), dp(24), dp(4), 0)
            })
            return
        }

        entries.asReversed().forEach { e ->   // 最新的在最上面
            container.addView(TextView(this).apply {
                text = UsageLog.timeLabel(e.time)
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(dp(4), dp(12), dp(4), 0)
            })
            container.addView(TextView(this).apply {
                text = e.text
                textSize = 15f
                setTextColor(getColor(R.color.text_primary))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(4), dp(1), dp(4), dp(2))
            })
        }
    }
}
