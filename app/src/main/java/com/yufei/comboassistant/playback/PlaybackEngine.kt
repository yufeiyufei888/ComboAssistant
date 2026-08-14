package com.yufei.comboassistant.playback

import com.yufei.comboassistant.domain.Combo
import com.yufei.comboassistant.domain.DisplaySnapshot
import com.yufei.comboassistant.domain.GestureSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

sealed interface PlaybackState {
    data object Idle : PlaybackState
    data class Running(val comboId: String, val repetition: Int, val total: Int) : PlaybackState
    data class Stopped(val reason: String) : PlaybackState
    data class Failed(val reason: String) : PlaybackState
}

enum class GestureResult { COMPLETED, CANCELLED, REJECTED }

interface GesturePerformer {
    suspend fun perform(segment: GestureSegment, speed: Float, display: DisplaySnapshot): GestureResult
    fun cancelActive(): CancelRequestResult
}

/** Whether Android accepted the best-effort gesture used to cancel the current injection. */
enum class CancelRequestResult { ACCEPTED, UNAVAILABLE, REJECTED }

sealed interface ExecutionGateResult {
    data class Allowed(val display: DisplaySnapshot) : ExecutionGateResult
    data class Blocked(val reason: String) : ExecutionGateResult
}

fun interface ExecutionGate {
    fun check(combo: Combo): ExecutionGateResult
}

class PlaybackEngine(
    private val scope: CoroutineScope,
    private val performer: GesturePerformer,
    private val executionGate: ExecutionGate,
) {
    private val mutableState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    private var playbackJob: Job? = null

    fun play(combo: Combo): Boolean {
        // A cancelled job still owns the performer until its finally block runs. Keeping the
        // identity here prevents stop() followed by an immediate play() from overlapping jobs.
        if (playbackJob != null) return false
        if (combo.timeline.segments.isEmpty()) {
            mutableState.value = PlaybackState.Failed("连招没有可执行动作")
            return false
        }
        val initialGate = executionGate.check(combo)
        if (initialGate is ExecutionGateResult.Blocked) {
            mutableState.value = PlaybackState.Failed(initialGate.reason)
            return false
        }

        val newJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                repeat(combo.repeatCount) { repetitionIndex ->
                    mutableState.value = PlaybackState.Running(
                        comboId = combo.id,
                        repetition = repetitionIndex + 1,
                        total = combo.repeatCount,
                    )
                    combo.timeline.segments.forEach { segment ->
                        delay(scaleDelay(segment.gapBeforeMs, combo.speed))
                        val gate = executionGate.check(combo)
                        if (gate is ExecutionGateResult.Blocked) error(gate.reason)
                        val display = (gate as ExecutionGateResult.Allowed).display
                        when (performer.perform(segment, combo.speed, display)) {
                            GestureResult.COMPLETED -> Unit
                            GestureResult.CANCELLED -> error("手势被系统或用户操作取消")
                            GestureResult.REJECTED -> error("系统拒绝执行手势")
                        }
                    }
                    if (repetitionIndex < combo.repeatCount - 1) delay(combo.repeatIntervalMs)
                }
                mutableState.value = PlaybackState.Idle
            } catch (_: CancellationException) {
                if (mutableState.value !is PlaybackState.Stopped) {
                    mutableState.value = PlaybackState.Stopped("已停止")
                }
            } catch (error: Throwable) {
                mutableState.value = PlaybackState.Failed(error.message ?: "回放失败")
            } finally {
                if (playbackJob === coroutineContext.job) playbackJob = null
            }
        }
        playbackJob = newJob
        // A LAZY coroutine cancelled before its first dispatch never enters the body, so its
        // finally block cannot release the slot. Defer that exceptional cleanup onto the owning
        // scope; until it runs, an immediate replacement is still rejected.
        newJob.invokeOnCompletion {
            scope.launch {
                if (playbackJob === newJob) playbackJob = null
            }
        }
        newJob.start()
        return true
    }

    fun stop(reason: String) {
        val activeJob = playbackJob ?: return
        if (activeJob.isCompleted) return
        val cancellation = performer.cancelActive()
        val reportedReason = if (cancellation == CancelRequestResult.ACCEPTED) {
            reason
        } else {
            "$reason；后续动作已停止，但系统未接受当前手势的取消请求，本段可能继续到结束"
        }
        mutableState.value = PlaybackState.Stopped(reportedReason)
        activeJob.cancel()
    }

    companion object {
        fun scaleDelay(valueMs: Long, speed: Float): Long =
            (valueMs / speed.coerceIn(0.25f, 4f)).toLong().coerceAtLeast(0L)

        fun scaleStrokeDuration(valueMs: Long, speed: Float): Long =
            (valueMs / speed.coerceIn(0.25f, 4f)).toLong().coerceAtLeast(16L)
    }
}
