package com.yufei.comboassistant.overlay

data class OverlayDiff<K, V>(
    val added: Map<K, V>,
    val updated: Map<K, V>,
    val removed: Set<K>,
)

class OverlayCoordinator<K, V>(
    private val equivalent: (V, V) -> Boolean = { first, second -> first == second },
) {
    private val rendered = mutableMapOf<K, V>()

    /**
     * Reconciles the desired state with the last state that was successfully applied.
     *
     * WindowManager operations can fail transiently (for example while a display token is being
     * replaced). A failed operation is deliberately not acknowledged here, so the next reconcile
     * call will attempt it again instead of assuming that the platform view matches [desired].
     * Callback exceptions are treated exactly like a `false` result and never escape this method.
     */
    fun reconcile(
        desired: Map<K, V>,
        onRemoved: (K) -> Boolean = { true },
        onUpdated: (K, V) -> Boolean = { _, _ -> true },
        onAdded: (K, V) -> Boolean = { _, _ -> true },
    ): OverlayDiff<K, V> {
        val removed = rendered.keys - desired.keys
        val added = desired.filterKeys { it !in rendered }
        val updated = desired.filter { (key, value) ->
            key in rendered && !equivalent(rendered.getValue(key), value)
        }

        removed.forEach { key ->
            if (applied { onRemoved(key) }) rendered.remove(key)
        }
        updated.forEach { (key, value) ->
            if (applied { onUpdated(key, value) }) rendered[key] = value
        }
        added.forEach { (key, value) ->
            if (applied { onAdded(key, value) }) rendered[key] = value
        }
        return OverlayDiff(added = added, updated = updated, removed = removed)
    }

    fun clear(onRemoved: (K) -> Boolean = { true }): OverlayDiff<K, V> =
        reconcile(emptyMap(), onRemoved = onRemoved)

    private fun applied(operation: () -> Boolean): Boolean =
        runCatching(operation).getOrDefault(false)
}
