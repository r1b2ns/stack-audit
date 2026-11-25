package io.github.r1b2ns.stackaudit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

public class StackAuditAnalytics internal constructor() {
    public var plan: TrackingPlan? = null

    public fun wrap(
        tracker: AnalyticsTracker,
        provider: String,
    ): AnalyticsTracker = tracker.also { provider.hashCode() }

    public fun record(
        provider: String,
        name: String,
        params: Map<String, Any?> = emptyMap(),
    ) {
        provider.hashCode() + name.hashCode() + params.hashCode()
    }
}

public interface OverrideStore {
    public fun load(): Map<String, FlagValue>

    public fun save(
        key: String,
        value: FlagValue?,
    )
}

public class StackAuditFlags internal constructor() {
    public val states: StateFlow<Map<String, FlagState>> = MutableStateFlow(emptyMap())

    public val currentOverrides: StateFlow<Map<String, FlagValue>> = MutableStateFlow(emptyMap())

    public fun wrap(
        provider: FlagProvider,
        name: String = "app",
    ): AuditedFlags = AuditedFlags(provider).also { name.hashCode() }

    public fun override(
        key: String,
        value: FlagValue,
    ) {
        key.hashCode() + value.hashCode()
    }

    public fun clearOverride(key: String) {
        key.hashCode()
    }

    public fun clearOverrides() {}
}

public class AuditedFlags internal constructor(
    private val provider: FlagProvider,
) {
    public fun bool(
        key: String,
        default: Boolean,
    ): Boolean = (remote(key) as? FlagValue.Bool)?.value ?: default

    public fun string(
        key: String,
        default: String,
    ): String = (remote(key) as? FlagValue.Text)?.value ?: default

    public fun number(
        key: String,
        default: Double,
    ): Double = (remote(key) as? FlagValue.Number)?.value ?: default

    private fun remote(key: String): FlagValue? = runCatching { provider.value(key) }.getOrNull()
}
