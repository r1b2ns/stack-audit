package io.github.r1b2ns.stackaudit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

public fun interface AnalyticsTracker {
    public fun track(
        name: String,
        params: Map<String, Any?>,
    )
}

@Serializable
public data class EventSpec(
    val required: List<String> = emptyList(),
    val optional: List<String> = emptyList(),
    val types: Map<String, String> = emptyMap(),
)

@Serializable
public data class TrackingPlan(
    val events: Map<String, EventSpec> = emptyMap(),
    val strict: Boolean = false,
) {
    public fun validate(
        name: String,
        params: Map<String, Any?>,
    ): Pair<ValidationStatus, List<String>> {
        val spec = events[name] ?: return ValidationStatus.UnknownEvent to listOf("event \"$name\" is not in the tracking plan")
        val issues = mutableListOf<String>()
        spec.required.filter { it !in params || params[it] == null }.forEach { issues += "missing required param \"$it\"" }
        if (strict) {
            val known = spec.required.toSet() + spec.optional
            params.keys.filter { it !in known }.forEach { issues += "unexpected param \"$it\"" }
        }
        spec.types.forEach { (param, type) ->
            val value = params[param] ?: return@forEach
            if (!matches(value, type)) issues += "param \"$param\" should be $type"
        }
        return (if (issues.isEmpty()) ValidationStatus.Valid else ValidationStatus.Invalid) to issues
    }

    private fun matches(
        value: Any,
        type: String,
    ): Boolean =
        when (type.lowercase()) {
            "string" -> value is String
            "number" -> value is Number
            "integer" -> value is Int || value is Long || value is Short || value is Byte
            "boolean" -> value is Boolean
            else -> true
        }

    public companion object {
        private val json = Json { ignoreUnknownKeys = true }

        public fun fromJson(text: String): TrackingPlan = json.decodeFromString(serializer(), text)
    }
}

public fun interface FlagProvider {
    public fun value(key: String): FlagValue?
}

public data class FlagState(
    val key: String,
    val provider: String,
    val defaultValue: FlagValue,
    val remoteValue: FlagValue? = null,
    val overrideValue: FlagValue? = null,
) {
    val effective: FlagValue get() = overrideValue ?: remoteValue ?: defaultValue

    val source: FlagSource
        get() =
            when {
                overrideValue != null -> FlagSource.Override
                remoteValue != null -> FlagSource.Remote
                else -> FlagSource.Default
            }
}

internal fun FlagValue?.sameKindAs(other: FlagValue): FlagValue? = this?.takeIf { it::class == other::class }
