package com.yufei.comboassistant.foreground

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface UsageForegroundSource {
    fun hasUsageAccess(): Boolean

    suspend fun latestForegroundObservation(
        lookbackMs: Long = DEFAULT_LOOKBACK_MS,
    ): ForegroundObservation?

    companion object {
        /** Subsequent polls only need a small overlap; the first poll reconstructs current state. */
        const val DEFAULT_LOOKBACK_MS: Long = 60_000L
        const val MAX_LOOKBACK_MS: Long = 60_000L
        const val INITIAL_RECONSTRUCTION_MS: Long = 24L * 60L * 60L * 1_000L
    }
}

internal enum class UsageLifecycleTransition { FOREGROUND, BACKGROUND }

internal data class UsageLifecycleRecord(
    val packageName: String,
    val className: String?,
    val eventWallTimeMs: Long,
    val transition: UsageLifecycleTransition,
    val foregroundKind: ForegroundObservationKind,
)

internal data class UsageForegroundSnapshot(
    val packageName: String,
    val className: String?,
    val stateEventWallTimeMs: Long,
    val kind: ForegroundObservationKind,
)

internal fun shouldReconstructUsageQuery(
    initialized: Boolean,
    nowWallTimeMs: Long,
    lastQueryEndWallTimeMs: Long,
    incrementalLookbackMs: Long,
): Boolean = !initialized ||
    nowWallTimeMs < lastQueryEndWallTimeMs ||
    nowWallTimeMs - lastQueryEndWallTimeMs > incrementalLookbackMs

/**
 * Rebuilds the currently resumed Activity set instead of mistaking the latest historical RESUMED
 * row for the current application. More than one active package is deliberately ambiguous.
 */
internal class UsageForegroundReducer {
    private data class ActivityKey(val packageName: String, val className: String?)

    private val activeActivities = linkedMapOf<ActivityKey, UsageLifecycleRecord>()
    private var lastLifecycleEventWallTimeMs = Long.MIN_VALUE

    val isAmbiguous: Boolean
        get() = activeActivities.values.mapTo(mutableSetOf()) { it.packageName }.size > 1

    val latestEventWallTimeMs: Long?
        get() = activeActivities.values.maxOfOrNull { it.eventWallTimeMs }

    fun clear() {
        activeActivities.clear()
        lastLifecycleEventWallTimeMs = Long.MIN_VALUE
    }

    fun accept(record: UsageLifecycleRecord) {
        val packageName = record.packageName.trim()
        if (packageName.isEmpty()) return
        lastLifecycleEventWallTimeMs = maxOf(lastLifecycleEventWallTimeMs, record.eventWallTimeMs)
        val className = record.className?.trim()?.takeIf(String::isNotEmpty)
        val key = ActivityKey(packageName, className)
        when (record.transition) {
            UsageLifecycleTransition.FOREGROUND -> {
                // A class-less legacy event represents the whole package.
                if (className == null) {
                    activeActivities.keys.filter { it.packageName == packageName }
                        .forEach(activeActivities::remove)
                }
                activeActivities[key] = record.copy(packageName = packageName, className = className)
            }
            UsageLifecycleTransition.BACKGROUND -> {
                if (className == null) {
                    activeActivities.keys.filter { it.packageName == packageName }
                        .forEach(activeActivities::remove)
                } else {
                    activeActivities.remove(key)
                }
            }
        }
    }

    fun snapshot(): UsageForegroundSnapshot? {
        val packages = activeActivities.values.groupBy { it.packageName }
        if (packages.size != 1) return null
        val latest = packages.values.single().maxByOrNull { it.eventWallTimeMs } ?: return null
        return UsageForegroundSnapshot(
            packageName = latest.packageName,
            className = latest.className,
            stateEventWallTimeMs = maxOf(latest.eventWallTimeMs, lastLifecycleEventWallTimeMs),
            kind = latest.foregroundKind,
        )
    }
}

/**
 * Reconstructs Activity lifecycle state on the first query, then incrementally updates it. Results
 * are kept only in memory and contain no window text, usage duration, or account data.
 */
