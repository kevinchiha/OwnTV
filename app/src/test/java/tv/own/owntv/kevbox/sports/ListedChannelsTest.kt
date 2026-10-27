// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.sports

import java.net.UnknownHostException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.sports.WatchOption
import tv.own.owntv.core.sports.WatchTier
import tv.own.owntv.core.sports.model.Competitor
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.core.sports.model.Team
import tv.own.owntv.kevbox.session.KevBoxAuth
import tv.own.owntv.kevbox.session.SessionStore
import tv.own.owntv.kevbox.session.StoredSession
import tv.own.owntv.kevbox.supabase.SupabaseApi

class ListedChannelsTest {

    /** Answers every listing read with 200 and [body]. */
    private class FakeServer(var body: String) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (request.url.encodedPath != LISTING_PATH) throw UnknownHostException("no canned response")
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("canned")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
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

    /** The profile's channels; [hidden] ids are the ones the profile hid, which a read never returns. */
    private class FakeReads(val all: List<ChannelEntity>, val hidden: Set<Long> = emptySet()) : ListedChannelReads {
        val asked = mutableListOf<List<String>>()
        var fail = false

        override suspend fun visibleByRemoteIdsOrNames(keys: List<String>): List<ChannelEntity> {
            asked += keys
            if (fail) throw IllegalStateException("database closed")
            return all.filter { (it.remoteId in keys || it.name in keys) && it.id !in hidden }
        }
    }

    private val clock = Instant.parse("2026-10-28T12:00:00Z").toEpochMilli()

    private fun listedChannels(channels: List<ListedChannel>, reads: ListedChannelReads): ListedChannels {
        val api = SupabaseApi(OkHttpClient.Builder().addInterceptor(FakeServer(body(channels))).build(), BASE, "anon-key")
        val store = MemoryStore(StoredSession("user-a", "a@example.com", "a-token", "r-token", clock + 100 * HOUR))
        val auth = KevBoxAuth(api, store) { clock }
        return ListedChannels(FootballListings(auth, api) { clock }, reads)
    }

    @Test
    fun `the stream id beats the name and the order follows the listing`() = runBlocking {
        val sky = channel(1, remoteId = "1001", name = "DE: Sky Sport 1")
        // The stream id agrees on the name once case and runs of spaces are ignored.
        val ardById = channel(2, remoteId = "1002", name = "de:  ARD")
        val ardByName = channel(3, remoteId = "7777", name = "DE: ARD")
        val reads = FakeReads(listOf(sky, ardById, ardByName))
        val listed = listedChannels(
            listOf(
                ListedChannel("1002", "DE: ARD", "ARD"),
                // The stream id is not in the playlist any more: the name finds it.
                ListedChannel("9999", "DE: Sky Sport 1", "Sky Sport Germany 1"),
                // The same channel listed twice gives one row.
                ListedChannel("1001", "DE: Sky Sport 1", "Sky Sport Germany 1 HD"),
            ),
            reads,
        )

        val options = listed.options(cologneUnion())

        assertEquals(listOf(ardById, sky), options.map { it.channel })
        assertTrue(options.all { it.tier == WatchTier.GUIDE && it.guideTitle == ListedChannels.MARK })
        assertTrue(options.all(ListedChannels::isListed))
    }

    @Test
    fun `a stream id reused by another playlist for another channel does not count`() = runBlocking {
        // Playlist 2 reuses stream id 1001 for a channel that has nothing to do with the listing.
        val unrelated = channel(5, remoteId = "1001", name = "UK: Sky Sports Main Event", sourceId = 2)
        val sky = channel(6, remoteId = "4242", name = "DE: Sky Sport 1", sourceId = 1)
        val listed = listedChannels(
            listOf(ListedChannel("1001", "DE: Sky Sport 1", "Sky Sport Germany 1")),
            FakeReads(listOf(unrelated, sky)),
        )

        assertEquals(listOf(sky), listed.options(cologneUnion()).map { it.channel })
    }

    @Test
    fun `listedFirst moves the listed options to the front and keeps each group's order`() {
        val sky = WatchOption(channel(1, remoteId = "1001", name = "DE: Sky Sport 1"), WatchTier.GUIDE, "Bundesliga: Köln v Union")
        val ard = WatchOption(channel(2, remoteId = "1002", name = "DE: ARD"), WatchTier.GUIDE, ListedChannels.MARK)
        val bein = WatchOption(channel(3, remoteId = "1003", name = "AR: beIN Sports 1"), WatchTier.EVENT)
        val zdf = WatchOption(channel(4, remoteId = "1004", name = "DE: ZDF"), WatchTier.GUIDE, ListedChannels.MARK)

        assertEquals(listOf(ard, zdf, sky, bein), ListedChannels.listedFirst(listOf(sky, ard, bein, zdf)))
        val none = listOf(sky, bein)
        assertSame(none, ListedChannels.listedFirst(none))
    }

