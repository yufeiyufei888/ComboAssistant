package com.yufei.comboassistant.playback

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import com.yufei.comboassistant.domain.DisplaySnapshot
import com.yufei.comboassistant.domain.GestureSegment
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class AndroidGesturePerformer(
    private val service: AccessibilityService,
    private val cancelPointProvider: () -> PointF?,
) : GesturePerformer {

    override suspend fun perform(
        segment: GestureSegment,
        speed: Float,
        display: DisplaySnapshot,
    ): GestureResult = suspendCancellableCoroutine { continuation ->
        if (segment.strokes.isEmpty() || segment.strokes.size > GestureDescription.getMaxStrokeCount()) {
            continuation.resume(GestureResult.REJECTED)
            return@suspendCancellableCoroutine
        }
        val gesture = runCatching { buildGesture(segment, speed, display) }.getOrNull()
        if (gesture == null) {
            continuation.resume(GestureResult.REJECTED)
            return@suspendCancellableCoroutine
        }
        val resolved = AtomicBoolean(false)
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (resolved.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resume(GestureResult.COMPLETED)
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (resolved.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resume(GestureResult.CANCELLED)
                }
            }
        }
        val accepted = service.dispatchGesture(gesture, callback, null)
        if (!accepted && resolved.compareAndSet(false, true) && continuation.isActive) {
            continuation.resume(GestureResult.REJECTED)
        }
    }

    override fun cancelActive() {
        val point = cancelPointProvider() ?: return
        val path = Path().apply { moveTo(point.x, point.y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 16L))
            .build()
        service.dispatchGesture(gesture, null, null)
    }

    private fun buildGesture(
        segment: GestureSegment,
        speed: Float,
        display: DisplaySnapshot,
    ): GestureDescription {
        val builder = GestureDescription.Builder()
        segment.strokes.forEach { stroke ->
            val first = stroke.samples.first()
            val path = Path().apply {
                moveTo(scaleX(first.x, display.width), scaleY(first.y, display.height))
                stroke.samples.drop(1).forEach { sample ->
                    lineTo(scaleX(sample.x, display.width), scaleY(sample.y, display.height))
                }
            }
            builder.addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    PlaybackEngine.scaleDelay(stroke.startOffsetMs, speed),
                    PlaybackEngine.scaleStrokeDuration(stroke.durationMs, speed),
                ),
            )
        }
        return builder.build()
    }

    private fun scaleX(value: Float, width: Int): Float =
        (value.coerceIn(0f, 1f) * (width - 1).coerceAtLeast(1)).coerceAtLeast(0f)

    private fun scaleY(value: Float, height: Int): Float =
        (value.coerceIn(0f, 1f) * (height - 1).coerceAtLeast(1)).coerceAtLeast(0f)
}
