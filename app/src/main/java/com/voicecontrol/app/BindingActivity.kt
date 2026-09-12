package com.voicecontrol.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 自定义说法页（v0.39.0）：把用户自己的说法绑定到既有动作，全程语音录入、零打字。
 * 录入 = 正常会话 + 捕获标志（captureArmed）：听到的第一句识别原文自动带回，页面确认后保存。
 * 引擎侧：CustomBindings 存储 → CommandMatcher 外挂别名（保存即生效）+ ASR 热词偏置自动带上。
 */
class BindingActivity : ThemedActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingAction: String? = null

    // 轮询捕获结果（录入会话把原文写进 VoiceService.lastCaptured）
    private val capturePoll = object : Runnable {
        override fun run() {
            VoiceService.lastCaptured?.let { captured ->
                VoiceService.lastCaptured = null
                confirmCaptured(captured)
                return
            }
            handler.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_binding)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_add).setOnClickListener { pickAction() }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
        // 录入会话结束回到本页：若捕获已武装则继续等结果
        if (VoiceService.captureArmed) handler.post(capturePoll)
    }

    override fun onPause() {
        handler.removeCallbacks(capturePoll)
        super.onPause()
    }

    /** 第一步：选目标动作（全部既有动作，按展示名排序） */
    private fun pickAction() {
        val actions = CustomBindings.BINDABLE_ACTIONS
        val labels = actions.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("绑定到哪个动作？")
            .setItems(labels) { _, which ->
                pendingAction = actions[which].first
                startCapture()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 第二步：语音录入——武装捕获标志并启动正常会话（安全链全在），说完第一句自动结束 */
    private fun startCapture() {
        VoiceService.lastCaptured = null
        VoiceService.captureArmed = true
        try {
            val i = Intent(this, VoiceService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(i)
            } else {
                startService(i)
            }
            Toast.makeText(this, "请说出你的专属说法，说完将自动结束会话", Toast.LENGTH_LONG).show()
            handler.post(capturePoll)
        } catch (e: Exception) {
            VoiceService.captureArmed = false
            Toast.makeText(this, "录入启动失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 第三步：确认绑定（原文回显，防听岔；可重录） */
    private fun confirmCaptured(captured: String) {
        val action = pendingAction
        pendingAction = null
        if (action == null) return
        val actionLabel = CustomBindings.ACTION_LABELS[action] ?: action
        AlertDialog.Builder(this)
            .setTitle("确认绑定")
            .setMessage("识别结果：「$captured」\n\n是否将其绑定为「$actionLabel」的触发说法？\n绑定后，说出这句话即执行「$actionLabel」。")
            .setPositiveButton("保存绑定") { _, _ ->
                val err = CustomBindings.upsert(this, captured, action)
                if (err == null) {
                    Toast.makeText(this, "已绑定，立即生效", Toast.LENGTH_SHORT).show()
                    render()
                } else {
                    Toast.makeText(this, "$err，请重新录入", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("重新录入") { _, _ -> startCapture() }
            .setNegativeButton("放弃", null)
            .show()
    }

    private fun render() {
        val container = findViewById<LinearLayout>(R.id.binding_container)
        container.removeAllViews()
        val dp = { v: Int -> (v * resources.displayMetrics.density + 0.5f).toInt() }
        val bindings = CustomBindings.all(this)

        if (bindings.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "暂无自定义说法\n点击右上角「添加」，按提示完成语音录入"
                textSize = 14f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(dp(4), dp(24), dp(4), 0)
            })
            return
        }

        bindings.forEach { b ->
            val label = CustomBindings.ACTION_LABELS[b.action] ?: b.action
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = getDrawable(R.drawable.bg_card)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
                setOnClickListener {
                    AlertDialog.Builder(this@BindingActivity)
                        .setTitle("删除这条绑定？")
                        .setMessage("「${b.phrase}」→ $label\n删除后立即失效")
                        .setPositiveButton("删除") { _, _ ->
                            CustomBindings.remove(this@BindingActivity, b.phrase)
                            render()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                addView(TextView(this@BindingActivity).apply {
                    text = b.phrase
                    textSize = 16f
                    setTextColor(getColor(R.color.text_primary))
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                addView(TextView(this@BindingActivity).apply {
                    text = "执行：$label · 点击删除"
                    textSize = 12f
                    setTextColor(getColor(R.color.text_secondary))
                })
            }
            container.addView(row)
        }
    }
}
