// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.vpn

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VpnEventQueueTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file: File get() = File(folder.root, VPN_EVENTS_FILE)

    private fun queue() = VpnEventQueue(file)

    private fun event(n: Int, kind: VpnEventKind = VpnEventKind.CONNECTED) = VpnEvent(
        id = "e-$n",
        kind = kind,
        server = if (kind == VpnEventKind.CONNECTED) "vpn-de.premiumize.me" else null,
        atMs = START + n * 1_000L,
        clientNumber = if (n % 2 == 0) "C$n" else null,
    )

    @Test
    fun `the queue survives a restart, caps at 500 and drops the oldest`() {
        val first = queue()
        for (n in 1..VPN_QUEUE_MAX + 5) first.add(event(n), USER_A)
        assertEquals(VPN_QUEUE_MAX, first.size())

        // A fresh queue on the same file: the app restarted.
        val restarted = queue()
        assertEquals(VPN_QUEUE_MAX, restarted.size())
        val kept = restarted.oldest(VPN_QUEUE_MAX, USER_A)
        assertEquals(event(6), kept.first())
        assertEquals(event(VPN_QUEUE_MAX + 5), kept.last())
    }

    @Test
    fun `every kind and a null server or client number come back from the file as they went in`() {
        val events = VpnEventKind.entries.mapIndexed { index, kind -> event(index + 1, kind) } +
            VpnEvent("e-9", VpnEventKind.FELL_BACK, "vpn-de.premiumize.me,vpn-nl.premiumize.me", START, "C9")
        events.forEach { queue().add(it, USER_A) }

        assertEquals(events, queue().oldest(10, USER_A))
        assertFalse(File(folder.root, "$VPN_EVENTS_FILE.tmp").exists())
    }

    @Test
    fun `a missing or unreadable file starts empty`() {
        assertEquals(0, queue().size())
        file.writeText("not json {")
        assertEquals(0, queue().size())
        queue().add(event(1), USER_A)
        assertEquals(1, queue().size())
    }

    @Test
    fun `oldest gives one member's events in order, and remove takes exactly the ids given`() {
        val queue = queue()
        (1..5).forEach { queue.add(event(it), if (it == 3) USER_B else USER_A) }

        assertEquals(listOf("e-1", "e-2"), queue.oldest(2, USER_A).map { it.id })
        assertEquals(listOf("e-3"), queue.oldest(10, USER_B).map { it.id })

        queue.remove(listOf("e-1", "e-4", "e-unknown"))
        assertEquals(listOf("e-2", "e-5"), queue().oldest(10, USER_A).map { it.id })
    }

    @Test
    fun `dropNotRecordedBy removes the other members' events and says how many`() {
        val queue = queue()
        queue.add(event(1), USER_A)
        queue.add(event(2), USER_B)
        queue.add(event(3), USER_A)

        assertEquals(2, queue.dropNotRecordedBy(USER_B))
        assertEquals(0, queue.dropNotRecordedBy(USER_B))
        assertEquals(listOf("e-2"), queue().oldest(10, USER_B).map { it.id })
        assertEquals(1, queue().size())
    }

    @Test
    fun `adding an event with a queued id replaces it instead of doubling it`() {
        val queue = queue()
        queue.add(event(1), USER_A)
        queue.add(event(2), USER_A)
        queue.add(event(1).copy(atMs = START + 99_000L), USER_A)

        assertEquals(listOf("e-2", "e-1"), queue.oldest(10, USER_A).map { it.id })
        assertEquals(START + 99_000L, queue.oldest(10, USER_A).last().atMs)
    }

    private companion object {
        const val USER_A = "user-a"
        const val USER_B = "user-b"
        const val START = 1_790_000_000_000L
    }
}
