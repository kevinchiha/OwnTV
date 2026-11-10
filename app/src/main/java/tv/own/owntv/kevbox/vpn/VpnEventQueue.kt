// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.vpn

import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The most VPN events the queue holds; past it the oldest go first. */
const val VPN_QUEUE_MAX = 500

/** The queue's file name, kept in noBackupFilesDir like the error queue: this TV's alone. */
const val VPN_EVENTS_FILE = "kevbox_live_vpn_events.json"

/**
 * VPN events waiting to go out, oldest first, kept in [file] so they survive a restart. Every change
 * rewrites the file whole, through a temp file and a rename, as ErrorQueue does; a missing or
 * unreadable file starts an empty queue.
 *
 * Each event carries the id of the member it was recorded for. The tag stays on the TV; it keeps one
 * member's events from ever going out under another's.
 *
 * Every method takes one lock. Callers keep it off the main thread.
 */
class VpnEventQueue(private val file: File) {
    private class Item(val event: VpnEvent, val userId: String)

    private val lock = Any()
    private var loaded = false
    private val items = ArrayList<Item>()

    /** Queues [event] for [userId], after every event already queued. */
    fun add(event: VpnEvent, userId: String) = synchronized(lock) {
        load()
        append(Item(event, userId))
        save()
        Unit
    }

    /** Up to [max] of [userId]'s events, oldest first. They stay queued until [remove]. */
    fun oldest(max: Int, userId: String): List<VpnEvent> = synchronized(lock) {
        load()
        items.asSequence().filter { it.userId == userId }.take(max).map { it.event }.toList()
    }

    fun remove(ids: Collection<String>) = synchronized(lock) {
        load()
        val gone = ids.toSet()
        if (items.removeAll { it.event.id in gone }) save()
        Unit
    }

    /** Drops every event tagged with a member other than [userId] and returns how many went. */
    fun dropNotRecordedBy(userId: String): Int = synchronized(lock) {
        load()
        val before = items.size
        items.removeAll { it.userId != userId }
        val dropped = before - items.size
        if (dropped > 0) save()
        dropped
    }

    fun size(): Int = synchronized(lock) {
        load()
        items.size
    }

    /** Adds [item] at the end; an event with the same id is replaced, never doubled. Call with [lock] held. */
    private fun append(item: Item) {
        items.removeAll { it.event.id == item.event.id }
        items += item
        trimToMax()
    }

    private fun trimToMax() {
        while (items.size > VPN_QUEUE_MAX) items.removeAt(0)
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val text = try {
            if (file.isFile) file.readText() else null
        } catch (_: IOException) {
            null
        } ?: return
        val array = try {
            JSONObject(text).optJSONArray(ITEMS)
        } catch (_: JSONException) {
            null
        } ?: return
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)?.let(::itemOf) ?: continue
            items.removeAll { it.event.id == item.event.id }
            items += item
        }
        trimToMax()
    }

    /** Writes a temp file beside [file], then renames it over. A failed write keeps the old copy. */
    private fun save() {
        val array = JSONArray()
        items.forEach { array.put(itemJson(it)) }
        val temp = File(file.parentFile, file.name + TEMP_SUFFIX)
        try {
            file.parentFile?.mkdirs()
            temp.writeText(JSONObject().put(ITEMS, array).toString())
            if (!temp.renameTo(file)) temp.delete()
        } catch (_: IOException) {
            temp.delete()
        }
    }

    private fun itemJson(item: Item): JSONObject = JSONObject()
        .put(ID, item.event.id)
        .put(KIND, item.event.kind.wire)
        .put(SERVER, item.event.server ?: JSONObject.NULL)
        .put(AT_MS, item.event.atMs)
        .put(CLIENT_NUMBER, item.event.clientNumber ?: JSONObject.NULL)
        .put(USER, item.userId)

    /** Reads [itemJson]'s shape back; null when a required field is missing or the wrong type. */
    private fun itemOf(row: JSONObject): Item? {
        val id = (row.opt(ID) as? String)?.takeIf { it.isNotEmpty() } ?: return null
        val kind = (row.opt(KIND) as? String)?.let(VpnEventKind::ofWire) ?: return null
        val atMs = (row.opt(AT_MS) as? Number)?.toLong() ?: return null
        val userId = row.opt(USER) as? String ?: return null
        val event = VpnEvent(
            id = id,
            kind = kind,
            server = row.opt(SERVER) as? String,
            atMs = atMs,
            clientNumber = row.opt(CLIENT_NUMBER) as? String,
        )
        return Item(event, userId)
    }

    private companion object {
        const val ITEMS = "items"
        const val ID = "id"
        const val KIND = "kind"
        const val SERVER = "server"
        const val AT_MS = "at_ms"
        const val CLIENT_NUMBER = "client_number"
        const val USER = "user"
        const val TEMP_SUFFIX = ".tmp"
    }
}
