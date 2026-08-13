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
    fun realExternalSwitchHidesImmediatelyThenConfirmsNewPackageWhenStable() {
        val tracker = tracker()
        tracker.observe(usage(GAME_PACKAGE, 100L))

        tracker.observe(accessibility(BROWSER_PACKAGE, 200L))
        assertEquals(BROWSER_PACKAGE, tracker.candidatePackageName)
        assertEquals(GAME_PACKAGE, tracker.confirmedPackageName)
        assertEquals(HiddenReason.DIFFERENT_APPLICATION, tracker.hiddenReason)
        assertNull(tracker.activePackageName)

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
        assertEquals(GAME_PACKAGE, tracker.activePackageName)
    }

    @Test
    fun recentAccessibilityCandidateWinsAgainstConflictingStaleUsagePoll() {
        val tracker = tracker()
        tracker.observe(accessibility(BROWSER_PACKAGE, 1_000L))

        val transition = tracker.observe(usage(GAME_PACKAGE, 1_100L))

        assertEquals(ForegroundDecision.IGNORED_RECENT_ACCESSIBILITY_CONFLICT, transition.decision)
        assertEquals(BROWSER_PACKAGE, tracker.candidatePackageName)
    }

    @Test
    fun recentTransientWindowIsNotUndoneByStaleUsagePoll() {
        val tracker = tracker()
        tracker.observe(usage(GAME_PACKAGE, 100L))
        tracker.observe(accessibility("com.android.systemui", 1_000L))

        val transition = tracker.observe(usage(GAME_PACKAGE, 1_100L))

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

        val transition = tracker.observe(usage(GAME_PACKAGE, 10_000L))

        assertEquals(ForegroundDecision.IGNORED_RECENT_ACCESSIBILITY_CONFLICT, transition.decision)
        assertTrue(tracker.state is ForegroundSessionState.TemporarilyObscured)
        assertNull(tracker.activePackageName)
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

        val transition = tracker.observe(usage(GAME_PACKAGE, 60_000L))

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
    ) = ForegroundObservation(
        packageName = packageName,
        className = className,
        source = ForegroundObservationSource.ACCESSIBILITY,
        kind = ForegroundObservationKind.WINDOW_STATE_CHANGED,
        observedAtElapsedRealtimeMs = timeMs,
    )

    private fun usage(packageName: String, timeMs: Long) = ForegroundObservation(
        packageName = packageName,
        source = ForegroundObservationSource.USAGE_STATS,
        kind = ForegroundObservationKind.ACTIVITY_RESUMED,
        observedAtElapsedRealtimeMs = timeMs,
        sourceEventWallTimeMs = 10_000L + timeMs,
    )

    private companion object {
        const val OWN_PACKAGE = "com.yufei.comboassistant"
        const val GAME_PACKAGE = "com.example.game"
        const val BROWSER_PACKAGE = "com.example.browser"
        const val IME_PACKAGE = "com.example.ime"
    }
}
