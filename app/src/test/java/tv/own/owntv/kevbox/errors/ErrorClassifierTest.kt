package tv.own.owntv.kevbox.errors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.player.PlayerFailureReason

class ErrorClassifierTest {

    private fun exo(raw: String?, reason: PlayerFailureReason? = null, online: Boolean = true) =
        ErrorClassifier.playback(Engine.EXO, raw, reason, online)

    private fun mpv(raw: String?, reason: PlayerFailureReason? = null, online: Boolean = true) =
        ErrorClassifier.playback(Engine.MPV, raw, reason, online)

    /** Every status row of the research file's main table (docs/superpowers/2026-10-14-0.3-error-reporting-tivimate-codes.md). */
    private val researchStatuses = listOf(
        400, 401, 402, 403, 404, 405, 406, 407, 409, 410, 429,
        451, 452, 453, 454, 455, 456, 457, 458, 461, 462, 463, 464, 469,
        500, 502, 503, 504, 509, 511, 512, 513,
        520, 521, 522, 523, 524, 525, 526, 530, 551, 884, 999,
    )

    /** The research table's no-status rows, as the engine text Core hands over, and the code each gives. */
    private val researchNoStatusRows = listOf(
        "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED: HttpDataSource\$HttpDataSourceException: java.net.ConnectException: Connection refused"
            to LiveErrorCodes.NET_REFUSED,
        "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED: HttpDataSource\$HttpDataSourceException: java.net.SocketTimeoutException: timeout"
            to LiveErrorCodes.NET_TIMEOUT,
        "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED: java.net.UnknownHostException: Unable to resolve host \"panel\""
            to LiveErrorCodes.NET_DNS,
        "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED: javax.net.ssl.SSLHandshakeException: Trust anchor for certification path not found."
            to LiveErrorCodes.NET_SSL,
        "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED: UnrecognizedInputFormatException: None of the available extractors (TsExtractor) could read the stream."
            to LiveErrorCodes.FORMAT_UNRECOGNISED,
        "ERROR_CODE_PARSING_MANIFEST_MALFORMED: ParserException: Couldn't parse playlist"
            to LiveErrorCodes.PARSE_FAILED,
        "ERROR_CODE_DECODING_FAILED: MediaCodecVideoRenderer\$MediaCodecVideoDecoderException: Decoder failed: c2.amlogic.avc.decoder"
            to LiveErrorCodes.DECODER_FAILED,
        "ERROR_CODE_DECODER_INIT_FAILED: MediaCodecRenderer\$DecoderInitializationException: Decoder init failed"
            to LiveErrorCodes.DECODER_FAILED,
        "ERROR_CODE_AUDIO_TRACK_INIT_FAILED: AudioSink\$InitializationException: AudioTrack init failed 0"
            to LiveErrorCodes.AUDIO_FAILED,
        "ERROR_CODE_BEHIND_LIVE_WINDOW: BehindLiveWindowException"
            to LiveErrorCodes.BEHIND_LIVE_WINDOW,
        "ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED: MediaDrmCallbackException"
            to LiveErrorCodes.DRM_FAILED,
    )

    @Test
    fun `every research table row maps to its code`() {
        researchStatuses.forEach { n ->
            val c = exo("ERROR_CODE_IO_BAD_HTTP_STATUS: Response code: $n")
            assertEquals("exo $n", LiveErrorCodes.http(n), c.code)
            assertEquals("exo $n", n, c.httpStatus)
            assertEquals("ERROR_CODE_IO_BAD_HTTP_STATUS", c.errorName)
        }
        listOf(403, 404).forEach { n ->
            val text = if (n == 403) "http: HTTP error 403 Forbidden" else "http: HTTP error 404 Not Found"
            val c = mpv(text)
            assertEquals("mpv $n", LiveErrorCodes.http(n), c.code)
            assertEquals(n, c.httpStatus)
            assertEquals(text, c.errorName)
        }
        researchNoStatusRows.forEach { (raw, code) ->
            val c = exo(raw)
            assertEquals(raw, code, c.code)
            assertNull(raw, c.httpStatus)
            assertEquals(raw.substringBefore(':'), c.errorName)
        }

        val loader = "androidx.media3.exoplayer.upstream.Loader\$UnexpectedLoaderException"
        assertEquals(Classified(LiveErrorCodes.OTHER, null, loader), exo(loader))
        val state = "java.lang.IllegalStateException"
        assertEquals(Classified(LiveErrorCodes.OTHER, null, state), exo(state))
    }

    @Test
    fun `mpv failure without a status is mpv_load_failed`() {
        val open = "Failed to open http://h/live/u/p/1.ts."
        assertEquals(Classified(LiveErrorCodes.MPV_LOAD_FAILED, null, open), mpv(open))

        assertEquals(LiveErrorCodes.STREAM_LOST, exo("no data for 12s; gave up after 5 reconnects").code)
        assertEquals(LiveErrorCodes.STREAM_LOST, mpv("no data for 12s; gave up after 5 reconnects").code)

        val noRoute = exo("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED: java.net.NoRouteToHostException")
        assertEquals(LiveErrorCodes.NET_REFUSED, noRoute.code)
        assertEquals("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", noRoute.errorName)
    }

    @Test
    fun `a status wins over every other rule, including offline`() {
        val c = exo("Response code: 458 timeout", online = false)
        assertEquals("http_458", c.code)
        assertEquals(458, c.httpStatus)
    }

