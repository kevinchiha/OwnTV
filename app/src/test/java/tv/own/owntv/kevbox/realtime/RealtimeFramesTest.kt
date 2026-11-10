// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.realtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RealtimeFramesTest {

    @Test
    fun `joinFrame carries the filter and the token`() {
        val frame = JSONObject(joinFrame("user-1", "token-1", 3))

        assertEquals(REALTIME_TOPIC, frame.getString("topic"))
        assertEquals("phx_join", frame.getString("event"))
        assertEquals("3", frame.getString("ref"))
        assertEquals("3", frame.getString("join_ref"))
        val payload = frame.getJSONObject("payload")
        assertEquals("token-1", payload.getString("access_token"))
        val config = payload.getJSONObject("config")
        assertEquals(false, config.getJSONObject("broadcast").getBoolean("self"))
        assertEquals("", config.getJSONObject("presence").getString("key"))
        val changes = config.getJSONArray("postgres_changes")
        assertEquals(1, changes.length())
        val change = changes.getJSONObject(0)
        assertEquals("*", change.getString("event"))
        assertEquals("public", change.getString("schema"))
        assertEquals("member_playlist", change.getString("table"))
        assertEquals("user_id=eq.user-1", change.getString("filter"))
    }

    @Test
    fun `heartbeat and access token frames have Phoenix's shapes`() {
        val heartbeat = JSONObject(heartbeatFrame(7))
        assertEquals("phoenix", heartbeat.getString("topic"))
        assertEquals("heartbeat", heartbeat.getString("event"))
        assertEquals("7", heartbeat.getString("ref"))
        assertEquals(0, heartbeat.getJSONObject("payload").length())

        val token = JSONObject(accessTokenFrame("token-2", 8))
        assertEquals(REALTIME_TOPIC, token.getString("topic"))
        assertEquals("access_token", token.getString("event"))
        assertEquals("8", token.getString("ref"))
        assertEquals("token-2", token.getJSONObject("payload").getString("access_token"))
    }

    @Test
    fun `parseIncoming sorts the server's frames`() {
        fun frame(topic: String, event: String, payload: String = "{}") =
            """{"topic":"$topic","event":"$event","ref":"1","payload":$payload}"""

        assertEquals(Incoming.Joined, parseIncoming(frame(REALTIME_TOPIC, "phx_reply", """{"status":"ok","response":{}}""")))
        assertEquals(Incoming.Refused, parseIncoming(frame(REALTIME_TOPIC, "phx_reply", """{"status":"error","response":{}}""")))
        assertEquals(
            Incoming.Change,
            parseIncoming(frame(REALTIME_TOPIC, "postgres_changes", """{"data":{"type":"UPDATE","table":"member_playlist"},"ids":[1]}""")),
        )
        assertEquals(
            Incoming.Refused,
            parseIncoming(frame(REALTIME_TOPIC, "system", """{"status":"error","extension":"postgres_changes","message":"x"}""")),
        )
        assertEquals(Incoming.Other, parseIncoming(frame(REALTIME_TOPIC, "system", """{"status":"ok","extension":"postgres_changes"}""")))
        assertEquals(Incoming.Refused, parseIncoming(frame(REALTIME_TOPIC, "phx_error")))
        assertEquals(Incoming.Refused, parseIncoming(frame(REALTIME_TOPIC, "phx_close")))
        // The heartbeat's reply, and another channel's frames, are not ours to act on.
        assertEquals(Incoming.Other, parseIncoming(frame("phoenix", "phx_reply", """{"status":"ok","response":{}}""")))
        assertEquals(Incoming.Other, parseIncoming(frame("realtime:other", "postgres_changes")))
        assertEquals(Incoming.Other, parseIncoming(frame("realtime:other", "phx_close")))
        assertEquals(Incoming.Other, parseIncoming("not json"))
        assertEquals(Incoming.Other, parseIncoming("[1,2]"))
        assertEquals(Incoming.Other, parseIncoming(""))
    }

    @Test
    fun `backoff doubles from 5 s to 5 minutes`() {
        assertEquals(
            listOf(5L, 10L, 20L, 40L, 80L, 160L, 300L, 300L, 300L),
            (0..8).map { backoffMs(it) / 1000 },
        )
        assertEquals(300_000L, backoffMs(1_000))
        assertEquals(5_000L, backoffMs(-1))
    }

    @Test
    fun `socketUrl swaps the scheme and adds the key and the protocol version`() {
        assertEquals(
            "wss://kevbox.example/realtime/v1/websocket?apikey=anon-key&vsn=1.0.0",
            socketUrl("https://kevbox.example/", "anon-key"),
        )
        assertEquals(
            "ws://10.0.2.2:54321/realtime/v1/websocket?apikey=anon-key&vsn=1.0.0",
            socketUrl(" http://10.0.2.2:54321 ", "anon-key"),
        )
        assertNull(socketUrl("", "anon-key"))
        assertNull(socketUrl("   ", "anon-key"))
        assertNull(socketUrl("https://kevbox.example", ""))
        assertNull(socketUrl("not a url", "anon-key"))
    }
}
