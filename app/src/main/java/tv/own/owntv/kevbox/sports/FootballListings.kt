// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.sports

import java.time.Instant
import java.time.OffsetDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.sports.TeamNames
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.kevbox.session.KevBoxAuth
import tv.own.owntv.kevbox.supabase.ApiResponse
import tv.own.owntv.kevbox.supabase.Fetch
import tv.own.owntv.kevbox.supabase.SupabaseApi

/** One provider channel kevbox.dev matched to a LiveOnSat entry; [listedAs] is LiveOnSat's own name for it. */
data class ListedChannel(val streamId: String, val name: String, val listedAs: String)

/** One football match LiveOnSat lists, with the provider channels that show it, in LiveOnSat's order. */
data class FootballListing(
    val kickoffMs: Long,
    val competition: String,
    val home: String,
    val away: String,
    val channels: List<ListedChannel>,
)

/**
 * The football_listing rows for now − 6 h to now + 36 h, kept in memory for the signed-in member.
 *
 * A good copy is reused for 10 minutes. A failed or non-2xx read keeps the last good copy (or none)
 * and the next read waits a minute. Signing out or another member signing in drops the copy. Nothing
 * here throws or logs.
 */
class FootballListings(
    private val auth: KevBoxAuth,
    private val api: SupabaseApi,
    private val now: () -> Long,
) {
    private val lock = Mutex()
    private var member: String? = null
    private var copy: List<FootballListing>? = null
    private var fetchedAtMs = 0L
    private var failedAtMs: Long? = null

    /** The listing that is [match], or null when [match] is not football or no listing links to it. */
    suspend fun listingFor(match: Match): FootballListing? {
        if (match.sport != Sport.SOCCER) return null
        return link(match, listings())
    }

    /** The current listings: the cached copy when fresh enough, else one read. */
    internal suspend fun listings(): List<FootballListing> = lock.withLock {
        val user = auth.userId.value
        if (user != member) {
            member = user
            copy = null
            failedAtMs = null
        }
        if (user == null) return@withLock emptyList()
        val at = now()
        copy?.let { if (at - fetchedAtMs < FRESH_MS) return@withLock it }
        failedAtMs?.let { if (at - it < RETRY_MS) return@withLock copy.orEmpty() }
        val read = read(user, at)
        if (read != null && auth.userId.value == user) {
            copy = read
            fetchedAtMs = at
            failedAtMs = null
        } else {
            failedAtMs = at
        }
        copy.orEmpty()
    }

    /** One read for [user]'s window around [at]; null when it failed, answered non-2xx or did not parse. */
    private suspend fun read(user: String, at: Long): List<FootballListing>? = try {
        val from = isoUtc(at - WINDOW_BEFORE_MS)
        val to = isoUtc(at + WINDOW_AFTER_MS)
        val result = auth.signedInCall { token ->
            // Another member signed in since the read began: their token must not fill this copy.
            if (auth.userId.value != user) return@signedInCall ApiResponse.NoNetwork
            api.selectFootballListings(token, from, to)
        }
        if (result is Fetch.Ok && result.value.code in HTTP_SUCCESS) parseListings(result.value.body) else null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60 * MINUTE_MS
        const val FRESH_MS = 10 * MINUTE_MS
        const val RETRY_MS = MINUTE_MS
        const val WINDOW_BEFORE_MS = 6 * HOUR_MS
        const val WINDOW_AFTER_MS = 36 * HOUR_MS
        val HTTP_SUCCESS = 200..299

        /** Whole seconds, UTC, ending in `Z`: "2026-10-28T19:45:00Z". */
        fun isoUtc(ms: Long): String = Instant.ofEpochSecond(Math.floorDiv(ms, 1_000L)).toString()
    }
}

/** A listing within this of the match's kickoff can link. */
private const val LINK_WINDOW_MS = 15 * 60_000L

/** Within this, one team found is enough. */
private const val ONE_TEAM_WINDOW_MS = 5 * 60_000L

/**
 * The listing that is [match]: kickoff within 15 minutes and both teams named in its `home v away`
 * text, or one team when the kickoffs are within 5 minutes. Teams compare by Core's [TeamNames]
 * tokens, so "FC Cologne" finds ESPN's "1. FC Köln". The closest kickoff wins, then both teams found.
 */
internal fun link(match: Match, listings: List<FootballListing>): FootballListing? {
    if (match.sport != Sport.SOCCER) return null
    val home = match.home?.team?.let(TeamNames::keyOf) ?: return null
    val away = match.away?.team?.let(TeamNames::keyOf) ?: return null
    return listings.asSequence()
        .map { it to abs(it.kickoffMs - match.startMs) }
        .filter { (_, gap) -> gap <= LINK_WINDOW_MS }
        .mapNotNull { (listing, gap) ->
            val tokens = TeamNames.tokens("${listing.home} v ${listing.away}").toSet()
            val found = listOf(home, away).count { it.foundIn(tokens) }
            val linked = found == 2 || (found == 1 && gap <= ONE_TEAM_WINDOW_MS)
            if (linked) Triple(listing, gap, found) else null
        }
        .minWithOrNull(compareBy<Triple<FootballListing, Long, Int>> { it.second }.thenByDescending { it.third })
        ?.first
}

/** football_listing rows as PostgREST sends them; null when [body] is not a JSON array. Bad rows are skipped. */
internal fun parseListings(body: String): List<FootballListing>? {
    val rows = try {
        JSONArray(body)
    } catch (_: Exception) {
        return null
    }
    return (0 until rows.length()).mapNotNull { i -> rows.optJSONObject(i)?.let(::parseListing) }
}

private fun parseListing(row: JSONObject): FootballListing? = try {
    val channels = row.optJSONArray("channels") ?: JSONArray()
    FootballListing(
        kickoffMs = OffsetDateTime.parse(row.getString("kickoff")).toInstant().toEpochMilli(),
        competition = row.text("competition"),
        home = row.getString("home"),
        away = row.getString("away"),
        channels = (0 until channels.length()).mapNotNull { j ->
            channels.optJSONObject(j)?.let { c ->
                ListedChannel(
                    streamId = c.text("stream_id"),
                    name = c.text("name"),
                    listedAs = c.text("listed_as"),
                )
            }
        },
    )
} catch (_: Exception) {
    null
}

/** The string at [key], or "" when it is missing or JSON null (optString would give "null"). */
private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)
