package io.github.r1b2ns.stackaudit.settings

import com.russhwolf.settings.Settings
import io.github.r1b2ns.stackaudit.StackAudit
import io.github.r1b2ns.stackaudit.StorageChange
import io.github.r1b2ns.stackaudit.StorageOperation
import io.github.r1b2ns.stackaudit.StorageRecorder
import kotlin.time.Clock

public fun Settings.audited(
    name: String,
    recorder: StorageRecorder = StackAudit,
): Settings = if (this is AuditedSettings) this else AuditedSettings(this, name, recorder)

internal class AuditedSettings(
    private val delegate: Settings,
    private val name: String,
    private val recorder: StorageRecorder,
) : Settings by delegate {
    override fun clear() {
        delegate.clear()
        record(null, StorageOperation.Clear, null, null, null)
    }

    override fun remove(key: String) {
        val old = current(key)
        delegate.remove(key)
        record(key, StorageOperation.Remove, old, null, null)
    }

    override fun putInt(
        key: String,
        value: Int,
    ) = write(key, value, "Int") { delegate.putInt(key, value) }

    override fun putLong(
        key: String,
        value: Long,
    ) = write(key, value, "Long") { delegate.putLong(key, value) }

    override fun putString(
        key: String,
        value: String,
    ) = write(key, value, "String") { delegate.putString(key, value) }

    override fun putFloat(
        key: String,
        value: Float,
    ) = write(key, value, "Float") { delegate.putFloat(key, value) }

    override fun putDouble(
        key: String,
        value: Double,
    ) = write(key, value, "Double") { delegate.putDouble(key, value) }

    override fun putBoolean(
        key: String,
        value: Boolean,
    ) = write(key, value, "Boolean") { delegate.putBoolean(key, value) }

    private inline fun write(
        key: String,
        value: Any,
        type: String,
        block: () -> Unit,
    ) {
        val old = current(key)
        block()
        record(key, StorageOperation.Write, old, value.toString(), type)
    }

    private fun current(key: String): String? {
        if (!recorder.isCapturingStorage || !delegate.hasKey(key)) return null
        return delegate.getStringOrNull(key)
            ?: runCatching { delegate.getLongOrNull(key) }.getOrNull()?.toString()
            ?: runCatching { delegate.getIntOrNull(key) }.getOrNull()?.toString()
            ?: runCatching { delegate.getDoubleOrNull(key) }.getOrNull()?.toString()
            ?: runCatching { delegate.getFloatOrNull(key) }.getOrNull()?.toString()
            ?: runCatching { delegate.getBooleanOrNull(key) }.getOrNull()?.toString()
    }

    private fun record(
        key: String?,
        operation: StorageOperation,
        old: String?,
        new: String?,
        type: String?,
    ) {
        if (!recorder.isCapturingStorage) return
        recorder.record(StorageChange("settings", name, key, operation, Clock.System.now().toEpochMilliseconds(), old, new, type))
    }
}
