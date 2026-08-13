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
