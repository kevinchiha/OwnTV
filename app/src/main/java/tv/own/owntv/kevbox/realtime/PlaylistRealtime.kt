// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.realtime

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import tv.own.owntv.kevbox.session.KevBoxAuth
import tv.own.owntv.kevbox.supabase.Fetch

/** One open socket, as [PlaylistRealtime] uses it. The OkHttp one in the app, a fake in tests. */
internal interface RealtimeSocket {
    /** False when the socket can no longer send. */
    fun send(text: String): Boolean

    fun close()

    /** What the socket reports; called from any thread. */
    interface Events {
        fun onMessage(text: String)

        /** Closed by either side, or failed. */
        fun onGone()
    }
}

/**
 * A Supabase Realtime socket on the member's own `member_playlist` rows, so a change made in
 * kevbox.dev reaches the TV in seconds instead of at the 15-minute check (0.6 AC-39, AC-33).
 *
 * It follows [KevBoxAuth.userId]: open while someone is signed in, closed while no one is. It joins
 * with a fresh access token, sends a heartbeat every 25 s and a new token every 30 minutes. No frame
 * within 10 s of a heartbeat, a refusal, or a socket that fails or closes means a reconnect after
 * [backoffMs]. The attempt count goes back to 0 only once a join has held for 60 s: Supabase answers
 * the join with ok and only then reports a change feed that cannot start (a server without migration
 * 0023), so counting the ok as success would reconnect every 5 s on every TV.
 *
 * A change, and every successful (re)join, calls [onChange]; a change made while the socket was down
 * is caught by the check the rejoin starts. The payload is never read: the playlist check fetches
 * the row itself. Nothing here logs: frames carry the token, the URL carries the key.
 */
class PlaylistRealtime internal constructor(
    private val url: String?,
    private val auth: KevBoxAuth,
    private val scope: CoroutineScope,
    private val sleep: suspend (Long) -> Unit,
    private val onChange: () -> Unit,
    private val connect: (url: String, events: RealtimeSocket.Events) -> RealtimeSocket,
) {
    constructor(
        http: OkHttpClient,
        baseUrl: String,
        anonKey: String,
        auth: KevBoxAuth,
        scope: CoroutineScope,
        sleep: suspend (Long) -> Unit,
        onChange: () -> Unit,
    ) : this(socketUrl(baseUrl, anonKey), auth, scope, sleep, onChange, okHttpConnect(http))

    @Volatile private var started = false

    /** Once, from KevBoxCoordinator.start; later calls do nothing. A build without a server URL never opens. */
    fun start() {
        val url = url ?: return
        if (started) return
        started = true
        scope.launch {
            // A new member (or none) cancels the old member's socket and its reconnects.
            auth.userId.collectLatest { userId -> if (userId != null) follow(url, userId) }
        }
    }

    private suspend fun follow(url: String, userId: String) {
        var attempt = 0
        while (true) {
            val held = try {
                connection(url, userId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (held) attempt = 0
            sleep(backoffMs(attempt))
            attempt = (attempt + 1).coerceAtMost(MAX_ATTEMPT)
        }
    }

    /** One socket's life; true when its join held for [HOLD_MS]. A token that cannot be read is a failed attempt. */
    private suspend fun connection(url: String, userId: String): Boolean {
        val token = (auth.accessToken() as? Fetch.Ok)?.value ?: return false
        val events = Channel<Event>(Channel.UNLIMITED)
        val socket = connect(
            url,
            object : RealtimeSocket.Events {
                override fun onMessage(text: String) {
                    events.trySend(Event.Frame(text))
                }

                override fun onGone() {
                    events.trySend(Event.Gone)
                }
            },
        )
        return try {
            coroutineScope {
                val held = talk(socket, events, userId, token)
                coroutineContext.cancelChildren()
                held
            }
        } finally {
            events.close()
            socket.close()
        }
    }

    /** Joins, then handles frames and timers one at a time until the socket has to go. */
    private suspend fun CoroutineScope.talk(
        socket: RealtimeSocket,
        events: Channel<Event>,
        userId: String,
        token: String,
    ): Boolean {
        var ref = 0
        var joined = false
        var held = false
        var awaitingReply: Int? = null

        ref += 1
        if (!socket.send(joinFrame(userId, token, ref))) return false
        launch {
            while (true) {
                sleep(HEARTBEAT_MS)
                events.send(Event.HeartbeatDue)
            }
        }
        launch {
            while (true) {
                sleep(TOKEN_MS)
                events.send(Event.TokenDue)
            }
        }

        for (event in events) {
            when (event) {
                is Event.Frame -> {
                    // Any frame proves the socket is alive; the heartbeat's own reply is one.
                    awaitingReply = null
                    when (parseIncoming(event.text)) {
                        Incoming.Joined -> if (!joined) {
                            joined = true
                            launch {
                                sleep(HOLD_MS)
                                events.send(Event.Held)
                            }
                            changed()
                        }
                        Incoming.Change -> changed()
                        Incoming.Refused -> return held
                        Incoming.Other -> Unit
                    }
                }
                Event.Gone -> return held
                Event.HeartbeatDue -> {
                    ref += 1
                    val sent = ref
                    if (!socket.send(heartbeatFrame(sent))) return held
                    awaitingReply = sent
                    launch {
                        sleep(REPLY_MS)
                        events.send(Event.ReplyDue(sent))
                    }
                }
                is Event.ReplyDue -> if (awaitingReply == event.ref) return held
                Event.Held -> held = true
                Event.TokenDue -> launch {
                    val fresh = try {
                        (auth.accessToken() as? Fetch.Ok)?.value
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    // A failed read keeps the socket: the server closes the channel if its token runs out.
                    if (fresh != null) events.send(Event.Token(fresh))
                }
                is Event.Token -> {
                    ref += 1
                    if (!socket.send(accessTokenFrame(event.token, ref))) return held
                }
            }
        }
        return held
    }

    private fun changed() {
        try {
            onChange()
        } catch (_: Exception) {
            // The coordinator's check guards itself; nothing here may end the socket.
        }
    }

    private sealed interface Event {
        class Frame(val text: String) : Event
        data object Gone : Event
        data object HeartbeatDue : Event
        class ReplyDue(val ref: Int) : Event
        data object Held : Event
        data object TokenDue : Event
        class Token(val token: String) : Event
    }

    internal companion object {
        const val HEARTBEAT_MS = 25_000L
        const val REPLY_MS = 10_000L
        const val TOKEN_MS = 30 * 60_000L
        const val HOLD_MS = 60_000L
        private const val MAX_ATTEMPT = 16
        private const val NORMAL_CLOSURE = 1000

        /**
         * OkHttp's socket over the shared client, always direct like SupabaseApi: the customer's
         * proxy is for the provider, and one that is down must not cut the TV off from kevbox.dev.
         */
        fun okHttpConnect(http: OkHttpClient): (String, RealtimeSocket.Events) -> RealtimeSocket {
            val client = http.newBuilder()
                .proxy(java.net.Proxy.NO_PROXY)
                .proxyAuthenticator(okhttp3.Authenticator.NONE)
                .build()
            return { url, events ->
                val listener = object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) = events.onMessage(text)

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(NORMAL_CLOSURE, null)
                        events.onGone()
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = events.onGone()

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = events.onGone()
                }
                val socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
                object : RealtimeSocket {
                    override fun send(text: String) = socket.send(text)

                    override fun close() {
                        socket.close(NORMAL_CLOSURE, null)
                    }
                }
            }
        }
    }
}
