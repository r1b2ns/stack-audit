package io.github.r1b2ns.stackaudit.storage

import android.content.Context
import android.content.SharedPreferences
import io.github.r1b2ns.stackaudit.StackAudit
import io.github.r1b2ns.stackaudit.StorageChange
import io.github.r1b2ns.stackaudit.StorageOperation
import io.github.r1b2ns.stackaudit.StorageRecorder

public fun SharedPreferences.audited(
    name: String,
    recorder: StorageRecorder = StackAudit,
): SharedPreferences = if (this is AuditedSharedPreferences) this else AuditedSharedPreferences(this, name, recorder)

public fun Context.auditedSharedPreferences(
    name: String,
    mode: Int = Context.MODE_PRIVATE,
    recorder: StorageRecorder = StackAudit,
): SharedPreferences = getSharedPreferences(name, mode).audited(name, recorder)

internal class AuditedSharedPreferences(
    private val delegate: SharedPreferences,
    private val name: String,
    private val recorder: StorageRecorder,
) : SharedPreferences by delegate {
    override fun edit(): SharedPreferences.Editor = AuditedEditor(delegate.edit())

    private sealed interface Change {
        data class Put(
            val key: String,
            val value: Any?,
        ) : Change

        data class Remove(
            val key: String,
        ) : Change

        data object Clear : Change
    }

    private inner class AuditedEditor(
        private val editor: SharedPreferences.Editor,
    ) : SharedPreferences.Editor {
        private val changes = mutableListOf<Change>()

        override fun putString(
            key: String,
            value: String?,
        ) = apply {
            changes += Change.Put(key, value)
            editor.putString(key, value)
        }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ) = apply {
            changes += Change.Put(key, values?.toSet())
            editor.putStringSet(key, values)
        }

        override fun putInt(
            key: String,
            value: Int,
        ) = apply {
            changes += Change.Put(key, value)
            editor.putInt(key, value)
        }

        override fun putLong(
            key: String,
            value: Long,
        ) = apply {
            changes += Change.Put(key, value)
            editor.putLong(key, value)
        }

        override fun putFloat(
            key: String,
            value: Float,
        ) = apply {
            changes += Change.Put(key, value)
            editor.putFloat(key, value)
        }

        override fun putBoolean(
            key: String,
            value: Boolean,
        ) = apply {
            changes += Change.Put(key, value)
            editor.putBoolean(key, value)
        }

        override fun remove(key: String) =
            apply {
                changes += Change.Remove(key)
                editor.remove(key)
            }

        override fun clear() =
            apply {
                changes += Change.Clear
                editor.clear()
            }

        override fun commit(): Boolean {
            val before = snapshot()
            return editor.commit().also { record(before) }
        }

        override fun apply() {
            val before = snapshot()
            editor.apply()
            record(before)
        }

        private fun snapshot(): Map<String, *> = if (recorder.isCapturingStorage && changes.isNotEmpty()) delegate.all.toMap() else emptyMap<String, Any?>()

        private fun record(before: Map<String, *>) {
            if (!recorder.isCapturingStorage) return
            val now = System.currentTimeMillis()
            changes.forEach { change ->
                recorder.record(
                    when (change) {
                        is Change.Put ->
                            if (change.value == null) {
                                StorageChange(SOURCE, name, change.key, StorageOperation.Remove, now, oldValue = describe(before[change.key]))
                            } else {
                                StorageChange(
                                    SOURCE,
                                    name,
                                    change.key,
                                    StorageOperation.Write,
                                    now,
                                    oldValue = describe(before[change.key]),
                                    newValue = describe(change.value),
                                    valueType = typeOf(change.value),
                                )
                            }
                        is Change.Remove -> StorageChange(SOURCE, name, change.key, StorageOperation.Remove, now, oldValue = describe(before[change.key]))
                        Change.Clear -> StorageChange(SOURCE, name, null, StorageOperation.Clear, now)
                    },
                )
            }
            changes.clear()
        }
    }

    private companion object {
        const val SOURCE = "shared_preferences"

        fun describe(value: Any?): String? =
            when (value) {
                null -> null
                is Set<*> -> value.joinToString(prefix = "[", postfix = "]")
                else -> value.toString()
            }

        fun typeOf(value: Any): String =
            when (value) {
                is String -> "String"
                is Int -> "Int"
                is Long -> "Long"
                is Float -> "Float"
                is Boolean -> "Boolean"
                is Set<*> -> "StringSet"
                else -> value::class.java.simpleName
            }
    }
}
