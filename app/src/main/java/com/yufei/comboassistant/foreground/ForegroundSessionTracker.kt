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
        val kind = classifier.classify(packageName)
        if (kind == ForegroundPackageKind.INVALID) {
            return transition(observation, state, ForegroundDecision.IGNORED_INVALID_PACKAGE)
        }
        // Some OEM surfaces emit accessibility events under their own package even though they
        // never became foreground. Ignore them before updating accepted/accessibility timestamps;
        // otherwise they can both obscure a confirmed game forever and reject a fresh UsageStats
        // observation as conflicting.
        if (
            kind == ForegroundPackageKind.IGNORED_OVERLAY &&
            observation.source == ForegroundObservationSource.ACCESSIBILITY &&
            observation.kind in ACCESSIBILITY_WINDOW_KINDS
        ) {
            return transition(
                observation,
                state,
                ForegroundDecision.IGNORED_NON_FOREGROUND_OVERLAY,
            )
        }
        if (
            observation.source == ForegroundObservationSource.ACCESSIBILITY &&
            observation.kind == ForegroundObservationKind.WINDOW_CONTENT_CHANGED &&
            state.activePackageName != packageName &&
            state.candidatePackageName != packageName
        ) {
            return transition(
                observation,
                state,
                ForegroundDecision.IGNORED_CONTENT_WITHOUT_FOREGROUND_EVIDENCE,
            )
        }
        if (observation.receivedAtElapsedRealtimeMs < lastAcceptedObservationAtMs) {
            return transition(observation, state, ForegroundDecision.IGNORED_STALE_OBSERVATION)
        }
        if (observation.source == ForegroundObservationSource.USAGE_STATS &&
            kind != ForegroundPackageKind.EXTERNAL
        ) {
            // A UsageStats lifecycle snapshot for our app or a system/OEM surface is a blocker,
            // not a competing external-app guess. Apply it immediately instead of leaving a game
            // active during the accessibility-conflict grace window.
            lastAcceptedObservationAtMs = observation.receivedAtElapsedRealtimeMs
            return when (kind) {
                ForegroundPackageKind.OWN_APP -> observeOwnApp(observation)
                ForegroundPackageKind.TRANSIENT,
                ForegroundPackageKind.IGNORED_OVERLAY,
                -> observeTransient(observation, packageName)
                ForegroundPackageKind.INVALID,
                ForegroundPackageKind.EXTERNAL,
                -> error("handled before this branch")
            }
        }

        if (observation.source == ForegroundObservationSource.ACCESSIBILITY) {
            lastAccessibilityObservationAtMs = observation.receivedAtElapsedRealtimeMs
            lastAccessibilityPackageName = packageName
        }

        if (
            observation.source == ForegroundObservationSource.USAGE_STATS &&
            hasRecentAccessibilityConflict(observation, packageName)
        ) {
            return transition(
                observation,
                state,
                ForegroundDecision.IGNORED_RECENT_ACCESSIBILITY_CONFLICT,
            )
        }

        lastAcceptedObservationAtMs = observation.receivedAtElapsedRealtimeMs
        return when (kind) {
            ForegroundPackageKind.INVALID ->
                transition(observation, state, ForegroundDecision.IGNORED_INVALID_PACKAGE)
            // A real Activity from an OEM package is a blocker, not an ignorable overlay.
            ForegroundPackageKind.IGNORED_OVERLAY -> observeTransient(observation, packageName)
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
        if (
            current.observationCount < 2 ||
            nowElapsedRealtimeMs - current.firstObservedAtElapsedRealtimeMs < policy.candidateStableMs
        ) {
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
        if (observation.kind == ForegroundObservationKind.WINDOW_CONTENT_CHANGED) {
            return when {
                currentConfirmation?.packageName == packageName -> {
                    val refreshed = currentConfirmation.copy(
                        lastObservedAtElapsedRealtimeMs = observation.observedAtElapsedRealtimeMs,
                    )
                    retainedConfirmation = refreshed
                    transition(
                        observation,
                        ForegroundSessionState.Confirmed(refreshed),
                        if (state is ForegroundSessionState.Confirmed) {
                            ForegroundDecision.CONFIRMED_REFRESHED
                        } else {
                            ForegroundDecision.CONFIRMED_RESTORED
                        },
                    )
                }
                else -> transition(
                    observation,
                    state,
                    ForegroundDecision.IGNORED_CONTENT_WITHOUT_FOREGROUND_EVIDENCE,
                )
            }
        }
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
                confirmStable(observation, updated)
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

    private fun confirmStable(
        observation: ForegroundObservation,
        candidate: ForegroundSessionState.Candidate,
    ): ForegroundTransition {
        val confirmed = ConfirmedForegroundPackage(
            packageName = candidate.packageName,
            confirmedAtElapsedRealtimeMs = observation.observedAtElapsedRealtimeMs,
            lastObservedAtElapsedRealtimeMs = candidate.lastObservedAtElapsedRealtimeMs,
            method = ForegroundConfirmationMethod.STABLE_OBSERVATION,
        )
        retainedConfirmation = confirmed
        return transition(
            observation,
            ForegroundSessionState.Confirmed(confirmed),
            ForegroundDecision.CONFIRMED_STABLE,
        )
    }

    private fun hasRecentAccessibilityConflict(
        observation: ForegroundObservation,
        packageName: String,
    ): Boolean {
        if (lastAccessibilityObservationAtMs == Long.MIN_VALUE) return false
        // An own/system window stays authoritative unless Usage lifecycle reconstruction contains
        // a state change newer than that obstruction (for example our Activity stopped and the
        // game resumed, or a screenshot editor paused). Query time alone is never enough.
        val obstructionAtMs = when (val current = state) {
            is ForegroundSessionState.OwnApp -> current.observedAtElapsedRealtimeMs
            is ForegroundSessionState.TemporarilyObscured -> current.observedAtElapsedRealtimeMs
            else -> null
        }
        if (obstructionAtMs != null && lastAccessibilityPackageName != packageName) {
            return (observation.sourceEventAtElapsedRealtimeMs ?: Long.MIN_VALUE) <= obstructionAtMs
        }
        val nowMs = observation.receivedAtElapsedRealtimeMs
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

    private companion object {
        val ACCESSIBILITY_WINDOW_KINDS = setOf(
            ForegroundObservationKind.WINDOW_STATE_CHANGED,
            ForegroundObservationKind.WINDOWS_CHANGED,
            ForegroundObservationKind.WINDOW_CONTENT_CHANGED,
        )
    }
}
