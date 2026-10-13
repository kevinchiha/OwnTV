// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.playlist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.own.owntv.kevbox.supabase.PlaylistRow

/** The only door into Core's sources and profiles. */
interface LocalSources {
    suspend fun fields(sourceId: Long): SourceFields?

    /** The ID of an Xtream source with this server URL and username, or null. */
    suspend fun findByLogin(serverUrl: String, username: String): Long?
    suspend fun profileIds(): List<Long>

    /** Linked to each of [profileIds], not yet synced. */
    suspend fun create(row: PlaylistRow, profileIds: List<Long>): Long
    suspend fun update(sourceId: Long, row: PlaylistRow)
    suspend fun link(sourceId: Long, profileIds: List<Long>)
    suspend fun unlinkEverywhere(sourceId: Long)
    suspend fun resync(sourceId: Long)
    suspend fun stopSync(sourceId: Long)
    suspend fun delete(sourceId: Long)
}

/**
 * Turns the member's rows into at most one visible Xtream source on this TV. [planReconcile]
 * decides; this class carries the plan out through [sources] and records it in [store].
 *
 * Every entry point runs behind one [Mutex] and reads the store inside it, so two triggers at once
 * never both create a source for an empty mapping (AC-15). [wipe] closes the reconciler until
 * [reopen], so a fetch that lands after sign-out creates nothing (AC-13, AC-14).
 */
