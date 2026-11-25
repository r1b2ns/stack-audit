package io.github.r1b2ns.stackaudit.settings

import com.russhwolf.settings.MapSettings
import io.github.r1b2ns.stackaudit.StorageChange
import io.github.r1b2ns.stackaudit.StorageOperation
import io.github.r1b2ns.stackaudit.StorageRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AuditedSettingsTest {
    private class Recorder(
        override val isCapturingStorage: Boolean = true,
    ) : StorageRecorder {
        val changes = mutableListOf<StorageChange>()

        override fun record(change: StorageChange) {
            changes += change
        }
    }

    @Test
    fun recordsWritesRemovesAndClear() {
        val recorder = Recorder()
        val settings = MapSettings("theme" to "light").audited("prefs", recorder)
        settings.putString("theme", "dark")
        settings.putInt("count", 2)
        settings.remove("theme")
        settings.clear()
        assertEquals(listOf(StorageOperation.Write, StorageOperation.Write, StorageOperation.Remove, StorageOperation.Clear), recorder.changes.map { it.operation })
        assertEquals("light", recorder.changes[0].oldValue)
        assertEquals("dark", recorder.changes[0].newValue)
        assertEquals("Int", recorder.changes[1].valueType)
        assertEquals("dark", recorder.changes[2].oldValue)
        assertEquals("prefs", recorder.changes[3].store)
        assertTrue(settings.keys.isEmpty())
    }

    @Test
    fun readsPassThroughAndWrappingIsIdempotent() {
        val recorder = Recorder()
        val settings = MapSettings("a" to 1).audited("prefs", recorder)
        assertEquals(1, settings.getInt("a", 0))
        assertTrue(recorder.changes.isEmpty())
        assertSame(settings, settings.audited("prefs", recorder))
    }

    @Test
    fun skipsWhenNotCapturing() {
        val recorder = Recorder(isCapturingStorage = false)
        MapSettings().audited("prefs", recorder).putString("a", "b")
        assertTrue(recorder.changes.isEmpty())
    }
}
