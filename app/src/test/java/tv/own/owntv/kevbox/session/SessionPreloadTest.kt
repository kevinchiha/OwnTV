// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.session

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPreloadTest {

    private val session = StoredSession("user-1", "member@example.com", "access", "refresh", 5_000L)

    @Test
    fun `the load runs on its own thread, so a stuck key store never holds the caller`() {
        val release = CountDownLatch(1)
        val loadThread = AtomicReference<Thread>()
        val preload = SessionPreload({
            loadThread.set(Thread.currentThread())
            release.await(5, TimeUnit.SECONDS)
            session
        })

        // The constructor came back while the load is still waiting on the "key store".
        assertFalse(preload.isDone)
        release.countDown()
        assertEquals(session, runBlocking { preload.await() })
        assertNotEquals(Thread.currentThread(), loadThread.get())
    }

    @Test
    fun `whenDone runs once the load ends, and at once when it already has`() {
        val release = CountDownLatch(1)
        val preload = SessionPreload({
            release.await(5, TimeUnit.SECONDS)
            session
        })
        val seen = CountDownLatch(1)
        val got = AtomicReference<StoredSession?>()
        preload.whenDone { got.set(it); seen.countDown() }
        assertNull(got.get())

        release.countDown()
        assertTrue(seen.await(5, TimeUnit.SECONDS))
        assertEquals(session, got.get())

        var late: StoredSession? = null
        preload.whenDone { late = it }
        assertEquals(session, late)
    }

    @Test
    fun `a load that throws reads as no session instead of never ending`() {
        val preload = SessionPreload({ throw IllegalStateException("keystore") }) { it.run() }
        assertTrue(preload.isDone)
        assertNull(runBlocking { preload.await() })
    }

    @Test
    fun `now loads on the caller, as before`() {
        val preload = SessionPreload.now(object : SessionStore {
            override fun load() = session
            override fun save(session: StoredSession) = Unit
            override fun clear() = Unit
        })
        assertTrue(preload.isDone)
        assertEquals(session, runBlocking { preload.await() })
    }
}
