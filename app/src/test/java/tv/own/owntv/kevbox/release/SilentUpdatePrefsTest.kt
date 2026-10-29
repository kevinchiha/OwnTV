// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.release

import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.kevbox.release.SilentUpdatePrefs.Outcome

class SilentUpdatePrefsTest {

    /** In-memory SharedPreferences: the JVM's android.jar stub would silently drop every write. */
    private class MemoryPrefs : SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        var commits = 0

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
        override fun getFloat(key: String, defValue: Float) = defValue
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
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
                commits++
                removed.forEach { values.remove(it) }
                values.putAll(pending)
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }

    private val day = 24 * 60 * 60 * 1000L

    @Test
    fun `a given-up version survives a new instance and is skipped, a newer one is not`() {
        val store = MemoryPrefs()
        assertNull(SilentUpdatePrefs(store).gaveUpVersion)

        SilentUpdatePrefs(store).giveUp("5.2.1.3")

        val prefs = SilentUpdatePrefs(store)
        assertEquals("5.2.1.3", prefs.gaveUpVersion)
        assertTrue(prefs.skips("5.2.1.3"))
        assertFalse(prefs.skips("5.2.1.4"))
    }

    @Test
    fun `giving up shows the card until the installed version reaches it`() {
        val store = MemoryPrefs()
        SilentUpdatePrefs(store).giveUp("5.2.1.3")

        val prefs = SilentUpdatePrefs(store)
        assertTrue(prefs.fallbackNeeded("5.2.1.2", boxNowMs = 0L))
        assertFalse(prefs.fallbackNeeded("5.2.1.3", boxNowMs = 0L))
        assertFalse(prefs.fallbackNeeded("5.2.1.4", boxNowMs = 0L))
    }

    @Test
    fun `an older given-up version never replaces a newer one`() {
        val prefs = SilentUpdatePrefs(MemoryPrefs())
        prefs.giveUp("5.2.1.4")
        prefs.giveUp("5.2.1.3")
        assertEquals("5.2.1.4", prefs.gaveUpVersion)
        prefs.giveUp("5.2.1.5")
        assertEquals("5.2.1.5", prefs.gaveUpVersion)
    }

    @Test
    fun `three failures give that version up and bring the card back`() {
        val store = MemoryPrefs()
        val prefs = SilentUpdatePrefs(store)

        prefs.failed("5.2.1.3", withProfile = false)
        prefs.failed("5.2.1.3", withProfile = false)
        assertFalse(SilentUpdatePrefs(store).skips("5.2.1.3"))
        assertFalse(SilentUpdatePrefs(store).fallbackNeeded("5.2.1.2", boxNowMs = 0L))

        prefs.failed("5.2.1.3", withProfile = false)
        assertTrue(SilentUpdatePrefs(store).skips("5.2.1.3"))
        assertTrue(SilentUpdatePrefs(store).fallbackNeeded("5.2.1.2", boxNowMs = 0L))
    }

    @Test
    fun `a failure with the profile drops the profile and does not count`() {
        val store = MemoryPrefs()
        val prefs = SilentUpdatePrefs(store)
        assertFalse(prefs.skipsProfile("5.2.1.3"))

        repeat(SilentUpdatePrefs.MAX_FAILURES) { prefs.failed("5.2.1.3", withProfile = true) }

        assertTrue(SilentUpdatePrefs(store).skipsProfile("5.2.1.3"))
        assertFalse(SilentUpdatePrefs(store).skipsProfile("5.2.1.4"))
        assertFalse(SilentUpdatePrefs(store).skips("5.2.1.3"))

        repeat(SilentUpdatePrefs.MAX_FAILURES) { prefs.failed("5.2.1.3", withProfile = false) }
        assertTrue(SilentUpdatePrefs(store).skips("5.2.1.3"))
    }

    @Test
    fun `a newer version starts with a clean count`() {
        val store = MemoryPrefs()
        val prefs = SilentUpdatePrefs(store)
        repeat(3) { prefs.failed("5.2.1.3", withProfile = false) }

        assertFalse(prefs.skips("5.2.1.4"))
        prefs.failed("5.2.1.4", withProfile = false)
        prefs.failed("5.2.1.4", withProfile = false)
        assertFalse(prefs.skips("5.2.1.4"))
    }

    private val hour = 60 * 60 * 1000L

    @Test
    fun `a seen release with a block brings the card back 72 hours after its after`() {
        val store = MemoryPrefs()
        val afterBox = 1_000 * day
        SilentUpdatePrefs(store).seen("5.2.1.3", hasSilent = true, afterBoxMs = afterBox)

        val prefs = SilentUpdatePrefs(store)
        assertFalse(prefs.fallbackNeeded("5.2.1.2", boxNowMs = afterBox + 72 * hour - 1))
        assertTrue(prefs.fallbackNeeded("5.2.1.2", boxNowMs = afterBox + 72 * hour))
        assertFalse(prefs.fallbackNeeded("5.2.1.3", boxNowMs = afterBox + 30 * day))
    }

    @Test
    fun `a seen release without a block brings the card back at once`() {
        val store = MemoryPrefs()
        SilentUpdatePrefs(store).seen("5.2.1.3", hasSilent = false, afterBoxMs = null)

        val prefs = SilentUpdatePrefs(store)
        assertTrue(prefs.fallbackNeeded("5.2.1.2", boxNowMs = 0L))
        assertFalse(prefs.fallbackNeeded("5.2.1.3", boxNowMs = 0L))
    }

    @Test
    fun `finding the block later replaces the no-block record of the same version, and back`() {
        val store = MemoryPrefs()
        val prefs = SilentUpdatePrefs(store)
        prefs.seen("5.2.1.3", hasSilent = false, afterBoxMs = null)
        assertTrue(prefs.fallbackNeeded("5.2.1.2", boxNowMs = 100 * day))

        prefs.seen("5.2.1.3", hasSilent = true, afterBoxMs = 100 * day)
        assertFalse(SilentUpdatePrefs(store).fallbackNeeded("5.2.1.2", boxNowMs = 100 * day))

        // The same version with a moved `after` (the box clock was set right) is stored too.
        prefs.seen("5.2.1.3", hasSilent = true, afterBoxMs = 90 * day)
        assertTrue(SilentUpdatePrefs(store).fallbackNeeded("5.2.1.2", boxNowMs = 93 * day))

        prefs.seen("5.2.1.3", hasSilent = false, afterBoxMs = null)
        assertTrue(SilentUpdatePrefs(store).fallbackNeeded("5.2.1.2", boxNowMs = 0L))
    }

    @Test
    fun `the latest seen release replaces the one before`() {
        val store = MemoryPrefs()
        val prefs = SilentUpdatePrefs(store)
        prefs.seen("5.2.1.3", hasSilent = false, afterBoxMs = null)
        prefs.seen("5.2.1.4", hasSilent = true, afterBoxMs = 100 * day)

        assertFalse(prefs.fallbackNeeded("5.2.1.3", boxNowMs = 100 * day + 72 * hour - 1))
        assertTrue(prefs.fallbackNeeded("5.2.1.3", boxNowMs = 100 * day + 72 * hour))
        assertFalse(prefs.fallbackNeeded("5.2.1.4", boxNowMs = 100 * day + 8 * day))
    }

    @Test
    fun `an unchanged record is not written again, and the old publish time goes`() {
        val store = MemoryPrefs()
        store.values["seen_published_at"] = 5L
        val prefs = SilentUpdatePrefs(store)
        prefs.seen("5.2.1.3", hasSilent = true, afterBoxMs = 100 * day)
        assertFalse(store.contains("seen_published_at"))

        val commits = store.commits
        prefs.seen("5.2.1.3", hasSilent = true, afterBoxMs = 100 * day)
        assertEquals(commits, store.commits)
        prefs.seen("5.2.1.3", hasSilent = false, afterBoxMs = null)
        assertEquals(commits + 1, store.commits)
        prefs.seen("5.2.1.3", hasSilent = false, afterBoxMs = null)
        assertEquals(commits + 1, store.commits)
    }

    @Test
    fun `an after that moved by under a minute is not written again`() {
        val store = MemoryPrefs()
        val prefs = SilentUpdatePrefs(store)
        val afterBox = 100 * day
        prefs.seen("5.2.1.3", hasSilent = true, afterBoxMs = afterBox)
        val commits = store.commits

        // The server's Date has whole seconds, so each check computes a slightly different value.
        prefs.seen("5.2.1.3", hasSilent = true, afterBoxMs = afterBox + 999)
        prefs.seen("5.2.1.3", hasSilent = true, afterBoxMs = afterBox - 59_999)
        prefs.seen("5.2.1.3", hasSilent = true, afterBoxMs = afterBox + 59_999)
        assertEquals(commits, store.commits)
        assertEquals(afterBox, store.values["seen_after_box_ms"])

        // A minute or more is a real move (the box clock was set right) and is stored.
        prefs.seen("5.2.1.3", hasSilent = true, afterBoxMs = afterBox + 60_000)
        assertEquals(commits + 1, store.commits)
        assertEquals(afterBox + 60_000, store.values["seen_after_box_ms"])

        // A gap that overflows a Long is never "close".
        prefs.seen("5.2.1.3", hasSilent = true, afterBoxMs = Long.MIN_VALUE + 1)
        assertEquals(Long.MIN_VALUE + 1, store.values["seen_after_box_ms"])
    }

    @Test
    fun `a record from before the silent block opens no card until a check rewrites it`() {
        // What Live 5.2.1.4 stored: the version and its publish time, no block flag.
        val store = MemoryPrefs()
        store.values["seen_version"] = "5.2.1.3"
        store.values["seen_published_at"] = 5L
        val prefs = SilentUpdatePrefs(store)
        assertFalse(prefs.fallbackNeeded("5.2.1.2", boxNowMs = 100 * day))

        // A real "no block" check of that same version is written and opens the card.
        prefs.seen("5.2.1.3", hasSilent = false, afterBoxMs = null)
        assertTrue(SilentUpdatePrefs(store).fallbackNeeded("5.2.1.2", boxNowMs = 100 * day))
    }

    @Test
    fun `an old record without its publish time is rewritten by the next check too`() {
        val store = MemoryPrefs()
        store.values["seen_version"] = "5.2.1.3"
        val prefs = SilentUpdatePrefs(store)
        prefs.seen("5.2.1.3", hasSilent = false, afterBoxMs = null)
        assertEquals(false, store.values["seen_has_silent"])
        assertTrue(prefs.fallbackNeeded("5.2.1.2", boxNowMs = 0L))
    }

    @Test
    fun `the last stop time survives a new instance`() {
        val store = MemoryPrefs()
        assertNull(SilentUpdatePrefs(store).lastStopElapsedMs)
        SilentUpdatePrefs(store).stopped(123_456L)
        assertEquals(123_456L, SilentUpdatePrefs(store).lastStopElapsedMs)
        SilentUpdatePrefs(store).stopped(0L)
        assertEquals(0L, SilentUpdatePrefs(store).lastStopElapsedMs)
    }

    @Test
    fun `a fresh box needs no fallback`() {
        assertFalse(SilentUpdatePrefs(MemoryPrefs()).fallbackNeeded("5.2.1.2", boxNowMs = Long.MAX_VALUE))
    }

    @Test
    fun `install status maps to an outcome`() {
        assertEquals(Outcome.SUCCESS, SilentUpdatePrefs.outcomeOf(PackageInstaller.STATUS_SUCCESS))
        assertEquals(Outcome.REFUSED, SilentUpdatePrefs.outcomeOf(PackageInstaller.STATUS_PENDING_USER_ACTION))
        assertEquals(Outcome.FAILED, SilentUpdatePrefs.outcomeOf(PackageInstaller.STATUS_FAILURE))
        assertEquals(Outcome.FAILED, SilentUpdatePrefs.outcomeOf(PackageInstaller.STATUS_FAILURE_CONFLICT))
        assertEquals(Outcome.FAILED, SilentUpdatePrefs.outcomeOf(PackageInstaller.STATUS_FAILURE_STORAGE))
        assertEquals(Outcome.FAILED, SilentUpdatePrefs.outcomeOf(PackageInstaller.STATUS_FAILURE_ABORTED))
    }
}
