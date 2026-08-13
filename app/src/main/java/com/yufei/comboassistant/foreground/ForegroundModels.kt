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
    IGNORED_STALE_OBSERVATION,
    IGNORED_RECENT_ACCESSIBILITY_CONFLICT,
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
}

enum class ForegroundPackageKind {
    INVALID,
    OWN_APP,
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
) : ForegroundPackageClassifier {
    private val transientPackages = transientPackages.mapTo(mutableSetOf()) { it.trim() }

    override fun classify(packageName: String?): ForegroundPackageKind {
        val normalized = packageName?.trim().orEmpty()
        return when {
            normalized.isEmpty() -> ForegroundPackageKind.INVALID
            normalized == ownPackageName -> ForegroundPackageKind.OWN_APP
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
    }
}
