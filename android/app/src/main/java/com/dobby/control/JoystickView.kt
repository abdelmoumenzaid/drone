package com.dobby.control

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/** Joystick tactile à rappel au centre. onMove(x, y) avec x,y dans [-1, 1]. */
class JoystickView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var onMove: ((Float, Float) -> Unit)? = null

    private var cx = 0f; private var cy = 0f
    private var knobX = 0f; private var knobY = 0f
    private var radius = 0f
    private var pointerId = -1

    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1b2430"); style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2a3645"); style = Paint.Style.STROKE; strokeWidth = 3f }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3dd6c4"); style = Paint.Style.FILL }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cx = w / 2f; cy = h / 2f
        knobX = cx; knobY = cy
        radius = min(w, h) / 2f * 0.78f
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(cx, cy, radius, base)
        canvas.drawCircle(cx, cy, radius, ring)
        canvas.drawCircle(knobX, knobY, radius * 0.32f, knob)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerId == -1) { pointerId = event.getPointerId(event.actionIndex); update(event) }
            }
            MotionEvent.ACTION_MOVE -> update(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val idx = event.actionIndex
                if (event.getPointerId(idx) == pointerId) { pointerId = -1; reset() }
            }
        }
        return true
    }

    private fun update(event: MotionEvent) {
        val i = event.findPointerIndex(pointerId)
        if (i < 0) return
        var dx = event.getX(i) - cx
        var dy = event.getY(i) - cy
        val d = hypot(dx, dy)
        if (d > radius) { dx *= radius / d; dy *= radius / d }
        knobX = cx + dx; knobY = cy + dy
        onMove?.invoke(dx / radius, dy / radius)
        invalidate()
    }

    private fun reset() {
        knobX = cx; knobY = cy
        onMove?.invoke(0f, 0f)
        invalidate()
    }
}