    @Test
    fun `a channel the profile hid is left out`() = runBlocking {
        val sky = channel(1, remoteId = "1001", name = "DE: Sky Sport 1")
        val ard = channel(2, remoteId = "1002", name = "DE: ARD")
        val listed = listedChannels(
            listOf(ListedChannel("1001", "DE: Sky Sport 1", "Sky"), ListedChannel("1002", "DE: ARD", "ARD")),
            FakeReads(listOf(sky, ard), hidden = setOf(1L)),
        )

        assertEquals(listOf(ard), listed.options(cologneUnion()).map { it.channel })
    }

    @Test
    fun `no link, or a failed read, gives no options`() = runBlocking {
        val reads = FakeReads(listOf(channel(1, remoteId = "1001", name = "DE: Sky Sport 1")))
        val listed = listedChannels(listOf(ListedChannel("1001", "DE: Sky Sport 1", "Sky")), reads)

        assertEquals(emptyList<WatchOption>(), listed.options(cologneUnion().copy(sport = Sport.BASKETBALL)))
        assertEquals(emptyList<WatchOption>(), listed.options(cologneUnion().copy(startMs = cologneUnion().startMs + 3 * HOUR)))
        assertTrue(reads.asked.isEmpty())

        reads.fail = true
        assertEquals(emptyList<WatchOption>(), listed.options(cologneUnion()))
    }

    @Test
    fun `merge puts the listed channels first and drops Core's duplicate`() {
        val sky = channel(1, remoteId = "1001", name = "DE: Sky Sport 1")
        val ard = channel(2, remoteId = "1002", name = "DE: ARD")
        val bein = channel(3, remoteId = "1003", name = "AR: beIN Sports 1")
        val listed = listOf(WatchOption(ard, WatchTier.GUIDE, ListedChannels.MARK))
        val core = listOf(
            WatchOption(sky, WatchTier.GUIDE, "Bundesliga: Köln v Union"),
            WatchOption(ard, WatchTier.LIKELY),
            WatchOption(bein, WatchTier.EVENT),
        )

        assertEquals(listOf(listed[0], core[0], core[2]), ListedChannels.merge(listed, core))
        assertSame(core, ListedChannels.merge(emptyList(), core))
        assertTrue(!ListedChannels.isListed(core[0]))
    }

    private fun channel(id: Long, remoteId: String, name: String, sourceId: Long = 1) =
        ChannelEntity(id = id, sourceId = sourceId, categoryId = 10, name = name, streamUrl = "http://x/$id.ts", remoteId = remoteId)

    private fun cologneUnion() = Match(
        id = "401",
        sport = Sport.SOCCER,
        feed = "soccer/ger.dfb_pokal",
        leagueSlug = "ger.dfb_pokal",
        leagueName = "German Cup",
        startMs = Instant.parse("2026-10-28T19:45:00Z").toEpochMilli(),
        state = MatchState.PRE,
        statusText = "",
        title = "${KOLN.name} v ${UNION.name}",
        competitors = listOf(
            Competitor(KOLN, home = true, winner = null, score = null),
            Competitor(UNION, home = false, winner = null, score = null),
        ),
    )

    private companion object {
        const val HOUR = 3_600_000L
        const val BASE = "https://test.supabase.local"
        const val LISTING_PATH = "/rest/v1/football_listing"

        val KOLN = Team(id = "1", name = "1. FC Köln", shortName = "Köln", abbreviation = "KOE", location = "Köln")
        val UNION = Team(id = "2", name = "1. FC Union Berlin", shortName = "Union Berlin", abbreviation = "FCU", location = "Berlin")

        /** One football_listing row, "FC Cologne v Union Berlin" at 19:45 UTC, with [channels]. */
        fun body(channels: List<ListedChannel>): String = JSONArray()
            .put(
                JSONObject()
                    .put("kickoff", "2026-10-28T19:45:00+00:00")
                    .put("competition", "German Cup (DFB-Pokal)")
                    .put("home", "FC Cologne")
                    .put("away", "Union Berlin")
                    .put(
                        "channels",
                        JSONArray().apply {
                            channels.forEach { put(JSONObject().put("stream_id", it.streamId).put("name", it.name).put("listed_as", it.listedAs)) }
                        },
                    ),
            )
            .toString()
    }
}
