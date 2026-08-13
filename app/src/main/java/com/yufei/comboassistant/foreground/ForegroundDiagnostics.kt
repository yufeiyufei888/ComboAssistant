package com.yufei.comboassistant.foreground

data class ForegroundDiagnosticEntry(
    val recordedAtElapsedRealtimeMs: Long,
    val source: ForegroundObservationSource,
    val kind: ForegroundObservationKind,
    val packageName: String?,
    val className: String?,
    val display: ForegroundDisplayInfo?,
    val previousState: String,
    val currentState: String,
    val decision: ForegroundDecision,
)

fun interface ForegroundDiagnosticLog {
    fun record(entry: ForegroundDiagnosticEntry)
}

object NoOpForegroundDiagnosticLog : ForegroundDiagnosticLog {
    override fun record(entry: ForegroundDiagnosticEntry) = Unit
}

/**
 * Process-only ring buffer intended for debug builds. It has no persistence or network behavior.
 */
class InMemoryForegroundDiagnosticLog(
    private val capacity: Int = 200,
) : ForegroundDiagnosticLog {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val entries = ArrayDeque<ForegroundDiagnosticEntry>(capacity)

    @Synchronized
    override fun record(entry: ForegroundDiagnosticEntry) {
        while (entries.size >= capacity) entries.removeFirst()
        entries.addLast(entry)
    }

    @Synchronized
    fun snapshot(): List<ForegroundDiagnosticEntry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()
}
