package tv.own.owntv.kevbox.errors

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.own.owntv.kevbox.supabase.ApiResponse
import tv.own.owntv.kevbox.supabase.Fetch

class ErrorQueueTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var clock = NOW
    private var user: String? = MEMBER

    private fun queue(file: File = File(folder.root, "errors.json")) =
        ErrorQueue(file, now = { clock }, currentUserId = { user })

    @Test
    fun `same code on the same channel within 5 minutes merges, after 5 minutes it does not`() {
        val queue = queue()
        queue.add(entry(at = NOW))
        queue.add(entry(at = NOW + 4 * MINUTE + 59_000))
        queue.add(entry(at = NOW + 9 * MINUTE + 58_000))

        val merged = queue.takeBatch(10).single().entry
        assertEquals(3, merged.repeatCount)
        assertEquals(NOW, merged.firstAtMs)
        assertEquals(NOW + 9 * MINUTE + 58_000, merged.lastAtMs)

        val fresh = queue(File(folder.root, "other.json"))
        fresh.add(entry(at = NOW))
        fresh.add(entry(at = NOW + 5 * MINUTE + 1_000))
        val apart = fresh.takeBatch(10)
        assertEquals(listOf(1, 1), apart.map { it.entry.repeatCount })
    }

    @Test
    fun `a different channel or code makes a new entry`() {
        val queue = queue()
        queue.add(entry(at = NOW))
        queue.add(entry(at = NOW + 1_000, channel = "ITV"))
        queue.add(entry(at = NOW + 2_000, code = "net_timeout"))

        assertEquals(3, queue.unsentCount())
    }

    @Test
    fun `an entry in flight is not a merge target`() {
        val queue = queue()
        queue.add(entry(at = NOW))
        val batch = queue.takeBatch(10)
        queue.add(entry(at = NOW + 1_000))

        assertEquals(1, queue.unsentCount())
        queue.settle(batch.map { it.id }, SendOutcome.Sent)
        assertEquals(1, queue.takeBatch(10).single().entry.repeatCount)
    }

    @Test
    fun `queue keeps the newest 500 and survives a reload`() {
        val file = File(folder.root, "errors.json")
        val queue = queue(file)
        repeat(501) { queue.add(entry(at = NOW + it * 10 * MINUTE)) }

        val reloaded = queue(file)
        assertEquals(500, reloaded.unsentCount())
        val all = reloaded.takeBatch(1_000)
        assertEquals(500, all.size)
        assertEquals(NOW + 10 * MINUTE, all.first().entry.firstAtMs)
        assertEquals(NOW + 500 * 10 * MINUTE, all.last().entry.firstAtMs)
    }

    @Test
    fun `a missing or unreadable file starts an empty queue`() {
        val garbage = File(folder.root, "garbage.json").apply { writeText("{not json") }

        assertEquals(0, queue(File(folder.root, "absent.json")).unsentCount())
        assertEquals(0, queue(garbage).unsentCount())
        assertNull(queue(garbage).oldestUnsentAtMs())
        queue(garbage).add(entry(at = NOW))
        assertEquals(1, queue(garbage).unsentCount())
    }

    @Test
    fun `a batch rejected three times is dropped, a 401 is never counted as a rejection`() {
        val queue = queue()
        queue.add(entry(at = NOW))

        repeat(5) { settleWith(queue, ApiResponse.Http(401, "")) }
        assertEquals(0, rejections(queue))

        settleWith(queue, ApiResponse.Http(400, ""))
        settleWith(queue, ApiResponse.Http(422, ""))
        assertEquals(2, rejections(queue))

        settleWith(queue, ApiResponse.Http(403, ""))
        assertEquals(0, queue.unsentCount())
        assertTrue(queue.takeBatch(10).isEmpty())
    }

    @Test
    fun `retry and signed out return the batch, sent removes it`() {
        val queue = queue()
        queue.add(entry(at = NOW))
        queue.add(entry(at = NOW, channel = "ITV"))

        val first = queue.takeBatch(1)
        assertEquals(1, queue.unsentCount())
        queue.settle(first.map { it.id }, SendOutcome.SignedOut)
        assertEquals(2, queue.unsentCount())

        val both = queue.takeBatch(50)
        queue.settle(both.map { it.id }, SendOutcome.Retry)
        assertEquals(2, queue.unsentCount())

        queue.settle(queue.takeBatch(50).map { it.id }, SendOutcome.Sent)
        assertEquals(0, queue.unsentCount())
        assertNull(queue.oldestUnsentAtMs())
    }

    @Test
    fun `oldest unsent time is when the entry joined the queue`() {
        val queue = queue()
        queue.add(entry(at = NOW - 3 * MINUTE))
        clock = NOW + MINUTE
        queue.add(entry(at = NOW, channel = "ITV"))

        assertEquals(NOW, queue.oldestUnsentAtMs())
    }

    @Test
    fun `entries recorded under another member or signed out are dropped, never sent`() {
        val file = File(folder.root, "errors.json")
        val queue = queue(file)
        queue.add(entry(at = NOW))
        user = "someone-else"
        queue.add(entry(at = NOW + 1_000))
        user = null
        queue.add(entry(at = NOW + 2_000))

        assertEquals(3, queue.unsentCount())
        assertEquals(2, queue(file).dropNotRecordedBy(MEMBER))
        val left = queue(file).takeBatch(10)
        assertEquals(listOf(NOW), left.map { it.entry.firstAtMs })
    }

    /** The single entry's rejection count; the peek puts it back unsent. */
    private fun rejections(queue: ErrorQueue): Int {
        val only = queue.takeBatch(10).single()
        queue.settle(listOf(only.id), SendOutcome.Retry)
        return only.rejections
    }

    private fun settleWith(queue: ErrorQueue, response: ApiResponse.Http) {
        val batch = queue.takeBatch(SendPolicy.BATCH_MAX)
        queue.settle(batch.map { it.id }, outcomeOf(Fetch.Ok(response)))
    }

    private fun entry(at: Long, code: String = "http_404", channel: String? = "BBC One") = ErrorEntry(
        firstAtMs = at, lastAtMs = at, repeatCount = 1,
        code = code, httpStatus = null, errorName = null,
        accountStatus = null, activeCons = null, maxConnections = null,
        engine = "exo", surface = "full", live = true,
        channel = channel, category = null, providerHost = null, proxy = null,
        model = null, android = null, messageShort = null,
    )

    private companion object {
        const val NOW = 1_792_000_931_000L
        const val MINUTE = 60_000L
        const val MEMBER = "member-1"
    }
}
