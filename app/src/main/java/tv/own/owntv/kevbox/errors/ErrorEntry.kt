// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.errors

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.json.JSONObject

/**
 * One error report as it waits on the TV and as it goes to record_live_errors. [toJson] writes
 * every key of the plan's Shared contract on every entry, JSON null where a field is null, so the
 * server never has to tell a missing key from a null one.
 */
data class ErrorEntry(
    val firstAtMs: Long,
    val lastAtMs: Long,
    val repeatCount: Int,
    val code: String,
    val httpStatus: Int?,
    val errorName: String?,
    val accountStatus: String?,
    val activeCons: Int?,
    val maxConnections: Int?,
    val engine: String?,
    val surface: String?,
    val live: Boolean?,
    val channel: String?,
    val category: String?,
    val providerHost: String?,
    val proxy: Boolean?,
    val model: String?,
    val android: String?,
    val messageShort: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put(FIRST_AT, isoUtc(firstAtMs))
        .put(LAST_AT, isoUtc(lastAtMs))
        .put(REPEAT_COUNT, repeatCount)
        .put(CODE, code)
        .put(HTTP_STATUS, httpStatus.orNull())
        .put(ERROR_NAME, errorName.orNull())
        .put(ACCOUNT_STATUS, accountStatus.orNull())
        .put(ACTIVE_CONS, activeCons.orNull())
        .put(MAX_CONNECTIONS, maxConnections.orNull())
        .put(ENGINE, engine.orNull())
        .put(SURFACE, surface.orNull())
        .put(LIVE, live.orNull())
        .put(CHANNEL, channel.orNull())
        .put(CATEGORY, category.orNull())
        .put(PROVIDER_HOST, providerHost.orNull())
        .put(PROXY, proxy.orNull())
        .put(MODEL, model.orNull())
        .put(ANDROID, android.orNull())
        .put(MESSAGE_SHORT, messageShort.orNull())

    companion object {
        /** The entry [toJson] wrote, or null when a required field is missing or malformed. */
        fun fromJson(o: JSONObject): ErrorEntry? = try {
            ErrorEntry(
                firstAtMs = Instant.parse(o.opt(FIRST_AT) as? String ?: return null).toEpochMilli(),
                lastAtMs = Instant.parse(o.opt(LAST_AT) as? String ?: return null).toEpochMilli(),
                repeatCount = (o.opt(REPEAT_COUNT) as? Number)?.toInt() ?: return null,
                code = (o.opt(CODE) as? String)?.takeIf { it.isNotEmpty() } ?: return null,
                httpStatus = int(o, HTTP_STATUS),
                errorName = text(o, ERROR_NAME),
                accountStatus = text(o, ACCOUNT_STATUS),
                activeCons = int(o, ACTIVE_CONS),
                maxConnections = int(o, MAX_CONNECTIONS),
                engine = text(o, ENGINE),
                surface = text(o, SURFACE),
                live = o.opt(LIVE) as? Boolean,
                channel = text(o, CHANNEL),
                category = text(o, CATEGORY),
                providerHost = text(o, PROVIDER_HOST),
                proxy = o.opt(PROXY) as? Boolean,
                model = text(o, MODEL),
                android = text(o, ANDROID),
                messageShort = text(o, MESSAGE_SHORT),
            )
        } catch (_: RuntimeException) {
            // A timestamp that does not parse: the entry is unusable, not the whole queue.
            null
        }

        /** UTC ISO-8601 with milliseconds and `Z`, as the server expects: 2026-10-14T18:02:11.123Z. */
        fun isoUtc(ms: Long): String = ISO_UTC.format(Instant.ofEpochMilli(ms))

        private val ISO_UTC: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        private fun Any?.orNull(): Any = this ?: JSONObject.NULL

        // opt() returns JSONObject.NULL for a JSON null, which these casts turn into Kotlin null.
        private fun text(o: JSONObject, key: String): String? = o.opt(key) as? String
        private fun int(o: JSONObject, key: String): Int? = (o.opt(key) as? Number)?.toInt()

        const val FIRST_AT = "first_at"
        const val LAST_AT = "last_at"
        const val REPEAT_COUNT = "repeat_count"
        const val CODE = "code"
        const val HTTP_STATUS = "http_status"
        const val ERROR_NAME = "error_name"
        const val ACCOUNT_STATUS = "account_status"
        const val ACTIVE_CONS = "active_cons"
        const val MAX_CONNECTIONS = "max_connections"
        const val ENGINE = "engine"
        const val SURFACE = "surface"
        const val LIVE = "live"
        const val CHANNEL = "channel"
        const val CATEGORY = "category"
        const val PROVIDER_HOST = "provider_host"
        const val PROXY = "proxy"
        const val MODEL = "model"
        const val ANDROID = "android"
        const val MESSAGE_SHORT = "message_short"
    }
}
