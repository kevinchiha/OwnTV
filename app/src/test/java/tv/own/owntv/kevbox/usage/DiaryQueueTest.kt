// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.usage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiaryQueueTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file: File get() = File(folder.root, "diary.json")
    private val openFile: File get() = File(folder.root, "diary_open.json")

    private fun queue() = DiaryQueue(file, openFile)

    private fun stretch(n: Int, lengthMs: Long = MINUTE) = Stretch(
        id = "s-$n",
        startedAtMs = START + n * HOUR,
        endedAtMs = START + n * HOUR + lengthMs,
        inBox = n % 2 == 0,
        lastPressAtMs = START + n * HOUR,
        channel = "channel $n",
        category = null,
        programme = null,
        providerHost = "provider.example",
        proxy = false,
    )

    @Test
    fun `the queue survives a restart, caps at 2,000 and drops the oldest`() {
        val first = queue()
        for (n in 1..DIARY_QUEUE_MAX + 5) first.add(stretch(n), USER_A)
        assertEquals(DIARY_QUEUE_MAX, first.size())

        // A fresh queue on the same file: the app restarted (AC-8).
        val restarted = queue()
        assertEquals(DIARY_QUEUE_MAX, restarted.size())
        val kept = restarted.oldest(DIARY_QUEUE_MAX, USER_A)
        assertEquals("s-6", kept.first().id)
        assertEquals("s-${DIARY_QUEUE_MAX + 5}", kept.last().id)
        assertEquals(stretch(6), kept.first())
    }

    @Test
    fun `a missing or unreadable file starts empty`() {
        assertEquals(0, queue().size())
        file.writeText("not json {")
        assertEquals(0, queue().size())
        queue().add(stretch(1), USER_A)
        assertEquals(1, queue().size())
    }

    @Test
    fun `a stretch under 10 seconds is ignored`() {
        val queue = queue()
        queue.add(stretch(1, lengthMs = 9_999), USER_A)
        queue.add(stretch(2, lengthMs = STRETCH_MIN_MS), USER_A)

        assertEquals(listOf("s-2"), queue.oldest(10, USER_A).map { it.id })
    }

    @Test
    fun `oldest and remove work per member and per id`() {
        val queue = queue()
        queue.add(stretch(1), USER_A)
        queue.add(stretch(2), USER_B)
        queue.add(stretch(3), USER_A)

        assertEquals(listOf("s-1", "s-3"), queue.oldest(10, USER_A).map { it.id })
        assertEquals(listOf("s-1"), queue.oldest(1, USER_A).map { it.id })

        queue.remove(listOf("s-1"))
        assertEquals(listOf("s-3"), queue().oldest(10, USER_A).map { it.id })
        assertEquals(2, queue().size())
    }

    @Test
    fun `dropNotRecordedBy removes other members' stretches, the open one included`() {
        val queue = queue()
        queue.add(stretch(1), USER_A)
        queue.add(stretch(2), USER_A)
        queue.add(stretch(3), USER_B)
        queue.saveOpen(stretch(4), USER_A)

        assertEquals(3, queue.dropNotRecordedBy(USER_B))
        assertEquals(listOf("s-3"), queue.oldest(10, USER_B).map { it.id })

        // Nothing of A's comes back from the open file either.
        queue().recoverOpen()
        assertEquals(1, queue().size())
    }

    @Test
    fun `a saved open stretch comes back after a kill`() {
        val running = queue()
        val opened = stretch(1, lengthMs = 0)
        // Saved every 60 s; the last save before the kill was 24 minutes in.
        running.saveOpen(opened.copy(endedAtMs = opened.startedAtMs + 23 * MINUTE), USER_A)
        running.saveOpen(opened.copy(endedAtMs = opened.startedAtMs + 24 * MINUTE), USER_A)

        // The process dies; the next start makes a fresh queue on the same files (AC-6).
        val next = queue()
        next.recoverOpen()

        val recovered = next.oldest(10, USER_A)
        assertEquals(1, recovered.size)
        assertEquals(opened.startedAtMs + 24 * MINUTE, recovered.single().endedAtMs)
        // The open file is cleared: recovering again adds nothing.
        queue().recoverOpen()
        assertEquals(1, queue().size())
    }

    @Test
    fun `a saved open stretch under 10 seconds is dropped`() {
        val opened = stretch(1, lengthMs = 9_000)
        queue().saveOpen(opened, USER_A)

        val next = queue()
        next.recoverOpen()

        assertEquals(0, next.size())
        assertEquals(false, openFile.exists())
    }

    @Test
    fun `a saved open stretch stays on disk when the queue write fails`() {
        // A queue file the rename can never replace: a directory with something in it.
        val blocked = File(folder.root, "blocked.json").apply { mkdirs() }
        File(blocked, "inside").writeText("x")
        val opened = stretch(1, lengthMs = 24 * MINUTE)
        DiaryQueue(blocked, openFile).saveOpen(opened, USER_A)

        DiaryQueue(blocked, openFile).recoverOpen()

        // Nothing reached the queue file, so the open save is kept for the next start (AC-6).
        assertTrue(openFile.exists())
        val next = queue()
        next.recoverOpen()
        assertEquals(listOf(opened), next.oldest(10, USER_A))
    }

    @Test
    fun `a recovered open save never replaces the finished stretch of the same id`() {
        val running = queue()
        val opened = stretch(1, lengthMs = 0)
        running.saveOpen(opened.copy(endedAtMs = opened.startedAtMs + 24 * MINUTE), USER_A)
        // It finished at 30 minutes and was queued; the process died before the open save was cleared.
        running.add(opened.copy(endedAtMs = opened.startedAtMs + 30 * MINUTE), USER_A)

        val next = queue()
        next.recoverOpen()

        assertEquals(opened.startedAtMs + 30 * MINUTE, next.oldest(10, USER_A).single().endedAtMs)
        assertFalse(openFile.exists())
    }

    @Test
    fun `clearOpen leaves nothing to recover`() {
        val queue = queue()
        queue.saveOpen(stretch(1), USER_A)
        queue.clearOpen()

        queue().recoverOpen()
        assertEquals(0, queue().size())
    }

    private companion object {
        const val USER_A = "user-a"
        const val USER_B = "user-b"
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val START = 1_760_000_000_000L
    }
}
