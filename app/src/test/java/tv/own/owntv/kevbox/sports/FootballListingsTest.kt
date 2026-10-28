// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.sports

import java.net.UnknownHostException
import java.time.Instant
import java.util.Collections
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.sports.model.Competitor
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.core.sports.model.Team
import tv.own.owntv.kevbox.session.KevBoxAuth
import tv.own.owntv.kevbox.session.SessionStore
import tv.own.owntv.kevbox.session.StoredSession
import tv.own.owntv.kevbox.supabase.SupabaseApi

class FootballListingsTest {

    /** Answers each listing read with the next of [answers] (200 and [body] once empty). */
    private class FakeServer : Interceptor {
        val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
        val answers = ArrayDeque<Int>()
        var body = TWO_ROWS

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            // A password sign-in always signs in member B.
            if (request.url.encodedPath == TOKEN_PATH) return canned(request, 200, B_TOKEN_BODY)
            if (request.url.encodedPath != LISTING_PATH) throw UnknownHostException("no canned response")
            requests += request
            val code = answers.removeFirstOrNull() ?: 200
            if (code == NO_NETWORK) throw UnknownHostException("no network")
            return canned(request, code, if (code == 200) body else "")
        }

        private fun canned(request: Request, code: Int, text: String): Response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("canned")
            .body(text.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private class MemoryStore(var session: StoredSession?) : SessionStore {
        override fun load() = session
        override fun save(session: StoredSession) {
            this.session = session
        }
        override fun clear() {
            session = null
        }
    }

    private val server = FakeServer()
    private var clock = Instant.parse("2026-10-28T12:00:00Z").toEpochMilli()
    private val api = SupabaseApi(OkHttpClient.Builder().addInterceptor(server).build(), BASE, "anon-key")
    private val store = MemoryStore(StoredSession(USER_A, "a@example.com", "a-token", "r-token", clock + 100 * HOUR))
    private val auth = KevBoxAuth(api, store) { clock }
    private val listings = FootballListings(auth, api) { clock }

    @Test
    fun `a two-row body parses into listings`() {
        val parsed = parseListings(TWO_ROWS)!!

        assertEquals(
            listOf(
                FootballListing(
                    kickoffMs = Instant.parse("2026-10-28T19:45:00Z").toEpochMilli(),
                    competition = "German Cup (DFB-Pokal)",
                    home = "FC Cologne",
                    away = "Union Berlin",
                    channels = listOf(
                        ListedChannel("1001", "DE: Sky Sport 1", "Sky Sport Germany 1"),
                        ListedChannel("1002", "DE: ARD", "ARD"),
                    ),
                ),
                FootballListing(
                    kickoffMs = Instant.parse("2026-10-28T20:00:00Z").toEpochMilli(),
                    competition = "English Premier League",
                    home = "Arsenal",
                    away = "Chelsea",
                    channels = emptyList(),
                ),
            ),
            parsed,
        )
    }

    @Test
    fun `the read asks for the window with the member's token and the anon key`() = runBlocking {
        listings.listings()

        val request = server.requests.single()
        assertEquals("kickoff,competition,home,away,channels,sport", request.url.queryParameter("select"))
        assertEquals(
            "(kickoff.gte.2026-10-28T06:00:00Z,kickoff.lte.2026-10-30T00:00:00Z)",
            request.url.queryParameter("and"),
        )
        assertEquals("kickoff.asc", request.url.queryParameter("order"))
        assertEquals("Bearer a-token", request.header("Authorization"))
        assertEquals("anon-key", request.header("apikey"))
        assertEquals("GET", request.method)
    }

    @Test
    fun `a second ask within 10 minutes makes no call`() = runBlocking {
        assertEquals(2, listings.listings().size)
        clock += 9 * MINUTE
        assertEquals(2, listings.listings().size)
        assertEquals(1, server.requests.size)

        clock += 2 * MINUTE
        listings.listings()
        assertEquals(2, server.requests.size)
    }

    @Test
    fun `a failed read keeps the last good copy and retries after a minute`() = runBlocking {
        assertEquals(2, listings.listings().size)
        clock += 11 * MINUTE
        server.answers.addAll(listOf(500, NO_NETWORK))

        // The 500: the last good copy stays.
        assertEquals(2, listings.listings().size)
        assertEquals(2, server.requests.size)
        // Within the minute after a failure: no call.
        clock += 30_000L
        assertEquals(2, listings.listings().size)
        assertEquals(2, server.requests.size)
        // After it: one more call, no network this time, copy still there.
        clock += 31_000L
        assertEquals(2, listings.listings().size)
        assertEquals(3, server.requests.size)
        // Then a good answer replaces the copy.
        clock += MINUTE
        server.body = "[]"
        assertEquals(0, listings.listings().size)
        assertEquals(4, server.requests.size)
    }

    @Test
    fun `a first read that fails gives nothing`() = runBlocking {
        server.answers.add(503)

        assertEquals(emptyList<FootballListing>(), listings.listings())
    }

    @Test
    fun `signing out or another member clears the copy`() = runBlocking {
        assertEquals(2, listings.listings().size)

        auth.signOut()
        assertEquals(emptyList<FootballListing>(), listings.listings())
        assertEquals(1, server.requests.size)

        // Member B, built over a store that holds B's session: a fresh read, never A's copy.
        store.session = StoredSession(USER_B, "b@example.com", "b-token", "r-b", clock + 100 * HOUR)
        val authB = KevBoxAuth(api, store) { clock }
        val listingsB = FootballListings(authB, api) { clock }
        server.body = "[]"
        assertEquals(0, listingsB.listings().size)
        assertEquals("Bearer b-token", server.requests.last().header("Authorization"))
    }

    @Test
    fun `the same instance reads again when another member signs in`() = runBlocking {
        val match = match(kickoff = "2026-10-28T19:45:00Z", home = KOLN, away = UNION)
        assertEquals("FC Cologne", listings.listingFor(match)?.home)
        assertEquals(1, server.requests.size)

        // B signs in on the same auth, well within A's 10 fresh minutes; B's own listings are empty.
        auth.signIn("b@example.com", "b-password")
        assertEquals(USER_B, auth.userId.value)
        server.body = "[]"
        clock += MINUTE

        assertNull(listings.listingFor(match))
        assertEquals(2, server.requests.size)
        assertEquals("Bearer b-token", server.requests.last().header("Authorization"))
    }

    @Test
    fun `listingFor links a football match by kickoff and teams`() = runBlocking {
        val match = match(kickoff = "2026-10-28T19:45:00Z", home = KOLN, away = UNION)

        assertEquals("FC Cologne", listings.listingFor(match)?.home)
        assertNull(listings.listingFor(match.copy(sport = Sport.BASKETBALL)))
    }

    @Test
    fun `FC Cologne v Union Berlin links to ESPN's names`() {
        val match = match(kickoff = "2026-10-28T19:45:00Z", home = KOLN, away = UNION)

        assertEquals(COLOGNE_UNION, link(match, listOf(COLOGNE_UNION)))
        // 12 minutes off still links with both teams.
        assertEquals(COLOGNE_UNION, link(match.copy(startMs = match.startMs + 12 * MINUTE), listOf(COLOGNE_UNION)))
    }

    @Test
    fun `a listing at the same kickoff with other teams does not link`() {
        val match = match(kickoff = "2026-10-28T19:45:00Z", home = KOLN, away = UNION)
        val other = listing("2026-10-28T19:45:00Z", "Bayern Munich", "Borussia Dortmund")

        assertNull(link(match, listOf(other)))
        assertEquals(COLOGNE_UNION, link(match, listOf(other, COLOGNE_UNION)))
    }

    @Test
    fun `the same teams 3 hours apart do not link`() {
        val match = match(kickoff = "2026-10-28T16:45:00Z", home = KOLN, away = UNION)

        assertNull(link(match, listOf(COLOGNE_UNION)))
    }

    @Test
    fun `one team is enough only within 5 minutes`() {
        val oneTeam = listing("2026-10-28T19:45:00Z", "Cologne", "Freiburg")
        val match = match(kickoff = "2026-10-28T19:48:00Z", home = KOLN, away = UNION)

        assertEquals(oneTeam, link(match, listOf(oneTeam)))
        assertNull(link(match.copy(startMs = match.startMs + 5 * MINUTE), listOf(oneTeam)))
    }

    @Test
    fun `the closest kickoff wins`() {
        val later = COLOGNE_UNION.copy(kickoffMs = COLOGNE_UNION.kickoffMs + 10 * MINUTE)
        val match = match(kickoff = "2026-10-28T19:47:00Z", home = KOLN, away = UNION)

        assertEquals(COLOGNE_UNION, link(match, listOf(later, COLOGNE_UNION)))
    }

    @Test
    fun `both teams found beats a closer kickoff with one team`() {
        val oneTeam = listing("2026-10-28T19:49:00Z", "Cologne", "Freiburg")
        // Gap 0 for the one-team listing, gap 4 minutes for the both-teams one.
        val match = match(kickoff = "2026-10-28T19:49:00Z", home = KOLN, away = UNION)

        assertEquals(COLOGNE_UNION, link(match, listOf(oneTeam, COLOGNE_UNION)))
    }

    @Test
    fun `a row's sport reads as soccer when missing or null, and an unknown one is kept`() {
        fun row(sport: Any?) = JSONObject()
            .put("kickoff", "2026-10-28T19:45:00+00:00")
            .put("competition", "Darts")
            .put("home", "Luke Littler")
            .put("away", "Luke Humphries")
            .put("channels", JSONArray())
            .apply { if (sport != null) put("sport", sport) }
        val body = JSONArray().put(row(null)).put(row(JSONObject.NULL)).put(row("darts")).put(row("basketball")).toString()

        assertEquals(listOf("soccer", "soccer", "darts", "basketball"), parseListings(body)!!.map { it.sport })
        // The darts row links to nothing, not even a match of a sport the app knows.
        val darts = parseListings(body)!![2]
        val soccer = match("2026-10-28T19:45:00Z", Team("7", "Luke Littler"), Team("8", "Luke Humphries"))
        assertNull(link(soccer, listOf(darts)))
    }

    @Test
    fun `a basketball listing links to ESPN's teams by the football rule`() {
        val lakers = Team(id = "13", name = "Los Angeles Lakers", shortName = "Lakers", abbreviation = "LAL", location = "Los Angeles")
        val blazers = Team(id = "22", name = "Portland Trail Blazers", shortName = "Trail Blazers", abbreviation = "POR", location = "Portland")
        val game = match("2026-10-29T02:30:00Z", lakers, blazers, Sport.BASKETBALL, "NBA")
        val nba = listing("2026-10-29T02:30:00Z", "Los Angeles Lakers", "Portland Trail Blazers", "NBA", "basketball")

        assertEquals(nba, link(game, listOf(nba)))
        assertEquals(nba, link(game.copy(startMs = game.startMs + 12 * MINUTE), listOf(nba)))
        assertNull(link(game.copy(startMs = game.startMs + 20 * MINUTE), listOf(nba)))
        // The same row tagged soccer is not this match's sport.
        assertNull(link(game, listOf(nba.copy(sport = "soccer"))))
        // And a basketball row never links a football match.
        val football = match("2026-10-29T02:30:00Z", lakers, blazers)
        assertNull(link(football, listOf(nba)))
    }

    @Test
    fun `an MMA listing links when both fighters are on the card, hours after its first prelim`() {
        val makhachev = Team(id = "3332412", name = "Islam Makhachev", shortName = "I. Makhachev", isAthlete = true)
        val tsarukyan = Team(id = "4029275", name = "Arman Tsarukyan", shortName = "A. Tsarukyan", isAthlete = true)
        val other = Team(id = "9", name = "Charles Oliveira", shortName = "C. Oliveira", isAthlete = true)
        // Core times the card from its first prelim; LiveOnSat lists the main card 3 hours later.
        val card = match("2026-10-28T23:00:00Z", makhachev, tsarukyan, Sport.MMA, "UFC")
        val main = listing("2026-10-29T02:00:00Z", "Islam Makhachev", "Arman Tsarukyan", "UFC 322", "mma")

        assertEquals(main, link(card, listOf(main)))
        // One of the two on the card is not enough.
        assertNull(link(match("2026-10-28T23:00:00Z", makhachev, other, Sport.MMA, "UFC"), listOf(main)))
        // More than 12 hours apart does not link.
        assertNull(link(card.copy(startMs = card.startMs - 10 * HOUR), listOf(main)))
    }

    @Test
    fun `a racing listing links an F1 session at the same time, never NASCAR or an earlier practice`() {
        val grandPrix = listing("2026-10-25T20:00:00Z", "Mexican Grand Prix", "", "Formula 1 Grand Prix - Round 19", "racing")
        val race = session("2026-10-25T20:00:00Z", "Formula 1", "Mexico City Grand Prix")
        val nascar = session("2026-10-25T20:00:00Z", "NASCAR Cup Series", "Xfinity 500")
        val practice = session("2026-10-25T18:00:00Z", "Formula 1", "Mexico City Grand Prix")

        assertEquals(grandPrix, link(race, listOf(grandPrix)))
        assertEquals(grandPrix, link(race.copy(startMs = race.startMs + 15 * MINUTE), listOf(grandPrix)))
        assertNull(link(nascar, listOf(grandPrix)))
        assertNull(link(practice, listOf(grandPrix)))
    }

    private fun session(start: String, series: String, event: String) = Match(
        id = "600-4",
        sport = Sport.RACING,
        feed = "racing/f1",
        leagueSlug = "f1",
        leagueName = series,
        startMs = Instant.parse(start).toEpochMilli(),
        state = MatchState.PRE,
        statusText = "",
        title = event,
    )

    private fun match(kickoff: String, home: Team, away: Team, sport: Sport = Sport.SOCCER, leagueName: String = "German Cup") = Match(
        id = "401",
        sport = sport,
        feed = "soccer/ger.dfb_pokal",
        leagueSlug = "ger.dfb_pokal",
        leagueName = leagueName,
        startMs = Instant.parse(kickoff).toEpochMilli(),
        state = MatchState.PRE,
        statusText = "",
        title = "${home.name} v ${away.name}",
        competitors = listOf(
            Competitor(home, home = true, winner = null, score = null),
            Competitor(away, home = false, winner = null, score = null),
        ),
    )

    private companion object {
        const val USER_A = "user-a"
        const val USER_B = "user-b"
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val NO_NETWORK = -1
        const val BASE = "https://test.supabase.local"
        const val LISTING_PATH = "/rest/v1/football_listing"
        const val TOKEN_PATH = "/auth/v1/token"

        val B_TOKEN_BODY: String = JSONObject()
            .put("access_token", "b-token")
            .put("refresh_token", "r-b")
            .put("expires_in", 3600)
            .put("user", JSONObject().put("id", USER_B).put("email", "b@example.com"))
            .toString()

        val KOLN = Team(id = "1", name = "1. FC Köln", shortName = "Köln", abbreviation = "KOE", location = "Köln")
        val UNION = Team(id = "2", name = "1. FC Union Berlin", shortName = "Union Berlin", abbreviation = "FCU", location = "Berlin")

        fun listing(
            kickoff: String,
            home: String,
            away: String,
            competition: String = "German Cup (DFB-Pokal)",
            sport: String = "soccer",
        ) = FootballListing(Instant.parse(kickoff).toEpochMilli(), competition, home, away, emptyList(), sport)

        val COLOGNE_UNION = listing("2026-10-28T19:45:00Z", "FC Cologne", "Union Berlin")

        val TWO_ROWS: String = JSONArray()
            .put(
                JSONObject()
                    .put("kickoff", "2026-10-28T19:45:00+00:00")
                    .put("competition", "German Cup (DFB-Pokal)")
                    .put("home", "FC Cologne")
                    .put("away", "Union Berlin")
                    .put(
                        "channels",
                        JSONArray()
                            .put(JSONObject().put("stream_id", "1001").put("name", "DE: Sky Sport 1").put("listed_as", "Sky Sport Germany 1"))
                            .put(JSONObject().put("stream_id", "1002").put("name", "DE: ARD").put("listed_as", "ARD")),
                    ),
            )
            .put(
                JSONObject()
                    .put("kickoff", "2026-10-28T20:00:00+00:00")
                    .put("competition", "English Premier League")
                    .put("home", "Arsenal")
                    .put("away", "Chelsea")
                    .put("channels", JSONArray()),
            )
            .toString()
    }
}
