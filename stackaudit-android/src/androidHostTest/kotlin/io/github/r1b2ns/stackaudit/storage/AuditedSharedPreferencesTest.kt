package io.github.r1b2ns.stackaudit.storage

import android.content.SharedPreferences
import io.github.r1b2ns.stackaudit.DatabaseRecorder
import io.github.r1b2ns.stackaudit.DatabaseStatement
import io.github.r1b2ns.stackaudit.StorageChange
import io.github.r1b2ns.stackaudit.StorageOperation
import io.github.r1b2ns.stackaudit.StorageRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FakePreferences : SharedPreferences {
    val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(
        key: String?,
        defValue: String?,
    ): String? = values[key] as? String ?: defValue

    override fun getStringSet(
        key: String?,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? = defValues

    override fun getInt(
        key: String?,
        defValue: Int,
    ): Int = values[key] as? Int ?: defValue

    override fun getLong(
        key: String?,
        defValue: Long,
    ): Long = values[key] as? Long ?: defValue

    override fun getFloat(
        key: String?,
        defValue: Float,
    ): Float = values[key] as? Float ?: defValue

    override fun getBoolean(
        key: String?,
        defValue: Boolean,
    ): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = key in values

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    inner class Editor : SharedPreferences.Editor {
        private val ops = mutableListOf<(MutableMap<String, Any?>) -> Unit>()

        private fun put(
            key: String?,
            value: Any?,
        ) = apply { ops += { it[key!!] = value } }

        override fun putString(
            key: String?,
            value: String?,
        ) = put(key, value)

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ) = put(key, values)

        override fun putInt(
            key: String?,
            value: Int,
        ) = put(key, value)

        override fun putLong(
            key: String?,
            value: Long,
        ) = put(key, value)

        override fun putFloat(
            key: String?,
            value: Float,
        ) = put(key, value)

        override fun putBoolean(
            key: String?,
            value: Boolean,
        ) = put(key, value)

        override fun remove(key: String?) = apply { ops += { it.remove(key) } }

        override fun clear() = apply { ops += { it.clear() } }

        override fun commit(): Boolean {
            ops.forEach { it(values) }
            ops.clear()
            return true
        }

        override fun apply() {
            commit()
        }
    }
}

class AuditedSharedPreferencesTest {
    private class Recorder(
        override val isCapturingStorage: Boolean = true,
    ) : StorageRecorder {
        val changes = mutableListOf<StorageChange>()

        override fun record(change: StorageChange) {
            changes += change
        }
    }

    @Test
    fun recordsWritesWithOldValues() {
        val fake = FakePreferences().apply { values["theme"] = "light" }
        val recorder = Recorder()
        val prefs = fake.audited("settings", recorder)
        prefs.edit().putString("theme", "dark").putInt("count", 3).apply()
        prefs.edit().remove("theme").commit()
        prefs.edit().clear().apply()

        assertEquals(listOf(StorageOperation.Write, StorageOperation.Write, StorageOperation.Remove, StorageOperation.Clear), recorder.changes.map { it.operation })
        val theme = recorder.changes[0]
        assertEquals("settings", theme.store)
        assertEquals("light", theme.oldValue)
        assertEquals("dark", theme.newValue)
        assertEquals("Int", recorder.changes[1].valueType)
        assertEquals("dark", recorder.changes[2].oldValue)
        assertTrue(fake.values.isEmpty())
    }

    @Test
    fun putNullIsARemove() {
        val recorder = Recorder()
        FakePreferences().audited("s", recorder).edit().putString("a", null).apply()
        assertEquals(StorageOperation.Remove, recorder.changes.single().operation)
    }

    @Test
    fun readsAreDelegatedAndNotRecorded() {
        val fake = FakePreferences().apply { values["a"] = "1" }
        val recorder = Recorder()
        val prefs = fake.audited("s", recorder)
        assertEquals("1", prefs.getString("a", null))
        assertTrue(recorder.changes.isEmpty())
        assertSame(prefs, prefs.audited("s", recorder))
    }

    @Test
    fun skipsWhenNotCapturing() {
        val recorder = Recorder(isCapturingStorage = false)
        val fake = FakePreferences()
        fake.audited("s", recorder).edit().putString("a", "1").apply()
        assertEquals("1", fake.values["a"])
        assertTrue(recorder.changes.isEmpty())
    }
}

class StackAuditRoomTest {
    @Test
    fun forwardsRoomQueries() {
        val statements = mutableListOf<DatabaseStatement>()
        val recorder =
            object : DatabaseRecorder {
                override val isCapturingDatabase = true

                override fun record(statement: DatabaseStatement) {
                    statements += statement
                }
            }
        stackAuditQueryCallback("app.db", recorder).onQuery("SELECT * FROM user WHERE id = ?", listOf(7))
        val statement = statements.single()
        assertEquals("room", statement.source)
        assertEquals("app.db", statement.database)
        assertEquals(listOf<Any?>(7), statement.arguments)
    }
}
