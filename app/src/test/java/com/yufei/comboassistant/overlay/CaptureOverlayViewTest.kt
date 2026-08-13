package com.yufei.comboassistant.overlay

import android.view.InputDevice
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CaptureOverlayViewTest {
    @Test
    fun actionUpDeliversAndRemainsArmedForNextGesture() {
        val captured = mutableListOf<CapturedGesture>()
        val view = captureView(onCaptured = captured::add)
        view.setArmed(true)

        view.send(singlePointerEvent(100L, 100L, MotionEvent.ACTION_DOWN, 10f, 20f))
        view.send(singlePointerEvent(100L, 120L, MotionEvent.ACTION_UP, 20f, 40f))
        view.send(singlePointerEvent(200L, 200L, MotionEvent.ACTION_DOWN, 50f, 100f))
        view.send(singlePointerEvent(200L, 230L, MotionEvent.ACTION_UP, 90f, 180f))

        assertEquals(2, captured.size)
        assertEquals(100L, captured[0].downUptimeMs)
        assertEquals(120L, captured[0].upUptimeMs)
        assertEquals(200L, captured[1].downUptimeMs)
        assertEquals(230L, captured[1].upUptimeMs)
        assertEquals(0.1f, captured[0].strokes.single().samples.first().x, 0.001f)
        assertEquals(0.1f, captured[0].strokes.single().samples.first().y, 0.001f)
        assertEquals(0.9f, captured[1].strokes.single().samples.last().x, 0.001f)
        assertEquals(0.9f, captured[1].strokes.single().samples.last().y, 0.001f)
    }

    @Test
    fun multiPointerGestureTracksEachPointersOwnDownAndUpTime() {
        val captured = mutableListOf<CapturedGesture>()
        val view = captureView(onCaptured = captured::add)
        view.setArmed(true)

        view.send(singlePointerEvent(100L, 100L, MotionEvent.ACTION_DOWN, 10f, 20f))
        view.send(
            multiPointerEvent(
                downTimeMs = 100L,
                eventTimeMs = 110L,
                action = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                pointers = listOf(Pointer(0, 15f, 25f), Pointer(7, 70f, 140f)),
            ),
        )
        view.send(
            multiPointerEvent(
                downTimeMs = 100L,
                eventTimeMs = 120L,
                action = MotionEvent.ACTION_MOVE,
                pointers = listOf(Pointer(0, 20f, 30f), Pointer(7, 75f, 145f)),
            ),
        )
        view.send(
            multiPointerEvent(
                downTimeMs = 100L,
                eventTimeMs = 130L,
                action = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                pointers = listOf(Pointer(0, 25f, 35f), Pointer(7, 80f, 150f)),
            ),
        )
        view.send(singlePointerEvent(100L, 140L, MotionEvent.ACTION_UP, 30f, 40f))

        val gesture = captured.single()
        assertEquals(100L, gesture.downUptimeMs)
        assertEquals(140L, gesture.upUptimeMs)
        assertEquals(setOf(0, 7), gesture.strokes.map { it.pointerId }.toSet())
        val primary = gesture.strokes.single { it.pointerId == 0 }
        val secondary = gesture.strokes.single { it.pointerId == 7 }
        assertEquals(0L, primary.startOffsetMs)
        assertEquals(40L, primary.durationMs)
        assertEquals(10L, secondary.startOffsetMs)
        assertEquals(20L, secondary.durationMs)
    }

    @Test
    fun actionCancelDiscardsActivePointersAndReportsReason() {
        val captured = mutableListOf<CapturedGesture>()
        val cancelled = mutableListOf<CaptureCancelReason>()
        val view = captureView(onCaptured = captured::add, onCancelled = cancelled::add)
        view.setArmed(true)

        view.send(singlePointerEvent(100L, 100L, MotionEvent.ACTION_DOWN, 10f, 20f))
        view.send(singlePointerEvent(100L, 110L, MotionEvent.ACTION_CANCEL, 15f, 25f))

        assertTrue(captured.isEmpty())
        assertEquals(listOf(CaptureCancelReason.MOTION_EVENT_CANCELLED), cancelled)

        // ACTION_CANCEL terminates the recording and prevents a race with a new gesture while
        // the service finalizer removes the capture window.
        view.send(singlePointerEvent(200L, 200L, MotionEvent.ACTION_DOWN, 20f, 40f))
        view.send(singlePointerEvent(200L, 220L, MotionEvent.ACTION_UP, 30f, 60f))
        assertTrue(captured.isEmpty())
    }

    @Test
    fun pausingMidGestureDropsItWithoutDelivering() {
        val captured = mutableListOf<CapturedGesture>()
        val view = captureView(onCaptured = captured::add)
        view.setArmed(true)
        view.send(singlePointerEvent(100L, 100L, MotionEvent.ACTION_DOWN, 10f, 20f))

        view.setArmed(false)
        view.send(singlePointerEvent(100L, 120L, MotionEvent.ACTION_UP, 20f, 40f))

        assertTrue(captured.isEmpty())
    }

    private fun captureView(
        onCaptured: (CapturedGesture) -> Unit,
        onCancelled: (CaptureCancelReason) -> Unit = {},
    ): CaptureOverlayView = CaptureOverlayView(
        context = RuntimeEnvironment.getApplication(),
        onCaptured = onCaptured,
        onCancelled = onCancelled,
    ).also { it.layout(0, 0, 100, 200) }

    private fun singlePointerEvent(
        downTimeMs: Long,
        eventTimeMs: Long,
        action: Int,
        x: Float,
        y: Float,
    ): MotionEvent = MotionEvent.obtain(
        downTimeMs,
        eventTimeMs,
        action,
        x,
        y,
        0,
    )

    private data class Pointer(val id: Int, val x: Float, val y: Float)

    private fun multiPointerEvent(
        downTimeMs: Long,
        eventTimeMs: Long,
        action: Int,
        pointers: List<Pointer>,
    ): MotionEvent {
        val properties = pointers.map { pointer ->
            MotionEvent.PointerProperties().apply {
                id = pointer.id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }.toTypedArray()
        val coordinates = pointers.map { pointer ->
            MotionEvent.PointerCoords().apply {
                x = pointer.x
                y = pointer.y
                pressure = 1f
                size = 1f
            }
        }.toTypedArray()
        return MotionEvent.obtain(
            downTimeMs,
            eventTimeMs,
            action,
            pointers.size,
            properties,
            coordinates,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }

    private fun CaptureOverlayView.send(event: MotionEvent) {
        try {
            assertTrue(onTouchEvent(event))
        } finally {
            event.recycle()
        }
    }
}