    @Test
    fun `offline and Core reasons decide when the text says nothing`() {
        assertEquals(LiveErrorCodes.NET_OFFLINE, exo("anything", online = false).code)
        assertEquals(LiveErrorCodes.NET_SSL, exo("x", PlayerFailureReason.SSL).code)
        assertEquals(LiveErrorCodes.NET_REFUSED, exo("x", PlayerFailureReason.NETWORK).code)
        assertEquals(LiveErrorCodes.FORMAT_UNRECOGNISED, mpv("x", PlayerFailureReason.FORMAT).code)
        assertEquals(LiveErrorCodes.DECODER_FAILED, exo("x", PlayerFailureReason.UNSUPPORTED_VIDEO).code)
        assertEquals(LiveErrorCodes.DECODER_FAILED, exo("x", PlayerFailureReason.DECODER_MEMORY).code)
        assertEquals(LiveErrorCodes.AUDIO_FAILED, exo("x", PlayerFailureReason.AUDIO).code)
        assertEquals(LiveErrorCodes.DRM_FAILED, exo(null, PlayerFailureReason.DRM).code)
        assertEquals(Classified(LiveErrorCodes.OTHER, null, null), exo(null))
        assertEquals(Classified(LiveErrorCodes.MPV_LOAD_FAILED, null, null), mpv("   "))
    }

    @Test
    fun `errorName takes the first line and stops at 120 characters`() {
        assertEquals("first line", mpv("  first line  \nsecond line").errorName)
        assertEquals(120, exo("y".repeat(300)).errorName!!.length)
    }

    @Test
    fun `playlist failures get playlist codes and keep the status`() {
        assertEquals(Classified(LiveErrorCodes.PLAYLIST_RATE_LIMITED, 429, "HTTP 429"), ErrorClassifier.playlist("HTTP 429", true))
        assertEquals(LiveErrorCodes.PLAYLIST_M3U_DISABLED, ErrorClassifier.playlist("HTTP 884", true).code)
        assertEquals(LiveErrorCodes.PLAYLIST_UNREACHABLE, ErrorClassifier.playlist("whatever", false).code)
        assertEquals(LiveErrorCodes.PLAYLIST_UNREACHABLE, ErrorClassifier.playlist("Unable to resolve host \"p\"", true).code)
        assertEquals(LiveErrorCodes.PLAYLIST_UNREACHABLE, ErrorClassifier.playlist("connect timed out", true).code)
        assertEquals(
            LiveErrorCodes.PLAYLIST_PARSE_FAILED,
            ErrorClassifier.playlist("Expected BEGIN_ARRAY but was STRING at line 1", true).code,
        )
        val forbidden = ErrorClassifier.playlist("HTTP 403\nbody", true)
        assertEquals(Classified(LiveErrorCodes.PLAYLIST_DOWNLOAD_FAILED, 403, "HTTP 403"), forbidden)
        assertEquals(Classified(LiveErrorCodes.PLAYLIST_DOWNLOAD_FAILED, null, null), ErrorClassifier.playlist(null, true))
    }

    @Test
    fun `guide failures get guide codes and keep the status`() {
        assertEquals(LiveErrorCodes.GUIDE_UNREACHABLE, ErrorClassifier.guide(null, false).code)
        assertEquals(LiveErrorCodes.GUIDE_UNREACHABLE, ErrorClassifier.guide("Connection reset", true).code)
        assertEquals(LiveErrorCodes.GUIDE_PARSE_FAILED, ErrorClassifier.guide("Unexpected end of document", true).code)
        assertEquals(LiveErrorCodes.GUIDE_EMPTY, ErrorClassifier.guide("Guide is empty", true).code)
        assertEquals(LiveErrorCodes.GUIDE_EMPTY, ErrorClassifier.guide("No programmes found", true).code)
        assertEquals(Classified(LiveErrorCodes.GUIDE_DOWNLOAD_FAILED, 404, "HTTP 404"), ErrorClassifier.guide("HTTP 404", true))
    }

    @Test
    fun `withAccount replaces only real account findings`() {
        val base = Classified("http_403", 403, "ERROR_CODE_IO_BAD_HTTP_STATUS")
        fun account(status: String, active: Int? = null, max: Int? = null) = AccountResult(status, active, max)

        mapOf(
            AccountStatus.EXPIRED to LiveErrorCodes.ACCOUNT_EXPIRED,
            AccountStatus.BANNED to LiveErrorCodes.ACCOUNT_BANNED,
            AccountStatus.DISABLED to LiveErrorCodes.ACCOUNT_DISABLED,
            AccountStatus.WRONG_LOGIN to LiveErrorCodes.ACCOUNT_WRONG_LOGIN,
        ).forEach { (status, code) ->
            assertEquals(base.copy(code = code), ErrorClassifier.withAccount(base, account(status), playlist = false))
            assertEquals(base.copy(code = code), ErrorClassifier.withAccount(base, account(status), playlist = true))
        }

        assertEquals(base, ErrorClassifier.withAccount(base, account(AccountStatus.UNREACHABLE), playlist = true))
        assertEquals(base, ErrorClassifier.withAccount(base, account(AccountStatus.ACTIVE, 1, 2), playlist = true))
        assertEquals(base, ErrorClassifier.withAccount(base, null, playlist = true))

        val full = account(AccountStatus.ACTIVE, 2, 2)
        assertEquals(base.copy(code = LiveErrorCodes.PLAYLIST_MAX_CONNECTIONS), ErrorClassifier.withAccount(base, full, playlist = true))
        assertEquals(base, ErrorClassifier.withAccount(base, full, playlist = false))
        assertEquals(base, ErrorClassifier.withAccount(base, account(AccountStatus.ACTIVE, 3, 0), playlist = true))
        assertEquals(base, ErrorClassifier.withAccount(base, account(AccountStatus.ACTIVE, null, 2), playlist = true))
    }
}
