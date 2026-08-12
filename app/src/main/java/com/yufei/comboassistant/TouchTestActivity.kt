package com.yufei.comboassistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yufei.comboassistant.ui.theme.ComboAssistantTheme

class TouchTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ComboAssistantTheme {
                var taps by remember { mutableIntStateOf(0) }
                var completedGestures by remember { mutableIntStateOf(0) }
                var maxPointers by remember { mutableIntStateOf(0) }
                var targetView by remember { mutableStateOf<TouchTargetView?>(null) }
                Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("触控测试场", style = MaterialTheme.typography.headlineSmall)
                    Text("点击：$taps　完成手势：$completedGestures　最大同时触点：$maxPointers")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { targetView?.clear() }) { Text("清空轨迹") }
                        Button(onClick = { finish() }) { Text("返回") }
                    }
                    AndroidView(
                        factory = { context ->
                            TouchTargetView(context) { tap, gesture, pointers ->
                                taps += tap
                                completedGestures += gesture
                                maxPointers = maxOf(maxPointers, pointers)
                            }.also { targetView = it }
                        },
                        modifier = Modifier.fillMaxWidth().height(520.dp),
                    )
                }
            }
        }
    }
}

private class TouchTargetView(
    context: Context,
    private val onStats: (tap: Int, gesture: Int, maxPointers: Int) -> Unit,
) : View(context) {
    private val paths = SparseArray<Path>()
    private val finished = mutableListOf<Path>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(84, 194, 255)
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 255, 255)
        strokeWidth = 2f
    }
    private var downX = 0f
    private var downY = 0f
    private var maxPointersInGesture = 0

    init { setBackgroundColor(Color.rgb(31, 35, 50)) }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        maxPointersInGesture = maxOf(maxPointersInGesture, event.pointerCount)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                paths.clear()
                maxPointersInGesture = 1
                downX = event.x
                downY = event.y
                paths.put(event.getPointerId(0), Path().apply { moveTo(event.x, event.y) })
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                paths.put(event.getPointerId(index), Path().apply { moveTo(event.getX(index), event.getY(index)) })
            }
            MotionEvent.ACTION_MOVE -> for (index in 0 until event.pointerCount) {
                paths[event.getPointerId(index)]?.lineTo(event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_POINTER_UP -> finishPath(event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_UP -> {
                paths[event.getPointerId(event.actionIndex)]?.lineTo(event.x, event.y)
                finishPath(event.getPointerId(event.actionIndex))
                val isTap = kotlin.math.hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) < 24.0
                onStats(if (isTap) 1 else 0, 1, maxPointersInGesture)
            }
            MotionEvent.ACTION_CANCEL -> paths.clear()
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val step = 80f
        var x = step
        while (x < width) { canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint); x += step }
        var y = step
        while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, gridPaint); y += step }
        finished.forEach { canvas.drawPath(it, paint) }
        for (index in 0 until paths.size()) canvas.drawPath(paths.valueAt(index), paint)
    }

    fun clear() {
        finished.clear()
        paths.clear()
        invalidate()
    }

    private fun finishPath(pointerId: Int) {
        paths[pointerId]?.let(finished::add)
        paths.remove(pointerId)
    }
}
