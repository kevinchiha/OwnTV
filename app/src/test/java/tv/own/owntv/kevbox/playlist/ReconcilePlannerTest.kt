package tv.own.owntv.kevbox.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.kevbox.supabase.PlaylistRow

class ReconcilePlannerTest {

    private fun row(number: String, name: String = "Line $number") =
        PlaylistRow(clientNumber = number, name = name, serverUrl = "http://s$number.example", username = "u$number", password = "p$number")

    private val empty = ManagedState(pick = null, sources = emptyMap())

    private fun visible(id: Long) = ManagedSource(id, hidden = false)
    private fun hidden(id: Long) = ManagedSource(id, hidden = true)

    @Test
    fun `one row is created and picked with no prompt`() {
        val only = row("100")
        val plan = planReconcile(listOf(only), empty, current = null)
        assertEquals(listOf(ReconcileStep.Create(only)), plan.steps)
        assertEquals("100", plan.pick)
        assertEquals(PlaylistScreen.Home, plan.screen)
    }

    @Test
    fun `two rows with no pick ask, sorted by name`() {
        val zed = row("1", name = "Zed")
        val alpha = row("2", name = "Alpha")
        val plan = planReconcile(listOf(zed, alpha), empty, current = null)
        assertEquals(emptyList<ReconcileStep>(), plan.steps)
        assertNull(plan.pick)
        assertEquals(PlaylistScreen.ChooseFirst(listOf(alpha, zed)), plan.screen)
    }

    @Test
    fun `a row added later does not ask a TV with a pick`() {
        val picked = row("1")
        val state = ManagedState(pick = "1", sources = mapOf("1" to visible(7)))
        val plan = planReconcile(listOf(picked, row("2")), state, current = picked.toFields())
        assertEquals(emptyList<ReconcileStep>(), plan.steps)
        assertEquals("1", plan.pick)
        assertEquals(PlaylistScreen.Home, plan.screen)
    }

    @Test
    fun `the picked row removed hides its source and uses the single row left`() {
        val left = row("2")
        val state = ManagedState(pick = "1", sources = mapOf("1" to visible(7)))
        val plan = planReconcile(listOf(left), state, current = row("1").toFields())
        assertEquals(listOf(ReconcileStep.Hide("1", 7), ReconcileStep.Create(left)), plan.steps)
        assertEquals("2", plan.pick)
        assertEquals(PlaylistScreen.Home, plan.screen)
    }

    @Test
    fun `the picked row removed hides its source and asks among several`() {
        val b = row("2", name = "B")
        val a = row("3", name = "A")
        val state = ManagedState(pick = "1", sources = mapOf("1" to visible(7)))
        val plan = planReconcile(listOf(b, a), state, current = row("1").toFields())
        assertEquals(listOf(ReconcileStep.Hide("1", 7)), plan.steps)
        assertNull(plan.pick)
        assertEquals(PlaylistScreen.ChooseFirst(listOf(a, b)), plan.screen)
    }

    @Test
    fun `the picked row removed with nothing left hides its source and shows NoPlaylist`() {
        val state = ManagedState(pick = "1", sources = mapOf("1" to visible(7), "2" to hidden(8)))
        val plan = planReconcile(emptyList(), state, current = row("1").toFields())
        assertEquals(listOf(ReconcileStep.Hide("1", 7)), plan.steps)
        assertNull(plan.pick)
        assertEquals(PlaylistScreen.NoPlaylist, plan.screen)
    }

    @Test
    fun `a changed name, server, username or password updates in place with the same source id`() {
        val original = row("1")
        val state = ManagedState(pick = "1", sources = mapOf("1" to visible(7)))
        listOf(
            original.copy(name = "Renamed"),
            original.copy(serverUrl = "http://moved.example"),
            original.copy(username = "other"),
            original.copy(password = "secret2"),
        ).forEach { changed ->
            val plan = planReconcile(listOf(changed), state, current = original.toFields())
            assertEquals(listOf(ReconcileStep.UpdateInPlace(7, changed)), plan.steps)
            assertEquals("1", plan.pick)
            assertEquals(PlaylistScreen.Home, plan.screen)
        }
    }

    @Test
    fun `choosing another row hides the old one and brings back a hidden match`() {
        val first = row("1")
        val second = row("2")
        val state = ManagedState(pick = "1", sources = mapOf("1" to visible(7), "2" to hidden(8)))
        val plan = planReconcile(listOf(first, second), state, current = first.toFields(), chosen = "2")
        assertEquals(listOf(ReconcileStep.Hide("1", 7), ReconcileStep.Unhide("2", 8, second)), plan.steps)
        assertEquals("2", plan.pick)
        assertEquals(PlaylistScreen.Home, plan.screen)
    }

    @Test
    fun `a chosen row that is not in the fetch falls back to the stored pick`() {
        val first = row("1")
        val state = ManagedState(pick = "1", sources = mapOf("1" to visible(7)))
        val plan = planReconcile(listOf(first, row("2")), state, current = first.toFields(), chosen = "9")
        assertEquals(emptyList<ReconcileStep>(), plan.steps)
        assertEquals("1", plan.pick)
    }

    @Test
    fun `a stored pick that waits for its source creates it and stays Home`() {
        val picked = row("1")
        val state = ManagedState(pick = "1", sources = emptyMap())
        val plan = planReconcile(listOf(picked, row("2")), state, current = null)
        assertEquals(listOf(ReconcileStep.Create(picked)), plan.steps)
        assertEquals(PlaylistScreen.Home, plan.screen)
    }
}
