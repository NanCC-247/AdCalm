package cn.adcalm.guard.ui

import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import kotlin.math.PI
import kotlin.math.sin

/** Lightweight vector hero inspired by the supplied AdCalm reference. */
class CalmShieldView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shield = Path().apply {
        moveTo(180f, 51f)
        cubicTo(165f, 66f, 147f, 72f, 131f, 77f)
        cubicTo(121f, 80f, 124f, 94f, 126f, 117f)
        cubicTo(129f, 148f, 147f, 174f, 180f, 188f)
        cubicTo(213f, 174f, 231f, 148f, 234f, 117f)
        cubicTo(236f, 94f, 239f, 80f, 229f, 77f)
        cubicTo(213f, 72f, 195f, 66f, 180f, 51f)
        close()
    }
    private val orbit = RectF(59f, 80f, 301f, 158f)
    private val star = Path()
    private val haloGradient = RadialGradient(
        180f, 118f, 112f,
        intArrayOf(Color.rgb(225, 249, 242), Color.rgb(237, 249, 245), Color.TRANSPARENT),
        floatArrayOf(0f, .67f, 1f), Shader.TileMode.CLAMP,
    )
    private val activeGradient = LinearGradient(
        133f, 75f, 227f, 185f,
        intArrayOf(Color.rgb(177, 241, 225), Color.rgb(72, 180, 171)),
        null, Shader.TileMode.CLAMP,
    )
    private val observingGradient = LinearGradient(
        133f, 75f, 227f, 185f,
        intArrayOf(Color.rgb(196, 234, 225), Color.rgb(105, 183, 173)),
        null, Shader.TileMode.CLAMP,
    )
    private var phase = 0f
    private var lastFrameTime = 0L
    private var motionAnimator: ValueAnimator? = null
    private var viewLifecycleOwner: LifecycleOwner? = null
    private var motionObserverRegistered = false
    private var systemMotionEnabled = true
    private val refreshMotionPreference = Runnable {
        readMotionPreference()
        updateMotion()
    }
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) = updateMotion()
        override fun onPause(owner: LifecycleOwner) = stopMotion()
    }
    private val motionSettingObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            readMotionPreference()
            updateMotion()
            // WindowManager may update ValueAnimator's scale just after this observer.
            removeCallbacks(refreshMotionPreference)
            postDelayed(refreshMotionPreference, 100L)
        }
    }

    var isObserving: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewLifecycleOwner = findViewTreeLifecycleOwner()
        viewLifecycleOwner?.lifecycle?.addObserver(lifecycleObserver)
        motionObserverRegistered = runCatching {
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false, motionSettingObserver,
            )
            true
        }.getOrDefault(false)
        readMotionPreference()
        updateMotion()
    }

    override fun onDetachedFromWindow() {
        stopMotion()
        removeCallbacks(refreshMotionPreference)
        viewLifecycleOwner?.lifecycle?.removeObserver(lifecycleObserver)
        viewLifecycleOwner = null
        if (motionObserverRegistered) {
            context.contentResolver.unregisterContentObserver(motionSettingObserver)
            motionObserverRegistered = false
        }
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) updateMotion() else stopMotion()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) updateMotion() else stopMotion()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            readMotionPreference()
            updateMotion()
        } else stopMotion()
    }

    private fun readMotionPreference() {
        systemMotionEnabled = ValueAnimator.areAnimatorsEnabled() && runCatching {
            Settings.Global.getFloat(
                context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) > 0f
        }.getOrDefault(true)
    }

    private fun updateMotion() {
        val resumed = viewLifecycleOwner?.lifecycle?.currentState
            ?.isAtLeast(Lifecycle.State.RESUMED) != false
        if (!isAttachedToWindow || !isShown || windowVisibility != VISIBLE ||
            !hasWindowFocus() || !resumed || !systemMotionEnabled
        ) {
            stopMotion()
            return
        }
        if (motionAnimator?.isStarted == true) return
        val animator = motionAnimator ?: ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 8_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (!ValueAnimator.areAnimatorsEnabled()) {
                    stopMotion()
                    return@addUpdateListener
                }
                // The ambient illustration needs only 30 drawn frames per second.
                val now = SystemClock.uptimeMillis()
                if (now - lastFrameTime >= 32L) {
                    phase = it.animatedValue as Float
                    lastFrameTime = now
                    invalidate()
                }
            }
        }.also { motionAnimator = it }
        lastFrameTime = 0L
        animator.start()
    }

    private fun stopMotion() {
        motionAnimator?.cancel()
        if (phase != 0f) {
            phase = 0f
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        setMeasuredDimension(
            resolveSize((360 * density).toInt(), widthMeasureSpec),
            resolveSize((232 * density).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width / 360f, height / 236f)
        if (scale <= 0f) return
        canvas.save()
        canvas.translate((width - 360f * scale) / 2f, (height - 236f * scale) / 2f)
        canvas.scale(scale, scale)
        val cycle = phase * (2.0 * PI)
        val wave = sin(cycle).toFloat()
        val breath = (1f - kotlin.math.cos(cycle).toFloat()) * .5f

        fill(Color.WHITE)
        paint.shader = haloGradient
        paint.alpha = (220 + 35 * breath).toInt()
        canvas.save()
        val haloScale = 1f + .025f * breath
        canvas.scale(haloScale, haloScale, 180f, 118f)
        canvas.drawCircle(180f, 118f, 112f, paint)
        canvas.restore()
        paint.shader = null
        fill(0x55FFFFFF)
        canvas.drawCircle(180f, 118f, 81f, paint)

        canvas.save()
        canvas.translate(0f, -3.6f * wave)
        // A broad translucent orbit gives the shield its quiet, floating shape.
        canvas.save()
        canvas.rotate(30f + 1.6f * wave, 180f, 118f)
        stroke(0x6BBAEDE1, 12f)
        canvas.drawOval(orbit, paint)
        stroke(0xB2FFFFFF.toInt(), 2f)
        canvas.drawArc(orbit, 200f, 148f, false, paint)
        canvas.restore()

        canvas.save()
        canvas.translate(0f, 5f)
        fill(0x0D389F94)
        canvas.drawPath(shield, paint)
        canvas.restore()

        fill(Color.WHITE)
        paint.shader = if (isObserving) observingGradient else activeGradient
        canvas.drawPath(shield, paint)
        paint.shader = null
        stroke(0xFFD9FFF5.toInt(), 1.4f)
        canvas.drawPath(shield, paint)

        stroke(0xFFF1FFFB.toInt(), 10.5f)
        canvas.drawCircle(180f, 116f, 29f, paint)
        canvas.drawLine(159f, 137f, 201f, 95f, paint)

        canvas.save()
        canvas.rotate(30f + 1.6f * wave, 180f, 118f)
        stroke(0xB5DBF8EF.toInt(), 8.5f)
        canvas.drawArc(orbit, 8f, 167f, false, paint)
        stroke(0xD5FFFFFF.toInt(), 1.5f)
        canvas.drawArc(orbit, 8f, 167f, false, paint)
        stroke(0xBFFFFFFF.toInt(), 3f, .55f + .25f * breath)
        canvas.drawArc(orbit, 38f + 58f * breath, 16f, false, paint)
        canvas.restore()
        canvas.restore()

        sparkle(canvas, 263f, 88f - 1.8f * wave, 12f, 0xFF91E0D1.toInt(), .72f + .22f * breath)
        sparkle(canvas, 97f, 175f + 2f * wave, 10f, 0xFF9BE9D8.toInt(), .94f - .22f * breath)
        sparkle(canvas, 130f, 41f, 5f, 0xFFB4EDE0.toInt(), .64f + .28f * breath)
        sparkle(canvas, 280f, 117f + wave, 5f, 0xFFB2EBDD.toInt(), .92f - .28f * breath)
        sparkle(canvas, 246f, 190f - wave, 3f, 0xFFB7E9DE.toInt(), .58f + .18f * breath)
        fill(0xFFA6E7D9.toInt(), .82f)
        canvas.drawCircle(72f, 131f - 2f * wave, 3.8f, paint)
        fill(0xFFCDEFE5.toInt(), .84f - .18f * breath)
        canvas.drawCircle(280f, 72f + wave, 2.1f, paint)
        fill(0xFFBCEADF.toInt(), .52f + .18f * breath)
        canvas.drawCircle(95f, 84f + 1.5f * wave, 1.8f, paint)
        canvas.drawCircle(221f, 203f - wave, 1.5f, paint)
        canvas.restore()
    }

    private fun fill(color: Int, opacity: Float = 1f) {
        paint.shader = null
        paint.color = color
        paint.alpha = (Color.alpha(color) * opacity).toInt()
        paint.style = Paint.Style.FILL
    }

    private fun stroke(color: Int, width: Float, opacity: Float = 1f) {
        paint.shader = null
        paint.color = color
        paint.alpha = (Color.alpha(color) * opacity).toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
    }

    private fun sparkle(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int, opacity: Float) {
        star.reset()
        star.moveTo(x, y - radius)
        star.quadTo(x + radius * .18f, y - radius * .18f, x + radius, y)
        star.quadTo(x + radius * .18f, y + radius * .18f, x, y + radius)
        star.quadTo(x - radius * .18f, y + radius * .18f, x - radius, y)
        star.quadTo(x - radius * .18f, y - radius * .18f, x, y - radius)
        star.close()
        fill(color, opacity)
        canvas.drawPath(star, paint)
    }
}
