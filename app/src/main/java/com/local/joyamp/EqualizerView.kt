package com.local.joyamp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Three bouncing bars marking the playing row; frozen while paused. */
class EqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ja_accent)
    }
    private val bar = RectF()
    private var phase = 0f
    private var playing = false
    private var animator: ValueAnimator? = null

    fun setPlaying(value: Boolean) {
        if (playing == value) return
        playing = value
        syncAnimator()
        invalidate()
    }

    private fun syncAnimator() {
        val run = playing && isAttachedToWindow && visibility == VISIBLE
        if (!run) {
            animator?.cancel()
            return
        }
        val a = animator ?: ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
        }.also { animator = it }
        if (!a.isStarted) a.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncAnimator()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncAnimator()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = w * 0.16f
        val barW = (w - gap * 2) / 3
        for (i in 0 until 3) {
            val level = if (playing) {
                0.28f + 0.72f * abs(sin((phase * SPEED[i] + OFFSET[i]) * 2 * PI)).toFloat()
            } else {
                STILL[i]
            }
            val left = i * (barW + gap)
            bar.set(left, h * (1 - level), left + barW, h)
            canvas.drawRoundRect(bar, barW / 2, barW / 2, paint)
        }
    }

    private companion object {
        val SPEED = floatArrayOf(1f, 2f, 1f)
        val OFFSET = floatArrayOf(0f, 0.3f, 0.62f)
        val STILL = floatArrayOf(0.45f, 0.8f, 0.6f)
    }
}
