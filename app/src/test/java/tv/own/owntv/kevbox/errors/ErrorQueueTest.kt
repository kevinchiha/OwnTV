// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
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
    fun `entries recorded under another member are dropped, signed-out ones go out under the next member`() {
        val file = File(folder.root, "errors.json")
        val queue = queue(file)
        queue.add(entry(at = NOW))
        user = "someone-else"
        queue.add(entry(at = NOW + 1_000))
        user = null
        queue.add(entry(at = NOW + 2_000))

        assertEquals(3, queue.unsentCount())
        assertEquals(1, queue(file).dropNotRecordedBy(MEMBER))
        val left = queue(file).takeBatch(10)
        assertEquals(listOf(NOW, NOW + 2_000), left.map { it.entry.firstAtMs })

        // The signed-out entry now belongs to MEMBER, so a later member sends neither.
        assertEquals(2, queue(file).dropNotRecordedBy("third-member"))
        assertEquals(0, queue(file).unsentCount())
    }

    @Test
    fun `a refused batch is resent one entry at a time and only the bad entry collects rejections`() {
        val file = File(folder.root, "errors.json")
        val queue = queue(file)
        queue.add(entry(at = NOW, channel = "Good 1"))
        queue.add(entry(at = NOW, channel = "Bad"))
        queue.add(entry(at = NOW, channel = "Good 2"))

        val first = queue.takeBatch(SendPolicy.BATCH_MAX)
        assertEquals(3, first.size)
        queue.settle(first.map { it.id }, SendOutcome.Rejected)

        // Each goes alone now, also after a restart; the server takes the good ones and keeps
        // refusing the bad one, which goes after its third refusal of its own.
        val reloaded = queue(file)
        val sent = mutableListOf<String?>()
        repeat(5) {
            val batch = reloaded.takeBatch(SendPolicy.BATCH_MAX)
            assertEquals(1, batch.size)
            val only = batch.single()
            if (only.entry.channel == "Bad") {
                reloaded.settle(listOf(only.id), SendOutcome.Rejected)
            } else {
                assertEquals(0, only.rejections)
                sent += only.entry.channel
                reloaded.settle(listOf(only.id), SendOutcome.Sent)
            }
        }
        assertEquals(listOf("Good 1", "Good 2"), sent)
        assertEquals(0, reloaded.unsentCount())
        assertTrue(reloaded.takeBatch(10).isEmpty())
    }

    @Test
    fun `a new occurrence merged into a refused entry resets its rejections`() {
        val queue = queue()
        queue.add(entry(at = NOW))
        settleWith(queue, ApiResponse.Http(400, ""))
        settleWith(queue, ApiResponse.Http(400, ""))
        assertEquals(2, rejections(queue))

        queue.add(entry(at = NOW + MINUTE))
        assertEquals(0, rejections(queue))
    }

    @Test
    fun `entries with no channel merge only when their provider host matches`() {
        val queue = queue()
        queue.add(entry(at = NOW, code = "guide_unreachable", channel = null, host = "epg-a.example"))
        queue.add(entry(at = NOW + 1_000, code = "guide_unreachable", channel = null, host = "epg-b.example"))
        queue.add(entry(at = NOW + 2_000, code = "guide_unreachable", channel = null, host = "epg-a.example"))
        queue.add(entry(at = NOW + 3_000, code = "guide_unreachable", channel = null, host = null))
        queue.add(entry(at = NOW + 4_000, code = "guide_unreachable", channel = null, host = null))
        // With a channel set the host plays no part.
        queue.add(entry(at = NOW + 5_000, channel = "BBC One", host = "cdn-1.example"))
        queue.add(entry(at = NOW + 6_000, channel = "BBC One", host = "cdn-2.example"))

        val batch = queue.takeBatch(10)
        assertEquals(
            listOf("epg-a.example" to 2, "epg-b.example" to 1, null to 2, "cdn-1.example" to 2),
            batch.map { it.entry.providerHost to it.entry.repeatCount },
        )
    }

    @Test
    fun `a merged entry reports the proxy flag of its latest repeat`() {
        // 2026-10-15, client 237: the first channel-list failure came before the playlist check
        // switched the proxy on, the next five went through it.
        val queue = queue()
        queue.add(playlistEntry(at = NOW, proxy = false))
        (1..5).forEach { queue.add(playlistEntry(at = NOW + it * 30_000L, proxy = true)) }

        val merged = queue.takeBatch(10).single().entry
        assertEquals(6, merged.repeatCount)
        assertEquals(true, merged.proxy)
        assertEquals(NOW, merged.firstAtMs)
    }

    @Test
    fun `an older repeat does not overwrite a newer flag`() {
        val queue = queue()
        queue.add(playlistEntry(at = NOW + MINUTE, proxy = true))
        queue.add(playlistEntry(at = NOW, proxy = false))

        val merged = queue.takeBatch(10).single().entry
        assertEquals(2, merged.repeatCount)
        assertEquals(true, merged.proxy)
    }

    @Test
    fun `a latest repeat with no proxy reading keeps the known flag`() {
        // The server's merge does coalesce(v_proxy, proxy): a failed read never wipes a known flag.
        listOf(true, false).forEach { known ->
            val queue = queue(File(folder.root, "errors-$known.json"))
            queue.add(playlistEntry(at = NOW, proxy = known))
            queue.add(playlistEntry(at = NOW + 30_000L, proxy = null))

            val merged = queue.takeBatch(10).single().entry
            assertEquals(2, merged.repeatCount)
            assertEquals(known, merged.proxy)
        }
    }

    @Test
    fun `proxy off stays false`() {
        val queue = queue()
        repeat(6) { queue.add(playlistEntry(at = NOW + it * 30_000L, proxy = false)) }

        val merged = queue.takeBatch(10).single().entry
        assertEquals(6, merged.repeatCount)
        assertEquals(false, merged.proxy)
    }

    private fun playlistEntry(at: Long, proxy: Boolean?) =
        entry(at = at, code = "playlist_download_failed", channel = null, host = "provider.example").copy(proxy = proxy)

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

    private fun entry(at: Long, code: String = "http_404", channel: String? = "BBC One", host: String? = null) = ErrorEntry(
        firstAtMs = at, lastAtMs = at, repeatCount = 1,
        code = code, httpStatus = null, errorName = null,
        accountStatus = null, activeCons = null, maxConnections = null,
        engine = "exo", surface = "full", live = true,
        channel = channel, category = null, providerHost = host, proxy = null,
        model = null, android = null, messageShort = null,
    )

    private companion object {
        const val NOW = 1_792_000_931_000L
        const val MINUTE = 60_000L
        const val MEMBER = "member-1"
    }
}
