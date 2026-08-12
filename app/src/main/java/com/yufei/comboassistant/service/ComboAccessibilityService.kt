package com.yufei.comboassistant.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.yufei.comboassistant.BuildConfig
import com.yufei.comboassistant.MainActivity
import com.yufei.comboassistant.TouchTestActivity
import com.yufei.comboassistant.data.ComboRepository
import com.yufei.comboassistant.data.GlobalSettings
import com.yufei.comboassistant.data.GlobalSettingsRepository
import com.yufei.comboassistant.domain.Combo
import com.yufei.comboassistant.domain.DisplaySnapshot
import com.yufei.comboassistant.domain.GestureSegment
import com.yufei.comboassistant.domain.PrepareSegmentResult
import com.yufei.comboassistant.domain.RecordingSession
import com.yufei.comboassistant.domain.currentOrientation
import com.yufei.comboassistant.overlay.CaptureOverlayView
import com.yufei.comboassistant.overlay.CapturedGesture
import com.yufei.comboassistant.playback.AndroidGesturePerformer
import com.yufei.comboassistant.playback.GestureResult
import com.yufei.comboassistant.playback.PlaybackEngine
import com.yufei.comboassistant.playback.PlaybackState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class ComboAccessibilityService : AccessibilityService() {
    @Inject lateinit var comboRepository: ComboRepository
    @Inject lateinit var settingsRepository: GlobalSettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var gesturePerformer: AndroidGesturePerformer
    private lateinit var playbackEngine: PlaybackEngine

    private var latestCombos: List<Combo> = emptyList()
    private var settings = GlobalSettings()
    private var currentExternalPackage: String? = null

    private var ballView: TextView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panelView: View? = null
    private var editorView: View? = null
    private val comboButtons = mutableMapOf<String, Pair<TextView, WindowManager.LayoutParams>>()

    private var playbackStopView: TextView? = null
    private var playbackStopParams: WindowManager.LayoutParams? = null

    private var recordingView: CaptureOverlayView? = null
    private var recordingParams: WindowManager.LayoutParams? = null
    private var recordingStopView: TextView? = null
    private var recordingStopParams: WindowManager.LayoutParams? = null
    private var recordingSession: RecordingSession? = null
    private var recordingTargetPackage: String? = null
    private var recordingDisplay: DisplaySnapshot? = null
    private var recordingCountdownJob: Job? = null
    private var recordingTimeoutJob: Job? = null

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                playbackEngine.stop("屏幕已关闭")
                scope.launch { abortRecording("屏幕关闭，录制已取消") }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        gesturePerformer = AndroidGesturePerformer(this) { playbackStopCenter() }
        playbackEngine = PlaybackEngine(scope, gesturePerformer)
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        scope.launch {
            comboRepository.observeAll().collectLatest {
                latestCombos = it
                refreshOverlays()
            }
        }
        scope.launch {
            settingsRepository.settings.collectLatest {
                settings = it
                refreshOverlays()
            }
        }
        scope.launch {
            playbackEngine.state.collectLatest { state -> handlePlaybackState(state) }
        }
        refreshOverlays()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val observedPackage = event?.packageName?.toString() ?: return
        val observedClass = event.className?.toString()
        val candidate = when {
            BuildConfig.DEBUG &&
                observedPackage == packageName &&
                observedClass == TouchTestActivity::class.java.name -> observedPackage
            observedPackage == packageName && observedClass == MainActivity::class.java.name -> {
                stopForForegroundChange("已切换到连招助手主界面")
                return
            }
            else -> observedPackage.takeIf(::isExternalPackage) ?: return
        }
        if (candidate == currentExternalPackage) return
        currentExternalPackage = candidate

        val running = playbackEngine.state.value as? PlaybackState.Running
        if (running != null) {
            val activeCombo = latestCombos.firstOrNull { it.id == running.comboId }
            if (activeCombo?.targetPackage != candidate) playbackEngine.stop("已切换到其他应用")
        }
        if (recordingSession != null && recordingTargetPackage != candidate) {
            scope.launch { abortRecording("已切换到其他应用，录制已取消") }
        }
        refreshOverlays()
    }

    private fun stopForForegroundChange(reason: String) {
        currentExternalPackage = null
        playbackEngine.stop(reason)
        if (recordingSession != null) {
            scope.launch { abortRecording("$reason，录制已取消") }
        }
        refreshOverlays()
    }

    override fun onInterrupt() {
        playbackEngine.stop("无障碍服务被中断")
        scope.launch { abortRecording("无障碍服务被中断") }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        playbackEngine.stop("屏幕方向或尺寸已改变")
        scope.launch { abortRecording("屏幕方向或尺寸改变，录制已取消") }
        refreshOverlays()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOffReceiver) }
        playbackEngine.stop("服务已关闭")
        cleanupRecordingWindows()
        removePanel()
        removeEditor()
        removeBall()
        removeComboButtons()
        removePlaybackStop()
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshOverlays() {
        if (!::windowManager.isInitialized) return
        removeComboButtons()
        if (!settings.disclosureAccepted || recordingSession != null || playbackEngine.state.value is PlaybackState.Running) {
            removePanel()
            removeEditor()
            removeBall()
            return
        }

        if (settings.floatingBallEnabled) showBall() else {
            removePanel()
            removeBall()
        }
        if (settings.buttonsHidden) return

        val display = displaySnapshot()
        latestCombos
            .filter {
                it.visible &&
                    it.targetPackage == currentExternalPackage &&
                    it.orientation == display.orientation
            }
            .forEach { showComboButton(it, display) }
    }

    private fun showBall() {
        if (ballView != null) return
        val display = displaySnapshot()
        val size = dp(52)
        val params = overlayParams(size, size).apply {
            x = (settings.ballX * (display.width - size).coerceAtLeast(0)).toInt()
            y = (settings.ballY * (display.height - size).coerceAtLeast(0)).toInt()
        }
        val view = makePill("连", 0xFF6D5CE7.toInt(), 18f).apply {
            background = circleBackground(0xFF6D5CE7.toInt())
            elevation = dp(8).toFloat()
        }
        view.setOnTouchListener(
            DragClickListener(
                params = params,
                onClick = { togglePanel() },
                onPositionSaved = { x, y ->
                    scope.launch {
                        settingsRepository.setBallPosition(
                            x / (display.width - size).coerceAtLeast(1).toFloat(),
                            y / (display.height - size).coerceAtLeast(1).toFloat(),
                        )
                    }
                },
            ),
        )
        windowManager.addView(view, params)
        ballView = view
        ballParams = params
    }

    private fun togglePanel() {
        if (panelView != null) removePanel() else showPanel()
    }

    private fun showPanel() {
        removeEditor()
        val display = displaySnapshot()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground(0xE6292C3A.toInt(), 18f)
            elevation = dp(10).toFloat()
        }
        layout.addView(panelTitle("连招助手"))
        layout.addView(panelButton("● 新建录制") { startRecording() })
        layout.addView(
            panelButton(if (settings.buttonsHidden) "显示全部连招键" else "隐藏全部连招键") {
                scope.launch { settingsRepository.setButtonsHidden(!settings.buttonsHidden) }
                removePanel()
            },
        )
        layout.addView(panelHint("点击连招键执行；拖动改位置；长按编辑参数"))
        layout.addView(panelButton("打开主界面") { openMainActivity() })
        layout.addView(panelButton("收起") { removePanel() })

        val width = dp(238)
        val params = overlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            val ball = ballParams
            x = ((ball?.x ?: 0) + dp(58)).coerceAtMost((display.width - width).coerceAtLeast(0))
            y = (ball?.y ?: dp(80)).coerceIn(0, (display.height - dp(300)).coerceAtLeast(0))
        }
        windowManager.addView(layout, params)
        panelView = layout
    }

    private fun showComboButton(combo: Combo, display: DisplaySnapshot) {
        val size = dp(combo.buttonSizeDp.toInt())
        val params = overlayParams(size, size).apply {
            x = (combo.buttonX * (display.width - size).coerceAtLeast(0)).toInt()
            y = (combo.buttonY * (display.height - size).coerceAtLeast(0)).toInt()
        }
        val text = combo.name.trim().take(2).ifBlank { "招" }
        val view = makePill(text, 0xFF157BD6.toInt(), if (text.length == 1) 17f else 13f).apply {
            alpha = combo.buttonOpacity
            background = circleBackground(0xFF157BD6.toInt())
            elevation = dp(6).toFloat()
        }
        view.setOnTouchListener(comboTouchListener(combo, view, params, display, size))
        windowManager.addView(view, params)
        comboButtons[combo.id] = view to params
    }

    private fun comboTouchListener(
        combo: Combo,
        view: View,
        params: WindowManager.LayoutParams,
        display: DisplaySnapshot,
        size: Int,
    ): View.OnTouchListener {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var originX = 0
        var originY = 0
        var moved = false
        var longTriggered = false
        val longPress = Runnable {
            if (!moved) {
                longTriggered = true
                openComboEditor(combo)
            }
        }
        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    originX = params.x
                    originY = params.y
                    moved = false
                    longTriggered = false
                    mainHandler.postDelayed(longPress, 600L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        moved = true
                        mainHandler.removeCallbacks(longPress)
                    }
                    if (moved) {
                        params.x = (originX + dx.toInt()).coerceIn(0, (display.width - size).coerceAtLeast(0))
                        params.y = (originY + dy.toInt()).coerceIn(0, (display.height - size).coerceAtLeast(0))
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPress)
                    when {
                        moved -> scope.launch {
                            comboRepository.save(
                                combo.copy(
                                    buttonX = params.x / (display.width - size).coerceAtLeast(1).toFloat(),
                                    buttonY = params.y / (display.height - size).coerceAtLeast(1).toFloat(),
                                    updatedAt = System.currentTimeMillis(),
                                ),
                            )
                        }
                        !longTriggered -> playCombo(combo)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPress)
                    true
                }
                else -> false
            }
        }
    }

    private fun playCombo(combo: Combo) {
        removePanel()
        removeEditor()
        showPlaybackStop(combo.repeatCount)
        removeBall()
        removeComboButtons()
        val started = playbackEngine.play(combo, displaySnapshot(), currentExternalPackage)
        if (!started) {
            removePlaybackStop()
            refreshOverlays()
            toast((playbackEngine.state.value as? PlaybackState.Failed)?.reason ?: "已有连招正在执行")
        }
    }

    private suspend fun handlePlaybackState(state: PlaybackState) {
        when (state) {
            PlaybackState.Idle -> {
                removePlaybackStop()
                refreshOverlays()
            }
            is PlaybackState.Running -> {
                if (playbackStopView == null) showPlaybackStop(state.total)
                playbackStopView?.text = "停止 ${state.repetition}/${state.total}"
            }
            is PlaybackState.Stopped -> {
                delay(100L)
                removePlaybackStop()
                toast(state.reason)
                refreshOverlays()
            }
            is PlaybackState.Failed -> {
                removePlaybackStop()
                toast(state.reason)
                refreshOverlays()
            }
        }
    }

    private fun showPlaybackStop(total: Int) {
        if (playbackStopView != null) return
        val display = displaySnapshot()
        val width = dp(112)
        val height = dp(46)
        val params = overlayParams(width, height).apply {
            x = (display.width - width - dp(20)).coerceAtLeast(0)
            y = dp(20)
        }
        val view = makePill("停止 1/$total", 0xFFD13C4B.toInt(), 14f).apply {
            background = roundedBackground(0xF2D13C4B.toInt(), 23f)
            elevation = dp(10).toFloat()
            setOnClickListener { playbackEngine.stop("用户停止") }
        }
        windowManager.addView(view, params)
        playbackStopView = view
        playbackStopParams = params
    }

    private fun playbackStopCenter(): PointF? {
        val params = playbackStopParams ?: return null
        return PointF(params.x + params.width / 2f, params.y + params.height / 2f)
    }

    private fun startRecording() {
        removePanel()
        removeEditor()
        if (playbackEngine.state.value is PlaybackState.Running) {
            toast("请先停止当前回放")
            return
        }
        val target = currentExternalPackage
        if (target == null) {
            toast("请先打开目标游戏，再开始录制")
            return
        }
        val display = displaySnapshot()
        recordingTargetPackage = target
        recordingDisplay = display
        recordingSession = RecordingSession()
        removeBall()
        removeComboButtons()

        val capture = CaptureOverlayView(this) { captured ->
            scope.launch { mirrorCapturedGesture(captured) }
        }
        val captureParams = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        ).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        windowManager.addView(capture, captureParams)
        recordingView = capture
        recordingParams = captureParams

        val stopWidth = dp(128)
        val stopHeight = dp(46)
        val stopParams = overlayParams(stopWidth, stopHeight).apply {
            x = (display.width - stopWidth - dp(20)).coerceAtLeast(0)
            y = dp(20)
        }
        val stop = makePill("停止并保存", 0xFFD13C4B.toInt(), 14f).apply {
            background = roundedBackground(0xF2D13C4B.toInt(), 23f)
            setOnClickListener { scope.launch { finishRecording(save = true, message = "录制已保存") } }
        }
        windowManager.addView(stop, stopParams)
        recordingStopView = stop
        recordingStopParams = stopParams

        recordingCountdownJob = scope.launch {
            for (count in 3 downTo 1) {
                capture.showCountdown(count)
                delay(1_000L)
            }
            val session = recordingSession ?: return@launch
            // MotionEvent.downTime uses the uptime clock, so recording gaps must use the
            // same clock. elapsedRealtime() would incorrectly absorb time spent in deep sleep.
            session.start(SystemClock.uptimeMillis())
            armRecording()
        }
    }

    private fun armRecording() {
        setRecordingWindowsTouchable(true)
        recordingView?.setArmed(true)
        recordingTimeoutJob?.cancel()
        val remaining = (60_000L - (recordingSession?.durationMs ?: 0L)).coerceAtLeast(1L)
        recordingTimeoutJob = scope.launch {
            delay(remaining)
            finishRecording(save = true, message = "已达到 60 秒上限并自动保存")
        }
    }

    private suspend fun mirrorCapturedGesture(captured: CapturedGesture) {
        recordingTimeoutJob?.cancel()
        val session = recordingSession ?: return
        val display = recordingDisplay ?: return
        when (val prepared = session.prepare(captured.strokes, captured.downElapsedMs)) {
            is PrepareSegmentResult.Invalid -> abortRecording(prepared.message)
            is PrepareSegmentResult.LimitReached -> finishRecording(save = true, message = prepared.message)
            is PrepareSegmentResult.Ready -> {
                setRecordingWindowsTouchable(false)
                recordingView?.setArmed(false)
                val mirrorSegment = prepared.segment.copy(gapBeforeMs = 0L)
                when (gesturePerformer.perform(mirrorSegment, 1f, display)) {
                    GestureResult.COMPLETED -> {
                        session.commit(prepared.segment, SystemClock.uptimeMillis())
                        if (session.segmentCount >= 200) {
                            finishRecording(save = true, message = "已达到 200 次触摸上限并自动保存")
                        } else {
                            armRecording()
                        }
                    }
                    GestureResult.CANCELLED -> abortRecording("镜像手势被系统或用户操作取消")
                    GestureResult.REJECTED -> abortRecording("系统拒绝镜像手势，此游戏可能不兼容")
                }
            }
        }
    }

    private suspend fun finishRecording(save: Boolean, message: String) {
        val session = recordingSession ?: return
        val timeline = session.finish()
        val target = recordingTargetPackage
        val display = recordingDisplay
        cleanupRecordingWindows()
        if (!save || timeline.segments.isEmpty() || target == null || display == null) {
            toast(if (timeline.segments.isEmpty()) "没有录到有效动作" else message)
            refreshOverlays()
            return
        }
        val now = System.currentTimeMillis()
        val combo = Combo(
            id = UUID.randomUUID().toString(),
            name = "连招 ${SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(now))}",
            targetPackage = target,
            orientation = display.orientation,
            recordedWidth = display.width,
            recordedHeight = display.height,
            timeline = timeline,
            createdAt = now,
            updatedAt = now,
        )
        comboRepository.save(combo)
        toast(message)
        refreshOverlays()
        openComboEditor(combo)
    }

    private suspend fun abortRecording(message: String) {
        if (recordingSession == null) return
        recordingSession?.finish()
        cleanupRecordingWindows()
        toast(message)
        refreshOverlays()
    }

    private fun cleanupRecordingWindows() {
        recordingCountdownJob?.cancel()
        recordingTimeoutJob?.cancel()
        recordingCountdownJob = null
        recordingTimeoutJob = null
        removeView(recordingView)
        removeView(recordingStopView)
        recordingView = null
        recordingParams = null
        recordingStopView = null
        recordingStopParams = null
        recordingSession = null
        recordingTargetPackage = null
        recordingDisplay = null
    }

    private fun setRecordingWindowsTouchable(touchable: Boolean) {
        updateTouchable(recordingView, recordingParams, touchable)
        updateTouchable(recordingStopView, recordingStopParams, touchable)
    }

    private fun openComboEditor(combo: Combo) {
        if (recordingSession != null || playbackEngine.state.value is PlaybackState.Running) return
        removePanel()
        removeEditor()
        val display = displaySnapshot()
        var sizeDp = combo.buttonSizeDp
        var opacity = combo.buttonOpacity
        var speed = combo.speed
        var intervalMs = combo.repeatIntervalMs

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = roundedBackground(0xF22A2D3B.toInt(), 18f)
        }
        content.addView(panelTitle("编辑连招"))
        val nameInput = EditText(this).apply {
            setText(combo.name)
            setTextColor(Color.WHITE)
            setHintTextColor(0xFFB6BAC8.toInt())
            hint = "连招名称"
            isSingleLine = true
            selectAll()
        }
        content.addView(nameInput)
        content.addView(panelHint("${combo.targetPackage} · ${combo.orientation.name} · ${combo.timeline.segments.size} 段"))

        addSeek(content, "按键大小", 60, (sizeDp - 36f).toInt()) { value, label ->
            sizeDp = 36f + value
            label.text = "按键大小：${sizeDp.toInt()}dp"
        }
        addSeek(content, "按键透明度", 80, (opacity * 100 - 20).toInt()) { value, label ->
            opacity = (20 + value) / 100f
            label.text = "按键透明度：${(opacity * 100).toInt()}%"
        }
        addSeek(content, "执行倍速", 15, ((speed * 4).toInt() - 1).coerceIn(0, 15)) { value, label ->
            speed = (value + 1) / 4f
            label.text = "执行倍速：${speed}×"
        }

        val repeatLabel = panelHint("重复次数（1–999）")
        val repeatInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(combo.repeatCount.toString())
            setTextColor(Color.WHITE)
            isSingleLine = true
        }
        content.addView(repeatLabel)
        content.addView(repeatInput)
        addSeek(content, "重复间隔", 200, (intervalMs / 50L).toInt()) { value, label ->
            intervalMs = value * 50L
            label.text = "重复间隔：${intervalMs}ms"
        }
        val visible = CheckBox(this).apply {
            text = "显示此连招按键"
            setTextColor(Color.WHITE)
            isChecked = combo.visible
        }
        content.addView(visible)
        content.addView(panelHint("在游戏中直接拖动连招键可调整位置"))
        content.addView(panelButton("保存") {
            val repeatCount = repeatInput.text.toString().toIntOrNull()?.coerceIn(1, 999) ?: 1
            scope.launch {
                comboRepository.save(
                    combo.copy(
                        name = nameInput.text.toString(),
                        buttonSizeDp = sizeDp,
                        buttonOpacity = opacity,
                        speed = speed,
                        repeatCount = repeatCount,
                        repeatIntervalMs = intervalMs,
                        visible = visible.isChecked,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                removeEditor()
                toast("连招设置已保存")
            }
        })
        content.addView(panelButton("取消") { removeEditor() })

        var confirmDelete = false
        val deleteButton = panelButton("删除连招") { }
        deleteButton.setTextColor(0xFFFF9BA5.toInt())
        deleteButton.setOnClickListener {
            if (!confirmDelete) {
                confirmDelete = true
                deleteButton.text = "再次点击确认删除"
                mainHandler.postDelayed({
                    confirmDelete = false
                    deleteButton.text = "删除连招"
                }, 2_500L)
            } else {
                scope.launch {
                    comboRepository.delete(combo.id)
                    removeEditor()
                    toast("连招已删除")
                }
            }
        }
        content.addView(deleteButton)

        val scroll = ScrollView(this).apply { addView(content) }
        val width = dp(326)
        val height = (display.height - dp(40)).coerceAtMost(dp(620))
        val params = overlayParams(width, height).apply {
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            x = dp(20).coerceAtMost((display.width - width).coerceAtLeast(0))
            y = dp(20)
        }
        windowManager.addView(scroll, params)
        editorView = scroll
        nameInput.requestFocus()
    }

    private fun addSeek(
        parent: LinearLayout,
        title: String,
        max: Int,
        progress: Int,
        onChanged: (Int, TextView) -> Unit,
    ) {
        val label = panelHint(title)
        val seek = SeekBar(this).apply {
            this.max = max
            this.progress = progress.coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    onChanged(value, label)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        onChanged(seek.progress, label)
        parent.addView(label)
        parent.addView(seek)
    }

    private fun openMainActivity() {
        removePanel()
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
        )
    }

    private fun displaySnapshot(): DisplaySnapshot {
        val bounds = if (Build.VERSION.SDK_INT >= 30) {
            windowManager.currentWindowMetrics.bounds
        } else {
            val point = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(point)
            android.graphics.Rect(0, 0, point.x, point.y)
        }
        return DisplaySnapshot(
            width = bounds.width().coerceAtLeast(1),
            height = bounds.height().coerceAtLeast(1),
            orientation = currentOrientation(bounds.width(), bounds.height()),
        )
    }

    private fun overlayParams(width: Int, height: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

    private inner class DragClickListener(
        private val params: WindowManager.LayoutParams,
        private val onClick: () -> Unit,
        private val onPositionSaved: (Int, Int) -> Unit,
    ) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var originX = 0
        private var originY = 0
        private var moved = false
        private val slop = ViewConfiguration.get(this@ComboAccessibilityService).scaledTouchSlop

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val display = displaySnapshot()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    originX = params.x
                    originY = params.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        params.x = (originX + dx.toInt()).coerceIn(0, (display.width - view.width).coerceAtLeast(0))
                        params.y = (originY + dy.toInt()).coerceIn(0, (display.height - view.height).coerceAtLeast(0))
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                }
                MotionEvent.ACTION_UP -> if (moved) onPositionSaved(params.x, params.y) else onClick()
            }
            return true
        }
    }

    private fun makePill(text: String, color: Int, textSizeSp: Float): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = textSizeSp
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        background = roundedBackground(color, 16f)
    }

    private fun panelTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 18f
        setPadding(dp(8), dp(4), dp(8), dp(8))
    }

    private fun panelHint(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(0xFFC5C9D6.toInt())
        textSize = 12f
        setPadding(dp(8), dp(5), dp(8), dp(5))
    }

    private fun panelButton(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF44495D.toInt())
        setOnClickListener { action() }
    }

    private fun circleBackground(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(1), 0x66FFFFFF)
    }

    private fun roundedBackground(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        setStroke(dp(1), 0x33FFFFFF)
    }

    private fun updateTouchable(
        view: View?,
        params: WindowManager.LayoutParams?,
        touchable: Boolean,
    ) {
        if (view == null || params == null) return
        params.flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun removeComboButtons() {
        comboButtons.values.forEach { removeView(it.first) }
        comboButtons.clear()
    }

    private fun removePanel() {
        removeView(panelView)
        panelView = null
    }

    private fun removeEditor() {
        removeView(editorView)
        editorView = null
    }

    private fun removeBall() {
        removeView(ballView)
        ballView = null
        ballParams = null
    }

    private fun removePlaybackStop() {
        removeView(playbackStopView)
        playbackStopView = null
        playbackStopParams = null
    }

    private fun removeView(view: View?) {
        if (view == null || !::windowManager.isInitialized) return
        runCatching { windowManager.removeViewImmediate(view) }
    }

    private fun isExternalPackage(value: String): Boolean {
        if (value == packageName || value == "android") return false
        return value !in setOf(
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        )
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
