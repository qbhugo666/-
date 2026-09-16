package com.voicecontrol.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
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
 * 等待水波（v0.55.6 引入，v0.55.9 改挂父卡片 overlay）：
 * 点「开始控制」后 [startWaitingRipple] 起两圈相位错开的涟漪从圆边向外荡开渐隐
 * （水纹=正在等待启动）；会话建立后调用方 [stopWaitingRipple] 停。
 * 2026-09-17 用户实测纠偏：涟漪曾画在本 View 自己的画布上——硬件加速下每个 View 的
 * 绘制天生裁剪在自己的矩形边界内（clipChildren=false 只放行父级裁剪，管不到这一层），
 * 涟漪四边中点被切平（用户截图「上面一段被切断」，真机逐像素取证实锤）。
 * 改为画到直接父容器（hero_idle 卡片，尺寸足够容纳全环）的 ViewOverlay 上：
 * overlay 由父容器整体绘制，无小方框裁剪，布局零改动。
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

    // 水波涟漪：Drawable 挂在父容器 overlay 上（null=静止）；animator 只负责推进相位并驱动重绘
    private var rippleDrawable: WaitingRippleDrawable? = null
    private var rippleAnimator: ValueAnimator? = null

    /** 启动等待水波：两圈相位错开的涟漪循环荡开（已在跑则忽略） */
    fun startWaitingRipple() {
        if (rippleAnimator?.isRunning == true) return
        val parent = parent as? ViewGroup ?: return
        val host = WaitingRippleDrawable(this, parent)
        rippleDrawable = host
        parent.overlay.add(host)
        rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600L
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                host.phase = it.animatedValue as Float
                // 直接重绘父卡片：overlay drawable 的 invalidateSelf 回调链在部分
                // 机型/路径上不触发宿主重绘（2026-09-17 真机探针实锤 draw 零调用）
                parent.invalidate()
            }
            start()
        }    }

    /** 停水波（会话建立/离开启动链） */
    fun stopWaitingRipple() {
        rippleAnimator?.cancel()
        rippleAnimator = null
        (parent as? ViewGroup)?.overlay?.let { overlay ->
            rippleDrawable?.let { overlay.remove(it) }
        }
        rippleDrawable = null
    }

    override fun onDraw(canvas: Canvas) {
        val d = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f

        // 品牌蓝正圆（直径=视图较短边）
        canvas.drawCircle(cx, cy, d / 2f, circlePaint)

        // 设计图原样图形：宽 56.7%D，等比缩放，居中
        val gw = d * 0.567f
        val gh = gw * glyph.height / glyph.width
        val rect = RectF(cx - gw / 2f, cy - gh / 2f, cx + gw / 2f, cy + gh / 2f)
        canvas.drawBitmap(glyph, null, rect, glyphPaint)
    }

    override fun onDetachedFromWindow() {
        stopWaitingRipple()
        super.onDetachedFromWindow()
    }
}

/**
 * 等待水波画笔：画在父卡片 overlay 上，坐标 = logo 圆心在父容器里的位置。
 * 2026-09-17 拆出本类：View 自绘被裁剪在自己边界内，父卡片尺寸足够容纳全环。
 */
private class WaitingRippleDrawable(
    private val logo: LogoCircleView,
    private val host: ViewGroup
) : Drawable() {

    var phase: Float = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF246BFE.toInt()
        style = Paint.Style.STROKE
    }
    private val boundsScratch = Rect()

    /** logo 圆心（父容器坐标）与直径。每次绘制现算，宿主布局变化自动跟随 */
    private fun geometry(): Triple<Float, Float, Float> {
        val d = logo.width.toFloat()
        return Triple(logo.x + d / 2f, logo.y + logo.height / 2f, d)
    }

    override fun draw(canvas: Canvas) {
        // overlay 要求 Drawable 有合法 bounds 才会触发宿主重绘；跟随父卡片尺寸
        if (bounds.width() != host.width || bounds.height() != host.height) {
            boundsScratch.set(0, 0, host.width, host.height)
            bounds = boundsScratch
        }
        val (cx, cy, d) = geometry()
        val base = d / 2f
        val reach = d * 0.4f
        // 水波：两圈相位错开（半周期差），从圆边向外荡、线宽随扩散变细、渐隐。
        // reach=0.4d：最大半径 0.9d 恰在英雄卡内缘消失（圆中心距卡边约 0.9d），不越卡片
        for (p in floatArrayOf(phase, (phase + 0.5f) % 1f)) {
            val alpha = ((1f - p) * 130).toInt()
            if (alpha <= 0) continue
            paint.alpha = alpha
            paint.strokeWidth = 2f + (1f - p) * d * 0.045f
            canvas.drawCircle(cx, cy, base + p * reach, paint)
        }
    }

    override fun setAlpha(alpha: Int) {}   // 透明度由涟漪自身相位控制，外部 alpha 不支持
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
