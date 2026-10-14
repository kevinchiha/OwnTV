// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.errors

import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** An entry waiting in the queue: its queue id and how many times the server refused its batch. */
data class Queued(val id: Long, val entry: ErrorEntry, val rejections: Int)

/**
 * The error entries waiting to be sent, kept in [file] so they survive a restart (AC-16).
 *
 * Every change rewrites the file whole, through a temp file and a rename, so a crash mid-write
 * leaves the previous copy. A missing or unreadable file starts an empty queue. In-flight marks live
 * in memory only: after a restart every entry is unsent again.
 *
 * Each entry carries the id of the member signed in when it was recorded ([currentUserId]). The tag
 * stays on the TV; [dropNotRecordedBy] uses it so one member's errors never go out under another's.
 *
 * Every method takes one lock, so the reporter's worker and the sender can share the queue and only
 * one of them touches the file at a time. Callers keep it off the main thread.
 */
class ErrorQueue(
    private val file: File,
    private val now: () -> Long,
    private val currentUserId: () -> String? = { null },
) {
    private class Item(
        val id: Long,
        var entry: ErrorEntry,
        var rejections: Int,
        val userId: String?,
        val addedAtMs: Long,
        var inFlight: Boolean = false,
    )

    private val lock = Any()
    private var loaded = false
    private val items = ArrayList<Item>()
    private var nextId = 1L

    fun add(entry: ErrorEntry) = synchronized(lock) {
        load()
        val userId = currentUserId()
        val target = items.lastOrNull {
            !it.inFlight &&
                it.userId == userId &&
                it.entry.code == entry.code &&
                it.entry.channel == entry.channel &&
                entry.firstAtMs - it.entry.lastAtMs in -SendPolicy.MERGE_WINDOW_MS..SendPolicy.MERGE_WINDOW_MS
        }
        if (target != null) {
            target.entry = target.entry.copy(
                repeatCount = target.entry.repeatCount + entry.repeatCount,
                lastAtMs = maxOf(target.entry.lastAtMs, entry.lastAtMs),
            )
        } else {
            items += Item(nextId++, entry, rejections = 0, userId = userId, addedAtMs = now())
            trimToMax()
        }
        save()
    }

    /** Up to [max] unsent entries, oldest first, now marked in flight until [settle]. */
    fun takeBatch(max: Int): List<Queued> = synchronized(lock) {
        load()
        items.filter { !it.inFlight }.take(max).map {
            it.inFlight = true
            Queued(it.id, it.entry, it.rejections)
        }
    }

    fun settle(ids: List<Long>, outcome: SendOutcome) = synchronized(lock) {
        load()
        val settled = ids.toSet()
        when (outcome) {
            SendOutcome.Sent -> items.removeAll { it.id in settled }
            SendOutcome.Retry, SendOutcome.SignedOut ->
                items.filter { it.id in settled }.forEach { it.inFlight = false }
            SendOutcome.Rejected -> {
                items.filter { it.id in settled }.forEach {
                    it.inFlight = false
                    it.rejections++
                }
                items.removeAll { it.id in settled && it.rejections >= SendPolicy.MAX_REJECTIONS }
            }
        }
        save()
    }

    fun unsentCount(): Int = synchronized(lock) {
        load()
        items.count { !it.inFlight }
    }

    /** When the oldest unsent entry joined the queue, by [now]; null when nothing is unsent. */
    fun oldestUnsentAtMs(): Long? = synchronized(lock) {
        load()
        items.filter { !it.inFlight }.minOfOrNull { it.addedAtMs }
    }

    /**
     * Drops every entry not recorded under [userId], in flight or not, and returns how many went.
     * Entries recorded while signed out go too: nobody can vouch whose they are.
     */
    fun dropNotRecordedBy(userId: String): Int = synchronized(lock) {
        load()
        val before = items.size
        items.removeAll { it.userId != userId }
        val dropped = before - items.size
        if (dropped > 0) save()
        dropped
    }

    /** Past [SendPolicy.QUEUE_MAX], drop the oldest: unsent ones first, in-flight ones last. */
    private fun trimToMax() {
        while (items.size > SendPolicy.QUEUE_MAX) {
            val victim = items.firstOrNull { !it.inFlight } ?: items.first()
            items.remove(victim)
        }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val root = try {
            if (!file.isFile) return
            JSONObject(file.readText())
        } catch (_: IOException) {
            return
        } catch (_: JSONException) {
            return
        }
        val array = root.optJSONArray(ITEMS) ?: return
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            val id = (row.opt(ID) as? Number)?.toLong() ?: continue
            val entry = row.optJSONObject(ENTRY)?.let { ErrorEntry.fromJson(it) } ?: continue
            items += Item(
                id = id,
                entry = entry,
                rejections = (row.opt(REJECTIONS) as? Number)?.toInt() ?: 0,
                userId = row.opt(USER) as? String,
                addedAtMs = (row.opt(ADDED_AT) as? Number)?.toLong() ?: entry.firstAtMs,
            )
        }
        val storedNext = (root.opt(NEXT_ID) as? Number)?.toLong() ?: 1L
        nextId = maxOf(storedNext, (items.maxOfOrNull { it.id } ?: 0L) + 1)
        trimToMax()
    }

    /** Writes a temp file beside [file], then renames it over. A failed write keeps the old copy. */
    private fun save() {
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject()
                    .put(ID, it.id)
                    .put(REJECTIONS, it.rejections)
                    .put(USER, it.userId ?: JSONObject.NULL)
                    .put(ADDED_AT, it.addedAtMs)
                    .put(ENTRY, it.entry.toJson()),
            )
        }
        val text = JSONObject().put(NEXT_ID, nextId).put(ITEMS, array).toString()
        val temp = File(file.parentFile, file.name + TEMP_SUFFIX)
        try {
            file.parentFile?.mkdirs()
            temp.writeText(text)
            if (!temp.renameTo(file)) temp.delete()
        } catch (_: IOException) {
            temp.delete()
        }
    }

    private companion object {
        const val NEXT_ID = "next_id"
        const val ITEMS = "items"
        const val ID = "id"
        const val REJECTIONS = "rejections"
        const val USER = "user"
        const val ADDED_AT = "added_at"
        const val ENTRY = "entry"
        const val TEMP_SUFFIX = ".tmp"
    }
}
