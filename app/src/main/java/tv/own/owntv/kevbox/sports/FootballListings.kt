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

/**
 * One match LiveOnSat lists, with the provider channels that show it, in LiveOnSat's order. [sport] is
 * the server's own word for it ("soccer", "basketball", "mma", "racing"), kept as text so a sport the
 * server adds later still reads here; it links to nothing until [LISTED_SPORTS] knows it.
 */
data class FootballListing(
    val kickoffMs: Long,
    val competition: String,
    val home: String,
    val away: String,
    val channels: List<ListedChannel>,
    val sport: String = SOCCER_TEXT,
)

/** A row with no sport is football: rows written before the server tagged them. */
private const val SOCCER_TEXT = "soccer"

/** The server's sport words this app links, and the Core sport each one is. */
private val LISTED_SPORTS = mapOf(
    SOCCER_TEXT to Sport.SOCCER,
    "basketball" to Sport.BASKETBALL,
    "mma" to Sport.MMA,
    "racing" to Sport.RACING,
)

/**
 * The football_listing rows for now − 6 h to now + 36 h, kept in memory for the signed-in member.
 *
 * A good copy is reused for 10 minutes. A failed or non-2xx read keeps the last good copy (or none)
 * and the next read waits a minute; a read that runs out of SupabaseApi's 4 s listings timeout is a
 * failed read. Signing out or another member signing in drops the copy. Nothing here throws or logs.
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

    /** The listing that is [match], or null when [match]'s sport is not listed or no listing links to it. */
    suspend fun listingFor(match: Match): FootballListing? {
        if (match.sport !in LISTED_SPORTS.values) return null
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
 * An MMA listing within this of the card's start can link. Core times a card from its first prelim
 * fight, LiveOnSat from the main card, hours later.
 */
private const val MMA_WINDOW_MS = 12 * 60 * 60_000L

/**
 * The listing that is [match], among the listings of its own sport. Football and basketball link by
 * teams ([linkByTeams]), MMA by both fighters ([linkByFighters]), motor sport by time and series
 * ([linkBySeries]). Any other sport links to nothing.
 */
internal fun link(match: Match, listings: List<FootballListing>): FootballListing? {
    val same = listings.filter { LISTED_SPORTS[it.sport] == match.sport }
    return when (match.sport) {
        Sport.SOCCER, Sport.BASKETBALL -> linkByTeams(match, same)
        Sport.MMA -> linkByFighters(match, same)
        Sport.RACING -> linkBySeries(match, same)
        else -> null
    }
}

/**
 * Kickoff within 15 minutes and both teams named in the listing's `home v away` text, or one team
 * when the kickoffs are within 5 minutes. Teams compare by Core's [TeamNames] tokens, so "FC Cologne"
 * finds ESPN's "1. FC Köln". A listing with both teams found beats one with one team; among equals,
 * the closest kickoff wins.
 */
private fun linkByTeams(match: Match, listings: List<FootballListing>): FootballListing? {
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
        .minWithOrNull(compareByDescending<Triple<FootballListing, Long, Int>> { it.third }.thenBy { it.second })
        ?.first
}

/**
 * Both fighters named in the listing's `home v away` text, within 12 hours of the card's start. The
 * fighters are the card's first two competitors, its main event. The closest start wins.
 */
private fun linkByFighters(match: Match, listings: List<FootballListing>): FootballListing? {
    val first = match.home?.team?.let(TeamNames::keyOf) ?: return null
    val second = match.away?.team?.let(TeamNames::keyOf) ?: return null
    return listings
        .filter { abs(it.kickoffMs - match.startMs) <= MMA_WINDOW_MS }
        .filter {
            val tokens = TeamNames.tokens("${it.home} v ${it.away}").toSet()
            first.foundIn(tokens) && second.foundIn(tokens)
        }
        .minByOrNull { abs(it.kickoffMs - match.startMs) }
}

/**
 * Start within 15 minutes and the listing's competition naming the session's series ("Formula 1
 * Grand Prix - Round 19" names "Formula 1"), ignoring case. By series, not event name: LiveOnSat's
 * "Mexican Grand Prix" can be ESPN's "Mexico City Grand Prix". The closest start wins.
 */
private fun linkBySeries(match: Match, listings: List<FootballListing>): FootballListing? {
    val series = match.leagueName.trim().takeIf { it.isNotEmpty() } ?: return null
    return listings
        .filter { abs(it.kickoffMs - match.startMs) <= LINK_WINDOW_MS }
        .filter { it.competition.contains(series, ignoreCase = true) }
        .minByOrNull { abs(it.kickoffMs - match.startMs) }
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
        sport = row.text("sport").ifEmpty { SOCCER_TEXT },
    )
} catch (_: Exception) {
    null
}

/** The string at [key], or "" when it is missing or JSON null (optString would give "null"). */
private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)
