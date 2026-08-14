package com.yufei.comboassistant.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
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
import com.yufei.comboassistant.domain.AppendSegmentResult
import com.yufei.comboassistant.domain.Combo
import com.yufei.comboassistant.domain.DisplaySnapshot
import com.yufei.comboassistant.domain.MacroTimeline
import com.yufei.comboassistant.domain.RecordingLimit
import com.yufei.comboassistant.domain.RecordingSession
import com.yufei.comboassistant.domain.currentOrientation
import com.yufei.comboassistant.foreground.AndroidUsageForegroundSource
import com.yufei.comboassistant.foreground.ForegroundDecision
import com.yufei.comboassistant.foreground.ForegroundDiagnosticLog
import com.yufei.comboassistant.foreground.ForegroundDisplayInfo
import com.yufei.comboassistant.foreground.ForegroundDisplayOrientation
import com.yufei.comboassistant.foreground.ForegroundObservation
import com.yufei.comboassistant.foreground.ForegroundObservationKind
import com.yufei.comboassistant.foreground.ForegroundObservationSource
import com.yufei.comboassistant.foreground.ForegroundPackageClassifier
import com.yufei.comboassistant.foreground.ForegroundPackageKind
import com.yufei.comboassistant.foreground.ForegroundSessionState
import com.yufei.comboassistant.foreground.ForegroundSessionTracker
import com.yufei.comboassistant.foreground.ForegroundTransition
import com.yufei.comboassistant.foreground.HiddenReason
import com.yufei.comboassistant.foreground.InMemoryForegroundDiagnosticLog
import com.yufei.comboassistant.foreground.NoOpForegroundDiagnosticLog
import com.yufei.comboassistant.foreground.SetBasedForegroundPackageClassifier
import com.yufei.comboassistant.foreground.UsageForegroundSource
import com.yufei.comboassistant.overlay.CaptureCancelReason
import com.yufei.comboassistant.overlay.CaptureOverlayView
import com.yufei.comboassistant.overlay.CapturedGesture
import com.yufei.comboassistant.overlay.FloatingBallPosition
import com.yufei.comboassistant.overlay.LayoutSession
import com.yufei.comboassistant.overlay.OverlayCoordinator
import com.yufei.comboassistant.playback.AndroidGesturePerformer
import com.yufei.comboassistant.playback.ExecutionGateResult
import com.yufei.comboassistant.playback.PlaybackEngine
import com.yufei.comboassistant.playback.PlaybackState
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@AndroidEntryPoint
class ComboAccessibilityService : AccessibilityService() {
    @Inject lateinit var comboRepository: ComboRepository
    @Inject lateinit var settingsRepository: GlobalSettingsRepository

    private data class ComboButtonSpec(
        val combo: Combo,
        val display: DisplaySnapshot,
        val mode: OverlayMode,
        val selected: Boolean,
    )

    private data class ComboButtonEntry(
        val root: FrameLayout,
        val visual: TextView,
        val layoutRing: View,
        val params: WindowManager.LayoutParams,
    )

    private data class PanelWindow(
        val view: LinearLayout,
        val params: WindowManager.LayoutParams,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recordingFinishMutex = Mutex()
    private val displayTracker = DisplayTracker()
    private val layoutCommitGuard = LayoutCommitGuard()
    private val overlayCoordinator = OverlayCoordinator<String, ComboButtonSpec>()
    private val debugDiagnostics = if (BuildConfig.DEBUG) InMemoryForegroundDiagnosticLog(200) else null

    private lateinit var windowManager: WindowManager
    private lateinit var powerManager: PowerManager
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var gesturePerformer: AndroidGesturePerformer
    private lateinit var playbackEngine: PlaybackEngine
    private lateinit var foregroundTracker: ForegroundSessionTracker
    private lateinit var usageForegroundSource: UsageForegroundSource

    private var latestCombos: List<Combo> = emptyList()
    private var settings = GlobalSettings()
    private var debugTouchTestVisible = false
    private var foregroundSettleJob: Job? = null
    private var usagePollJob: Job? = null
    private var displayStabilizeJob: Job? = null
    private var comboObserverJob: Job? = null
    private var settingsObserverJob: Job? = null
    private var playbackStateJob: Job? = null
    private var screenOffReceiverRegistered = false

    private var overlayMode = OverlayMode.LOCKED
    private var layoutSession: LayoutSession? = null
    private var layoutTargetPackage: String? = null
    private var selectedLayoutComboId: String? = null
    private var layoutSessionId: String? = null

    private var ballView: TextView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var optimisticBallPosition: FloatingBallPosition? = null
    private var ballDragInProgress = false
    private var ballPositionSaveGeneration = 0L
    private var panelRequestedOpen = false
    private var panelView: View? = null
    private var panelSummaryView: TextView? = null
    private var editorView: View? = null
    private val comboButtons = mutableMapOf<String, ComboButtonEntry>()
    private val pendingOverlayRemovals = linkedSetOf<View>()
    private var removalRetryScheduled = false
    private val removalRetry = object : Runnable {
        override fun run() {
            removalRetryScheduled = false
            if (!::windowManager.isInitialized) return
            pendingOverlayRemovals.toList().forEach(::detachOverlayView)
            if (pendingOverlayRemovals.isNotEmpty()) scheduleRemovalRetry()
        }
    }

    private var playbackStopView: TextView? = null
    private var playbackStopParams: WindowManager.LayoutParams? = null

    private var recordingView: CaptureOverlayView? = null
    private var recordingParams: WindowManager.LayoutParams? = null
    private var recordingHudView: View? = null
    private var recordingHudParams: WindowManager.LayoutParams? = null
    private var recordingHudStatus: TextView? = null
    private var recordingSession: RecordingSession? = null
    private var recordingTargetPackage: String? = null
    private var recordingDisplay: DisplaySnapshot? = null
    private var recordingState: RecordingState = RecordingState.Idle
    private var recordingCountdownJob: Job? = null
    private var recordingTimeoutJob: Job? = null
    private var recordingHudJob: Job? = null

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF || !::foregroundTracker.isInitialized) return
            foregroundTracker.onScreenOff(SystemClock.elapsedRealtime())
            playbackEngine.stop("屏幕已关闭")
            cancelLayoutMode(silent = true)
            activeRecordingSessionId()?.let { sessionId ->
                scope.launch { finalizeRecording(sessionId, save = false, "屏幕关闭，录制已取消") }
            }
            refreshOverlays()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Android can reconnect the same service instance. Tear down connection-scoped work
        // before rebuilding the trackers so collectors and callbacks never multiply.
        if (activeRecordingSessionId() != null) {
            recordingView?.setArmed(false)
            recordingSession?.cancel()
            cleanupRecordingWindows()
            recordingState = RecordingState.Idle
            toast("无障碍服务已重连，旧录制已取消")
        }
        comboObserverJob?.cancel()
        settingsObserverJob?.cancel()
        playbackStateJob?.cancel()
        foregroundSettleJob?.cancel()
        usagePollJob?.cancel()
        displayStabilizeJob?.cancel()
        if (::playbackEngine.isInitialized) playbackEngine.stop("无障碍服务正在重新连接")
        removePlaybackStop()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        displayTracker.initialize(displaySnapshot())

        // Resolve the default IME dynamically so changing keyboards while the service stays
        // connected cannot turn the new input method into a false external-app switch.
        val classifier = ForegroundPackageClassifier(::classifyObservedPackage)
        val diagnosticLog: ForegroundDiagnosticLog = debugDiagnostics ?: NoOpForegroundDiagnosticLog
        foregroundTracker = ForegroundSessionTracker(classifier, diagnostics = diagnosticLog)
        usageForegroundSource = AndroidUsageForegroundSource(this)

