package com.yufei.comboassistant.service

import com.yufei.comboassistant.domain.DisplaySnapshot
import com.yufei.comboassistant.foreground.ForegroundSessionState

sealed interface DisplayState {
    data class Stable(val snapshot: DisplaySnapshot) : DisplayState
    data class Unstable(val previous: DisplaySnapshot?) : DisplayState
}

enum class OverlayMode { LOCKED, LAYOUT }

sealed interface RecordingState {
    data object Idle : RecordingState
    data class Countdown(val sessionId: String, val remainingSeconds: Int) : RecordingState
    data class Capturing(
        val sessionId: String,
        val startedAtUptimeMs: Long,
        val segmentCount: Int,
    ) : RecordingState
    data class Finalizing(val sessionId: String) : RecordingState
    data class Failed(val reason: String) : RecordingState
}

internal sealed interface BallDragResult {
    data object None : BallDragResult
    data object Click : BallDragResult
    data class Position(val x: Int, val y: Int) : BallDragResult
}

/** Pure gesture bookkeeping shared by locked and layout-mode floating-ball dragging. */
internal class BallDragState(private val touchSlopPx: Int) {
    private var active = false
    private var moved = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var originX = 0
    private var originY = 0

    fun begin(rawX: Float, rawY: Float, x: Int, y: Int) {
        active = true
        moved = false
        downRawX = rawX
        downRawY = rawY
        originX = x
        originY = y
    }

    fun move(rawX: Float, rawY: Float, maxX: Int, maxY: Int): BallDragResult {
        if (!active) return BallDragResult.None
        val dx = rawX - downRawX
        val dy = rawY - downRawY
        if (kotlin.math.abs(dx) > touchSlopPx || kotlin.math.abs(dy) > touchSlopPx) moved = true
        if (!moved) return BallDragResult.None
        return BallDragResult.Position(
            x = (originX + dx.toInt()).coerceIn(0, maxX.coerceAtLeast(0)),
            y = (originY + dy.toInt()).coerceIn(0, maxY.coerceAtLeast(0)),
        )
    }

    fun finish(currentX: Int, currentY: Int): BallDragResult {
        if (!active) return BallDragResult.None
        active = false
        return if (moved) BallDragResult.Position(currentX, currentY) else BallDragResult.Click
    }

    fun cancel(): BallDragResult {
        if (!active) return BallDragResult.None
        active = false
        return BallDragResult.Position(originX, originY)
    }
}

internal fun panelHeightPx(
    displayHeightPx: Int,
    desiredHeightPx: Int,
    landscape: Boolean,
): Int {
    val safeDisplay = displayHeightPx.coerceAtLeast(1)
    val maxHeight = if (landscape) safeDisplay / 2 else (safeDisplay * 3) / 4
    return desiredHeightPx.coerceIn(1, maxHeight.coerceAtLeast(1))
}

/** Logical user intent is authoritative; a stale/pending physical view never reopens a panel. */
internal fun shouldRenderPanel(requestedOpen: Boolean, attached: Boolean): Boolean =
    requestedOpen && !attached

/** Layout selection may redraw an open panel, but never changes an explicit closed intent. */
internal fun shouldRedrawLayoutPanel(requestedOpen: Boolean, selectionChanged: Boolean): Boolean =
    requestedOpen && selectionChanged

/** Keeps a layout save single-flight and rejects stale completion callbacks. */
internal class LayoutCommitGuard {
    private var activeSessionId: String? = null
    private var saving = false

    fun begin(sessionId: String) {
        activeSessionId = sessionId
        saving = false
    }

    fun tryStart(sessionId: String): Boolean {
        if (activeSessionId != sessionId || saving) return false
        saving = true
        return true
    }

    fun isSaving(sessionId: String?): Boolean =
        sessionId != null && activeSessionId == sessionId && saving

    fun fail(sessionId: String): Boolean {
        if (activeSessionId != sessionId || !saving) return false
        saving = false
        return true
    }

    fun complete(sessionId: String): Boolean {
        if (activeSessionId != sessionId || !saving) return false
        activeSessionId = null
        saving = false
        return true
    }

    /** User cancellation is blocked during a save; lifecycle cancellation invalidates it. */
    fun cancel(force: Boolean): Boolean {
        if (saving && !force) return false
        activeSessionId = null
        saving = false
        return true
    }
}

internal data class OwnPackageEventDecision(
    val shouldProcess: Boolean,
    val debugTouchTestVisible: Boolean,
)

/** Filters events produced by this service's own overlays from real Activity foreground changes. */
internal fun ownPackageEventDecision(
    isWindowStateChanged: Boolean,
    className: String?,
    mainActivityClassName: String,
    touchTestActivityClassName: String,
    debugBuild: Boolean,
    wasDebugTouchTestVisible: Boolean,
): OwnPackageEventDecision {
    if (!isWindowStateChanged) {
        return OwnPackageEventDecision(false, wasDebugTouchTestVisible)
    }
    return when (className) {
        touchTestActivityClassName -> OwnPackageEventDecision(debugBuild, debugBuild)
        mainActivityClassName -> OwnPackageEventDecision(true, false)
        else -> OwnPackageEventDecision(wasDebugTouchTestVisible, wasDebugTouchTestVisible)
    }
}

internal fun isRealForegroundSwitch(
    previous: ForegroundSessionState,
    current: ForegroundSessionState,
    guardedTargets: Collection<String>,
): Boolean = when (current) {
    is ForegroundSessionState.Candidate ->
        current.previousConfirmation?.packageName?.let { it != current.packageName } == true ||
            guardedTargets.any { it != current.packageName }
    is ForegroundSessionState.Confirmed ->
        previous.confirmedPackageName?.let { it != current.confirmation.packageName } == true ||
            guardedTargets.any { it != current.confirmation.packageName }
    is ForegroundSessionState.OwnApp,
    is ForegroundSessionState.Unknown,
    -> true
    is ForegroundSessionState.TemporarilyObscured -> false
}