class PlaylistReconciler(
    private val sources: LocalSources,
    private val store: ManagedSourceStore,
) {
    private val mutex = Mutex()

    @Volatile
    private var closed = false

    private val _screen = MutableStateFlow<PlaylistScreen?>(null)

    /** Null until the first [apply] of this run. */
    val screen: StateFlow<PlaylistScreen?> = _screen.asStateFlow()

    private val _rows = MutableStateFlow<List<PlaylistRow>>(emptyList())

    /** The last successful fetch, sorted by name. */
    val rows: StateFlow<List<PlaylistRow>> = _rows.asStateFlow()

    private val _currentClientNumber = MutableStateFlow<String?>(null)
    val currentClientNumber: StateFlow<String?> = _currentClientNumber.asStateFlow()

    private val _visibleSourceChanges = MutableStateFlow(0)

    /**
     * Goes up by one whenever the visible source changes (switch, re-pick, bring back, create) or
     * its credentials change, so the expiry check knows its last answer is stale.
     */
    val visibleSourceChanges: StateFlow<Int> = _visibleSourceChanges.asStateFlow()

    /** Only ever called with a successful fetch. */
    suspend fun apply(rows: List<PlaylistRow>) = mutex.withLock {
        if (closed) return@withLock
        val sorted = rows.sortedBy { it.name }
        reconcile(sorted, chosen = null)
        _rows.value = sorted
    }

    /** The first-run screen and Settings > Choose playlist. */
    suspend fun choose(clientNumber: String) = mutex.withLock {
        if (closed || _screen.value == null) return@withLock
        if (_rows.value.none { it.clientNumber == clientNumber }) return@withLock
        // A re-pick of the same row counts as a change too.
        if (!reconcile(_rows.value, chosen = clientNumber)) _visibleSourceChanges.value += 1
    }

    /**
     * Run after any profile insert or delete. Core's `ProfileManager.create` links every existing
     * source to a new profile, hidden ones included, so this links the visible source to every
     * profile and takes every hidden one off again (AC-24). A pick that waited for the first profile
     * gets its source now.
     */
    suspend fun onProfilesChanged(profileIds: List<Long>) = mutex.withLock {
        if (closed) return@withLock
        val state = store.read()
        state.sources.values.forEach { mapping ->
            if (sources.fields(mapping.sourceId) == null) return@forEach
            if (mapping.hidden) {
                sources.unlinkEverywhere(mapping.sourceId)
            } else {
                sources.link(mapping.sourceId, profileIds)
            }
        }
        val waitingForProfile = state.pick != null && state.sources[state.pick] == null
        if (profileIds.isNotEmpty() && waitingForProfile && _screen.value != null) {
            reconcile(_rows.value, chosen = null)
        }
    }

    /** The visible picked source, for the expiry check. */
    suspend fun currentSourceId(): Long? {
        val state = store.read()
        val mapping = state.pick?.let { state.sources[it] } ?: return null
        if (mapping.hidden) return null
        return mapping.sourceId.takeIf { sources.fields(it) != null }
    }

    /**
     * Sign-out and a refused refresh: deletes every managed source, visible and hidden (Core
     * cascades their channels, movies and series), forgets the mapping and closes the reconciler.
     */
    suspend fun wipe() = mutex.withLock {
        closed = true
        val state = store.read()
        val ids = state.sources.values.map { it.sourceId }.toMutableSet()
        // A Create the process died in may have left a source the mapping never recorded.
        state.pendingCreate
            ?.let { pending -> _rows.value.firstOrNull { it.clientNumber == pending } }
            ?.let { row -> sources.findByLogin(row.serverUrl, row.username) }
            ?.let { ids += it }
        ids.forEach { id ->
            try {
                sources.delete(id)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
        store.clear()
        _screen.value = null
        _rows.value = emptyList()
        _currentClientNumber.value = null
    }

    /** After a successful sign-in. */
    fun reopen() {
        closed = false
    }

    /** Must run inside [mutex]. Returns true when it bumped [visibleSourceChanges]. */
    private suspend fun reconcile(rows: List<PlaylistRow>, chosen: String?): Boolean {
        val stored = store.read()
        // A mapping whose source Core no longer has (for example after "Reset data") counts as no
        // mapping, so a new source that reuses its Room ID is never touched through it.
        val live = mutableMapOf<String, ManagedSource>()
        var pickFields: SourceFields? = null
        stored.sources.forEach { (clientNumber, mapping) ->
            val fields = sources.fields(mapping.sourceId) ?: return@forEach
            live[clientNumber] = mapping
            if (clientNumber == stored.pick && !mapping.hidden) pickFields = fields
        }
        val state = stored.copy(sources = live)
        val plan = planReconcile(rows, state, pickFields, chosen)

        val visibleBefore = state.visibleId()
        val mapping = live.toMutableMap()
        var pending = state.pendingCreate
        var visibleChanged = false
        try {
            val profiles = sources.profileIds()
            for (step in plan.steps) {
                when (step) {
                    is ReconcileStep.Hide -> {
                        sources.stopSync(step.sourceId)
                        sources.unlinkEverywhere(step.sourceId)
                        mapping[step.clientNumber] = ManagedSource(step.sourceId, hidden = true)
                    }
                    is ReconcileStep.Unhide -> {
                        sources.update(step.sourceId, step.row)
                        sources.link(step.sourceId, profiles)
                        sources.resync(step.sourceId)
                        mapping[step.clientNumber] = ManagedSource(step.sourceId, hidden = false)
                        visibleChanged = true
                    }
                    is ReconcileStep.UpdateInPlace -> {
                        sources.update(step.sourceId, step.row)
                        sources.resync(step.sourceId)
                        visibleChanged = true
                    }
                    is ReconcileStep.Create -> {
                        // Sign-in comes before onboarding: keep the pick, create once a profile exists.
                        if (profiles.isEmpty()) continue
                        val row = step.row
                        val adopted = if (pending == row.clientNumber) {
                            sources.findByLogin(row.serverUrl, row.username)
                        } else {
                            null
                        }
                        val id = if (adopted != null) {
                            sources.update(adopted, row)
                            sources.link(adopted, profiles)
                            adopted
                        } else {
                            pending = row.clientNumber
                            store.write(ManagedState(plan.pick, mapping.toMap(), pending))
                            sources.create(row, profiles)
                        }
                        mapping[row.clientNumber] = ManagedSource(id, hidden = false)
                        pending = null
                        sources.resync(id)
                        visibleChanged = true
                    }
                }
            }
        } finally {
            store.write(ManagedState(plan.pick, mapping.toMap(), pending))
        }

        val after = ManagedState(plan.pick, mapping, pending)
        val bump = visibleChanged || after.visibleId() != visibleBefore
        if (bump) _visibleSourceChanges.value += 1
        _screen.value = plan.screen
        _currentClientNumber.value = plan.pick
        return bump
    }

    private fun ManagedState.visibleId(): Long? =
        pick?.let { sources[it] }?.takeIf { !it.hidden }?.sourceId
}
