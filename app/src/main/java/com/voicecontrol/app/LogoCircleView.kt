package com.voicecontrol.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 品牌 Logo 大圆（主页英雄卡/关于页/聆听状态卡）。
 *
 * 唯一标准见 LOGO_STANDARD.md：蓝色正圆（品牌蓝 #246BFE，规范图印制值）+
 * 从设计规范图原样抠取的白色图形资产（ic_logo_glyph_white.png，含圆点+实心箭头）。
 * 图形宽 = 圆直径的 56.7%（规范图深色方块实测 93/164，与印刷值 56% 一致），水平垂直居中。
 *
 * 教训（2026-09-11）：不要用手绘几何翻译 logo——圆点/箭头形态始终画不像（用户两次点名）。
 * 图形一律用设计图抠出的位图资产，改 logo = 重新抠图替换资产，不在代码里画形。
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
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
}
