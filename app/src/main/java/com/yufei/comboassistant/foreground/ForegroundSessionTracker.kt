package com.yufei.comboassistant.foreground

data class ForegroundTrackerPolicy(
    val candidateStableMs: Long = 300L,
    val usageConflictGraceMs: Long = 1_500L,
) {
    init {
        require(candidateStableMs >= 0L) { "candidateStableMs must not be negative" }
        require(usageConflictGraceMs >= 0L) { "usageConflictGraceMs must not be negative" }
    }
}

/**
 * Pure transition logic for foreground-package tracking.
 *
 * The tracker deliberately treats a different external package as hidden immediately, but waits
 * for a short stable period before making that package the new confirmed session. Trusted system
 * and input-method packages retain the previous confirmation without exposing it as active.
 */
class ForegroundSessionTracker(
    private val classifier: ForegroundPackageClassifier,
    private val policy: ForegroundTrackerPolicy = ForegroundTrackerPolicy(),
    private val diagnostics: ForegroundDiagnosticLog = NoOpForegroundDiagnosticLog,
) {
    var state: ForegroundSessionState = ForegroundSessionState.Unknown()
        private set

    val activePackageName: String? get() = state.activePackageName
    val confirmedPackageName: String? get() = state.confirmedPackageName
    val candidatePackageName: String? get() = state.candidatePackageName
    val hiddenReason: HiddenReason get() = state.hiddenReason

    private var retainedConfirmation: ConfirmedForegroundPackage? = null
    private var lastAcceptedObservationAtMs: Long = Long.MIN_VALUE
    private var lastAccessibilityObservationAtMs: Long = Long.MIN_VALUE
    private var lastAccessibilityPackageName: String? = null

    @Synchronized
    fun observe(observation: ForegroundObservation): ForegroundTransition {
        val packageName = observation.packageName?.trim().orEmpty()
        if (classifier.classify(packageName) == ForegroundPackageKind.INVALID) {
            return transition(observation, state, ForegroundDecision.IGNORED_INVALID_PACKAGE)
        }
        if (observation.observedAtElapsedRealtimeMs < lastAcceptedObservationAtMs) {
            return transition(observation, state, ForegroundDecision.IGNORED_STALE_OBSERVATION)
        }

        val kind = classifier.classify(packageName)
        if (observation.source == ForegroundObservationSource.ACCESSIBILITY) {
            lastAccessibilityObservationAtMs = observation.observedAtElapsedRealtimeMs
            lastAccessibilityPackageName = packageName
        }

        if (
            observation.source == ForegroundObservationSource.USAGE_STATS &&
            hasRecentAccessibilityConflict(packageName, observation.observedAtElapsedRealtimeMs)
        ) {
            return transition(
                observation,
                state,
                ForegroundDecision.IGNORED_RECENT_ACCESSIBILITY_CONFLICT,
            )
        }

        lastAcceptedObservationAtMs = observation.observedAtElapsedRealtimeMs
        return when (kind) {
            ForegroundPackageKind.INVALID ->
                transition(observation, state, ForegroundDecision.IGNORED_INVALID_PACKAGE)
            ForegroundPackageKind.OWN_APP -> observeOwnApp(observation)
            ForegroundPackageKind.TRANSIENT -> observeTransient(observation, packageName)
            ForegroundPackageKind.EXTERNAL -> observeExternal(observation, packageName)
        }
    }

    @Synchronized
    fun settle(nowElapsedRealtimeMs: Long): ForegroundTransition {
        val current = state as? ForegroundSessionState.Candidate
            ?: return syntheticTransition(
                nowElapsedRealtimeMs,
                state,
                ForegroundDecision.NO_CANDIDATE,
            )
        if (nowElapsedRealtimeMs - current.firstObservedAtElapsedRealtimeMs < policy.candidateStableMs) {
            return syntheticTransition(
                nowElapsedRealtimeMs,
                state,
                ForegroundDecision.CANDIDATE_NOT_READY,
                packageName = current.packageName,
            )
        }
        val confirmed = ConfirmedForegroundPackage(
            packageName = current.packageName,
            confirmedAtElapsedRealtimeMs = nowElapsedRealtimeMs,
            lastObservedAtElapsedRealtimeMs = current.lastObservedAtElapsedRealtimeMs,
            method = ForegroundConfirmationMethod.STABLE_OBSERVATION,
        )
        retainedConfirmation = confirmed
        return syntheticTransition(
            nowElapsedRealtimeMs,
            ForegroundSessionState.Confirmed(confirmed),
            ForegroundDecision.CONFIRMED_STABLE,
            packageName = current.packageName,
        )
    }

    @Synchronized
    fun confirmCandidate(nowElapsedRealtimeMs: Long): ForegroundTransition {
        val current = state as? ForegroundSessionState.Candidate
            ?: return syntheticTransition(
                nowElapsedRealtimeMs,
                state,
                ForegroundDecision.NO_CANDIDATE,
            )
        val confirmed = ConfirmedForegroundPackage(
            packageName = current.packageName,
            confirmedAtElapsedRealtimeMs = nowElapsedRealtimeMs,
            lastObservedAtElapsedRealtimeMs = current.lastObservedAtElapsedRealtimeMs,
            method = ForegroundConfirmationMethod.MANUAL,
        )
        retainedConfirmation = confirmed
        return syntheticTransition(
            nowElapsedRealtimeMs,
            ForegroundSessionState.Confirmed(confirmed),
            ForegroundDecision.CONFIRMED_MANUALLY,
            packageName = current.packageName,
            kind = ForegroundObservationKind.MANUAL_CONFIRMATION,
            source = ForegroundObservationSource.MANUAL,
        )
    }

    @Synchronized
    fun onScreenOff(nowElapsedRealtimeMs: Long): ForegroundTransition {
        clearSession()
        return syntheticTransition(
            nowElapsedRealtimeMs,
            ForegroundSessionState.Unknown(HiddenReason.SCREEN_OFF),
            ForegroundDecision.SCREEN_OFF,
            kind = ForegroundObservationKind.SCREEN_OFF,
            source = ForegroundObservationSource.LIFECYCLE,
        )
    }

    @Synchronized
    fun onServiceDisconnected(nowElapsedRealtimeMs: Long): ForegroundTransition {
        clearSession()
        return syntheticTransition(
            nowElapsedRealtimeMs,
            ForegroundSessionState.Unknown(HiddenReason.SERVICE_DISCONNECTED),
            ForegroundDecision.SERVICE_DISCONNECTED,
            kind = ForegroundObservationKind.SERVICE_DISCONNECTED,
            source = ForegroundObservationSource.LIFECYCLE,
        )
    }

    private fun observeOwnApp(observation: ForegroundObservation): ForegroundTransition =
        transition(
            observation,
            ForegroundSessionState.OwnApp(
                confirmation = retainedConfirmation,
                observedAtElapsedRealtimeMs = observation.observedAtElapsedRealtimeMs,
            ),
            ForegroundDecision.OWN_APP_OPENED,
        )

    private fun observeTransient(
        observation: ForegroundObservation,
        packageName: String,
    ): ForegroundTransition = transition(
        observation,
        ForegroundSessionState.TemporarilyObscured(
            obscuringPackageName = packageName,
            confirmation = retainedConfirmation,
            observedAtElapsedRealtimeMs = observation.observedAtElapsedRealtimeMs,
        ),
        ForegroundDecision.TEMPORARILY_OBSCURED,
    )

    private fun observeExternal(
        observation: ForegroundObservation,
        packageName: String,
    ): ForegroundTransition {
        if (observation.source == ForegroundObservationSource.USAGE_STATS) {
            val confirmed = ConfirmedForegroundPackage(
                packageName = packageName,
                confirmedAtElapsedRealtimeMs = observation.observedAtElapsedRealtimeMs,
                lastObservedAtElapsedRealtimeMs = observation.observedAtElapsedRealtimeMs,
                method = ForegroundConfirmationMethod.USAGE_STATS,
            )
            retainedConfirmation = confirmed
            return transition(
                observation,
                ForegroundSessionState.Confirmed(confirmed),
                ForegroundDecision.CONFIRMED_USAGE,
            )
        }

        val currentConfirmation = retainedConfirmation
        if (currentConfirmation?.packageName == packageName) {
            val refreshed = currentConfirmation.copy(
                lastObservedAtElapsedRealtimeMs = observation.observedAtElapsedRealtimeMs,
            )
            retainedConfirmation = refreshed
            val decision = if (state is ForegroundSessionState.Confirmed) {
                ForegroundDecision.CONFIRMED_REFRESHED
            } else {
                ForegroundDecision.CONFIRMED_RESTORED
            }
            return transition(
                observation,
                ForegroundSessionState.Confirmed(refreshed),
                decision,
            )
        }

        val currentCandidate = state as? ForegroundSessionState.Candidate
        if (currentCandidate?.packageName == packageName) {
            val updated = currentCandidate.copy(
                lastObservedAtElapsedRealtimeMs = observation.observedAtElapsedRealtimeMs,
                observationCount = currentCandidate.observationCount + 1,
            )
            return if (
                observation.observedAtElapsedRealtimeMs - updated.firstObservedAtElapsedRealtimeMs >=
                policy.candidateStableMs
            ) {
                val confirmed = ConfirmedForegroundPackage(
                    packageName = updated.packageName,
                    confirmedAtElapsedRealtimeMs = observation.observedAtElapsedRealtimeMs,
                    lastObservedAtElapsedRealtimeMs = updated.lastObservedAtElapsedRealtimeMs,
                    method = ForegroundConfirmationMethod.STABLE_OBSERVATION,
                )
                retainedConfirmation = confirmed
                transition(
                    observation,
                    ForegroundSessionState.Confirmed(confirmed),
                    ForegroundDecision.CONFIRMED_STABLE,
                )
            } else {
                transition(observation, updated, ForegroundDecision.CANDIDATE_UPDATED)
            }
        }

        // A manual confirmation is only valid for the current foreground session. A real switch
        // to a different external package expires it instead of retaining it for auto-restore.
        if (currentConfirmation?.method == ForegroundConfirmationMethod.MANUAL) {
            retainedConfirmation = null
        }
        val candidate = ForegroundSessionState.Candidate(
            packageName = packageName,
            firstObservedAtElapsedRealtimeMs = observation.observedAtElapsedRealtimeMs,
            lastObservedAtElapsedRealtimeMs = observation.observedAtElapsedRealtimeMs,
            observationCount = 1,
            previousConfirmation = retainedConfirmation,
        )
        return transition(observation, candidate, ForegroundDecision.CANDIDATE_STARTED)
    }

    private fun hasRecentAccessibilityConflict(packageName: String, nowMs: Long): Boolean {
        if (lastAccessibilityObservationAtMs == Long.MIN_VALUE) return false
        // A trusted transient/own-app observation remains authoritative until Accessibility
        // reports the external package again. A timer must not let stale UsageEvents expose
        // controls through an IME or SystemUI window.
        if (
            state is ForegroundSessionState.OwnApp ||
            state is ForegroundSessionState.TemporarilyObscured
        ) return lastAccessibilityPackageName != packageName
        if (nowMs - lastAccessibilityObservationAtMs > policy.usageConflictGraceMs) return false
        val accessibilityPackage = when (val current = state) {
            is ForegroundSessionState.Candidate -> current.packageName
            is ForegroundSessionState.Confirmed -> current.confirmation.packageName
            is ForegroundSessionState.TemporarilyObscured -> current.obscuringPackageName
            // An external UsageStats result is necessarily stale while our own app is the latest
            // accessibility foreground. Cold-start recovery happens from Unknown instead.
            is ForegroundSessionState.OwnApp -> return true
            is ForegroundSessionState.Unknown -> null
        }
        return accessibilityPackage != null && accessibilityPackage != packageName
    }

    private fun clearSession() {
        retainedConfirmation = null
        lastAcceptedObservationAtMs = Long.MIN_VALUE
        lastAccessibilityObservationAtMs = Long.MIN_VALUE
        lastAccessibilityPackageName = null
    }

    private fun transition(
        observation: ForegroundObservation,
        next: ForegroundSessionState,
        decision: ForegroundDecision,
    ): ForegroundTransition {
        val previous = state
        state = next
        val result = ForegroundTransition(previous, next, decision)
        diagnostics.record(
            ForegroundDiagnosticEntry(
                recordedAtElapsedRealtimeMs = observation.observedAtElapsedRealtimeMs,
                source = observation.source,
                kind = observation.kind,
                packageName = observation.packageName,
                className = observation.className,
                display = observation.display,
                previousState = previous.stateName(),
                currentState = next.stateName(),
                decision = decision,
            ),
        )
        return result
    }

    private fun syntheticTransition(
        nowElapsedRealtimeMs: Long,
        next: ForegroundSessionState,
        decision: ForegroundDecision,
        packageName: String? = null,
        kind: ForegroundObservationKind = ForegroundObservationKind.MANUAL_CONFIRMATION,
        source: ForegroundObservationSource = ForegroundObservationSource.MANUAL,
    ): ForegroundTransition = transition(
        ForegroundObservation(
            packageName = packageName,
            source = source,
            kind = kind,
            observedAtElapsedRealtimeMs = nowElapsedRealtimeMs,
        ),
        next,
        decision,
    )

    private fun ForegroundSessionState.stateName(): String = when (this) {
        is ForegroundSessionState.Unknown -> "Unknown"
        is ForegroundSessionState.Candidate -> "Candidate"
        is ForegroundSessionState.Confirmed -> "Confirmed"
        is ForegroundSessionState.TemporarilyObscured -> "TemporarilyObscured"
        is ForegroundSessionState.OwnApp -> "OwnApp"
    }
}
