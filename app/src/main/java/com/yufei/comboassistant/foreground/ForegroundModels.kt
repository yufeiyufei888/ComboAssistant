package com.yufei.comboassistant.foreground

/** Where a foreground observation came from. */
enum class ForegroundObservationSource {
    ACCESSIBILITY,
    USAGE_STATS,
    MANUAL,
    LIFECYCLE,
}

/**
 * Platform-neutral event kinds understood by [ForegroundSessionTracker].
 *
 * Accessibility event integer constants deliberately stay outside the tracker so its transition
 * rules can be tested on the local JVM.
 */
enum class ForegroundObservationKind {
    WINDOW_STATE_CHANGED,
    WINDOWS_CHANGED,
    WINDOW_CONTENT_CHANGED,
    ACTIVITY_RESUMED,
    MOVE_TO_FOREGROUND,
    MANUAL_CONFIRMATION,
    SCREEN_OFF,
    SERVICE_DISCONNECTED,
}

enum class ForegroundDisplayOrientation {
    UNKNOWN,
    PORTRAIT,
    LANDSCAPE,
    SQUARE,
}

data class ForegroundDisplayInfo(
    val width: Int,
    val height: Int,
    val orientation: ForegroundDisplayOrientation,
)

/**
 * A deliberately small observation. Window text and view content are never accepted or retained.
 */
data class ForegroundObservation(
    val packageName: String?,
    val className: String? = null,
    val source: ForegroundObservationSource,
    val kind: ForegroundObservationKind,
    val observedAtElapsedRealtimeMs: Long,
    /** When this process received/sampled the observation; source event time remains above. */
    val receivedAtElapsedRealtimeMs: Long = observedAtElapsedRealtimeMs,
    /** Monotonic time of the lifecycle change proving a UsageStats state snapshot, if available. */
    val sourceEventAtElapsedRealtimeMs: Long? = null,
    val sourceEventWallTimeMs: Long? = null,
    val display: ForegroundDisplayInfo? = null,
)

enum class HiddenReason {
    NONE,
    UNKNOWN_FOREGROUND,
    CANDIDATE_UNCONFIRMED,
    TEMPORARY_SYSTEM_WINDOW,
    OWN_APPLICATION,
    DIFFERENT_APPLICATION,
    SCREEN_OFF,
    SERVICE_DISCONNECTED,
}

enum class ForegroundConfirmationMethod {
    STABLE_OBSERVATION,
    USAGE_STATS,
    MANUAL,
}

data class ConfirmedForegroundPackage(
    val packageName: String,
    val confirmedAtElapsedRealtimeMs: Long,
    val lastObservedAtElapsedRealtimeMs: Long,
    val method: ForegroundConfirmationMethod,
)

sealed interface ForegroundSessionState {
    /** Package that is safe to expose to playback and overlay filtering right now. */
    val activePackageName: String?

    /** Retained confirmed package, including while a trusted transient window obscures it. */
    val confirmedPackageName: String?

    val candidatePackageName: String?
    val hiddenReason: HiddenReason

    data class Unknown(
        override val hiddenReason: HiddenReason = HiddenReason.UNKNOWN_FOREGROUND,
    ) : ForegroundSessionState {
        override val activePackageName: String? = null
        override val confirmedPackageName: String? = null
        override val candidatePackageName: String? = null
    }

    data class Candidate(
        val packageName: String,
        val firstObservedAtElapsedRealtimeMs: Long,
        val lastObservedAtElapsedRealtimeMs: Long,
        val observationCount: Int,
        val previousConfirmation: ConfirmedForegroundPackage? = null,
    ) : ForegroundSessionState {
        override val activePackageName: String? = null
        override val confirmedPackageName: String? = previousConfirmation?.packageName
        override val candidatePackageName: String = packageName
        override val hiddenReason: HiddenReason =
            if (previousConfirmation != null && previousConfirmation.packageName != packageName) {
                HiddenReason.DIFFERENT_APPLICATION
            } else {
                HiddenReason.CANDIDATE_UNCONFIRMED
            }
    }

    data class Confirmed(
        val confirmation: ConfirmedForegroundPackage,
    ) : ForegroundSessionState {
        override val activePackageName: String = confirmation.packageName
        override val confirmedPackageName: String = confirmation.packageName
        override val candidatePackageName: String? = null
        override val hiddenReason: HiddenReason = HiddenReason.NONE
    }

