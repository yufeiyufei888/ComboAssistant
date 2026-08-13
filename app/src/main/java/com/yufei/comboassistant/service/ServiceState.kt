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
