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
        assertEquals("kickoff,competition,home,away,channels", request.url.queryParameter("select"))
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

    private fun match(kickoff: String, home: Team, away: Team) = Match(
        id = "401",
        sport = Sport.SOCCER,
        feed = "soccer/ger.dfb_pokal",
        leagueSlug = "ger.dfb_pokal",
        leagueName = "German Cup",
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

        fun listing(kickoff: String, home: String, away: String) =
            FootballListing(Instant.parse(kickoff).toEpochMilli(), "German Cup (DFB-Pokal)", home, away, emptyList())

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
