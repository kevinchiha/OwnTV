// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.playlist

import android.content.SharedPreferences
import org.json.JSONException
import org.json.JSONObject

/** Keeps the member's pick and the client-number-to-source mapping (AC-25). */
interface ManagedSourceStore {
    suspend fun read(): ManagedState
    suspend fun write(state: ManagedState)
    suspend fun clear()
}

/**
 * Stores [ManagedState] as one JSON blob in [prefs], which the caller opens as the [PREFS_NAME]
 * file. A blob that does not parse reads as an empty state.
 */
class PrefsManagedSourceStore(private val prefs: SharedPreferences) : ManagedSourceStore {

    override suspend fun read(): ManagedState {
        val raw = prefs.getString(STATE_KEY, null) ?: return EMPTY
        return try {
            val json = JSONObject(raw)
            val sourcesJson = json.optJSONObject(SOURCES) ?: JSONObject()
            val sources = sourcesJson.keys().asSequence().associateWith { clientNumber ->
                val entry = sourcesJson.getJSONObject(clientNumber)
                ManagedSource(sourceId = entry.getLong(SOURCE_ID), hidden = entry.getBoolean(HIDDEN))
            }
            ManagedState(
                pick = json.optStringOrNull(PICK),
                sources = sources,
                pendingCreate = json.optStringOrNull(PENDING_CREATE),
            )
        } catch (_: JSONException) {
            EMPTY
        }
    }

    override suspend fun write(state: ManagedState) {
        val sources = JSONObject()
        state.sources.forEach { (clientNumber, mapping) ->
            sources.put(clientNumber, JSONObject().put(SOURCE_ID, mapping.sourceId).put(HIDDEN, mapping.hidden))
        }
        val json = JSONObject().put(SOURCES, sources)
        state.pick?.let { json.put(PICK, it) }
        state.pendingCreate?.let { json.put(PENDING_CREATE, it) }
        // commit, not apply: a mapping lost to a crash would create a second copy of the line.
        prefs.edit().putString(STATE_KEY, json.toString()).commit()
    }

    override suspend fun clear() {
        prefs.edit().remove(STATE_KEY).commit()
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    companion object {
        /** SharedPreferences file that holds the state. */
        const val PREFS_NAME = "kevbox_live_playlist"

        private val EMPTY = ManagedState(pick = null, sources = emptyMap())
        private const val STATE_KEY = "state"
        private const val PICK = "pick"
        private const val SOURCES = "sources"
        private const val PENDING_CREATE = "pending_create"
        private const val SOURCE_ID = "source_id"
        private const val HIDDEN = "hidden"
    }
}