    data class TemporarilyObscured(
        val obscuringPackageName: String,
        val confirmation: ConfirmedForegroundPackage?,
        val observedAtElapsedRealtimeMs: Long,
    ) : ForegroundSessionState {
        override val activePackageName: String? = null
        override val confirmedPackageName: String? = confirmation?.packageName
        override val candidatePackageName: String? = null
        override val hiddenReason: HiddenReason = HiddenReason.TEMPORARY_SYSTEM_WINDOW
    }

    data class OwnApp(
        val confirmation: ConfirmedForegroundPackage?,
        val observedAtElapsedRealtimeMs: Long,
    ) : ForegroundSessionState {
        override val activePackageName: String? = null
        override val confirmedPackageName: String? = confirmation?.packageName
        override val candidatePackageName: String? = null
        override val hiddenReason: HiddenReason = HiddenReason.OWN_APPLICATION
    }
}

enum class ForegroundDecision {
    IGNORED_INVALID_PACKAGE,
    IGNORED_NON_FOREGROUND_OVERLAY,
    IGNORED_STALE_OBSERVATION,
    IGNORED_RECENT_ACCESSIBILITY_CONFLICT,
    IGNORED_CONTENT_WITHOUT_FOREGROUND_EVIDENCE,
    CANDIDATE_STARTED,
    CANDIDATE_UPDATED,
    CANDIDATE_NOT_READY,
    CONFIRMED_STABLE,
    CONFIRMED_USAGE,
    CONFIRMED_MANUALLY,
    CONFIRMED_REFRESHED,
    CONFIRMED_RESTORED,
    TEMPORARILY_OBSCURED,
    OWN_APP_OPENED,
    NO_CANDIDATE,
    SCREEN_OFF,
    SERVICE_DISCONNECTED,
}

data class ForegroundTransition(
    val previous: ForegroundSessionState,
    val current: ForegroundSessionState,
    val decision: ForegroundDecision,
) {
    val changed: Boolean get() = previous != current

    /** Only a real candidate observation may (re)schedule its stability deadline. */
    val shouldScheduleCandidateSettlement: Boolean
        get() = current is ForegroundSessionState.Candidate &&
            decision == ForegroundDecision.CANDIDATE_STARTED
}

enum class ForegroundPackageKind {
    INVALID,
    OWN_APP,
    /**
     * A package that may emit accessibility window events without becoming foreground itself.
     *
     * This is deliberately different from [TRANSIENT]: a transient IME/SystemUI window blocks
     * execution, whereas a non-interactive screenshot/plugin event must not replace or obscure
     * the already observed foreground application.
     */
    IGNORED_OVERLAY,
    TRANSIENT,
    EXTERNAL,
}

fun interface ForegroundPackageClassifier {
    fun classify(packageName: String?): ForegroundPackageKind
}

/** Pure set-based classifier. Callers may include the currently selected input-method package. */
class SetBasedForegroundPackageClassifier(
    private val ownPackageName: String,
    transientPackages: Set<String>,
    ignoredOverlayPackages: Set<String> = DEFAULT_IGNORED_OVERLAY_PACKAGES,
) : ForegroundPackageClassifier {
    private val transientPackages = transientPackages.mapTo(mutableSetOf()) { it.trim() }
    private val ignoredOverlayPackages = ignoredOverlayPackages.mapTo(mutableSetOf()) { it.trim() }

    override fun classify(packageName: String?): ForegroundPackageKind {
        val normalized = packageName?.trim().orEmpty()
        return when {
            normalized.isEmpty() -> ForegroundPackageKind.INVALID
            normalized == ownPackageName -> ForegroundPackageKind.OWN_APP
            normalized in ignoredOverlayPackages -> ForegroundPackageKind.IGNORED_OVERLAY
            normalized in transientPackages -> ForegroundPackageKind.TRANSIENT
            else -> ForegroundPackageKind.EXTERNAL
        }
    }

    companion object {
        val DEFAULT_TRANSIENT_PACKAGES: Set<String> = setOf(
            "android",
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        )

        /**
         * OEM packages observed on HyperOS that emit window events for a screenshot/system UI
         * surface while the underlying game remains foreground. Keep this exact and conservative:
         * real third-party packages must continue through the normal candidate/switch path.
         */
        val DEFAULT_IGNORED_OVERLAY_PACKAGES: Set<String> = setOf(
            "com.miui.screenshot",
            "miui.systemui.plugin",
            "com.miui.systemui.plugin",
        )
    }
}
