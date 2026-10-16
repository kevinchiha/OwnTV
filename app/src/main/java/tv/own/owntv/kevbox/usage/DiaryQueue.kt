// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.usage

import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The most stretches the queue holds; past it the oldest go first (AC-8). */
const val DIARY_QUEUE_MAX = 2_000

/**
 * Finished stretches waiting to go out, oldest first, kept in [file] so they survive a restart
 * (AC-8). Every change rewrites the file whole, through a temp file and a rename, as ErrorQueue
 * does; a missing or unreadable file starts an empty queue.
 *
 * The stretch still running lives in [openFile], a separate small file: it is rewritten every
 * 60 seconds, and rewriting the whole queue that often would wear the TV's flash. [recoverOpen]
 * moves what a killed process left there into the queue (AC-6).
 *
 * Each stretch carries the id of the member it was added for. The tag stays on the TV; it keeps one
 * member's stretches from ever going out under another's. A signed-out TV adds nothing, so there is
 * never an untagged stretch to adopt.
 *
 * Every method takes one lock. Callers keep it off the main thread.
 */
class DiaryQueue(private val file: File, private val openFile: File) {
    private class Item(val stretch: Stretch, val userId: String)

    private val lock = Any()
    private var loaded = false
    private val items = ArrayList<Item>()

    /** Queues a finished stretch for [userId]. One under [STRETCH_MIN_MS] is ignored. */
    fun add(stretch: Stretch, userId: String) = synchronized(lock) {
        if (!longEnough(stretch)) return@synchronized
        load()
        append(Item(stretch, userId))
        save()
        Unit
    }

    /** Saves the open stretch as of now, replacing the previous save. */
    fun saveOpen(stretch: Stretch, userId: String) = synchronized(lock) {
        writeAtomically(openFile, itemJson(Item(stretch, userId)).toString())
        Unit
    }

    fun clearOpen() = synchronized(lock) {
        openFile.delete()
        Unit
    }

    /**
     * A stretch saved open by a process that was killed joins the queue, ending at its last save,
     * and the open file is cleared. One under [STRETCH_MIN_MS] is dropped (AC-6). A queued stretch
     * with the same id finished after that save, so it stays and the save goes. The open file is
     * cleared only once the queue file holds the stretch: a failed write keeps it for the next start.
     */
    fun recoverOpen() = synchronized(lock) {
        val saved = readOpen()
        if (saved != null && longEnough(saved.stretch)) {
            load()
            if (items.none { it.stretch.id == saved.stretch.id }) {
                append(saved)
                if (!save()) return@synchronized
            }
        }
        openFile.delete()
        Unit
    }

    /** Up to [max] of [userId]'s stretches, oldest first. They stay queued until [remove]. */
    fun oldest(max: Int, userId: String): List<Stretch> = synchronized(lock) {
        load()
        items.asSequence().filter { it.userId == userId }.take(max).map { it.stretch }.toList()
    }

    fun remove(ids: Collection<String>) = synchronized(lock) {
        load()
        val gone = ids.toSet()
        if (items.removeAll { it.stretch.id in gone }) save()
        Unit
    }

    /**
     * Drops every stretch tagged with a member other than [userId], the saved open one included,
     * and returns how many went (AC-8).
     */
    fun dropNotRecordedBy(userId: String): Int = synchronized(lock) {
        load()
        val before = items.size
        items.removeAll { it.userId != userId }
        var dropped = before - items.size
        if (dropped > 0) save()
        val open = readOpen()
        if (open != null && open.userId != userId) {
            openFile.delete()
            dropped++
        }
        dropped
    }

    fun size(): Int = synchronized(lock) {
        load()
        items.size
    }

    private fun longEnough(stretch: Stretch) = stretch.endedAtMs - stretch.startedAtMs >= STRETCH_MIN_MS

    /** Adds [item] at the end; an item with the same id is replaced, never doubled. Call with [lock] held. */
    private fun append(item: Item) {
        items.removeAll { it.stretch.id == item.stretch.id }
        items += item
        trimToMax()
    }

    private fun trimToMax() {
        while (items.size > DIARY_QUEUE_MAX) items.removeAt(0)
    }

    private fun readOpen(): Item? {
        val text = readText(openFile) ?: return null
        return try {
            itemOf(JSONObject(text))
        } catch (_: JSONException) {
            null
        }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val text = readText(file) ?: return
        val array = try {
            JSONObject(text).optJSONArray(ITEMS)
        } catch (_: JSONException) {
            null
        } ?: return
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)?.let(::itemOf) ?: continue
            items.removeAll { it.stretch.id == item.stretch.id }
            items += item
        }
        trimToMax()
    }

    /** Rewrites [file] from [items]; false when the write failed and the old copy stays. */
    private fun save(): Boolean {
        val array = JSONArray()
        items.forEach { array.put(itemJson(it)) }
        return writeAtomically(file, JSONObject().put(ITEMS, array).toString())
    }

    private fun itemJson(item: Item): JSONObject = item.stretch.toStored().put(USER, item.userId)

    private fun itemOf(row: JSONObject): Item? {
        val userId = row.opt(USER) as? String ?: return null
        val stretch = storedStretch(row) ?: return null
        return Item(stretch, userId)
    }

    private fun readText(target: File): String? = try {
        if (target.isFile) target.readText() else null
    } catch (_: IOException) {
        null
    }

    /**
     * Writes a temp file beside [target], then renames it over. A failed write keeps the old copy
     * and answers false.
     */
    private fun writeAtomically(target: File, text: String): Boolean {
        val temp = File(target.parentFile, target.name + TEMP_SUFFIX)
        return try {
            target.parentFile?.mkdirs()
            temp.writeText(text)
            temp.renameTo(target).also { renamed -> if (!renamed) temp.delete() }
        } catch (_: IOException) {
            temp.delete()
            false
        }
    }

    private companion object {
        const val ITEMS = "items"
        const val USER = "user"
        const val TEMP_SUFFIX = ".tmp"
    }
}
