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
    val downUptimeMs: Long,
    val upUptimeMs: Long,
    val strokes: List<PointerStroke>,
) {
    @Deprecated("Use downUptimeMs; MotionEvent timestamps use the uptime clock")
    val downElapsedMs: Long get() = downUptimeMs
}

enum class CaptureCancelReason {
    MOTION_EVENT_CANCELLED,
}

class CaptureOverlayView(
    context: Context,
    private val onCaptured: (CapturedGesture) -> Unit,
    private val onCancelled: (CaptureCancelReason) -> Unit = {},
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
    private var gestureDownUptimeMs = 0L

    init {
        setBackgroundColor(Color.argb(24, 0, 0, 0))
    }

    fun showCountdown(value: Int) {
        armed = false
        clearGesture()
        statusText = value.toString()
        invalidate()
    }

    fun setArmed(value: Boolean) {
        armed = value
        statusText = if (value) "录制中 · 点击结束后保存" else "录制已暂停"
        if (!value) clearGesture()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!armed) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearGesture()
                gestureDownUptimeMs = event.downTime
                startPointer(event.getPointerId(0), event.eventTime)
                addSamples(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                startPointer(event.getPointerId(event.actionIndex), event.eventTime)
                addSamples(event)
            }
            MotionEvent.ACTION_MOVE -> addSamples(event)
            MotionEvent.ACTION_POINTER_UP -> {
                addSamples(event, forceCurrent = true)
                finishPointer(event.getPointerId(event.actionIndex))
            }
            MotionEvent.ACTION_UP -> {
                addSamples(event, forceCurrent = true)
                finishPointer(event.getPointerId(event.actionIndex))
                deliverGesture(event.eventTime)
            }
            MotionEvent.ACTION_CANCEL -> {
                armed = false
                statusText = "触摸流已取消"
                clearGesture()
                onCancelled(CaptureCancelReason.MOTION_EVENT_CANCELLED)
            }
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

    private fun addSamples(event: MotionEvent, forceCurrent: Boolean = false) {
        for (historyIndex in 0 until event.historySize) {
            val time = event.getHistoricalEventTime(historyIndex)
            for (pointerIndex in 0 until event.pointerCount) {
                active[event.getPointerId(pointerIndex)]?.appendSample(
                    RawSample(
                        eventTimeMs = time,
                        x = event.getHistoricalX(pointerIndex, historyIndex),
                        y = event.getHistoricalY(pointerIndex, historyIndex),
                    ),
                )
            }
        }
        for (pointerIndex in 0 until event.pointerCount) {
            active[event.getPointerId(pointerIndex)]?.appendSample(
                sample = RawSample(event.eventTime, event.getX(pointerIndex), event.getY(pointerIndex)),
                force = forceCurrent,
            )
        }
    }

    private fun MutableStroke.appendSample(sample: RawSample, force: Boolean = false) {
        if (!sample.x.isFinite() || !sample.y.isFinite()) return
        if (sample.eventTimeMs < downTimeMs) return
        val previous = samples.lastOrNull()
        if (previous != null && sample.eventTimeMs < previous.eventTimeMs) return
        if (!force && previous != null && sample.eventTimeMs - previous.eventTimeMs < SAMPLE_INTERVAL_MS) {
            return
        }
        if (samples.size >= MAX_SAMPLES_PER_STROKE) {
            if (force) samples[samples.lastIndex] = sample
            return
        }
        if (previous != sample) samples += sample
    }

    private fun finishPointer(pointerId: Int) {
        active.remove(pointerId)?.let(completed::add)
    }

    private fun deliverGesture(upUptimeMs: Long) {
        if (completed.isEmpty()) {
            clearGesture()
            return
        }
        val firstDown = completed.minOf { it.downTimeMs }
        val viewWidth = width.coerceAtLeast(1).toFloat()
        val viewHeight = height.coerceAtLeast(1).toFloat()
        val strokes = completed.mapNotNull { stroke ->
            if (stroke.samples.isEmpty()) return@mapNotNull null
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
        val captured = CapturedGesture(
            downUptimeMs = gestureDownUptimeMs,
            upUptimeMs = upUptimeMs,
            strokes = strokes,
        )
        // Clear before notifying so callback-triggered window changes cannot leave stale pointers.
        clearGesture()
        onCaptured(captured)
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

    private companion object {
        // Bound a 60-second recording while retaining enough resolution for touch playback.
        const val SAMPLE_INTERVAL_MS = 8L
        const val MAX_SAMPLES_PER_STROKE = 7_500
    }
}
