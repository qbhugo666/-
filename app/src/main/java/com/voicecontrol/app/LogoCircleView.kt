package com.voicecontrol.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * 品牌 Logo 大圆（主页英雄卡/关于页/聆听状态卡）。
 *
 * 唯一标准见 LOGO_STANDARD.md：蓝色正圆（品牌蓝 #246BFE，规范图印制值）+
 * 从设计规范图原样抠取的白色图形资产（ic_logo_glyph_white.png，含圆点+实心箭头）。
 * 图形宽 = 圆直径的 56.7%（规范图深色方块实测 93/164，与印刷值 56% 一致），水平垂直居中。
 *
 * 教训（2026-09-11）：不要用手绘几何翻译 logo——圆点/箭头形态始终画不像（用户两次点名）。
 * 图形一律用设计图抠出的位图资产，改 logo = 重新抠图替换资产，不在代码里画形。
 *
 * 等待水波（v0.55.6）：点「开始控制」后 [startWaitingRipple] 起两圈相位错开的涟漪
 * 从圆边向外荡开渐隐（水纹=正在等待启动）；会话建立后调用方 [stopWaitingRipple] 停。
 * 涟漪画出视图边界需父容器 clipChildren=false（hero_idle 已设）。
 */
class LogoCircleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF246BFE.toInt()
        style = Paint.Style.FILL
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glyph: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.ic_logo_glyph_white)

    // 水波涟漪：品牌蓝描边圆环，跑起来才有非空进度；null=静止（onDraw 不画环）
    private var rippleAnimator: ValueAnimator? = null
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF246BFE.toInt()
        style = Paint.Style.STROKE
    }

    /** 启动等待水波：两圈相位错开的涟漪循环荡开（已在跑则忽略） */
    fun startWaitingRipple() {
        if (rippleAnimator?.isRunning == true) return
        rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600L
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { invalidate() }
            start()
        }
    }

    /** 停水波（会话建立/离开启动链） */
    fun stopWaitingRipple() {
        rippleAnimator?.cancel()
        rippleAnimator = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val t = rippleAnimator?.animatedValue as? Float

        // 水波：两圈相位错开（半周期差），从圆边向外荡、线宽随扩散变细、渐隐。
        // reach=0.4d：最大半径 0.9d 恰在英雄卡内缘消失（圆中心距卡边约 0.9d），不越卡片
        if (t != null) {
            val base = d / 2f
            val reach = d * 0.4f
            for (phase in floatArrayOf(t, (t + 0.5f) % 1f)) {
                val alpha = ((1f - phase) * 130).toInt()
                if (alpha <= 0) continue
                ripplePaint.alpha = alpha
                ripplePaint.strokeWidth = 2f + (1f - phase) * d * 0.045f
                canvas.drawCircle(cx, cy, base + phase * reach, ripplePaint)
            }
        }

        // 品牌蓝正圆（直径=视图较短边）
        canvas.drawCircle(cx, cy, d / 2f, circlePaint)

        // 设计图原样图形：宽 56.7%D，等比缩放，居中
        val gw = d * 0.567f
        val gh = gw * glyph.height / glyph.width
        val rect = RectF(cx - gw / 2f, cy - gh / 2f, cx + gw / 2f, cy + gh / 2f)
        canvas.drawBitmap(glyph, null, rect, glyphPaint)
    }

    override fun onDetachedFromWindow() {
        rippleAnimator?.cancel()
        rippleAnimator = null
        super.onDetachedFromWindow()
    }
}
