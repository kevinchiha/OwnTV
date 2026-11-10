// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.realtime

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// Pure: the Supabase Realtime frames the playlist socket sends and reads (Phoenix protocol
// vsn=1.0.0, one JSON object per frame). Nothing here logs; the frames carry the access token.

/** The one channel the TV joins: its member's own `member_playlist` rows. */
const val REALTIME_TOPIC = "realtime:kevbox-member-playlist"

private const val SOCKET_PATH = "realtime/v1/websocket"
private const val APIKEY = "apikey"
private const val VSN = "vsn"
private const val VSN_VALUE = "1.0.0"
private const val HTTPS = "https"
private const val HTTP = "http"
private const val WSS = "wss"
private const val WS = "ws"

private const val PHOENIX_TOPIC = "phoenix"
private const val TOPIC = "topic"
private const val EVENT = "event"
private const val REF = "ref"
private const val JOIN_REF = "join_ref"
private const val PAYLOAD = "payload"
private const val STATUS = "status"
private const val ACCESS_TOKEN = "access_token"
private const val CONFIG = "config"
private const val BROADCAST = "broadcast"
private const val SELF = "self"
private const val PRESENCE = "presence"
private const val KEY = "key"
private const val POSTGRES_CHANGES = "postgres_changes"
private const val SCHEMA = "schema"
private const val TABLE = "table"
private const val FILTER = "filter"

private const val PHX_JOIN = "phx_join"
private const val PHX_REPLY = "phx_reply"
private const val PHX_ERROR = "phx_error"
private const val PHX_CLOSE = "phx_close"
private const val HEARTBEAT = "heartbeat"
private const val SYSTEM = "system"
private const val STATUS_OK = "ok"
private const val STATUS_ERROR = "error"

private const val ANY_EVENT = "*"
private const val PUBLIC_SCHEMA = "public"
private const val MEMBER_PLAYLIST = "member_playlist"
private const val USER_FILTER_PREFIX = "user_id=eq."

private const val BACKOFF_FIRST_MS = 5_000L
private const val BACKOFF_MAX_MS = 300_000L

/**
 * `<wss base>/realtime/v1/websocket?apikey=<anon key>&vsn=1.0.0`: https becomes wss and http
 * becomes ws. A blank or unusable base URL, or a blank key, gives null: no socket at all.
 */
fun socketUrl(baseUrl: String, anonKey: String): String? {
    val key = anonKey.trim()
    if (key.isEmpty()) return null
    val base = baseUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() }?.toHttpUrlOrNull() ?: return null
    val http = base.newBuilder()
        .addPathSegments(SOCKET_PATH)
        .addQueryParameter(APIKEY, key)
        .addQueryParameter(VSN, VSN_VALUE)
        .build()
        .toString()
    return when (base.scheme) {
        HTTPS -> WSS + http.removePrefix(HTTPS)
        HTTP -> WS + http.removePrefix(HTTP)
        else -> null
    }
}

/** Joins [REALTIME_TOPIC] for every change to [userId]'s `member_playlist` rows. */
fun joinFrame(userId: String, accessToken: String, ref: Int): String {
    val change = JSONObject()
        .put(EVENT, ANY_EVENT)
        .put(SCHEMA, PUBLIC_SCHEMA)
        .put(TABLE, MEMBER_PLAYLIST)
        .put(FILTER, USER_FILTER_PREFIX + userId)
    val config = JSONObject()
        .put(BROADCAST, JSONObject().put(SELF, false))
        .put(PRESENCE, JSONObject().put(KEY, ""))
        .put(POSTGRES_CHANGES, JSONArray().put(change))
    return JSONObject()
        .put(TOPIC, REALTIME_TOPIC)
        .put(EVENT, PHX_JOIN)
        .put(REF, ref.toString())
        .put(JOIN_REF, ref.toString())
        .put(PAYLOAD, JSONObject().put(CONFIG, config).put(ACCESS_TOKEN, accessToken))
        .toString()
}

fun heartbeatFrame(ref: Int): String = JSONObject()
    .put(TOPIC, PHOENIX_TOPIC)
    .put(EVENT, HEARTBEAT)
    .put(REF, ref.toString())
    .put(PAYLOAD, JSONObject())
    .toString()

/** Hands the joined channel a newer access token, so it outlives the one it joined with. */
fun accessTokenFrame(accessToken: String, ref: Int): String = JSONObject()
    .put(TOPIC, REALTIME_TOPIC)
    .put(EVENT, ACCESS_TOKEN)
    .put(REF, ref.toString())
    .put(PAYLOAD, JSONObject().put(ACCESS_TOKEN, accessToken))
    .toString()

/** What a frame from the server means to the socket. */
sealed interface Incoming {
    /** The server accepted the join. Not proof the change feed works: a [Refused] can follow. */
    data object Joined : Incoming

    /** A `member_playlist` row of this member changed. The payload is never read. */
    data object Change : Incoming

    /** The join, the channel or the change feed failed: reconnect. */
    data object Refused : Incoming

    /** Anything else, a heartbeat reply or a malformed frame included. */
    data object Other : Incoming
}

/** Sorts one server frame; never throws. */
fun parseIncoming(text: String): Incoming {
    val frame = try {
        JSONObject(text)
    } catch (_: JSONException) {
        return Incoming.Other
    }
    val topic = frame.opt(TOPIC) as? String
    val event = frame.opt(EVENT) as? String
    val status = (frame.opt(PAYLOAD) as? JSONObject)?.opt(STATUS) as? String
    val ours = topic == REALTIME_TOPIC
    return when {
        ours && event == PHX_REPLY && status == STATUS_OK -> Incoming.Joined
        ours && event == PHX_REPLY && status == STATUS_ERROR -> Incoming.Refused
        ours && event == POSTGRES_CHANGES -> Incoming.Change
        ours && (event == PHX_ERROR || event == PHX_CLOSE) -> Incoming.Refused
        event == SYSTEM && status == STATUS_ERROR -> Incoming.Refused
        else -> Incoming.Other
    }
}

/** The wait before reconnect [attempt] (0 first): 5 s, doubling, capped at 5 minutes. */
fun backoffMs(attempt: Int): Long {
    // Past 6 doublings the cap holds anyway; the bound keeps the shift from overflowing.
    val doublings = attempt.coerceIn(0, 16)
    return (BACKOFF_FIRST_MS shl doublings).coerceAtMost(BACKOFF_MAX_MS)
}
