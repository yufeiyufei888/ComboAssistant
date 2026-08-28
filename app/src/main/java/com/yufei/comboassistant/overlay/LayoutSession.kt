package com.yufei.comboassistant.overlay

import com.yufei.comboassistant.domain.Combo
import com.yufei.comboassistant.domain.normalized

data class FloatingBallPosition(val x: Float, val y: Float)
data class StopButtonLayout(val x: Float = 0.98f, val y: Float = 0.03f, val sizeDp: Float = 40f)

class LayoutSession(
    combos: List<Combo>,
    ballPosition: FloatingBallPosition,
    stopButtonLayout: StopButtonLayout = StopButtonLayout(),
) {
    private val originals = combos.associateBy(Combo::id)
    private val working = originals.toMutableMap()
    private val originalBall = ballPosition.normalized()
    private var workingBall = originalBall
    private val originalStopButton = stopButtonLayout.normalized()
    private var workingStopButton = originalStopButton

    val comboIds: Set<String> get() = working.keys
    fun combo(id: String): Combo? = working[id]
    fun combos(): List<Combo> = working.values.toList()
    fun ballPosition(): FloatingBallPosition = workingBall
    fun stopButtonLayout(): StopButtonLayout = workingStopButton

    fun moveCombo(id: String, x: Float, y: Float) {
        working[id]?.let { working[id] = it.copy(buttonX = x, buttonY = y).normalized() }
    }

    fun resizeComboKeepingCenter(
        id: String,
        sizeDp: Float,
        displayWidthPx: Int,
        displayHeightPx: Int,
        density: Float,
    ) {
        val current = working[id] ?: return
        val oldHitPx = (maxOf(current.buttonSizeDp, 48f) * density).toInt().coerceAtLeast(1)
        val centerX = current.buttonX * (displayWidthPx - oldHitPx).coerceAtLeast(0) + oldHitPx / 2f
        val centerY = current.buttonY * (displayHeightPx - oldHitPx).coerceAtLeast(0) + oldHitPx / 2f
        val normalizedSize = sizeDp.coerceIn(36f, 96f)
        val newHitPx = (maxOf(normalizedSize, 48f) * density).toInt().coerceAtLeast(1)
        val availableX = (displayWidthPx - newHitPx).coerceAtLeast(1)
        val availableY = (displayHeightPx - newHitPx).coerceAtLeast(1)
        working[id] = current.copy(
            buttonX = ((centerX - newHitPx / 2f) / availableX).coerceIn(0f, 1f),
            buttonY = ((centerY - newHitPx / 2f) / availableY).coerceIn(0f, 1f),
            buttonSizeDp = normalizedSize,
        ).normalized()
    }

    fun setOpacity(id: String, opacity: Float) {
        working[id]?.let { working[id] = it.copy(buttonOpacity = opacity).normalized() }
    }

    fun moveBall(x: Float, y: Float) {
        workingBall = FloatingBallPosition(x, y).normalized()
    }

    fun moveStopButton(x: Float, y: Float) {
        workingStopButton = workingStopButton.copy(x = x, y = y).normalized()
    }

    fun resizeStopButtonKeepingCenter(
        sizeDp: Float,
        displayWidthPx: Int,
        displayHeightPx: Int,
        density: Float,
    ) {
        val oldHitPx = (maxOf(workingStopButton.sizeDp, 48f) * density).toInt().coerceAtLeast(1)
        val centerX = workingStopButton.x * (displayWidthPx - oldHitPx).coerceAtLeast(0) + oldHitPx / 2f
        val centerY = workingStopButton.y * (displayHeightPx - oldHitPx).coerceAtLeast(0) + oldHitPx / 2f
        val normalizedSize = sizeDp.coerceIn(36f, 72f)
        val newHitPx = (maxOf(normalizedSize, 48f) * density).toInt().coerceAtLeast(1)
        val availableX = (displayWidthPx - newHitPx).coerceAtLeast(1)
        val availableY = (displayHeightPx - newHitPx).coerceAtLeast(1)
        workingStopButton = StopButtonLayout(
            x = ((centerX - newHitPx / 2f) / availableX).coerceIn(0f, 1f),
            y = ((centerY - newHitPx / 2f) / availableY).coerceIn(0f, 1f),
            sizeDp = normalizedSize,
        )
    }

    fun committed(now: Long): List<Combo> = working.values.map { it.copy(updatedAt = now).normalized() }
    fun cancelledCombos(): List<Combo> = originals.values.toList()
    fun cancelledBall(): FloatingBallPosition = originalBall
    fun cancelledStopButton(): StopButtonLayout = originalStopButton

    private fun FloatingBallPosition.normalized() = copy(x = x.coerceIn(0f, 1f), y = y.coerceIn(0f, 1f))
    private fun StopButtonLayout.normalized() = copy(
        x = x.coerceIn(0f, 1f),
        y = y.coerceIn(0f, 1f),
        sizeDp = sizeDp.coerceIn(36f, 72f),
    )
}
