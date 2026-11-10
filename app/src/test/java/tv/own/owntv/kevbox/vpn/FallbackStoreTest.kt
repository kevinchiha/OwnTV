// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.vpn

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FallbackStoreTest {

    /** In-memory SharedPreferences: the JVM's android.jar stub would silently drop every write. */
    private class MemoryPrefs : SharedPreferences {
        val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
        override fun getFloat(key: String, defValue: Float) = defValue
        override fun getBoolean(key: String, defValue: Boolean) = defValue
        override fun contains(key: String) = key in values
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removed += key }
            override fun clear() = apply { removed += values.keys }
            override fun commit(): Boolean {
                removed.forEach { values.remove(it) }
                values.putAll(pending)
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }

    @Test
    fun `an empty store has no fall-back`() {
        val store = FallbackStore(MemoryPrefs())
        assertNull(store.atMs())
        assertNull(store.server())
        assertNull(store.changedAt())
    }

    @Test
    fun `a saved fall-back survives a restart and feeds the rules`() {
        val prefs = MemoryPrefs()
        FallbackStore(prefs).save(AT, HOST, CHANGED)

        // A fresh store on the same preferences: the app restarted.
        val restarted = FallbackStore(prefs)
        assertEquals(AT, restarted.atMs())
        assertEquals(HOST, restarted.server())
        assertEquals(CHANGED, restarted.changedAt())

        val state = VpnRules.initial(restarted.atMs(), restarted.server(), restarted.changedAt())
        assertEquals(AT + FALLBACK_WAIT_MS, state.dueAtMs)
    }

    @Test
    fun `a time of zero is still a stored time`() {
        val store = FallbackStore(MemoryPrefs())
        store.save(0L, HOST, null)
        assertEquals(0L, store.atMs())
    }

    @Test
    fun `saving without a changed time drops an older one`() {
        val store = FallbackStore(MemoryPrefs())
        store.save(AT, HOST, CHANGED)
        store.save(AT + 1, "vpn-nl.premiumize.me", null)
        assertEquals(AT + 1, store.atMs())
        assertEquals("vpn-nl.premiumize.me", store.server())
        assertNull(store.changedAt())
    }

    @Test
    fun `clear removes every part`() {
        val prefs = MemoryPrefs()
        val store = FallbackStore(prefs)
        store.save(AT, HOST, CHANGED)
        store.clear()
        assertNull(store.atMs())
        assertNull(store.server())
        assertNull(store.changedAt())
        assertEquals(emptyMap<String, Any?>(), prefs.values)
    }

    private companion object {
        const val AT = 1_762_800_000_000L
        const val HOST = "vpn-de.premiumize.me"
        const val CHANGED = "2026-11-10T20:00:00+00:00"
    }
}
