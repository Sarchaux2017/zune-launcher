package com.example.zunelauncher

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.PowerManager
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Choreographer
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.annotation.ColorInt
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Pure-black (#000000) canvas animation for OLED panels.
 *
 *  - three flowing sine "ribbons" drawn as layered strokes (cheap glow)
 *  - drifting typographic particles on three depth layers, glowing via a text shadow
 *  - [setGlowColor] cross-fades every glowing element to a new colour (dominant app colour)
 *  - [setScrollProgress] adds a parallax/flow response to the pivot swipe
 *
 * Battery: capped at ~30 fps (15 fps in battery-saver), stops completely when the window
 * is hidden (e.g. while another app is in the foreground), and only a few pixels are lit.
 */
class ZuneOledWallpaperView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private class Glyph(
        var x: Float, var y: Float,
        val sizePx: Float, val speedPx: Float,
        val swayPx: Float, val swayFreq: Float, val phase: Float,
        val alpha: Float, val text: String, val layer: Int
    )

    private class Wave(
        val yFrac: Float, val ampDp: Float, val wavelengthDp: Float,
        val speed: Float, val phase: Float, val strength: Float
    )

    private val density = resources.displayMetrics.density
    private val random = Random(7)

    private val glyphs = ArrayList<Glyph>()
    private val waves = listOf(
        Wave(0.20f, 36f, 380f, 0.55f, 0.0f, 0.80f),
        Wave(0.57f, 52f, 540f, -0.40f, 2.0f, 1.00f),
        Wave(0.86f, 30f, 310f, 0.70f, 4.0f, 0.70f)
    )
    private val words = listOf(
        "Z", "U", "N", "E", "0", "1", "2", "3", "5", "8", "+", "/", "<", ">", "#", "%", "@",
        "play", "apps", "now", "pivot"
    )

    // per-layer tuning: far / mid / near
    private val layerTextSp = arrayOf(floatArrayOf(11f, 17f), floatArrayOf(20f, 34f), floatArrayOf(60f, 100f))
    private val layerAlpha = floatArrayOf(0.34f, 0.26f, 0.09f)
    private val layerGlowDp = floatArrayOf(5f, 9f, 18f)
    private val layerSpeedDp = arrayOf(floatArrayOf(5f, 9f), floatArrayOf(10f, 18f), floatArrayOf(18f, 28f))
    private val layerParallax = floatArrayOf(0.05f, 0.12f, 0.26f)
    private val layerCount = intArrayOf(16, 13, 5)

    private val wavePath = Path()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
    }

    @ColorInt private var glowColor = DEFAULT_GLOW
    @ColorInt private var targetColor = DEFAULT_GLOW
    private var colorAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null
    private var intensity = 1f

    private var scrollProgress = 0f
    private var smoothScroll = 0f
    private var timeSec = 0f

    private var running = false
    private var lastFrameNanos = 0L
    private var frameIntervalNs = FRAME_INTERVAL_NS

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.BLACK)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    // ------------------------------------------------------------------ public API

    /** Cross-fade the glow of every element to [color]. Cheap to call repeatedly. */
    fun setGlowColor(@ColorInt color: Int, animate: Boolean = true) {
        if (color == targetColor) return
        targetColor = color
        colorAnimator?.cancel()
        if (!animate || !isAttachedToWindow) {
            glowColor = color
            invalidate()
            return
        }
        colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), glowColor, color).apply {
            duration = 650L
            interpolator = DecelerateInterpolator()
            addUpdateListener { glowColor = it.animatedValue as Int }
            start()
        }
    }

    /** 0f..1f pivot position; makes ribbons flow and glyphs parallax while swiping. */
    fun setScrollProgress(progress: Float) {
        scrollProgress = progress.coerceIn(0f, 1f)
    }

    /** Short brightness swell, used as feedback when an app is launched. */
    fun pulse() {
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(2.0f, 1f).apply {
            duration = 700L
            interpolator = DecelerateInterpolator()
            addUpdateListener { intensity = it.animatedValue as Float }
            start()
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateRunState()
    }

    override fun onDetachedFromWindow() {
        stop()
        colorAnimator?.cancel()
        pulseAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateRunState()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateRunState()
    }

    private fun updateRunState() {
        if (!isAttachedToWindow) return // also guards calls made during View construction
        if (windowVisibility == VISIBLE && visibility == VISIBLE) start() else stop()
    }

    private fun start() {
        if (running) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        frameIntervalNs = if (pm.isPowerSaveMode) FRAME_INTERVAL_NS * 2 else FRAME_INTERVAL_NS
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastFrameNanos == 0L) lastFrameNanos = frameTimeNanos
            val elapsed = frameTimeNanos - lastFrameNanos
            // 2 ms of slack so a 90 Hz display (11.1 ms) still lands on 3 frames = 33 ms
            if (elapsed >= frameIntervalNs - 2_000_000L) {
                lastFrameNanos = frameTimeNanos
                step(min(elapsed / 1_000_000_000f, 0.1f))
                invalidate()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ------------------------------------------------------------------ simulation

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) seedGlyphs(w, h)
    }

    private fun seedGlyphs(w: Int, h: Int) {
        glyphs.clear()
        for (layer in 0..2) repeat(layerCount[layer]) {
            val sp = layerTextSp[layer]
            val spd = layerSpeedDp[layer]
            glyphs += Glyph(
                x = random.nextFloat() * w,
                y = random.nextFloat() * h,
                sizePx = sp(sp[0] + random.nextFloat() * (sp[1] - sp[0])),
                speedPx = (spd[0] + random.nextFloat() * (spd[1] - spd[0])) * density,
                swayPx = (6f + random.nextFloat() * 18f) * density,
                swayFreq = 0.2f + random.nextFloat() * 0.5f,
                phase = random.nextFloat() * (2f * PI.toFloat()),
                alpha = layerAlpha[layer] * (0.6f + 0.4f * random.nextFloat()),
                text = words[random.nextInt(words.size)],
                layer = layer
            )
        }
    }

    private fun step(dt: Float) {
        timeSec += dt
        smoothScroll += (scrollProgress - smoothScroll) * (1f - exp(-dt * 6f))
        val w = width.toFloat()
        val h = height.toFloat()
        for (g in glyphs) {
            g.y -= g.speedPx * dt
            if (g.y < -g.sizePx * 1.5f) {
                g.y = h + g.sizePx
                g.x = random.nextFloat() * w
            }
        }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK) // #000000: unlit OLED pixels draw no power
        drawWaves(canvas)
        drawGlyphs(canvas)
    }

    private fun drawWaves(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val stepPx = 8f * density
        val widths = floatArrayOf(16f * density, 7f * density, 1.4f * density)
        val alphas = floatArrayOf(0.05f, 0.12f, 0.70f)

        waves.forEachIndexed { i, wave ->
            val k = (2.0 * PI / (wave.wavelengthDp * density)).toFloat()
            val amp = wave.ampDp * density
            val ph = wave.phase + timeSec * wave.speed + smoothScroll * (3f + i)
            val baseY = h * wave.yFrac + (smoothScroll - 0.5f) * h * 0.05f * (i - 1)

            wavePath.rewind()
            var x = -stepPx
            var first = true
            while (x <= w + stepPx) {
                val y = baseY + amp * sin(k * x + ph) + amp * 0.45f * sin(k * x * 2.1f - ph * 0.7f)
                if (first) { wavePath.moveTo(x, y); first = false } else wavePath.lineTo(x, y)
                x += stepPx
            }
            for (pass in 0..2) {
                linePaint.strokeWidth = widths[pass]
                linePaint.color = withAlpha(glowColor, alphas[pass] * wave.strength * intensity)
                canvas.drawPath(wavePath, linePaint)
            }
        }
    }

    private fun drawGlyphs(canvas: Canvas) {
        val w = width.toFloat()
        val margin = 100f * density
        val span = w + 2f * margin
        var currentLayer = -1

        for (g in glyphs) {
            if (g.layer != currentLayer) { // shadow layer is identical per depth layer: set once
                currentLayer = g.layer
                glyphPaint.setShadowLayer(layerGlowDp[currentLayer] * density, 0f, 0f, withAlpha(glowColor, min(1f, 0.9f * intensity)))
            }
            val sway = sin(timeSec * g.swayFreq + g.phase) * g.swayPx
            val raw = g.x + sway - smoothScroll * layerParallax[g.layer] * w
            val dx = (((raw + margin) % span) + span) % span - margin

            glyphPaint.textSize = g.sizePx
            glyphPaint.color = withAlpha(glowColor, g.alpha * intensity)
            canvas.drawText(g.text, dx, g.y, glyphPaint)
        }
        glyphPaint.clearShadowLayer()
    }

    // ------------------------------------------------------------------ helpers

    private fun sp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun withAlpha(@ColorInt color: Int, a: Float): Int =
        (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255f).toInt() shl 24)

    companion object {
        val DEFAULT_GLOW: Int = 0xFFFF2E93.toInt()
        private const val FRAME_INTERVAL_NS = 33_333_333L // ~30 fps
    }
}
