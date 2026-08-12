package com.yufei.comboassistant.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import com.yufei.comboassistant.domain.PointerStroke
import com.yufei.comboassistant.domain.TouchSample

data class CapturedGesture(
    val downElapsedMs: Long,
    val strokes: List<PointerStroke>,
)

class CaptureOverlayView(
    context: Context,
    private val onCaptured: (CapturedGesture) -> Unit,
) : View(context) {
    private data class MutableStroke(
        val pointerId: Int,
        val downTimeMs: Long,
        val samples: MutableList<RawSample> = mutableListOf(),
    )

    private data class RawSample(val eventTimeMs: Long, val x: Float, val y: Float)

    private val active = linkedMapOf<Int, MutableStroke>()
    private val completed = mutableListOf<MutableStroke>()
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 84, 194, 255)
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 92, 122)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 52f
        isFakeBoldText = true
        setShadowLayer(8f, 0f, 2f, Color.BLACK)
    }

    private var armed = false
    private var statusText = "准备录制"
    private var gestureDownMs = 0L

    init {
        setBackgroundColor(Color.argb(24, 0, 0, 0))
    }

    fun showCountdown(value: Int) {
        armed = false
        statusText = value.toString()
        invalidate()
    }

    fun setArmed(value: Boolean) {
        armed = value
        statusText = if (value) "录制中 · 抬手后镜像" else "正在镜像手势…"
        if (!value) clearGesture()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!armed) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearGesture()
                gestureDownMs = event.downTime
                startPointer(event.getPointerId(0), event.eventTime)
                addSamples(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                startPointer(event.getPointerId(event.actionIndex), event.eventTime)
                addSamples(event)
            }
            MotionEvent.ACTION_MOVE -> addSamples(event)
            MotionEvent.ACTION_POINTER_UP -> {
                addSamples(event)
                finishPointer(event.getPointerId(event.actionIndex))
            }
            MotionEvent.ACTION_UP -> {
                addSamples(event)
                finishPointer(event.getPointerId(event.actionIndex))
                deliverGesture()
            }
            MotionEvent.ACTION_CANCEL -> clearGesture()
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        (completed + active.values).forEachIndexed { index, stroke ->
            if (stroke.samples.isEmpty()) return@forEachIndexed
            trailPaint.color = pointerColor(index, 220)
            pointPaint.color = pointerColor(index, 235)
            val path = Path()
            val first = stroke.samples.first()
            path.moveTo(first.x, first.y)
            stroke.samples.drop(1).forEach { path.lineTo(it.x, it.y) }
            canvas.drawPath(path, trailPaint)
            val last = stroke.samples.last()
            canvas.drawCircle(last.x, last.y, 18f, pointPaint)
        }
        canvas.drawText(statusText, width / 2f, height * 0.13f, textPaint)
    }

    private fun startPointer(pointerId: Int, eventTimeMs: Long) {
        active.putIfAbsent(pointerId, MutableStroke(pointerId, eventTimeMs))
    }

    private fun addSamples(event: MotionEvent) {
        for (historyIndex in 0 until event.historySize) {
            val time = event.getHistoricalEventTime(historyIndex)
            for (pointerIndex in 0 until event.pointerCount) {
                active[event.getPointerId(pointerIndex)]?.samples?.add(
                    RawSample(
                        eventTimeMs = time,
                        x = event.getHistoricalX(pointerIndex, historyIndex),
                        y = event.getHistoricalY(pointerIndex, historyIndex),
                    ),
                )
            }
        }
        for (pointerIndex in 0 until event.pointerCount) {
            active[event.getPointerId(pointerIndex)]?.samples?.add(
                RawSample(event.eventTime, event.getX(pointerIndex), event.getY(pointerIndex)),
            )
        }
    }

    private fun finishPointer(pointerId: Int) {
        active.remove(pointerId)?.let(completed::add)
    }

    private fun deliverGesture() {
        if (completed.isEmpty()) return
        val firstDown = completed.minOf { it.downTimeMs }
        val viewWidth = width.coerceAtLeast(1).toFloat()
        val viewHeight = height.coerceAtLeast(1).toFloat()
        val strokes = completed.map { stroke ->
            PointerStroke(
                pointerId = stroke.pointerId,
                startOffsetMs = (stroke.downTimeMs - firstDown).coerceAtLeast(0L),
                durationMs = ((stroke.samples.lastOrNull()?.eventTimeMs ?: stroke.downTimeMs) - stroke.downTimeMs)
                    .coerceAtLeast(1L),
                samples = stroke.samples
                    .distinctBy { Triple(it.eventTimeMs, it.x, it.y) }
                    .map {
                        TouchSample(
                            timeOffsetMs = (it.eventTimeMs - stroke.downTimeMs).coerceAtLeast(0L),
                            x = (it.x / viewWidth).coerceIn(0f, 1f),
                            y = (it.y / viewHeight).coerceIn(0f, 1f),
                        )
                    },
            )
        }
        armed = false
        onCaptured(CapturedGesture(gestureDownMs, strokes))
    }

    private fun clearGesture() {
        active.clear()
        completed.clear()
    }

    private fun pointerColor(index: Int, alpha: Int): Int {
        val colors = intArrayOf(
            Color.rgb(84, 194, 255),
            Color.rgb(255, 92, 122),
            Color.rgb(122, 230, 146),
            Color.rgb(255, 196, 87),
            Color.rgb(188, 129, 255),
        )
        val base = colors[index % colors.size]
        return Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
    }
}