class AndroidUsageForegroundSource(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
) : UsageForegroundSource {
    private val appContext = context.applicationContext
    private val usageStatsManager =
        appContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val appOpsManager = appContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    private val reducer = UsageForegroundReducer()
    private var queryInitialized = false
    private var lastQueryEndWallTimeMs = Long.MIN_VALUE

    override fun hasUsageAccess(): Boolean = runCatching {
        appOpsManager.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            appContext.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    override suspend fun latestForegroundObservation(
        lookbackMs: Long,
    ): ForegroundObservation? = withContext(dispatcher) {
        if (!hasUsageAccess()) {
            resetQueryState()
            return@withContext null
        }

        val nowWallMs = wallClockMs()
        val nowElapsedMs = elapsedRealtimeMs()
        val boundedIncrementalLookback = lookbackMs.coerceIn(1L, UsageForegroundSource.MAX_LOOKBACK_MS)
        val reconstruct = shouldReconstructUsageQuery(
            initialized = queryInitialized,
            nowWallTimeMs = nowWallMs,
            lastQueryEndWallTimeMs = lastQueryEndWallTimeMs,
            incrementalLookbackMs = boundedIncrementalLookback,
        )
        val startWallMs = if (reconstruct) {
            reducer.clear()
            val availableThisBoot = nowElapsedMs.coerceIn(1L, UsageForegroundSource.INITIAL_RECONSTRUCTION_MS)
            nowWallMs - availableThisBoot
        } else {
            maxOf(
                nowWallMs - boundedIncrementalLookback,
                lastQueryEndWallTimeMs - QUERY_OVERLAP_MS,
            )
        }
        val events = runCatching {
            usageStatsManager.queryEvents(startWallMs, nowWallMs)
        }.getOrNull() ?: return@withContext null

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.timeStamp !in startWallMs..nowWallMs) continue
            event.toLifecycleRecord()?.let(reducer::accept)
        }
        queryInitialized = true
        lastQueryEndWallTimeMs = nowWallMs

        val snapshot = reducer.snapshot()
        if (snapshot == null && !reducer.isAmbiguous) return@withContext null
        ForegroundObservation(
            packageName = snapshot?.packageName ?: AMBIGUOUS_TRANSIENT_PACKAGE,
            className = snapshot?.className,
            source = ForegroundObservationSource.USAGE_STATS,
            kind = snapshot?.kind ?: ForegroundObservationKind.ACTIVITY_RESUMED,
            // This is a current-state sample. Keep the historical lifecycle time separately for
            // diagnostics and the screen-off boundary, but use sample time for cross-source order.
            observedAtElapsedRealtimeMs = nowElapsedMs,
            receivedAtElapsedRealtimeMs = nowElapsedMs,
            sourceEventAtElapsedRealtimeMs = eventElapsedRealtimeMs(
                nowElapsedRealtimeMs = nowElapsedMs,
                nowWallTimeMs = nowWallMs,
                eventWallTimeMs = snapshot?.stateEventWallTimeMs
                    ?: reducer.latestEventWallTimeMs
                    ?: nowWallMs,
                maxAgeMs = UsageForegroundSource.INITIAL_RECONSTRUCTION_MS,
            ),
            sourceEventWallTimeMs = snapshot?.stateEventWallTimeMs
                ?: reducer.latestEventWallTimeMs,
        )
    }

    private fun resetQueryState() {
        reducer.clear()
        queryInitialized = false
        lastQueryEndWallTimeMs = Long.MIN_VALUE
    }

    @Suppress("DEPRECATION")
    private fun UsageEvents.Event.toLifecycleRecord(): UsageLifecycleRecord? {
        val packageName = packageName?.toString()?.trim().orEmpty()
        if (packageName.isEmpty()) return null
        val className = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            className?.toString()
        } else {
            null
        }
        val transition: UsageLifecycleTransition
        val foregroundKind: ForegroundObservationKind
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    transition = UsageLifecycleTransition.FOREGROUND
                    foregroundKind = ForegroundObservationKind.ACTIVITY_RESUMED
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED,
                -> {
                    transition = UsageLifecycleTransition.BACKGROUND
                    foregroundKind = ForegroundObservationKind.ACTIVITY_RESUMED
                }
                else -> return null
            }
        } else {
            when (eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    transition = UsageLifecycleTransition.FOREGROUND
                    foregroundKind = ForegroundObservationKind.MOVE_TO_FOREGROUND
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    transition = UsageLifecycleTransition.BACKGROUND
                    foregroundKind = ForegroundObservationKind.MOVE_TO_FOREGROUND
                }
                else -> return null
            }
        }
        return UsageLifecycleRecord(
            packageName = packageName,
            className = className,
            eventWallTimeMs = timeStamp,
            transition = transition,
            foregroundKind = foregroundKind,
        )
    }

    private companion object {
        const val QUERY_OVERLAP_MS = 2_000L
        const val AMBIGUOUS_TRANSIENT_PACKAGE = "android"

        internal fun eventElapsedRealtimeMs(
            nowElapsedRealtimeMs: Long,
            nowWallTimeMs: Long,
            eventWallTimeMs: Long,
            maxAgeMs: Long,
        ): Long {
            val ageMs = (nowWallTimeMs - eventWallTimeMs).coerceIn(0L, maxAgeMs)
            return (nowElapsedRealtimeMs - ageMs).coerceAtLeast(0L)
        }
    }
}
