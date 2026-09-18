package com.voicecontrol.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.LinearLayout

/**
 * 新拟物卡片：真·高斯模糊双阴影（左上高光 + 右下暗影），表面与页面同色的凸起材料。
 *
 * 为什么不用 XML drawable：shape/layer-list 画不出模糊，只能做硬边偏移渐变的假阴影
 * （v0.56.0 方案，真机被嫌廉价）。setShadowLayer 的高斯模糊只在软件渲染层生效，
 * 因此本视图固定 LAYER_TYPE_SOFTWARE。
 *
 * 阴影必须画在视图边界之内：渲染层（无论硬件还是软件）的画布只有视图自身大小，
 * 出界部分一律被裁——v0.56.3 首版把表面铺满整个视图、阴影画在界外，真机上阴影
 * 被整圈裁掉，卡片与背景融成一片灰（LogoCircleView 涟漪切平的同款坑）。
 * 正确做法：视图四周留 ext 空当（onFinishInflate 追加 padding），表面内缩 ext，
 * 阴影画在空当里，内容由 padding 压到表面上。
 *
 * 性能：模糊在尺寸/按压变化时一次性烘进缓存 Bitmap，onDraw 只做一次贴图；卡片内含
 * 逐帧动画（等待涟漪/进度圈）时每帧成本≈一次 blit，不会反复重算模糊。
 *
 * 按压（可点击卡片）：双阴影翻转方向（高光→右下、暗影→左上），呈现按进表面的凹陷感。
 * 昼夜配色由布局传入 @color/neu_hi / neu_lo / neu_bg，values-night 自动切换。
 */
class NeuCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val radiusPx: Float
    private val blurPx: Float
    private val offsetPx: Float
    private val surfaceColor: Int
    private val lightColor: Int
    private val darkColor: Int

    /** 阴影空当：表面到视图边缘的距离，= 模糊半径 + 偏移（再留 1px 余量） */
    private val insetPx: Float

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blitPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var shadowCache: Bitmap? = null
    private var cacheIsPressed = false
    private var cacheInset = 0f

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.NeuCardView)
        val density = resources.displayMetrics.density
        radiusPx = a.getDimension(R.styleable.NeuCardView_neuCornerRadius, 22 * density)
        blurPx = a.getDimension(R.styleable.NeuCardView_neuBlur, 10 * density)
        val off = a.getDimension(R.styleable.NeuCardView_neuOffset, -1f)
        offsetPx = if (off >= 0f) off else blurPx / 2f
        surfaceColor = a.getColor(R.styleable.NeuCardView_neuSurfaceColor, 0xFFE0E5EC.toInt())
        lightColor = a.getColor(R.styleable.NeuCardView_neuLightColor, 0xFFFFFFFF.toInt())
        darkColor = a.getColor(R.styleable.NeuCardView_neuDarkColor, 0xFFA3B1C6.toInt())
        a.recycle()
        paint.color = surfaceColor
        insetPx = blurPx + offsetPx + density
        // 软件层：setShadowLayer 模糊生效的唯一通道（阴影都在边界内，不怕裁剪）
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setWillNotDraw(false)
    }

    /** XML 声明的 padding 已就位后，四周追加阴影空当（表面 = 内容区） */
    override fun onFinishInflate() {
        super.onFinishInflate()
        val ext = insetPx.toInt()
        setPadding(paddingLeft + ext, paddingTop + ext, paddingRight + ext, paddingBottom + ext)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildCache()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        if (isPressed != cacheIsPressed) {
            cacheIsPressed = isPressed
            rebuildCache()
            invalidate()
        }
    }

    /** 表面内缩 inset，两把模糊（暗影右下→高光左上）+ 不透明表面，一次烘焙；按压时整体翻转方向 */
    private fun rebuildCache() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) {
            shadowCache = null
            return
        }
        cacheInset = insetPx
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val i = insetPx
        val rect = RectF(i, i, w - i, h - i)
        val rr = radiusPx
        val sign = if (cacheIsPressed) -1f else 1f
        // 暗影（右下）：先画；高光覆盖区靠后画自然压过交界
        paint.setShadowLayer(blurPx, sign * offsetPx, sign * offsetPx, darkColor)
        c.drawRoundRect(rect, rr, rr, paint)
        // 高光（左上）
        paint.setShadowLayer(blurPx, -sign * offsetPx, -sign * offsetPx, lightColor)
        c.drawRoundRect(rect, rr, rr, paint)
        // 不透明表面：卡片内部只呈现表面色，阴影只留在四周空当
        paint.clearShadowLayer()
        c.drawRoundRect(rect, rr, rr, paint)
        shadowCache = bmp
    }

    override fun onDraw(canvas: Canvas) {
        shadowCache?.let { canvas.drawBitmap(it, 0f, 0f, blitPaint) }
    }
}
