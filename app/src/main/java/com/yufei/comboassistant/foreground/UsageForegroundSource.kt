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
        const val DEFAULT_LOOKBACK_MS: Long = 15_000L
        const val MAX_LOOKBACK_MS: Long = 60_000L
    }
}

/**
 * Queries only a recent UsageEvents window and retains only ACTIVITY_RESUMED or the legacy
 * MOVE_TO_FOREGROUND event. Results are never persisted.
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
        if (!hasUsageAccess()) return@withContext null

        val boundedLookback = lookbackMs.coerceIn(1L, UsageForegroundSource.MAX_LOOKBACK_MS)
        val nowWallMs = wallClockMs()
        val startWallMs = nowWallMs - boundedLookback
        val events = runCatching {
            usageStatsManager.queryEvents(startWallMs, nowWallMs)
        }.getOrNull() ?: return@withContext null

        val event = UsageEvents.Event()
        var latestPackage: String? = null
        var latestClass: String? = null
        var latestKind: ForegroundObservationKind? = null
        var latestWallTimeMs = Long.MIN_VALUE

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val kind = event.toSupportedForegroundKind() ?: continue
            val packageName = event.packageName?.trim().orEmpty()
            if (packageName.isEmpty()) continue
            if (event.timeStamp !in startWallMs..nowWallMs || event.timeStamp < latestWallTimeMs) continue

            latestPackage = packageName
            latestClass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) event.className else null
            latestKind = kind
            latestWallTimeMs = event.timeStamp
        }

        val packageName = latestPackage ?: return@withContext null
        ForegroundObservation(
            packageName = packageName,
            className = latestClass,
            source = ForegroundObservationSource.USAGE_STATS,
            kind = latestKind ?: return@withContext null,
            // Preserve the event's actual age in the monotonic clock domain. Stamping a stale
            // UsageEvents row with query time could overrule a newer accessibility observation.
            observedAtElapsedRealtimeMs = eventElapsedRealtimeMs(
                nowElapsedRealtimeMs = elapsedRealtimeMs(),
                nowWallTimeMs = nowWallMs,
                eventWallTimeMs = latestWallTimeMs,
                maxAgeMs = boundedLookback,
            ),
            sourceEventWallTimeMs = latestWallTimeMs,
        )
    }

    @Suppress("DEPRECATION")
    private fun UsageEvents.Event.toSupportedForegroundKind(): ForegroundObservationKind? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            eventType == UsageEvents.Event.ACTIVITY_RESUMED ->
            ForegroundObservationKind.ACTIVITY_RESUMED
        eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ->
            ForegroundObservationKind.MOVE_TO_FOREGROUND
        else -> null
    }

    companion object {
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
