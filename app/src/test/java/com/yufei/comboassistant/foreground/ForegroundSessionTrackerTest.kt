package com.yufei.comboassistant.foreground

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundSessionTrackerTest {
    private val classifier = SetBasedForegroundPackageClassifier(
        ownPackageName = OWN_PACKAGE,
        transientPackages = SetBasedForegroundPackageClassifier.DEFAULT_TRANSIENT_PACKAGES + IME_PACKAGE,
    )

    @Test
    fun samePackageActivityChangesRemainOneConfirmedSession() {
        val tracker = tracker()

        tracker.observe(accessibility(GAME_PACKAGE, 100L, className = "LoginActivity"))
        assertEquals(GAME_PACKAGE, tracker.candidatePackageName)
        assertNull(tracker.activePackageName)

        tracker.observe(accessibility(GAME_PACKAGE, 450L, className = "MatchActivity"))

        assertEquals(GAME_PACKAGE, tracker.activePackageName)
        assertEquals(HiddenReason.NONE, tracker.hiddenReason)
        assertEquals(
            ForegroundConfirmationMethod.STABLE_OBSERVATION,
            (tracker.state as ForegroundSessionState.Confirmed).confirmation.method,
        )
    }

    @Test
    fun transientSystemAndImePackagesRetainThenRestoreGame() {
        val tracker = tracker()
        tracker.observe(usage(GAME_PACKAGE, 100L))

        tracker.observe(accessibility("com.android.systemui", 200L))
        assertTrue(tracker.state is ForegroundSessionState.TemporarilyObscured)
        assertEquals(GAME_PACKAGE, tracker.confirmedPackageName)
        assertNull(tracker.activePackageName)

        tracker.observe(accessibility(IME_PACKAGE, 250L))
        assertEquals(GAME_PACKAGE, tracker.confirmedPackageName)

        val restored = tracker.observe(accessibility(GAME_PACKAGE, 300L))
        assertEquals(ForegroundDecision.CONFIRMED_RESTORED, restored.decision)
        assertEquals(GAME_PACKAGE, tracker.activePackageName)
    }

    @Test
    fun hyperOsScreenshotDoesNotObscureOrReplaceConfirmedGame() {
        val tracker = tracker()
        tracker.observe(usage(GAME_PACKAGE, 100L))

        val ignored = tracker.observe(accessibility("com.miui.screenshot", 200L))

        assertEquals(ForegroundDecision.IGNORED_NON_FOREGROUND_OVERLAY, ignored.decision)
        assertTrue(ignored.current is ForegroundSessionState.Confirmed)
        assertEquals(GAME_PACKAGE, tracker.activePackageName)
        assertEquals(HiddenReason.NONE, tracker.hiddenReason)

        // The ignored event must not poison accessibility-conflict tracking. A later usage poll
        // for the real game remains valid rather than getting stuck behind the screenshot package.
        val refreshed = tracker.observe(usage(GAME_PACKAGE, 250L))
        assertEquals(ForegroundDecision.CONFIRMED_USAGE, refreshed.decision)
        assertEquals(GAME_PACKAGE, tracker.activePackageName)
    }

    @Test
    fun hyperOsSystemUiPluginNeverStartsOrReplacesGameCandidate() {
        val tracker = tracker()

        val coldStart = tracker.observe(accessibility("miui.systemui.plugin", 100L))
        assertEquals(ForegroundDecision.IGNORED_NON_FOREGROUND_OVERLAY, coldStart.decision)
        assertTrue(tracker.state is ForegroundSessionState.Unknown)
        assertEquals(ForegroundDecision.NO_CANDIDATE, tracker.settle(1_000L).decision)

        tracker.observe(accessibility(GAME_PACKAGE, 1_100L))
        val ignoredDuringCandidate =
            tracker.observe(accessibility("com.miui.systemui.plugin", 1_250L))
        assertEquals(GAME_PACKAGE, tracker.candidatePackageName)
        assertTrue(ignoredDuringCandidate.current is ForegroundSessionState.Candidate)
        assertTrue(!ignoredDuringCandidate.shouldScheduleCandidateSettlement)

        tracker.observe(accessibility(GAME_PACKAGE, 1_350L))
        tracker.settle(1_400L)
        assertEquals(GAME_PACKAGE, tracker.activePackageName)

        tracker.observe(accessibility("miui.systemui.plugin", 2_000L))
        assertEquals(GAME_PACKAGE, tracker.activePackageName)
        assertEquals(HiddenReason.NONE, tracker.hiddenReason)
    }

    @Test
    fun ignoredHyperOsOverlayDoesNotWeakenImeOrSystemUiGate() {
        val tracker = tracker()
        tracker.observe(usage(GAME_PACKAGE, 100L))
        tracker.observe(accessibility("com.miui.screenshot", 150L))

        tracker.observe(accessibility(IME_PACKAGE, 200L))
        assertTrue(tracker.state is ForegroundSessionState.TemporarilyObscured)
        assertNull(tracker.activePackageName)
        assertEquals(HiddenReason.TEMPORARY_SYSTEM_WINDOW, tracker.hiddenReason)

        tracker.observe(accessibility(GAME_PACKAGE, 300L))
        tracker.observe(accessibility("com.android.systemui", 400L))
        assertTrue(tracker.state is ForegroundSessionState.TemporarilyObscured)
        assertNull(tracker.activePackageName)
    }

    @Test
    fun hyperOsPackageUsageActivityIsARealBlockerNotAnIgnoredOverlay() {
        val tracker = tracker()
        tracker.observe(accessibility(GAME_PACKAGE, 100L))
        tracker.observe(accessibility(GAME_PACKAGE, 450L))

        // It must block even inside the ordinary accessibility/usage conflict grace period.
        val transition = tracker.observe(usage("com.miui.screenshot", 500L))

        assertEquals(ForegroundDecision.TEMPORARILY_OBSCURED, transition.decision)
        assertTrue(tracker.state is ForegroundSessionState.TemporarilyObscured)
        assertNull(tracker.activePackageName)
        assertEquals(GAME_PACKAGE, tracker.confirmedPackageName)
    }

    @Test
    fun usageSystemBlockerAppliesImmediatelyDuringAccessibilityGrace() {
        val tracker = tracker()
        tracker.observe(accessibility(GAME_PACKAGE, 100L))
        tracker.observe(accessibility(GAME_PACKAGE, 450L))
        assertEquals(GAME_PACKAGE, tracker.activePackageName)

        val transition = tracker.observe(usage("android", 500L))

        assertEquals(ForegroundDecision.TEMPORARILY_OBSCURED, transition.decision)
        assertNull(tracker.activePackageName)
    }

    @Test
    fun contentChangeCannotCreateSwitchOrConfirmCandidate() {
        val tracker = tracker()

        val coldContent = tracker.observe(
            accessibility(GAME_PACKAGE, 100L, kind = ForegroundObservationKind.WINDOW_CONTENT_CHANGED),
        )
        assertEquals(
            ForegroundDecision.IGNORED_CONTENT_WITHOUT_FOREGROUND_EVIDENCE,
            coldContent.decision,
        )
        assertTrue(tracker.state is ForegroundSessionState.Unknown)

        tracker.observe(accessibility(GAME_PACKAGE, 200L))
        tracker.observe(
            accessibility(GAME_PACKAGE, 550L, kind = ForegroundObservationKind.WINDOW_CONTENT_CHANGED),
        )
        assertNull(tracker.activePackageName)
        assertEquals(GAME_PACKAGE, tracker.candidatePackageName)

        tracker.observe(accessibility(GAME_PACKAGE, 600L))
        assertEquals(GAME_PACKAGE, tracker.activePackageName)

        tracker.observe(
            accessibility(BROWSER_PACKAGE, 700L, kind = ForegroundObservationKind.WINDOW_CONTENT_CHANGED),
        )
        assertEquals(GAME_PACKAGE, tracker.activePackageName)
        assertNull(tracker.candidatePackageName)
    }

    @Test
    fun oneWindowStateEventNeverConfirmsWithoutSecondEvidence() {
        val tracker = tracker()
        tracker.observe(accessibility(GAME_PACKAGE, 100L))

        val transition = tracker.settle(10_000L)

        assertEquals(ForegroundDecision.CANDIDATE_NOT_READY, transition.decision)
        assertNull(tracker.activePackageName)
        assertEquals(GAME_PACKAGE, tracker.candidatePackageName)
    }

    @Test
    fun usageConflictGraceUsesReceivedTimeRatherThanOldSourceEventTime() {
        val tracker = tracker()
        tracker.observe(accessibility(GAME_PACKAGE, 1_000L, receivedAtMs = 1_000L))

        val duringGrace = tracker.observe(usage(BROWSER_PACKAGE, 900L, receivedAtMs = 1_200L))
        assertEquals(ForegroundDecision.IGNORED_RECENT_ACCESSIBILITY_CONFLICT, duringGrace.decision)

        val afterGrace = tracker.observe(usage(BROWSER_PACKAGE, 900L, receivedAtMs = 3_000L))
        assertEquals(ForegroundDecision.CONFIRMED_USAGE, afterGrace.decision)
        assertEquals(BROWSER_PACKAGE, tracker.activePackageName)
    }

    @Test
    fun realExternalSwitchHidesImmediatelyThenConfirmsNewPackageWhenStable() {
        val tracker = tracker()
        tracker.observe(usage(GAME_PACKAGE, 100L))

        tracker.observe(accessibility(BROWSER_PACKAGE, 200L))
        assertEquals(BROWSER_PACKAGE, tracker.candidatePackageName)
        assertEquals(GAME_PACKAGE, tracker.confirmedPackageName)
        assertEquals(HiddenReason.DIFFERENT_APPLICATION, tracker.hiddenReason)
        assertNull(tracker.activePackageName)

        tracker.observe(accessibility(BROWSER_PACKAGE, 250L))
        val settled = tracker.settle(500L)
        assertEquals(ForegroundDecision.CONFIRMED_STABLE, settled.decision)
        assertEquals(BROWSER_PACKAGE, tracker.activePackageName)
    }

    @Test
    fun manualCandidateConfirmationExpiresOnRealApplicationSwitch() {
        val tracker = tracker()
        tracker.observe(accessibility(GAME_PACKAGE, 100L))

        tracker.confirmCandidate(120L)
        assertEquals(GAME_PACKAGE, tracker.activePackageName)
        assertEquals(
            ForegroundConfirmationMethod.MANUAL,
            (tracker.state as ForegroundSessionState.Confirmed).confirmation.method,
        )

        tracker.observe(accessibility(BROWSER_PACKAGE, 200L))
        assertNull(tracker.confirmedPackageName)

        tracker.observe(accessibility(GAME_PACKAGE, 250L))
        assertEquals(GAME_PACKAGE, tracker.candidatePackageName)
        assertNull(tracker.activePackageName)
    }

    @Test
    fun usageObservationRestoresColdStartWithoutAccessibilityWarmup() {
        val tracker = tracker()

        val transition = tracker.observe(usage(GAME_PACKAGE, 1_000L))

        assertEquals(ForegroundDecision.CONFIRMED_USAGE, transition.decision)
        assertEquals(GAME_PACKAGE, tracker.activePackageName)
        assertEquals(
            ForegroundConfirmationMethod.USAGE_STATS,
            (tracker.state as ForegroundSessionState.Confirmed).confirmation.method,
        )
    }

    @Test
    fun oneOffPackageJitterNeverBecomesConfirmed() {
        val tracker = tracker()
        tracker.observe(accessibility(GAME_PACKAGE, 100L))
        tracker.observe(accessibility(BROWSER_PACKAGE, 200L))
        tracker.observe(accessibility(GAME_PACKAGE, 250L))

        assertEquals(ForegroundDecision.CANDIDATE_NOT_READY, tracker.settle(500L).decision)
        assertNull(tracker.activePackageName)
        assertEquals(GAME_PACKAGE, tracker.candidatePackageName)

        tracker.settle(550L)
        assertNull(tracker.activePackageName)

        tracker.observe(accessibility(GAME_PACKAGE, 600L))
        assertEquals(GAME_PACKAGE, tracker.activePackageName)
    }

    @Test
    fun recentAccessibilityCandidateWinsAgainstConflictingStaleUsagePoll() {
        val tracker = tracker()
        tracker.observe(accessibility(BROWSER_PACKAGE, 1_000L))

        val transition = tracker.observe(usage(GAME_PACKAGE, 1_100L, sourceEventAtMs = 100L))

        assertEquals(ForegroundDecision.IGNORED_RECENT_ACCESSIBILITY_CONFLICT, transition.decision)
        assertEquals(BROWSER_PACKAGE, tracker.candidatePackageName)
    }

    @Test
    fun recentTransientWindowIsNotUndoneByStaleUsagePoll() {
        val tracker = tracker()
        tracker.observe(usage(GAME_PACKAGE, 100L))
        tracker.observe(accessibility("com.android.systemui", 1_000L))

        val transition = tracker.observe(usage(GAME_PACKAGE, 1_100L, sourceEventAtMs = 100L))

        assertEquals(ForegroundDecision.IGNORED_RECENT_ACCESSIBILITY_CONFLICT, transition.decision)
        assertTrue(tracker.state is ForegroundSessionState.TemporarilyObscured)
        assertEquals(GAME_PACKAGE, tracker.confirmedPackageName)
        assertNull(tracker.activePackageName)
    }

    @Test
    fun longLivedInputMethodIsNotUndoneByStaleUsagePoll() {
        val tracker = tracker()
        tracker.observe(usage(GAME_PACKAGE, 100L))
        tracker.observe(accessibility(IME_PACKAGE, 1_000L))

        val transition = tracker.observe(usage(GAME_PACKAGE, 10_000L, sourceEventAtMs = 100L))

        assertEquals(ForegroundDecision.IGNORED_RECENT_ACCESSIBILITY_CONFLICT, transition.decision)
        assertTrue(tracker.state is ForegroundSessionState.TemporarilyObscured)
        assertNull(tracker.activePackageName)
    }

    @Test
    fun newerUsageLifecycleEvidenceRestoresAfterTemporaryObstruction() {
        val tracker = tracker()
        tracker.observe(usage(GAME_PACKAGE, 100L, sourceEventAtMs = 100L))
        tracker.observe(accessibility("com.android.systemui", 1_000L))

        val restored = tracker.observe(
            usage(GAME_PACKAGE, 1_100L, sourceEventAtMs = 1_050L),
        )

        assertEquals(ForegroundDecision.CONFIRMED_USAGE, restored.decision)
        assertEquals(GAME_PACKAGE, tracker.activePackageName)
    }

    @Test
    fun olderUsageObservationCannotOverwriteNewerAccessibilityState() {
        val tracker = tracker()
        tracker.observe(usage(GAME_PACKAGE, 1_000L))
        tracker.observe(accessibility(BROWSER_PACKAGE, 2_000L))

        val transition = tracker.observe(usage(GAME_PACKAGE, 1_500L))

        assertEquals(ForegroundDecision.IGNORED_STALE_OBSERVATION, transition.decision)
        assertEquals(BROWSER_PACKAGE, tracker.candidatePackageName)
        assertEquals(GAME_PACKAGE, tracker.confirmedPackageName)
        assertNull(tracker.activePackageName)
    }

    @Test
    fun systemUiRemainsObscuringEvenAfterUsageConflictGraceExpires() {
        val tracker = tracker()
        tracker.observe(usage(GAME_PACKAGE, 100L))
        tracker.observe(accessibility("com.android.systemui", 1_000L))

        val transition = tracker.observe(usage(GAME_PACKAGE, 60_000L, sourceEventAtMs = 100L))

        assertEquals(ForegroundDecision.IGNORED_RECENT_ACCESSIBILITY_CONFLICT, transition.decision)
        assertTrue(tracker.state is ForegroundSessionState.TemporarilyObscured)
        assertEquals(GAME_PACKAGE, tracker.confirmedPackageName)
        assertNull(tracker.activePackageName)
    }

    @Test
    fun screenOffAndServiceDisconnectClearSession() {
        val tracker = tracker()
        tracker.observe(usage(GAME_PACKAGE, 100L))

        tracker.onScreenOff(200L)
        assertEquals(HiddenReason.SCREEN_OFF, tracker.hiddenReason)
        assertNull(tracker.confirmedPackageName)

        tracker.observe(usage(GAME_PACKAGE, 300L))
        tracker.onServiceDisconnected(400L)
        assertEquals(HiddenReason.SERVICE_DISCONNECTED, tracker.hiddenReason)
        assertNull(tracker.confirmedPackageName)
    }

    @Test
    fun diagnosticLogIsBoundedAndContainsOnlyStructuredMetadata() {
        val log = InMemoryForegroundDiagnosticLog(capacity = 2)
        val tracker = tracker(log)
        tracker.observe(accessibility(GAME_PACKAGE, 100L, "LoginActivity"))
        tracker.observe(accessibility(BROWSER_PACKAGE, 200L, "BrowserActivity"))
        tracker.observe(accessibility(GAME_PACKAGE, 250L, "MatchActivity"))

        val entries = log.snapshot()
        assertEquals(2, entries.size)
        assertEquals(BROWSER_PACKAGE, entries.first().packageName)
        assertEquals("MatchActivity", entries.last().className)
        assertEquals(ForegroundDecision.CANDIDATE_STARTED, entries.last().decision)
    }

    private fun tracker(
        diagnostics: ForegroundDiagnosticLog = NoOpForegroundDiagnosticLog,
    ) = ForegroundSessionTracker(
        classifier = classifier,
        policy = ForegroundTrackerPolicy(candidateStableMs = 300L, usageConflictGraceMs = 1_500L),
        diagnostics = diagnostics,
    )

    private fun accessibility(
        packageName: String,
        timeMs: Long,
        className: String? = null,
        kind: ForegroundObservationKind = ForegroundObservationKind.WINDOW_STATE_CHANGED,
        receivedAtMs: Long = timeMs,
    ) = ForegroundObservation(
        packageName = packageName,
        className = className,
        source = ForegroundObservationSource.ACCESSIBILITY,
        kind = kind,
        observedAtElapsedRealtimeMs = timeMs,
        receivedAtElapsedRealtimeMs = receivedAtMs,
    )

    private fun usage(
        packageName: String,
        timeMs: Long,
        receivedAtMs: Long = timeMs,
        sourceEventAtMs: Long = timeMs,
    ) = ForegroundObservation(
        packageName = packageName,
        source = ForegroundObservationSource.USAGE_STATS,
        kind = ForegroundObservationKind.ACTIVITY_RESUMED,
        observedAtElapsedRealtimeMs = timeMs,
        receivedAtElapsedRealtimeMs = receivedAtMs,
        sourceEventAtElapsedRealtimeMs = sourceEventAtMs,
        sourceEventWallTimeMs = 10_000L + timeMs,
    )

    private companion object {
        const val OWN_PACKAGE = "com.yufei.comboassistant"
        const val GAME_PACKAGE = "com.example.game"
        const val BROWSER_PACKAGE = "com.example.browser"
        const val IME_PACKAGE = "com.example.ime"
    }
}
