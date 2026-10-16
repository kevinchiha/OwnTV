// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.usage

import java.time.ZoneId
import org.json.JSONObject

/** A stretch shorter than this is never queued (AC-3). */
const val STRETCH_MIN_MS: Long = 10_000L

/**
 * One finished (or, while open, saved) stretch. Times are ms on one clock: StretchCutter returns
 * them on its caller's clock (elapsed, in DiaryWatcher), and DiaryWatcher moves them to the TV wall
 * clock before they are queued, so every queued or sent stretch is TV wall clock ms. Nothing here is
 * a stream address.
 */
data class Stretch(
    val id: String, // random UUID made on the TV
    val startedAtMs: Long,
    val endedAtMs: Long,
    val inBox: Boolean,
    val lastPressAtMs: Long,
    val channel: String?,
    val category: String?,
    val programme: String?,
    val providerHost: String?,
    val proxy: Boolean,
)

/** The wire entry of record_live_stretches: exactly ten keys, times through [playedAtText] in [zone]. */
fun Stretch.toEntry(zone: ZoneId): JSONObject = JSONObject()
    .put(StretchKeys.ID, id)
    .put(StretchKeys.STARTED_AT, playedAtText(startedAtMs, zone))
    .put(StretchKeys.ENDED_AT, playedAtText(endedAtMs, zone))
    .put(StretchKeys.IN_BOX, inBox)
    .put(StretchKeys.LAST_PRESS_AT, playedAtText(lastPressAtMs, zone))
    .put(StretchKeys.CHANNEL, channel ?: JSONObject.NULL)
    .put(StretchKeys.CATEGORY, category ?: JSONObject.NULL)
    .put(StretchKeys.PROGRAMME, programme ?: JSONObject.NULL)
    .put(StretchKeys.PROVIDER_HOST, providerHost ?: JSONObject.NULL)
    .put(StretchKeys.PROXY, proxy)

/** The stretch as [DiaryQueue] stores it: the wire keys, with times kept as epoch ms. */
internal fun Stretch.toStored(): JSONObject = JSONObject()
    .put(StretchKeys.ID, id)
    .put(StretchKeys.STARTED_AT, startedAtMs)
    .put(StretchKeys.ENDED_AT, endedAtMs)
    .put(StretchKeys.IN_BOX, inBox)
    .put(StretchKeys.LAST_PRESS_AT, lastPressAtMs)
    .put(StretchKeys.CHANNEL, channel ?: JSONObject.NULL)
    .put(StretchKeys.CATEGORY, category ?: JSONObject.NULL)
    .put(StretchKeys.PROGRAMME, programme ?: JSONObject.NULL)
    .put(StretchKeys.PROVIDER_HOST, providerHost ?: JSONObject.NULL)
    .put(StretchKeys.PROXY, proxy)

/** Reads [toStored]'s shape back; null when a required field is missing or the wrong type. */
internal fun storedStretch(row: JSONObject): Stretch? {
    val id = row.opt(StretchKeys.ID) as? String ?: return null
    return Stretch(
        id = id,
        startedAtMs = (row.opt(StretchKeys.STARTED_AT) as? Number)?.toLong() ?: return null,
        endedAtMs = (row.opt(StretchKeys.ENDED_AT) as? Number)?.toLong() ?: return null,
        inBox = row.opt(StretchKeys.IN_BOX) as? Boolean ?: return null,
        lastPressAtMs = (row.opt(StretchKeys.LAST_PRESS_AT) as? Number)?.toLong() ?: return null,
        channel = row.opt(StretchKeys.CHANNEL) as? String,
        category = row.opt(StretchKeys.CATEGORY) as? String,
        programme = row.opt(StretchKeys.PROGRAMME) as? String,
        providerHost = row.opt(StretchKeys.PROVIDER_HOST) as? String,
        proxy = row.opt(StretchKeys.PROXY) as? Boolean ?: return null,
    )
}

internal object StretchKeys {
    const val ID = "id"
    const val STARTED_AT = "started_at"
    const val ENDED_AT = "ended_at"
    const val IN_BOX = "in_box"
    const val LAST_PRESS_AT = "last_press_at"
    const val CHANNEL = "channel"
    const val CATEGORY = "category"
    const val PROGRAMME = "programme"
    const val PROVIDER_HOST = "provider_host"
    const val PROXY = "proxy"
}
