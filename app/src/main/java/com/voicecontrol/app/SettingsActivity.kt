package com.voicecontrol.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Window
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 设置页（v0.31.0，对标设计稿）：语音 / 反馈(震动) / 服务(无障碍) / 关于。
 * 震动开关默认关（用户拍板），SharedPreferences 持久化，VoiceService 执行命令时读取。
 */
class SettingsActivity : ThemedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<android.view.View>(R.id.btn_back).setOnClickListener { finish() }

        // 深色模式：跟随系统（默认）/ 浅色 / 深色，选中立即整页生效
        val darkValue = findViewById<TextView>(R.id.tv_dark_value)
        val modes = arrayOf("跟随系统", "浅色", "深色")
        fun refreshDarkLabel() {
            val mode = getSharedPreferences("app", MODE_PRIVATE)
                .getInt("dark_mode", ThemedActivity.DARK_FOLLOW_SYSTEM)
            darkValue.text = modes[mode.coerceIn(0, 2)]
        }
        refreshDarkLabel()
        findViewById<android.view.View>(R.id.row_dark_mode).setOnClickListener {
            showDarkModeDialog()
        }

        // 震动反馈开关（默认关）
        val sw = findViewById<Switch>(R.id.sw_vibrate)
        sw.isChecked = getSharedPreferences("app", MODE_PRIVATE).getBoolean("vibrate_feedback", false)
        sw.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences("app", MODE_PRIVATE).edit()
                .putBoolean("vibrate_feedback", checked).apply()
        }

        // 识别灵敏度滑块（v0.38.0，1~10 格，默认 5=作者日常基准；VAD 每会话新建→下次会话生效）
        val sensValue = findViewById<TextView>(R.id.tv_sens_value)
        val seek = findViewById<SeekBar>(R.id.seek_sensitivity)
        fun refreshSensText(level: Int) {
            sensValue.text = if (level == RecognitionSensitivity.DEFAULT_LEVEL) {
                "$level（默认推荐）"
            } else {
                "$level"
            }
        }
        val savedLevel = RecognitionSensitivity.level(this)
        seek.max = RecognitionSensitivity.MAX_LEVEL - RecognitionSensitivity.MIN_LEVEL
        seek.progress = savedLevel - RecognitionSensitivity.MIN_LEVEL
        refreshSensText(savedLevel)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                refreshSensText(progress + RecognitionSensitivity.MIN_LEVEL)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                val level = (sb?.progress ?: 0) + RecognitionSensitivity.MIN_LEVEL
                RecognitionSensitivity.save(this@SettingsActivity, level)
                Toast.makeText(this@SettingsActivity, "灵敏度已设为 $level，下次会话生效", Toast.LENGTH_SHORT).show()
            }
        })

        // 自定义说法（v0.39.0）：语音把自己的说法绑到动作
        findViewById<android.view.View>(R.id.row_custom_say).setOnClickListener {
            startActivity(Intent(this, BindingActivity::class.java))
        }

        // 常用词（v0.42.0）：个人热词，识别优先成这些字
        findViewById<android.view.View>(R.id.row_custom_vocab).setOnClickListener {
            startActivity(Intent(this, VocabActivity::class.java))
        }

        // 微信支持（v0.45.0）：引导开启系统「随选朗读」——微信编号/点击/听写的白名单钥匙
        val stsState = findViewById<TextView>(R.id.tv_sts_state)
        fun refreshSts() {
            val on = stsEnabled()
            stsState.text = if (on) "已就绪" else "未开启 · 点击前往"
            stsState.setTextColor(if (on) 0xFF34C759.toInt() else 0xFFFF9500.toInt())
        }
        refreshSts()
        findViewById<android.view.View>(R.id.row_sts_support).setOnClickListener {
            if (stsEnabled()) {
                Toast.makeText(this, "已就绪：微信里编号、点击、听写均可使用", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("开启微信支持")
                .setMessage(
                    "在微信里使用编号、点击和听写，需要开启系统自带的「随选朗读」。\n\n" +
                    "点「一键前往」后，把页面顶部的开关打开，再返回即可（一次性设置）。"
                )
                .setPositiveButton("一键前往") { _, _ ->
                    val sts = android.content.ComponentName(
                        "com.google.android.marvin.talkback",
                        "com.google.android.accessibility.selecttospeak.SelectToSpeakService"
                    )
                    val opened = runCatching {
                        val deep = Intent().apply {
                            setClassName(
                                "com.android.settings",
                                "com.android.settings.accessibility.Settings\$AccessibilityDetailsActivity"
                            )
                            putExtra(Intent.EXTRA_COMPONENT_NAME, sts)
                        }
                        startActivity(deep)
                        true
                    }.getOrDefault(false)
                    if (!opened) {
                        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        Toast.makeText(this, "请在列表中找到「随选朗读」并开启", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("暂不", null)
                .show()
        }

        // 无障碍服务：状态展示 + 点击跳系统设置
        val state = findViewById<TextView>(R.id.tv_a11y_state)
        fun refreshA11y() {
            val on = AccessibilityHelper.isServiceEnabled(this)
            state.text = if (on) "已开启" else "未开启"
            state.setTextColor(if (on) 0xFF34C759.toInt() else 0xFFFF3B30.toInt())
        }
        refreshA11y()
        findViewById<android.view.View>(R.id.row_accessibility).setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        // 关于 → 关于页（详细介绍，替代原三行弹窗）
        findViewById<android.view.View>(R.id.row_about).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        // 请作者喝杯咖啡 → 爱发电
        findViewById<android.view.View>(R.id.row_coffee).setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MainActivity.AFDIAN_URL)))
            }.onFailure {
                Toast.makeText(this, "打不开浏览器，请稍后再试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 随选朗读是否已启用（微信编号/点击/听写的白名单钥匙） */
    private fun stsEnabled(): Boolean {
        val s = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return s.contains("SelectToSpeakService", ignoreCase = true)
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页返回时刷新无障碍状态
        val state = findViewById<TextView>(R.id.tv_a11y_state)
        val on = AccessibilityHelper.isServiceEnabled(this)
        state.text = if (on) "已开启" else "未开启"
        state.setTextColor(if (on) 0xFF34C759.toInt() else 0xFFFF3B30.toInt())
        // 微信支持状态同步刷新（v0.45.0）
        val sts = findViewById<TextView>(R.id.tv_sts_state)
        val stsOn = stsEnabled()
        sts.text = if (stsOn) "已就绪" else "未开启 · 点击前往"
        sts.setTextColor(if (stsOn) 0xFF34C759.toInt() else 0xFFFF9500.toInt())
    }

    /** iOS 卡片式深色模式选择弹窗：居中标题 + 三行单选（右侧蓝圆勾）+ 完成胶囊；选中即生效 */
    private fun showDarkModeDialog() {
        val modes = arrayOf("跟随系统", "浅色", "深色")
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_dark_mode)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        val radios = listOf(
            dialog.findViewById<ImageView>(R.id.radio_follow),
            dialog.findViewById<ImageView>(R.id.radio_light),
            dialog.findViewById<ImageView>(R.id.radio_dark)
        )
        fun refreshRadios() {
            val current = getSharedPreferences("app", MODE_PRIVATE)
                .getInt("dark_mode", ThemedActivity.DARK_FOLLOW_SYSTEM)
            radios.forEachIndexed { i, iv ->
                iv.setImageResource(if (i == current.coerceIn(0, 2)) R.drawable.ic_radio_on else R.drawable.ic_radio_off)
            }
        }
        refreshRadios()

        val rows = listOf(R.id.row_follow, R.id.row_light, R.id.row_dark)
        rows.forEachIndexed { i, rowId ->
            dialog.findViewById<android.view.View>(rowId).setOnClickListener {
                getSharedPreferences("app", MODE_PRIVATE).edit().putInt("dark_mode", i).apply()
                refreshRadios()
                // 给用户 180ms 看到勾选反馈，再按新主题整页重载
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    dialog.dismiss()
                    recreate()
                }, 180)
            }
        }
        dialog.findViewById<android.view.View>(R.id.btn_dark_done).setOnClickListener { dialog.dismiss() }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        val dm = resources.displayMetrics
        dialog.window?.setLayout((dm.widthPixels * 0.82).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