        gesturePerformer = AndroidGesturePerformer(this) { playbackStopCenter() }
        playbackEngine = PlaybackEngine(
            scope = scope,
            performer = gesturePerformer,
            executionGate = { combo -> checkExecutionGate(combo) },
        )
        if (!screenOffReceiverRegistered) {
            screenOffReceiverRegistered = runCatching {
                ContextCompat.registerReceiver(
                    this,
                    screenOffReceiver,
                    IntentFilter(Intent.ACTION_SCREEN_OFF),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                true
            }.getOrDefault(false)
            if (!screenOffReceiverRegistered) {
                closePanel()
                removeEditor()
                removeBall()
                clearComboButtons()
                toast("无法监听熄屏事件，触控服务暂不可用")
                return
            }
        }

        comboObserverJob = scope.launch {
            comboRepository.observeAll().collectLatest {
                latestCombos = it
                refreshOverlays()
            }
        }
        settingsObserverJob = scope.launch {
            settingsRepository.settings.collectLatest { newSettings ->
                val enhancedChanged = settings.enhancedForegroundDetection !=
                    newSettings.enhancedForegroundDetection
                settings = newSettings
                optimisticBallPosition?.let { optimistic ->
                    if (abs(optimistic.x - newSettings.ballX) < 0.0005f &&
                        abs(optimistic.y - newSettings.ballY) < 0.0005f
                    ) {
                        optimisticBallPosition = null
                    }
                }
                if (enhancedChanged || usagePollJob == null) updateUsagePolling()
                refreshOverlays()
            }
        }
        playbackStateJob = scope.launch {
            playbackEngine.state.collectLatest(::handlePlaybackState)
        }
        updateUsagePolling()
        refreshOverlays()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val observedPackage = event.packageName?.toString()?.trim().orEmpty()
        if (observedPackage.isEmpty() || !::foregroundTracker.isInitialized) return
        val observedClass = event.className?.toString()
        // Our accessibility overlays can emit content-change events with this application's
        // package. They are not a foreground switch and must not hide buttons or cancel a session.
        // Only an Activity-level state change may update the debug test-field routing flag.
        if (observedPackage == packageName) {
            val decision = ownPackageEventDecision(
                isWindowStateChanged =
                    event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                className = observedClass,
                mainActivityClassName = MainActivity::class.java.name,
                touchTestActivityClassName = TouchTestActivity::class.java.name,
                debugBuild = BuildConfig.DEBUG,
                wasDebugTouchTestVisible = debugTouchTestVisible,
            )
            debugTouchTestVisible = decision.debugTouchTestVisible
            if (!decision.shouldProcess) return
        }
        val kind = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> ForegroundObservationKind.WINDOW_STATE_CHANGED
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> ForegroundObservationKind.WINDOWS_CHANGED
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> ForegroundObservationKind.WINDOW_CONTENT_CHANGED
            else -> return
        }
        submitForegroundObservation(
            ForegroundObservation(
                packageName = observedPackage,
                className = observedClass,
                source = ForegroundObservationSource.ACCESSIBILITY,
                kind = kind,
                observedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                // AccessibilityEvent.eventTime uses an uptime clock, not wall time.
                sourceEventWallTimeMs = null,
                display = displaySnapshot().toForegroundDisplayInfo(),
            ),
        )
    }

    override fun onInterrupt() {
        if (::foregroundTracker.isInitialized) {
            foregroundTracker.onServiceDisconnected(SystemClock.elapsedRealtime())
        }
        playbackEngine.stop("无障碍服务被中断")
        cancelLayoutMode(silent = true)
        activeRecordingSessionId()?.let { sessionId ->
            scope.launch { finalizeRecording(sessionId, save = false, "无障碍服务被中断，录制已取消") }
        }
        refreshOverlays()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        playbackEngine.stop("屏幕方向或尺寸已改变")
        cancelLayoutMode(silent = true)
        activeRecordingSessionId()?.let { sessionId ->
            scope.launch { finalizeRecording(sessionId, save = false, "屏幕方向或尺寸改变，录制已取消") }
        }
        scheduleDisplayStabilization()
        refreshOverlays()
    }

    override fun onDestroy() {
        if (screenOffReceiverRegistered) {
            runCatching { unregisterReceiver(screenOffReceiver) }
            screenOffReceiverRegistered = false
        }
        mainHandler.removeCallbacks(removalRetry)
        removalRetryScheduled = false
        foregroundSettleJob?.cancel()
        usagePollJob?.cancel()
        displayStabilizeJob?.cancel()
        comboObserverJob?.cancel()
        settingsObserverJob?.cancel()
        playbackStateJob?.cancel()
        if (::playbackEngine.isInitialized) playbackEngine.stop("服务已关闭")
        recordingSession?.cancel()
        cleanupRecordingWindows()
        closePanel()
        removeEditor()
        removeBall()
        clearComboButtons()
        removePlaybackStop()
        pendingOverlayRemovals.toList().forEach(::detachOverlayView)
        mainHandler.removeCallbacks(removalRetry)
        removalRetryScheduled = false
        pendingOverlayRemovals.clear()
        scope.cancel()
        super.onDestroy()
    }

    private fun submitForegroundObservation(observation: ForegroundObservation) {
        if (
            observation.source == ForegroundObservationSource.USAGE_STATS &&
            (!powerManager.isInteractive || keyguardManager.isKeyguardLocked)
        ) return
        val transition = foregroundTracker.observe(observation)
        handleForegroundTransition(transition)
        if (transition.shouldScheduleCandidateSettlement) {
            foregroundSettleJob?.cancel()
            foregroundSettleJob = scope.launch {
                delay(320L)
                handleForegroundTransition(foregroundTracker.settle(SystemClock.elapsedRealtime()))
            }
        }
    }

    private fun handleForegroundTransition(transition: ForegroundTransition) {
        val current = transition.current
        val running = playbackEngine.state.value as? PlaybackState.Running
        val runningTarget = running?.let { state -> latestCombos.firstOrNull { it.id == state.comboId } }
            ?.targetPackage
        val guardedTargets = listOfNotNull(
            runningTarget,
            recordingTargetPackage,
            layoutTargetPackage,
        )
        val isRealSwitch = isRealForegroundSwitch(
            previous = transition.previous,
            current = current,
            guardedTargets = guardedTargets,
        )
        if (isRealSwitch) {
            playbackEngine.stop("已切换到其他应用")
            val recordingTarget = recordingTargetPackage
            val observedTarget = current.activePackageName ?: current.candidatePackageName
            if (recordingTarget != null && observedTarget != recordingTarget) {
                activeRecordingSessionId()?.let { sessionId ->
                    scope.launch {
                        finalizeRecording(sessionId, save = false, "已切换到其他应用，录制已取消")
                    }
                }
            }
            cancelLayoutMode(silent = true)
        } else if (current is ForegroundSessionState.TemporarilyObscured && running != null) {
            playbackEngine.stop("系统窗口暂时遮挡，回放已停止")
        }
        if (transition.decision == ForegroundDecision.SERVICE_DISCONNECTED) {
            cancelLayoutMode(silent = true)
        }
        refreshOverlays()
    }

    private fun updateUsagePolling() {
        usagePollJob?.cancel()
        usagePollJob = null
        if (!::usageForegroundSource.isInitialized || !settings.enhancedForegroundDetection) return
        usagePollJob = scope.launch {
            while (isActive && settings.enhancedForegroundDetection) {
                if (powerManager.isInteractive && !keyguardManager.isKeyguardLocked) {
                    usageForegroundSource.latestForegroundObservation()?.let(::submitForegroundObservation)
                }
                delay(1_000L)
            }
        }
    }

    private fun refreshOverlays() {
        if (!::windowManager.isInitialized || !::playbackEngine.isInitialized) return
        val busy = recordingState !is RecordingState.Idle ||
            playbackEngine.state.value is PlaybackState.Running
        if (!settings.disclosureAccepted || busy) {
            closePanel()
            removeEditor()
            removeBall()
            reconcileComboButtons(emptyMap())
            return
        }

        val ownAppVisible = ::foregroundTracker.isInitialized &&
            foregroundTracker.state is ForegroundSessionState.OwnApp
        if (settings.floatingBallEnabled && !ownAppVisible) {
            if (!showOrUpdateBall()) closePanel()
        } else {
            closePanel()
            removeBall()
        }
        reconcileComboButtons(desiredComboButtons())
        panelSummaryView?.text = foregroundSummary()
        if (shouldRenderPanel(
                requestedOpen = panelRequestedOpen,
                attached = panelView?.isAttachedToWindow == true,
            )
        ) {
            if (overlayMode == OverlayMode.LAYOUT) {
                if (!showLayoutPanel()) closePanel()
            } else {
                if (!showPanel()) closePanel()
            }
        }
    }

    private fun desiredComboButtons(): Map<String, ComboButtonSpec> {
        if (!settings.disclosureAccepted || recordingState !is RecordingState.Idle) return emptyMap()
        if (playbackEngine.state.value is PlaybackState.Running) return emptyMap()
        if (overlayMode == OverlayMode.LOCKED && settings.buttonsHidden) return emptyMap()
        val packageName = foregroundTracker.activePackageName ?: return emptyMap()
        val display = (displayTracker.state as? DisplayState.Stable)?.snapshot ?: return emptyMap()
        val source = if (overlayMode == OverlayMode.LAYOUT) {
            if (layoutTargetPackage != packageName) return emptyMap()
            layoutSession?.combos().orEmpty()
        } else {
            latestCombos
        }
        return source.asSequence()
            .filter { it.visible && it.targetPackage == packageName && it.orientation == display.orientation }
            .associate { combo ->
                combo.id to ComboButtonSpec(
                    combo = combo,
                    display = display,
                    mode = overlayMode,
                    selected = combo.id == selectedLayoutComboId,
                )
            }
    }

    private fun reconcileComboButtons(desired: Map<String, ComboButtonSpec>) {
        overlayCoordinator.reconcile(
            desired = desired,
            onRemoved = ::removeComboButton,
            onUpdated = ::updateComboButton,
            onAdded = ::addComboButton,
        )
    }

    private fun addComboButton(id: String, spec: ComboButtonSpec): Boolean {
        comboButtons[id]?.let { existing ->
            if (existing.root.isAttachedToWindow) return updateComboButton(id, spec)
            comboButtons.remove(id)
        }
        val hitSize = dp(maxOf(spec.combo.buttonSizeDp, 48f))
        val params = overlayParams(hitSize, hitSize)
        val root = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "连招按键：${spec.combo.name}"
        }
        val visual = makePill("", 0xFF157BD6.toInt(), 14f)
        // The root owns the complete >=48dp hit target. If the visual child stays clickable it
        // consumes touches inside the circle before the root can execute/drag/long-press.
        visual.isClickable = false
        visual.isFocusable = false
        visual.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val layoutRing = View(this).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(visual)
        // The editing outline is a separate top layer. It intentionally does not inherit the
        // visual's user-configured alpha, so selected buttons remain obvious even at 20%.
        root.addView(layoutRing)
        val entry = ComboButtonEntry(root, visual, layoutRing, params)
        comboButtons[id] = entry
        applyComboButtonSpec(id, entry, spec)
        return runCatching {
            windowManager.addView(root, params)
            true
        }.getOrElse {
            if (!root.isAttachedToWindow) comboButtons.remove(id, entry)
            false
        }
    }

    private fun updateComboButton(id: String, spec: ComboButtonSpec): Boolean {
        val entry = comboButtons[id] ?: return addComboButton(id, spec)
        applyComboButtonSpec(id, entry, spec)
        return runCatching {
            windowManager.updateViewLayout(entry.root, entry.params)
            true
        }.getOrElse {
            if (!entry.root.isAttachedToWindow) comboButtons.remove(id, entry)
            false
        }
    }

    private fun applyComboButtonSpec(id: String, entry: ComboButtonEntry, spec: ComboButtonSpec) {
        val visualSize = dp(spec.combo.buttonSizeDp)
        val hitSize = dp(maxOf(spec.combo.buttonSizeDp, 48f))
        entry.params.width = hitSize
        entry.params.height = hitSize
        entry.params.x = (spec.combo.buttonX * (spec.display.width - hitSize).coerceAtLeast(0)).toInt()
        entry.params.y = (spec.combo.buttonY * (spec.display.height - hitSize).coerceAtLeast(0)).toInt()
        val visualParams = FrameLayout.LayoutParams(visualSize, visualSize, Gravity.CENTER)
        entry.visual.layoutParams = visualParams
        entry.layoutRing.layoutParams = FrameLayout.LayoutParams(visualSize, visualSize, Gravity.CENTER)
        entry.visual.text = spec.combo.name.trim().take(2).ifBlank { "招" }
        entry.visual.textSize = if (entry.visual.text.length == 1) 17f else 13f
        entry.visual.alpha = spec.combo.buttonOpacity
        entry.visual.background = comboButtonBackground()
        entry.layoutRing.apply {
            visibility = if (spec.mode == OverlayMode.LAYOUT) View.VISIBLE else View.GONE
            alpha = 1f
            background = layoutRingBackground(spec.selected)
        }
        entry.root.contentDescription = if (spec.mode == OverlayMode.LAYOUT) {
            "布局按键：${spec.combo.name}"
        } else {
            "执行连招：${spec.combo.name}"
        }
        entry.root.setOnTouchListener(comboTouchListener(id, entry.root, entry.params))
    }

    private fun comboTouchListener(
        comboId: String,
        view: View,
        params: WindowManager.LayoutParams,
    ): View.OnTouchListener {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var originX = 0
        var originY = 0
        var moved = false
        var longTriggered = false
        val longPress = Runnable {
            if (!moved && overlayMode == OverlayMode.LOCKED) {
                longTriggered = true
                currentCombo(comboId)?.let(::openComboEditor)
            }
        }
        return View.OnTouchListener { _, event ->
            if (overlayMode == OverlayMode.LAYOUT && layoutCommitGuard.isSaving(layoutSessionId)) {
                return@OnTouchListener true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    originX = params.x
                    originY = params.y
                    moved = false
                    longTriggered = false
                    if (overlayMode == OverlayMode.LAYOUT) {
                        selectLayoutCombo(comboId)
                    } else {
                        mainHandler.postDelayed(longPress, 600L)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        moved = true
                        mainHandler.removeCallbacks(longPress)
                    }
                    if (moved && overlayMode == OverlayMode.LAYOUT) {
                        val display = stableDisplay() ?: return@OnTouchListener true
                        params.x = (originX + dx.toInt())
                            .coerceIn(0, (display.width - params.width).coerceAtLeast(0))
                        params.y = (originY + dy.toInt())
                            .coerceIn(0, (display.height - params.height).coerceAtLeast(0))
                        layoutSession?.moveCombo(
                            comboId,
                            params.x / (display.width - params.width).coerceAtLeast(1).toFloat(),
                            params.y / (display.height - params.height).coerceAtLeast(1).toFloat(),
                        )
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPress)
                    if (overlayMode == OverlayMode.LOCKED && !moved && !longTriggered) {
                        currentCombo(comboId)?.let(::playCombo)
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

    private fun showOrUpdateBall(): Boolean {
        val display = displaySnapshot()
        val size = dp(52)
        val position = if (overlayMode == OverlayMode.LAYOUT) {
            layoutSession?.ballPosition() ?: FloatingBallPosition(settings.ballX, settings.ballY)
        } else {
            optimisticBallPosition ?: FloatingBallPosition(settings.ballX, settings.ballY)
        }
        val existingView = ballView
        val existingParams = ballParams
        if (existingView != null && existingParams != null) {
            if (!existingView.isAttachedToWindow) {
                ballView = null
                ballParams = null
            } else {
                pendingOverlayRemovals.remove(existingView)
                if (!ballDragInProgress) {
                    existingParams.x = (position.x * (display.width - size).coerceAtLeast(0)).toInt()
                    existingParams.y = (position.y * (display.height - size).coerceAtLeast(0)).toInt()
                    if (!updateOverlayView(existingView, existingParams)) return false
                }
                existingView.background = ballBackground()
                existingView.contentDescription = if (overlayMode == OverlayMode.LAYOUT) {
                    "拖动设置球"
                } else {
                    "拖动设置球；轻触打开设置"
                }
                return true
            }
        }
        val params = overlayParams(size, size).apply {
            x = (position.x * (display.width - size).coerceAtLeast(0)).toInt()
            y = (position.y * (display.height - size).coerceAtLeast(0)).toInt()
        }
        val view = makePill("连", 0xFF6D5CE7.toInt(), 18f).apply {
            background = ballBackground()
            elevation = dp(8).toFloat()
        }
        view.setOnTouchListener(ballTouchListener(view, params))
        if (!attachOverlayView(view, params)) return false
        ballView = view
        ballParams = params
        return true
    }

    private fun ballTouchListener(view: View, params: WindowManager.LayoutParams): View.OnTouchListener {
        val drag = BallDragState(ViewConfiguration.get(this).scaledTouchSlop)
        var panelClosedForDrag = false
        return View.OnTouchListener { _, event ->
            if (overlayMode == OverlayMode.LAYOUT && layoutCommitGuard.isSaving(layoutSessionId)) {
                return@OnTouchListener true
            }
            val display = displaySnapshot()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    drag.begin(event.rawX, event.rawY, params.x, params.y)
                    ballDragInProgress = true
                    panelClosedForDrag = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val moved = drag.move(
                        event.rawX,
                        event.rawY,
                        maxX = display.width - params.width,
                        maxY = display.height - params.height,
                    ) as? BallDragResult.Position
                    if (moved != null) {
                        if (!panelClosedForDrag) {
                            panelClosedForDrag = true
                            closePanel()
                        }
                        params.x = moved.x
                        params.y = moved.y
                        updateOverlayView(view, params)
                        if (overlayMode == OverlayMode.LAYOUT) {
                            layoutSession?.moveBall(
                                params.x / (display.width - params.width).coerceAtLeast(1).toFloat(),
                                params.y / (display.height - params.height).coerceAtLeast(1).toFloat(),
                            )
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    ballDragInProgress = false
                    when (drag.finish(params.x, params.y)) {
                        BallDragResult.Click -> togglePanel()
                        is BallDragResult.Position -> if (overlayMode == OverlayMode.LOCKED) {
                            persistLockedBallPosition(params, display)
                        }
                        BallDragResult.None -> Unit
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    ballDragInProgress = false
                    (drag.cancel() as? BallDragResult.Position)?.let { rollback ->
                        params.x = rollback.x
                        params.y = rollback.y
                        updateOverlayView(view, params)
                        if (overlayMode == OverlayMode.LAYOUT) {
                            layoutSession?.moveBall(
                                params.x / (display.width - params.width).coerceAtLeast(1).toFloat(),
                                params.y / (display.height - params.height).coerceAtLeast(1).toFloat(),
                            )
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun persistLockedBallPosition(
        params: WindowManager.LayoutParams,
        display: DisplaySnapshot,
    ) {
        val position = FloatingBallPosition(
            x = params.x / (display.width - params.width).coerceAtLeast(1).toFloat(),
            y = params.y / (display.height - params.height).coerceAtLeast(1).toFloat(),
        )
        optimisticBallPosition = position
        val generation = ++ballPositionSaveGeneration
        scope.launch {
            val failure = runCatching {
                settingsRepository.setBallPosition(position.x, position.y)
            }.exceptionOrNull()
            if (failure != null && generation == ballPositionSaveGeneration) {
                optimisticBallPosition = null
                toast("设置球位置保存失败，已恢复原位置")
                showOrUpdateBall()
            }
        }
    }

    private fun togglePanel() {
        if (panelRequestedOpen) {
            closePanel()
        } else {
            panelRequestedOpen = true
            if (!showPanel()) closePanel()
        }
    }

    private fun showPanel(): Boolean {
        if (overlayMode == OverlayMode.LAYOUT) return showLayoutPanel()
        discardPanelForRedraw()
        if (!removeEditor()) return false
        val display = displaySnapshot()
        val body = panelBodyContainer()
        val summary = panelHint(foregroundSummary())
        body.addView(summary)
        val candidate = foregroundTracker.candidatePackageName
        if (candidate != null) {
            body.addView(panelButton("确认本次游戏：$candidate") {
                handleForegroundTransition(
                    foregroundTracker.confirmCandidate(SystemClock.elapsedRealtime()),
                )
            })
        }
        if (foregroundTracker.activePackageName == null) {
            body.addView(panelButton("识别不到游戏？前往增强识别") { openMainActivity() })
        }
        body.addView(panelButton("● 新建连续录制") { startRecording() })
        body.addView(panelButton("布局按键") { beginLayoutMode() })
        body.addView(
            panelButton(if (settings.buttonsHidden) "显示全部连招键" else "隐藏全部连招键") {
                scope.launch { settingsRepository.setButtonsHidden(!settings.buttonsHidden) }
                closePanel()
            },
        )
        if (BuildConfig.DEBUG) {
            body.addView(panelButton("查看识别日志") { showDiagnostics() })
        }
        body.addView(panelHint("正常模式已锁定：短按执行，长按编辑；滑动不会移动按键"))
        if (foregroundTracker.activePackageName != null) {
            body.addView(panelButton("打开主界面") { openMainActivity() })
        }

        val width = dp(276)
        val panel = panelWindow("连招助手", body, width, display, desiredHeight = dp(420))
        if (!attachOverlayView(panel.view, panel.params)) {
            toast("设置面板暂时无法显示，请稍后重试")
            return false
        }
        panelView = panel.view
        panelSummaryView = summary
        return true
    }

    private fun beginLayoutMode(preferredCombo: Combo? = null) {
        val target = foregroundTracker.activePackageName
        val display = stableDisplay()
        if (target == null || display == null) {
            toast("请先确认目标游戏并等待屏幕方向稳定")
            return
        }
        val source = latestCombos
            .filter { it.targetPackage == target && it.visible && it.orientation == display.orientation }
            .toMutableList()
        preferredCombo?.takeIf {
            it.targetPackage == target && it.visible && it.orientation == display.orientation
        }?.let { preferred ->
            source.removeAll { it.id == preferred.id }
            source += preferred
        }
        if (source.isEmpty()) {
            toast("当前游戏没有可布局的连招键")
            return
        }
        closePanel()
        if (!removeEditor()) {
            toast("旧悬浮窗尚未关闭，请稍后重试")
            return
        }
        overlayMode = OverlayMode.LAYOUT
        layoutTargetPackage = target
        layoutSessionId = UUID.randomUUID().toString().also(layoutCommitGuard::begin)
        layoutSession = LayoutSession(
            source,
            optimisticBallPosition ?: FloatingBallPosition(settings.ballX, settings.ballY),
        )
        selectedLayoutComboId = preferredCombo?.id?.takeIf { id -> source.any { it.id == id } }
            ?: source.first().id
        panelRequestedOpen = true
        if (!showOrUpdateBall()) {
            cancelLayoutMode(silent = true)
            toast("无法显示布局悬浮窗，请稍后重试")
            return
        }
        reconcileComboButtons(desiredComboButtons())
        if (!showLayoutPanel()) {
            cancelLayoutMode(silent = true)
            toast("无法显示布局面板，已恢复锁定")
        }
    }

    private fun selectLayoutCombo(comboId: String) {
        if (overlayMode != OverlayMode.LAYOUT || comboId == selectedLayoutComboId) return
        selectedLayoutComboId = comboId
        reconcileComboButtons(desiredComboButtons())
        // Selecting a button updates the layout work copy, but must not reopen a panel that the
        // user explicitly closed with the ball or the fixed close control.
        if (shouldRedrawLayoutPanel(panelRequestedOpen, selectionChanged = true) &&
            !showLayoutPanel()
        ) {
            cancelLayoutMode(silent = true)
            toast("布局面板显示失败，已恢复锁定")
        }
    }

    private fun showLayoutPanel(): Boolean {
        if (overlayMode != OverlayMode.LAYOUT) return false
        panelRequestedOpen = true
        discardPanelForRedraw()
        val display = stableDisplay() ?: return false
        val session = layoutSession ?: return false
        if (layoutCommitGuard.isSaving(layoutSessionId)) {
            val body = panelBodyContainer().apply {
                addView(panelHint("按键已暂时冻结，请稍候"))
            }
            val width = dp(292)
            val panel = panelWindow("正在保存布局…", body, width, display, desiredHeight = dp(140))
            if (!attachOverlayView(panel.view, panel.params)) return false
            panelView = panel.view
            return true
        }
        val selected = selectedLayoutComboId?.let(session::combo)
        val body = panelBodyContainer()
        body.addView(panelHint("拖动设置球或连招键；布局期间绝不会执行连招"))
        if (selected != null) {
            body.addView(panelHint("已选择：${selected.name}"))
            addSeek(
                parent = body,
                title = "按键大小",
                max = 60,
                progress = (selected.buttonSizeDp - 36f).toInt(),
            ) { value, label ->
                if (layoutCommitGuard.isSaving(layoutSessionId)) return@addSeek
                val size = 36f + value
                label.text = "按键大小：${size.toInt()}dp"
                session.resizeComboKeepingCenter(
                    selected.id,
                    size,
                    display.width,
                    display.height,
                    resources.displayMetrics.density,
                )
                reconcileComboButtons(desiredComboButtons())
            }
            addSeek(
                parent = body,
                title = "按键透明度",
                max = 80,
                progress = (selected.buttonOpacity * 100 - 20).toInt(),
            ) { value, label ->
                if (layoutCommitGuard.isSaving(layoutSessionId)) return@addSeek
                val opacity = (20 + value) / 100f
                label.text = "按键透明度：${(opacity * 100).toInt()}%"
                session.setOpacity(selected.id, opacity)
                reconcileComboButtons(desiredComboButtons())
            }
        }
        body.addView(panelButton("完成并锁定") { commitLayoutMode() })
        body.addView(panelButton("取消并恢复") { cancelLayoutMode(silent = false) })

        val width = dp(292)
        val panel = panelWindow("布局按键 · 未保存", body, width, display, desiredHeight = dp(420))
        if (!attachOverlayView(panel.view, panel.params)) return false
        panelView = panel.view
        return true
    }

    private fun commitLayoutMode() {
        val session = layoutSession ?: return
        val sessionId = layoutSessionId ?: return
        if (!layoutCommitGuard.tryStart(sessionId)) {
            toast("布局正在保存，请稍候")
            return
        }
        val ball = session.ballPosition()
        val originalBall = session.cancelledBall()
        val combos = session.committed(System.currentTimeMillis())
        discardPanelForRedraw()
        showLayoutPanel()
        scope.launch {
            // Room and DataStore cannot share a transaction. Persist the ball first, then apply
            // the Room transaction; if Room fails, compensate by restoring the previous ball.
            var ballSaved = false
            val saveFailure = runCatching {
                settingsRepository.setBallPosition(ball.x, ball.y)
                ballSaved = true
                comboRepository.saveAll(combos)
            }.exceptionOrNull()
            val rollbackFailure = if (saveFailure != null && ballSaved) {
                runCatching {
                    settingsRepository.setBallPosition(originalBall.x, originalBall.y)
                }.exceptionOrNull()
            } else {
                null
            }
            if (!layoutCommitGuard.isSaving(sessionId)) return@launch
            if (saveFailure != null) {
                layoutCommitGuard.fail(sessionId)
                val detail = saveFailure.message ?: "本地存储不可用"
                toast(
                    if (rollbackFailure == null) {
                        "布局保存失败：$detail"
                    } else {
                        "布局保存失败且位置回滚失败，请重新进入布局：$detail"
                    },
                )
                // A failed asynchronous save must not override a close request made meanwhile.
                if (panelRequestedOpen && !showLayoutPanel()) {
                    cancelLayoutMode(silent = true)
                    toast("布局面板无法恢复，已返回锁定模式")
                }
                return@launch
            }
            if (!layoutCommitGuard.complete(sessionId)) return@launch
            overlayMode = OverlayMode.LOCKED
            layoutSession = null
            layoutTargetPackage = null
            selectedLayoutComboId = null
            layoutSessionId = null
            closePanel()
            refreshOverlays()
            toast("布局已保存并锁定")
        }
    }

    private fun cancelLayoutMode(silent: Boolean) {
        if (overlayMode != OverlayMode.LAYOUT) return
        if (!layoutCommitGuard.cancel(force = silent)) {
            toast("布局正在保存，暂时不能取消")
            return
        }
        overlayMode = OverlayMode.LOCKED
        layoutSession = null
        layoutTargetPackage = null
        selectedLayoutComboId = null
        layoutSessionId = null
        closePanel()
        refreshOverlays()
        if (!silent) toast("已取消布局并恢复原位置")
    }

    private fun playCombo(combo: Combo) {
        if (overlayMode != OverlayMode.LOCKED) return
        closePanel()
        if (!removeEditor()) {
            toast("旧悬浮窗尚未关闭，已拒绝执行连招")
            return
        }
        if (!showPlaybackStop(combo.repeatCount)) {
            toast("无法显示紧急停止按钮，已拒绝执行连招")
            refreshOverlays()
            return
        }
        removeBall()
        reconcileComboButtons(emptyMap())
        if (!playbackEngine.play(combo)) {
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
                if (playbackStopView?.isAttachedToWindow != true && !showPlaybackStop(state.total)) {
                    playbackEngine.stop("紧急停止按钮不可用，回放已终止")
                    return
                }
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

    private fun checkExecutionGate(combo: Combo): ExecutionGateResult {
        if (!powerManager.isInteractive) return ExecutionGateResult.Blocked("屏幕未点亮")
        val activePackage = foregroundTracker.activePackageName
            ?: return ExecutionGateResult.Blocked(hiddenReasonText(foregroundTracker.hiddenReason))
        if (activePackage != combo.targetPackage) {
            return ExecutionGateResult.Blocked("当前应用不是此连招绑定的游戏")
        }
        val display = stableDisplay()
            ?: return ExecutionGateResult.Blocked("屏幕方向或尺寸尚未稳定")
        if (display.orientation != combo.orientation) {
            return ExecutionGateResult.Blocked("屏幕方向与录制方向不一致")
        }
        return ExecutionGateResult.Allowed(display)
    }

    private fun showPlaybackStop(total: Int): Boolean {
        playbackStopView?.let { existing ->
            if (existing.isAttachedToWindow) {
                pendingOverlayRemovals.remove(existing)
                return true
            }
            playbackStopView = null
            playbackStopParams = null
        }
        val display = displaySnapshot()
        val width = dp(120)
        val height = dp(48)
        val params = overlayParams(width, height).apply {
            x = (display.width - width - dp(20)).coerceAtLeast(0)
            y = dp(20)
        }
        val view = makePill("停止 1/$total", 0xFFD13C4B.toInt(), 14f).apply {
            background = roundedBackground(0xF2D13C4B.toInt(), 24f)
            elevation = dp(10).toFloat()
            contentDescription = "紧急停止回放"
            setOnClickListener { playbackEngine.stop("用户停止") }
        }
        if (!attachOverlayView(view, params)) return false
        playbackStopView = view
        playbackStopParams = params
        return true
    }

    private fun playbackStopCenter(): PointF? {
        val params = playbackStopParams ?: return null
        return PointF(params.x + params.width / 2f, params.y + params.height / 2f)
    }

    private fun startRecording() {
        closePanel()
        if (!removeEditor()) {
            toast("旧悬浮窗尚未关闭，录制未开始")
            return
        }
        if (playbackEngine.state.value is PlaybackState.Running) {
            toast("请先停止当前回放")
            return
        }
        if (recordingState !is RecordingState.Idle) {
            toast("已有录制正在进行")
            return
        }
        val target = foregroundTracker.activePackageName
        val display = stableDisplay()
        if (target == null || display == null) {
            toast("请先确认目标游戏并等待屏幕方向稳定")
            return
        }
        val sessionId = UUID.randomUUID().toString()
        recordingTargetPackage = target
        recordingDisplay = display
        recordingSession = RecordingSession()
        recordingState = RecordingState.Countdown(sessionId, 3)
        removeBall()
        reconcileComboButtons(emptyMap())

        val capture = CaptureOverlayView(
            context = this,
            onCaptured = { captured -> onGestureCaptured(sessionId, captured) },
            onCancelled = { reason -> onCaptureCancelled(sessionId, reason) },
        )
        val captureParams = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        ).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
        }
        if (!attachOverlayView(capture, captureParams)) {
            failRecordingSetup("无法显示录制触摸层，录制未开始")
            return
        }
        recordingView = capture
        recordingParams = captureParams
        if (!showRecordingHud(sessionId, display)) {
            failRecordingSetup("无法显示录制控制栏，录制未开始")
            return
        }

        recordingCountdownJob = scope.launch {
            for (count in 3 downTo 1) {
                val current = recordingState as? RecordingState.Countdown ?: return@launch
                if (current.sessionId != sessionId) return@launch
                recordingState = current.copy(remainingSeconds = count)
                capture.showCountdown(count)
                recordingHudStatus?.text = "${count} 秒后连续录制\n录制时游戏不会响应"
                delay(1_000L)
            }
            if ((recordingState as? RecordingState.Countdown)?.sessionId != sessionId) return@launch
            val startedAt = SystemClock.uptimeMillis()
            recordingSession?.start(startedAt)
            recordingState = RecordingState.Capturing(sessionId, startedAt, 0)
            capture.setArmed(true)
            recordingTimeoutJob = scope.launch {
                delay(60_000L)
                // Finalize in a sibling job: cleanup cancels the timer job, and a timer that
                // finalizes itself would arrive at the suspending Room save already cancelled.
                scope.launch {
                    finalizeRecording(sessionId, save = true, "已达到 60 秒上限并自动保存")
                }
            }
            recordingHudJob = scope.launch {
                while (isActive && (recordingState as? RecordingState.Capturing)?.sessionId == sessionId) {
                    updateRecordingHud()
                    delay(200L)
                }
            }
        }
    }

    private fun showRecordingHud(sessionId: String, display: DisplaySnapshot): Boolean {
        val hud = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedBackground(0xF22A2D3B.toInt(), 16f)
            elevation = dp(12).toFloat()
        }
        val status = panelHint("3 秒后连续录制\n录制时游戏不会响应").apply {
            gravity = Gravity.CENTER
            textSize = 13f
        }
        hud.addView(status)
        hud.addView(panelButton("结束并保存") {
            scope.launch { finalizeRecording(sessionId, save = true, "录制已保存") }
        })
        hud.addView(panelButton("取消录制") {
            scope.launch { finalizeRecording(sessionId, save = false, "录制已取消") }
        })
        val width = dp(176)
        val params = overlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
            x = (display.width - width - dp(20)).coerceAtLeast(0)
            y = dp(20)
        }
        if (!attachOverlayView(hud, params)) return false
        recordingHudView = hud
        recordingHudParams = params
        recordingHudStatus = status
        return true
    }

    private fun failRecordingSetup(message: String) {
        recordingView?.setArmed(false)
        recordingSession?.cancel()
        cleanupRecordingWindows()
        recordingState = RecordingState.Idle
        toast(message)
        refreshOverlays()
    }

    private fun onGestureCaptured(sessionId: String, captured: CapturedGesture) {
        val state = recordingState as? RecordingState.Capturing ?: return
        if (state.sessionId != sessionId) return
        val session = recordingSession ?: return
        when (
            val result = session.append(
                strokes = captured.strokes,
                gestureDownUptimeMs = captured.downUptimeMs,
                gestureUpUptimeMs = captured.upUptimeMs,
            )
        ) {
            is AppendSegmentResult.Appended -> {
                recordingState = state.copy(segmentCount = session.segmentCount)
                updateRecordingHud()
                result.reachedLimit?.let { limit ->
                    val message = when (limit) {
                        RecordingLimit.DURATION -> "已达到 60 秒上限并自动保存"
                        RecordingLimit.SEGMENT_COUNT -> "已达到 200 次触摸上限并自动保存"
                    }
                    scope.launch { finalizeRecording(sessionId, save = true, message) }
                }
            }
            is AppendSegmentResult.LimitReached -> {
                scope.launch { finalizeRecording(sessionId, save = true, "${result.message}，已自动保存") }
            }
            is AppendSegmentResult.Invalid -> {
                scope.launch { finalizeRecording(sessionId, save = false, result.message) }
            }
        }
    }

    private fun onCaptureCancelled(sessionId: String, reason: CaptureCancelReason) {
        val message = when (reason) {
            CaptureCancelReason.MOTION_EVENT_CANCELLED -> "触摸流被系统取消，录制未保存"
        }
        scope.launch { finalizeRecording(sessionId, save = false, message) }
    }

    private fun updateRecordingHud() {
        val state = recordingState as? RecordingState.Capturing ?: return
        val elapsedMs = recordingSession?.elapsedDurationMs(SystemClock.uptimeMillis()) ?: 0L
        recordingHudStatus?.text = String.format(
            Locale.CHINA,
            "录制中 %02d.%01d 秒 · %d/200 段\n游戏不会响应；结束后再回放",
            elapsedMs / 1_000L,
            (elapsedMs % 1_000L) / 100L,
            state.segmentCount,
        )
    }

    private suspend fun finalizeRecording(sessionId: String, save: Boolean, message: String) {
        recordingFinishMutex.withLock {
            val currentId = activeRecordingSessionId() ?: return
            if (currentId != sessionId || recordingState is RecordingState.Finalizing) return
            recordingState = RecordingState.Finalizing(sessionId)
            recordingView?.setArmed(false)
            val session = recordingSession
            val target = recordingTargetPackage
            val display = recordingDisplay
            val timeline = if (save) {
                session?.finish() ?: MacroTimeline()
            } else {
                session?.cancel()
                MacroTimeline()
            }
            cleanupRecordingWindows()

            if (!save || timeline.segments.isEmpty() || target == null || display == null) {
                recordingState = RecordingState.Idle
                toast(if (save && timeline.segments.isEmpty()) "没有录到有效动作" else message)
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
            val saveFailure = runCatching { comboRepository.save(combo) }.exceptionOrNull()
            if (saveFailure != null) {
                recordingState = RecordingState.Idle
                toast("保存失败：${saveFailure.message ?: "本地数据库不可用"}")
                refreshOverlays()
                return
            }
            recordingState = RecordingState.Idle
            toast(message)
            refreshOverlays()
            openComboEditor(combo)
        }
    }

    private fun activeRecordingSessionId(): String? = when (val state = recordingState) {
        is RecordingState.Countdown -> state.sessionId
        is RecordingState.Capturing -> state.sessionId
        is RecordingState.Finalizing -> state.sessionId
        is RecordingState.Failed,
        RecordingState.Idle,
        -> null
    }

    private fun cleanupRecordingWindows() {
        recordingCountdownJob?.cancel()
        recordingTimeoutJob?.cancel()
        recordingHudJob?.cancel()
        recordingCountdownJob = null
        recordingTimeoutJob = null
        recordingHudJob = null
        recordingView?.setArmed(false)
        recordingParams?.let { params ->
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            recordingView?.let { view -> updateOverlayView(view, params) }
        }
        // Retire both windows locally before asking WindowManager to detach them. HyperOS can
        // transiently reject update/remove calls during a display transition; an invisible view
        // cannot keep swallowing the whole screen while the tracked removal retry runs.
        recordingView?.apply {
            visibility = View.GONE
            isClickable = false
            isEnabled = false
        }
        recordingHudView?.apply {
            visibility = View.GONE
            isClickable = false
            isEnabled = false
        }
        removeView(recordingView)
        removeView(recordingHudView)
        recordingView = null
        recordingParams = null
        recordingHudView = null
        recordingHudParams = null
        recordingHudStatus = null
        recordingSession = null
        recordingTargetPackage = null
        recordingDisplay = null
    }

    private fun openComboEditor(combo: Combo) {
        if (recordingState !is RecordingState.Idle ||
            playbackEngine.state.value is PlaybackState.Running ||
            overlayMode == OverlayMode.LAYOUT
        ) return
        closePanel()
        if (!removeEditor()) {
            toast("旧悬浮窗尚未关闭，请稍后重试")
            return
        }
        val display = displaySnapshot()
        var sizeDp = combo.buttonSizeDp
        var opacity = combo.buttonOpacity
        var speed = combo.speed
        var intervalMs = combo.repeatIntervalMs

        val content = panelContainer(horizontalPadding = 16, verticalPadding = 14)
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

        content.addView(panelHint("重复次数（1–999）"))
        val repeatInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(combo.repeatCount.toString())
            setTextColor(Color.WHITE)
            isSingleLine = true
        }
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
        content.addView(panelHint("保存后会进入一次布局模式；完成摆放后统一锁定"))
        content.addView(panelButton("保存并布局") {
            val repeatCount = repeatInput.text.toString().toIntOrNull()?.coerceIn(1, 999) ?: 1
            val updated = combo.copy(
                name = nameInput.text.toString(),
                buttonSizeDp = sizeDp,
                buttonOpacity = opacity,
                speed = speed,
                repeatCount = repeatCount,
                repeatIntervalMs = intervalMs,
                visible = visible.isChecked,
                updatedAt = System.currentTimeMillis(),
            )
            scope.launch {
                comboRepository.save(updated)
                removeEditor()
                toast("连招设置已保存")
                if (updated.visible) beginLayoutMode(updated) else refreshOverlays()
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
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            x = dp(20).coerceAtMost((display.width - width).coerceAtLeast(0))
            y = dp(20)
        }
        if (!attachOverlayView(scroll, params)) {
            toast("编辑窗口暂时无法显示，请稍后重试")
            return
        }
        editorView = scroll
        nameInput.requestFocus()
    }

    private fun showDiagnostics() {
        closePanel()
        if (!removeEditor()) {
            toast("旧悬浮窗尚未关闭，请稍后重试")
            return
        }
        val display = displaySnapshot()
        val content = panelContainer()
        content.addView(panelTitle("最近前台识别日志"))
        val entries = debugDiagnostics?.snapshot().orEmpty().takeLast(200).asReversed()
        content.addView(
            panelHint(
                if (entries.isEmpty()) {
                    "暂无日志"
                } else {
                    entries.joinToString("\n\n") {
                        "${it.recordedAtElapsedRealtimeMs}  ${it.kind}\n" +
                            "${it.packageName.orEmpty()}  ${it.className.orEmpty()}\n" +
                            "${it.previousState} → ${it.currentState}  ${it.decision}\n" +
                            (it.display?.let { d -> "${d.width}×${d.height} ${d.orientation}" }.orEmpty())
                    }
                },
            ),
        )
        content.addView(panelButton("关闭") { removeEditor() })
        val scroll = ScrollView(this).apply { addView(content) }
        val width = dp(344).coerceAtMost(display.width)
        val params = overlayParams(width, (display.height - dp(40)).coerceAtLeast(dp(240))).apply {
            x = dp(20).coerceAtMost((display.width - width).coerceAtLeast(0))
            y = dp(20)
        }
        if (!attachOverlayView(scroll, params)) {
            toast("识别日志窗口暂时无法显示，请稍后重试")
            return
        }
        editorView = scroll
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
            minimumHeight = dp(48)
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
        closePanel()
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
        )
    }

    private fun foregroundSummary(): String {
        val active = foregroundTracker.activePackageName
        val candidate = foregroundTracker.candidatePackageName
        val retained = foregroundTracker.confirmedPackageName
        val packageLabel = active ?: candidate ?: retained ?: "未识别"
        return "当前识别：$packageLabel\n状态：${hiddenReasonText(foregroundTracker.hiddenReason)}"
    }

    private fun hiddenReasonText(reason: HiddenReason): String = when (reason) {
        HiddenReason.NONE -> "已确认，可执行"
        HiddenReason.UNKNOWN_FOREGROUND -> "尚未识别前台应用"
        HiddenReason.CANDIDATE_UNCONFIRMED -> "候选应用等待稳定或手动确认"
        HiddenReason.TEMPORARY_SYSTEM_WINDOW -> "输入法或系统窗口暂时遮挡"
        HiddenReason.OWN_APPLICATION -> "连招助手主界面在前台"
        HiddenReason.DIFFERENT_APPLICATION -> "已进入其他应用"
        HiddenReason.SCREEN_OFF -> "屏幕已关闭"
        HiddenReason.SERVICE_DISCONNECTED -> "无障碍服务已断开"
    }

    private fun currentCombo(id: String): Combo? =
        if (overlayMode == OverlayMode.LAYOUT) layoutSession?.combo(id)
        else latestCombos.firstOrNull { it.id == id }

    private fun stableDisplay(): DisplaySnapshot? =
        (displayTracker.state as? DisplayState.Stable)?.snapshot

    private fun scheduleDisplayStabilization() {
        val token = displayTracker.markUnstable()
        displayStabilizeJob?.cancel()
        displayStabilizeJob = scope.launch {
            delay(100L)
            displayTracker.recordIntermediate(token, displaySnapshot())
            refreshOverlays()
            delay(200L)
            if (!displayTracker.recordStable(token, displaySnapshot())) {
                scheduleDisplayStabilization()
                return@launch
            }
            refreshOverlays()
        }
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

    private fun DisplaySnapshot.toForegroundDisplayInfo(): ForegroundDisplayInfo =
        ForegroundDisplayInfo(
            width = width,
            height = height,
            orientation = when {
                width == height -> ForegroundDisplayOrientation.SQUARE
                width > height -> ForegroundDisplayOrientation.LANDSCAPE
                else -> ForegroundDisplayOrientation.PORTRAIT
            },
        )

    private fun currentInputMethodPackage(): String? = runCatching {
        val flattened = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        ComponentName.unflattenFromString(flattened)?.packageName
    }.getOrNull()

    private fun classifyObservedPackage(value: String?): ForegroundPackageKind {
        val normalized = value?.trim().orEmpty()
        return when {
            normalized.isEmpty() -> ForegroundPackageKind.INVALID
            BuildConfig.DEBUG && debugTouchTestVisible && normalized == packageName ->
                ForegroundPackageKind.EXTERNAL
            normalized == packageName -> ForegroundPackageKind.OWN_APP
            normalized in SetBasedForegroundPackageClassifier.DEFAULT_IGNORED_OVERLAY_PACKAGES ->
                ForegroundPackageKind.IGNORED_OVERLAY
            normalized == currentInputMethodPackage() -> ForegroundPackageKind.TRANSIENT
            normalized in SetBasedForegroundPackageClassifier.DEFAULT_TRANSIENT_PACKAGES ->
                ForegroundPackageKind.TRANSIENT
            else -> ForegroundPackageKind.EXTERNAL
        }
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

    private fun panelParams(
        width: Int,
        display: DisplaySnapshot,
        desiredHeight: Int,
    ): WindowManager.LayoutParams {
        val height = panelHeightPx(
            displayHeightPx = display.height,
            desiredHeightPx = desiredHeight,
            landscape = display.width >= display.height,
        )
        return overlayParams(width, height).apply {
            val ball = ballParams
            x = ((ball?.x ?: 0) + dp(58))
                .coerceIn(0, (display.width - width).coerceAtLeast(0))
            y = (ball?.y ?: dp(80)).coerceIn(0, (display.height - height).coerceAtLeast(0))
        }
    }

    private fun panelWindow(
        title: String,
        body: LinearLayout,
        width: Int,
        display: DisplaySnapshot,
        desiredHeight: Int,
    ): PanelWindow {
        val root = panelContainer(horizontalPadding = 0, verticalPadding = 0)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(8), dp(6))
        }
        header.addView(
            panelTitle(title),
            LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(panelCloseButton())
        root.addView(
            header,
            LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            ),
        )
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            addView(
                body,
                FrameLayout.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        return PanelWindow(root, panelParams(width, display, desiredHeight))
    }

    private fun panelBodyContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(2), dp(10), dp(10))
    }

    private fun panelContainer(
        horizontalPadding: Int = 12,
        verticalPadding: Int = 12,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(horizontalPadding), dp(verticalPadding), dp(horizontalPadding), dp(verticalPadding))
        background = roundedBackground(0xF2292C3A.toInt(), 18f)
        elevation = dp(10).toFloat()
    }

    private fun makePill(text: String, color: Int, textSizeSp: Float): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = textSizeSp
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        minimumWidth = dp(48)
        minimumHeight = dp(48)
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

    private fun panelButton(text: String, action: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        setPadding(dp(12), 0, dp(12), 0)
        setTextColor(Color.WHITE)
        textSize = 13f
        isClickable = true
        isFocusable = true
        // Keep the accessibility hit target at 48dp while drawing a compact 36dp control.
        background = InsetDrawable(
            roundedBackground(0xFF44495D.toInt(), 6f),
            0,
            dp(6),
            0,
            dp(6),
        )
        layoutParams = LinearLayout.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, dp(3), 0, dp(3)) }
        setOnClickListener { action() }
    }

    private fun panelCloseButton(): TextView = TextView(this).apply {
        text = "×"
        gravity = Gravity.CENTER
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        setTextColor(Color.WHITE)
        textSize = 20f
        isClickable = true
        isFocusable = true
        contentDescription = "关闭悬浮面板"
        background = InsetDrawable(
            roundedBackground(0xFF53586B.toInt(), 6f),
            dp(6),
            dp(6),
            dp(6),
            dp(6),
        )
        setOnClickListener { closePanel() }
    }

    private fun comboButtonBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF157BD6.toInt())
            setStroke(dp(1), 0x66FFFFFF)
        }

    private fun layoutRingBackground(selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(3), if (selected) 0xFFFFCE57.toInt() else 0xFF58D7FF.toInt())
        }

    private fun ballBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0xFF6D5CE7.toInt())
        setStroke(
            dp(if (overlayMode == OverlayMode.LAYOUT) 3 else 1),
            if (overlayMode == OverlayMode.LAYOUT) 0xFFFFCE57.toInt() else 0x66FFFFFF,
        )
    }

    private fun roundedBackground(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), 0x33FFFFFF)
    }

    private fun removeComboButton(id: String): Boolean {
        val entry = comboButtons[id] ?: return true
        if (!entry.root.isAttachedToWindow) {
            comboButtons.remove(id, entry)
            return true
        }
        return runCatching {
            windowManager.removeViewImmediate(entry.root)
            comboButtons.remove(id, entry)
            true
        }.getOrElse {
            // A failed removal of a still-attached view remains tracked and will be retried by the
            // next reconciliation. If the platform detached it while throwing, cleanup is done.
            if (!entry.root.isAttachedToWindow) {
                comboButtons.remove(id, entry)
                true
            } else {
                false
            }
        }
    }

    private fun clearComboButtons() {
        overlayCoordinator.clear(onRemoved = ::removeComboButton)
    }

    private fun closePanel() {
        // Logical close happens before the best-effort WindowManager detach. Even if the platform
        // is replacing its window token, refreshOverlays() cannot resurrect a panel the user hid.
        panelRequestedOpen = false
        discardPanelForRedraw()
    }

    private fun discardPanelForRedraw() {
        val view = panelView
        panelView = null
        panelSummaryView = null
        if (view != null) {
            view.visibility = View.GONE
            detachOverlayView(view)
        }
    }

    private fun removeEditor(): Boolean {
        val view = editorView ?: return true
        val removed = detachOverlayView(view)
        if (removed) editorView = null
        return removed
    }

    private fun removeBall(): Boolean {
        val view = ballView ?: return true
        // Fail closed: once recording/playback starts, an old ball must not remain interactive
        // even if WindowManager temporarily rejects physical removal during a display transition.
        ballView = null
        ballParams = null
        ballDragInProgress = false
        view.visibility = View.GONE
        view.isClickable = false
        return detachOverlayView(view)
    }

    private fun removePlaybackStop(): Boolean {
        val view = playbackStopView ?: return true
        val removed = detachOverlayView(view)
        if (removed) {
            playbackStopView = null
            playbackStopParams = null
        }
        return removed
    }

    private fun attachOverlayView(view: View, params: WindowManager.LayoutParams): Boolean {
        if (!::windowManager.isInitialized) return false
        return runCatching {
            windowManager.addView(view, params)
            true
        }.getOrElse {
            // Some WindowManager implementations can attach before surfacing an exception.
            // Retire such a half-attached window before tracking it for cleanup. This is critical
            // for the full-screen capture layer, which otherwise could keep consuming touches.
            if (view.isAttachedToWindow) {
                view.visibility = View.GONE
                view.isClickable = false
                view.isEnabled = false
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                runCatching { windowManager.updateViewLayout(view, params) }
                pendingOverlayRemovals += view
                scheduleRemovalRetry()
            }
            false
        }
    }

    private fun updateOverlayView(view: View, params: WindowManager.LayoutParams): Boolean {
        if (!::windowManager.isInitialized || !view.isAttachedToWindow) return false
        return runCatching {
            windowManager.updateViewLayout(view, params)
            true
        }.getOrDefault(false)
    }

    private fun detachOverlayView(view: View): Boolean {
        if (!view.isAttachedToWindow) {
            pendingOverlayRemovals.remove(view)
            return true
        }
        val removed = runCatching {
            windowManager.removeViewImmediate(view)
            true
        }.getOrDefault(false) || !view.isAttachedToWindow
        if (removed) {
            pendingOverlayRemovals.remove(view)
        } else {
            pendingOverlayRemovals += view
            scheduleRemovalRetry()
        }
        return removed
    }

    private fun scheduleRemovalRetry() {
        if (removalRetryScheduled) return
        removalRetryScheduled = true
        mainHandler.postDelayed(removalRetry, 100L)
    }

    private fun removeView(view: View?) {
        view?.let(::detachOverlayView)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()
}
