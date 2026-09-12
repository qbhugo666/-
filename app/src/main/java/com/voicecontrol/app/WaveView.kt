package com.voicecontrol.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * iOS Siri 式实时声波：五段圆角竖条，高度=麦克风实时振幅（VoiceService.liveAmplitude）。
 * 主页状态卡与顶部胶囊共用同一数据源（识别循环每 100ms 回传）。
 * 生命周期：活跃（聆听且无文字）时跟随振幅起伏；失活时平滑衰减到完全消失并停帧（零开销）。
 * 重绘限频 ~30fps。
 */
class WaveView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bars = floatArrayOf(0.55f, 0.85f, 1f, 0.78f, 0.58f)
    private var level = 0f          // 平滑后的振幅 0~1
    private var listening = false
    private var textShown = false   // 胶囊显示文字时隐藏声波（避免与文字挤在一起）

    fun setListening(b: Boolean) {
        listening = b
        if (b) postInvalidateDelayed(33)
    }

    fun setTextShown(b: Boolean) {
        textShown = b
        if (!b) postInvalidateDelayed(33)
    }

    private fun active() = listening && !textShown

    private fun kick() {
        postInvalidateDelayed(33)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // 显示增益 3×：小声说话也有饱满起伏
        val target = if (active()) (VoiceService.liveAmplitude * 3f).coerceIn(0f, 1f) else 0f
        level += (target - level) * if (target > level) 0.5f else 0.2f
        level = level.coerceIn(0f, 1f)

        val n = bars.size
        val slot = w / n
        val barW = slot * 0.52f
        val r = barW / 2f
        val minH = h * 0.16f
        val t = System.currentTimeMillis() / 150f
        for (i in 0 until n) {
            val sway = 0.82f + 0.18f * kotlin.math.sin(t + i * 0.9f)
            val bh = (minH + (h - minH) * level * bars[i]).coerceAtLeast(minH * sway)
            val cx = slot * i + slot / 2f
            canvas.drawRoundRect(
                cx - barW / 2f, (h - bh) / 2f,
                cx + barW / 2f, (h + bh) / 2f,
                r, r, paint
            )
        }
        kick()
    }
}
