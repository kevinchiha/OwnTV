// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.playlist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.own.owntv.kevbox.supabase.PlaylistRow

/** The only door into Core's sources and profiles. */
interface LocalSources {
    suspend fun fields(sourceId: Long): SourceFields?

    /** The ID of an Xtream source with this server URL and username, or null. */
    suspend fun findByLogin(serverUrl: String, username: String): Long?
    suspend fun profileIds(): List<Long>

    /** The IDs of the sources linked to [profileId], again whenever that set changes. */
    fun observeLinkedSourceIds(profileId: Long): Flow<List<Long>>

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
     * Goes up by one whenever the visible source changes (switch, bring back, create) or its
     * credentials change, so the expiry check knows its last answer is stale. A re-pick of the row
     * already showing changes nothing and leaves it alone.
     */
    val visibleSourceChanges: StateFlow<Int> = _visibleSourceChanges.asStateFlow()

    private val _visibleSourceId = MutableStateFlow<Long?>(null)

    /**
     * The visible picked source after the last reconcile of this run, null before the first one and
     * after [wipe]. An admin edit keeps it, so only a real switch stops what is playing (AC-19, AC-36).
     */
    val visibleSourceId: StateFlow<Long?> = _visibleSourceId.asStateFlow()

    /** Only ever called with a successful fetch. */
    suspend fun apply(rows: List<PlaylistRow>) = mutex.withLock {
        if (closed) return@withLock
        val sorted = rows.sortedBy { it.name }
        reconcile(sorted, chosen = null)
        _rows.value = sorted
    }

    /** The first-run screen and Settings > Choose playlist. */
    suspend fun choose(clientNumber: String) {
        mutex.withLock {
            if (closed || _screen.value == null) return@withLock
            if (_rows.value.none { it.clientNumber == clientNumber }) return@withLock
            reconcile(_rows.value, chosen = clientNumber)
        }
    }

    /**
     * Run after any profile insert or delete: links the visible source to every profile and takes
     * every hidden one off again (AC-24). A pick that waited for the first profile gets its source
     * now. Core links a new profile's sources after its row lands, so [watchProfiles] also watches
     * the links themselves.
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

    /**
     * Calls [onProfilesChanged] for every emission of [profileIds], and while that profile list
     * stands, unlinks a hidden source the moment any of those profiles gains a link to it. Core's
     * `ProfileManager.create` inserts the profile first and links every existing source after it,
     * one by one and hidden ones included, so a single unlink at the profile emission can run before
     * the link it should undo (AC-24). Never returns while [profileIds] keeps emitting.
     */
    suspend fun watchProfiles(profileIds: Flow<List<Long>>) {
        profileIds.collectLatest { ids ->
            guarded { onProfilesChanged(ids) }
            ids.map { sources.observeLinkedSourceIds(it) }.merge().collect { linked ->
                guarded { unlinkHidden(linked) }
            }
        }
    }

    private suspend fun unlinkHidden(linkedSourceIds: List<Long>) = mutex.withLock {
        if (closed) return@withLock
        store.read().sources.values
            .filter { it.hidden && it.sourceId in linkedSourceIds && sources.fields(it.sourceId) != null }
            .forEach { sources.unlinkEverywhere(it.sourceId) }
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
     *
     * A source Core refused to delete stays in the mapping, hidden and unlinked where possible, so
     * the next wipe tries it again instead of leaving a line nobody tracks. A Create the process
     * died in is found by the login it recorded, so a cold start that goes straight to sign-out
     * still removes it.
     */
    suspend fun wipe() = mutex.withLock {
        closed = true
        val state = store.read()
        val kept = mutableMapOf<String, ManagedSource>()
        state.sources.forEach { (clientNumber, mapping) ->
            if (!tryDelete(mapping.sourceId)) {
                attempt { sources.unlinkEverywhere(mapping.sourceId) }
                kept[clientNumber] = mapping.copy(hidden = true)
            }
        }
        val pendingLeft = state.pendingCreate?.let { raw ->
            val pending = PendingCreate.parse(raw)
            val login = pending.login ?: _rows.value.firstOrNull { it.clientNumber == pending.clientNumber }
                ?.let { it.serverUrl to it.username }
                // Nothing says which source it was: nothing to delete.
                ?: return@let null
            val mapped = state.sources.values.map { it.sourceId }.toSet()
            var found: Long? = null
            val looked = attempt { found = sources.findByLogin(login.first, login.second) }
            val orphan = found
            when {
                !looked -> raw
                orphan == null || orphan in mapped -> null
                tryDelete(orphan) -> null
                else -> raw
            }
        }
        if (kept.isEmpty() && pendingLeft == null) {
            store.clear()
        } else {
            store.write(ManagedState(pick = null, sources = kept, pendingCreate = pendingLeft))
        }
        _screen.value = null
        _rows.value = emptyList()
        _currentClientNumber.value = null
        _visibleSourceId.value = null
    }

    private suspend fun tryDelete(sourceId: Long): Boolean = attempt { sources.delete(sourceId) }

    /** Runs [block]; false when it threw. Cancellation still ends the caller. */
    private suspend fun attempt(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        false
    }

    /** A failed Core call must not end [watchProfiles]: the next emission tries again. */
    private suspend fun guarded(block: suspend () -> Unit) {
        attempt(block)
    }

    /** After a successful sign-in. */
    fun reopen() {
        closed = false
    }

    /** Must run inside [mutex]. */
    private suspend fun reconcile(rows: List<PlaylistRow>, chosen: String?) {
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
        val pendingNumber = pending?.let { PendingCreate.parse(it).clientNumber }
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
                        val adopted = if (pendingNumber == row.clientNumber) {
                            sources.findByLogin(row.serverUrl, row.username)
                        } else {
                            null
                        }
                        val id = if (adopted != null) {
                            sources.update(adopted, row)
                            sources.link(adopted, profiles)
                            adopted
                        } else {
                            pending = PendingCreate(row.clientNumber, row.serverUrl to row.username).encode()
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
        val visibleAfter = after.visibleId()
        // The ID first: a reader of the counter compares it with the ID to tell a switch from an edit.
        _visibleSourceId.value = visibleAfter
        if (visibleChanged || visibleAfter != visibleBefore) _visibleSourceChanges.value += 1
        _screen.value = plan.screen
        _currentClientNumber.value = plan.pick
    }

    private fun ManagedState.visibleId(): Long? =
        pick?.let { sources[it] }?.takeIf { !it.hidden }?.sourceId
}

/**
 * What [ManagedState.pendingCreate] holds: the client number, then the server URL and username the
 * Create used, so [PlaylistReconciler.wipe] can find the source with no fetched rows in memory. A
 * value written before the login was recorded holds the client number alone.
 */
internal data class PendingCreate(val clientNumber: String, val login: Pair<String, String>?) {
    fun encode(): String = login?.let { (url, user) -> listOf(clientNumber, url, user).joinToString(SEPARATOR.toString()) }
        ?: clientNumber

    companion object {
        private const val SEPARATOR = '\u001F'

        fun parse(raw: String): PendingCreate {
            val parts = raw.split(SEPARATOR)
            return if (parts.size == 3) PendingCreate(parts[0], parts[1] to parts[2]) else PendingCreate(raw, null)
        }
    }
}
