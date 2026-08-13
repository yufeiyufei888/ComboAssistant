package com.yufei.comboassistant.overlay

import com.yufei.comboassistant.domain.Combo
import com.yufei.comboassistant.domain.normalized

data class FloatingBallPosition(val x: Float, val y: Float)

class LayoutSession(
    combos: List<Combo>,
    ballPosition: FloatingBallPosition,
) {
    private val originals = combos.associateBy(Combo::id)
    private val working = originals.toMutableMap()
    private val originalBall = ballPosition.normalized()
    private var workingBall = originalBall

    val comboIds: Set<String> get() = working.keys
    fun combo(id: String): Combo? = working[id]
    fun combos(): List<Combo> = working.values.toList()
    fun ballPosition(): FloatingBallPosition = workingBall

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

    fun committed(now: Long): List<Combo> = working.values.map { it.copy(updatedAt = now).normalized() }
    fun cancelledCombos(): List<Combo> = originals.values.toList()
    fun cancelledBall(): FloatingBallPosition = originalBall

    private fun FloatingBallPosition.normalized() = copy(x = x.coerceIn(0f, 1f), y = y.coerceIn(0f, 1f))
}
