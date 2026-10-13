// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.playlist

import tv.own.owntv.kevbox.supabase.PlaylistRow

/** The four fields of an Xtream source that the admin controls. */
data class SourceFields(val name: String, val serverUrl: String, val username: String, val password: String)

/** One of the member's lines as it exists in Core. A hidden source is unlinked from every profile. */
data class ManagedSource(val sourceId: Long, val hidden: Boolean)

/** What this TV remembers about the member's lines, kept in app storage, not in Core. */
data class ManagedState(
    val pick: String?,
    val sources: Map<String, ManagedSource>, // key: client number
    val pendingCreate: String? = null, // client number whose Create started but was not yet mapped
)

/** Where the app goes once the rows are reconciled. */
sealed interface PlaylistScreen {
    data object Home : PlaylistScreen
    data class ChooseFirst(val rows: List<PlaylistRow>) : PlaylistScreen // sorted by name
    data object NoPlaylist : PlaylistScreen
}

sealed interface ReconcileStep {
    data class Create(val row: PlaylistRow) : ReconcileStep
    data class UpdateInPlace(val sourceId: Long, val row: PlaylistRow) : ReconcileStep
    data class Hide(val clientNumber: String, val sourceId: Long) : ReconcileStep
    data class Unhide(val clientNumber: String, val sourceId: Long, val row: PlaylistRow) : ReconcileStep
}

data class ReconcilePlan(val steps: List<ReconcileStep>, val pick: String?, val screen: PlaylistScreen)

fun PlaylistRow.toFields() = SourceFields(name = name, serverUrl = serverUrl, username = username, password = password)

/**
 * Decides what to do with the member's [rows] on this TV, with no side effects.
 *
 * [current] holds the fields Core has for the stored pick's visible source, or null when there is
 * none. [chosen] is a row the member just picked by hand. At most one source stays visible: every
 * other visible mapping is hidden, which is also how a switch works (AC-17). Hidden sources are
 * never deleted here, so a line that comes back keeps its source ID (AC-21).
 */
fun planReconcile(
    rows: List<PlaylistRow>,
    state: ManagedState,
    current: SourceFields?,
    chosen: String? = null,
): ReconcilePlan {
    val byNumber = rows.associateBy { it.clientNumber }
    val target: PlaylistRow? = chosen?.let { byNumber[it] }
        ?: state.pick?.let { byNumber[it] }
        ?: rows.singleOrNull()

    val steps = mutableListOf<ReconcileStep>()
    state.sources.forEach { (clientNumber, mapping) ->
        if (!mapping.hidden && clientNumber != target?.clientNumber) {
            steps += ReconcileStep.Hide(clientNumber, mapping.sourceId)
        }
    }

    if (target == null) {
        val screen = if (rows.isEmpty()) {
            PlaylistScreen.NoPlaylist
        } else {
            PlaylistScreen.ChooseFirst(rows.sortedBy { it.name })
        }
        return ReconcilePlan(steps, pick = null, screen = screen)
    }

    val mapping = state.sources[target.clientNumber]
    when {
        mapping == null -> steps += ReconcileStep.Create(target)
        mapping.hidden -> steps += ReconcileStep.Unhide(target.clientNumber, mapping.sourceId, target)
        // [current] describes the stored pick's source, so only compare when the target is that pick.
        target.clientNumber == state.pick && current != null && current != target.toFields() ->
            steps += ReconcileStep.UpdateInPlace(mapping.sourceId, target)
    }
    return ReconcilePlan(steps, pick = target.clientNumber, screen = PlaylistScreen.Home)
}
