package io.github.r1b2ns.stackaudit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.Volatile

public class StackAuditAnalytics internal constructor(
    private val recorder: () -> AnalyticsRecorder?,
    private val now: () -> Long,
) {
    @Volatile
    public var plan: TrackingPlan? = null

    public fun wrap(
        tracker: AnalyticsTracker,
        provider: String,
    ): AnalyticsTracker =
        AnalyticsTracker { name, params ->
            record(provider, name, params)
            tracker.track(name, params)
        }

    public fun record(
        provider: String,
        name: String,
        params: Map<String, Any?> = emptyMap(),
    ) {
        val target = recorder()?.takeIf { it.isCapturingAnalytics } ?: return
        val (status, issues) = plan?.validate(name, params) ?: (ValidationStatus.NotChecked to emptyList())
        target.record(AnalyticsCall(provider, name, params, now(), status, issues))
    }
}

public interface OverrideStore {
    public fun load(): Map<String, FlagValue>

    public fun save(
        key: String,
        value: FlagValue?,
    )
}

public class StackAuditFlags internal constructor(
    private val recorder: () -> FlagRecorder?,
    private val now: () -> Long,
) {
    private val overrides = MutableStateFlow<Map<String, FlagValue>>(emptyMap())
    private val known = MutableStateFlow<Map<String, FlagState>>(emptyMap())

    @Volatile
    private var store: OverrideStore? = null

    public val states: StateFlow<Map<String, FlagState>> = known.asStateFlow()

    public val currentOverrides: StateFlow<Map<String, FlagValue>> = overrides.asStateFlow()

    internal fun attach(store: OverrideStore?) {
        this.store = store
        if (store == null) return
        val pending = overrides.value
        pending.forEach { (key, value) -> store.save(key, value) }
        overrides.value = store.load() + pending
        known.update { map -> map.mapValues { (key, state) -> state.copy(overrideValue = overrides.value[key].sameKindAs(state.defaultValue)) } }
    }

    public fun wrap(
        provider: FlagProvider,
        name: String = "app",
    ): AuditedFlags = AuditedFlags(provider, name, this)

    public fun override(
        key: String,
        value: FlagValue,
    ) {
        overrides.update { it + (key to value) }
        store?.save(key, value)
        refresh(key, "stackaudit")
    }

    public fun clearOverride(key: String) {
        overrides.update { it - key }
        store?.save(key, null)
        refresh(key, "stackaudit")
    }

    public fun clearOverrides() {
        val keys = overrides.value.keys
        keys.forEach(::clearOverride)
    }

    internal fun resolve(
        provider: String,
        key: String,
        default: FlagValue,
        remote: FlagValue?,
    ): FlagValue {
        val state =
            FlagState(
                key = key,
                provider = provider,
                defaultValue = default,
                remoteValue = remote.sameKindAs(default),
                overrideValue = overrides.value[key].sameKindAs(default),
            )
        var previous: FlagState? = null
        known.update { map ->
            previous = map[key]
            map + (key to state)
        }
        if (previous?.effective != state.effective || previous?.source != state.source) record(state, "app")
        return state.effective
    }

    private fun refresh(
        key: String,
        changedBy: String,
    ) {
        var updated: FlagState? = null
        known.update { map ->
            val state = map[key] ?: return@update map
            val next = state.copy(overrideValue = overrides.value[key].sameKindAs(state.defaultValue))
            updated = next
            map + (key to next)
        }
        updated?.let { record(it, changedBy) }
    }

    private fun record(
        state: FlagState,
        changedBy: String,
    ) {
        val target = recorder()?.takeIf { it.isCapturingFlags } ?: return
        target.record(
            FlagPayload(
                key = state.key,
                provider = state.provider,
                defaultValue = state.defaultValue,
                remoteValue = state.remoteValue,
                overrideValue = state.overrideValue,
                effective = state.effective,
                source = state.source,
                changedBy = changedBy,
            ),
            now(),
        )
    }
}

public class AuditedFlags internal constructor(
    private val provider: FlagProvider,
    private val name: String,
    private val flags: StackAuditFlags,
) {
    public fun bool(
        key: String,
        default: Boolean,
    ): Boolean = (flags.resolve(name, key, FlagValue.Bool(default), remote(key)) as FlagValue.Bool).value

    public fun string(
        key: String,
        default: String,
    ): String = (flags.resolve(name, key, FlagValue.Text(default), remote(key)) as FlagValue.Text).value

    public fun number(
        key: String,
        default: Double,
    ): Double = (flags.resolve(name, key, FlagValue.Number(default), remote(key)) as FlagValue.Number).value

    private fun remote(key: String): FlagValue? = runCatching { provider.value(key) }.getOrNull()
}
