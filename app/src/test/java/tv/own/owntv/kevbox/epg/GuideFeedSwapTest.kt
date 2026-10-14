// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.epg

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class GuideFeedSwapTest {
    private val oldUrl = "http://line.example/xmltv.php?username=u&password=p"
    private val newUrl = "http://new-line.example/xmltv.php?username=u&password=p"
    private val handAdded = "http://other.example/guide.xml"

    /** A fake EPG list that records each step and whether the lock was held during it. */
    private class FakeStore(val lock: Mutex, var feeds: List<GuideFeed>) {
        val steps = mutableListOf<String>()
        val lockedDuring = mutableListOf<Boolean>()

        fun remove(id: Long) {
            lockedDuring += lock.isLocked
            steps += "remove $id"
            feeds = feeds.filterNot { it.id == id }
        }

        fun save() {
            lockedDuring += lock.isLocked
            steps += "save"
        }
    }

    private suspend fun swap(store: FakeStore, old: List<String>, new: List<String>, save: suspend () -> Unit = { store.save() }) =
        swapGuideFeedsThenSave(
            lock = store.lock,
            oldGuideUrls = old,
            newGuideUrls = new,
            feeds = { store.feeds },
            remove = { store.remove(it) },
            save = save,
        )

    @Test
    fun theOldFeedIsRemovedBeforeTheSaveInsideOneLock() = runBlocking {
        val store = FakeStore(Mutex(), listOf(GuideFeed(7, oldUrl, 1_000L), GuideFeed(3, handAdded, 1_000L)))
        swap(store, listOf(oldUrl), listOf(newUrl))
        assertEquals(listOf("remove 7", "save"), store.steps)
        assertEquals(listOf(true, true), store.lockedDuring)
        assertEquals(listOf(GuideFeed(3, handAdded, 1_000L)), store.feeds)
        assertFalse(store.lock.isLocked)
    }

    @Test
    fun aSaveThatThrowsLeavesTheRemovalDoneAndReachesTheCaller() = runBlocking {
        val store = FakeStore(Mutex(), listOf(GuideFeed(7, oldUrl, 1_000L)))
        val boom = IllegalStateException()
        try {
            swap(store, listOf(oldUrl), listOf(newUrl), save = { throw boom })
            fail()
        } catch (e: IllegalStateException) {
            assertSame(boom, e)
        }
        assertEquals(listOf("remove 7"), store.steps)
        assertEquals(emptyList<GuideFeed>(), store.feeds)
        assertFalse(store.lock.isLocked)
    }

    @Test
    fun aRemovalThatThrowsSkipsTheSave() = runBlocking {
        val store = FakeStore(Mutex(), listOf(GuideFeed(7, oldUrl, 1_000L)))
        try {
            swapGuideFeedsThenSave(
                lock = store.lock,
                oldGuideUrls = listOf(oldUrl),
                newGuideUrls = listOf(newUrl),
                feeds = { store.feeds },
                remove = { throw IllegalStateException() },
                save = { store.save() },
            )
            fail()
        } catch (_: IllegalStateException) {
        }
        assertEquals(emptyList<String>(), store.steps)
        assertFalse(store.lock.isLocked)
    }

    @Test
    fun aSourceWithNoGuideStillSaves() = runBlocking {
        val store = FakeStore(Mutex(), listOf(GuideFeed(3, handAdded, 1_000L)))
        swap(store, emptyList(), listOf(newUrl))
        assertEquals(listOf("save"), store.steps)
        assertEquals(listOf(true), store.lockedDuring)
    }

    @Test
    fun anUnchangedGuideRemovesNothingAndSaves() = runBlocking {
        val store = FakeStore(Mutex(), listOf(GuideFeed(7, oldUrl, 1_000L)))
        swap(store, listOf(oldUrl), listOf(oldUrl))
        assertEquals(listOf("save"), store.steps)
    }
}
