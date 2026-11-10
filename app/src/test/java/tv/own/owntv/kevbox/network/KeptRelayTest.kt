// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.network

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeptRelayTest {

    /** In-memory SharedPreferences: the JVM's android.jar stub would silently drop every write. */
    private class MemoryPrefs : SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        var commits = 0
        var applies = 0

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
                commits += 1
                removed.forEach { values.remove(it) }
                values.putAll(pending)
                return true
            }
            override fun apply() {
                applies += 1
                removed.forEach { values.remove(it) }
                values.putAll(pending)
            }
        }
    }

    @Test
    fun `nothing kept reads as null`() {
        assertNull(KeptRelay(MemoryPrefs()).load())
    }

    @Test
    fun `a saved address survives a restart, with or without its IPv4 address`() {
        val prefs = MemoryPrefs()
        KeptRelay(prefs).save(TARGET)
        // A fresh store on the same preferences: the process restarted.
        assertEquals(TARGET, KeptRelay(prefs).load())

        KeptRelay(prefs).save(TARGET.copy(addr = null))
        assertEquals(TARGET.copy(addr = null), KeptRelay(prefs).load())
    }

    @Test
    fun `save and clear commit at once, so Core's setting is never written before the address is on disk`() {
        val prefs = MemoryPrefs()
        val kept = KeptRelay(prefs)
        kept.save(TARGET)
        kept.clear()
        assertEquals(2, prefs.commits)
        assertEquals(0, prefs.applies)
    }

    @Test
    fun `clear forgets the address`() {
        val prefs = MemoryPrefs()
        KeptRelay(prefs).save(TARGET)
        KeptRelay(prefs).clear()
        assertNull(KeptRelay(prefs).load())
    }

    @Test
    fun `a blank host or a port outside 1-65535 reads as nothing kept`() {
        val prefs = MemoryPrefs()
        val kept = KeptRelay(prefs)
        kept.save(TARGET.copy(host = "  "))
        assertNull(kept.load())
        kept.save(TARGET.copy(port = 0))
        assertNull(kept.load())
        kept.save(TARGET.copy(port = 65536))
        assertNull(kept.load())
        kept.save(TARGET.copy(port = 65535))
        assertEquals(TARGET.copy(port = 65535), kept.load())
    }

    @Test
    fun `a value of the wrong type reads as nothing kept`() {
        val prefs = MemoryPrefs()
        KeptRelay(prefs).save(TARGET)
        prefs.values["relay_port"] = "443"
        assertNull(KeptRelay(prefs).load())
    }

    private companion object {
        val TARGET = RelayTarget("proxy.kevbox.dev", 443, "203.0.113.7")
    }
}
