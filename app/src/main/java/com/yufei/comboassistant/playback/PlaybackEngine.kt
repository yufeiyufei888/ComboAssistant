package com.yufei.comboassistant.playback

import com.yufei.comboassistant.domain.Combo
import com.yufei.comboassistant.domain.DisplaySnapshot
import com.yufei.comboassistant.domain.GestureSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    fun cancelActive()
}

class PlaybackEngine(
    private val scope: CoroutineScope,
    private val performer: GesturePerformer,
) {
    private val mutableState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    private var playbackJob: Job? = null

    fun play(combo: Combo, display: DisplaySnapshot, currentPackage: String?): Boolean {
        if (playbackJob?.isActive == true) return false
        if (currentPackage != combo.targetPackage) {
            mutableState.value = PlaybackState.Failed("当前应用与连招绑定游戏不一致")
            return false
        }
        if (display.orientation != combo.orientation) {
            mutableState.value = PlaybackState.Failed("屏幕方向与录制方向不一致")
            return false
        }
        if (combo.timeline.segments.isEmpty()) {
            mutableState.value = PlaybackState.Failed("连招没有可执行动作")
            return false
        }

        playbackJob = scope.launch {
            try {
                repeat(combo.repeatCount) { repetitionIndex ->
                    mutableState.value = PlaybackState.Running(
                        comboId = combo.id,
                        repetition = repetitionIndex + 1,
                        total = combo.repeatCount,
                    )
                    combo.timeline.segments.forEach { segment ->
                        delay(scaleDelay(segment.gapBeforeMs, combo.speed))
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
                playbackJob = null
            }
        }
        return true
    }

    fun stop(reason: String) {
        if (playbackJob?.isActive != true) return
        mutableState.value = PlaybackState.Stopped(reason)
        performer.cancelActive()
        playbackJob?.cancel()
        playbackJob = null
    }

    companion object {
        fun scaleDelay(valueMs: Long, speed: Float): Long =
            (valueMs / speed.coerceIn(0.25f, 4f)).toLong().coerceAtLeast(0L)

        fun scaleStrokeDuration(valueMs: Long, speed: Float): Long =
            (valueMs / speed.coerceIn(0.25f, 4f)).toLong().coerceAtLeast(16L)
    }
}
