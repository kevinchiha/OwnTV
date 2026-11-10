// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.vpn

import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Measures how far a VPN server is, for the auto pick (AC-41). */
interface Pinger {
    /** The average round trip to [host] in whole ms; null when it gave no answer within 2 s. */
    suspend fun averageMs(host: String): Long?
}

/** How long the ping program may run before it is killed. */
private const val PING_KILL_MS = 2_500L

/** How long the output read may take once the program ended or was killed. */
private const val PING_READ_MS = 500L

// iputils prints "rtt min/avg/max/mdev = a/b/c/d ms", toybox "round-trip min/avg/max = a/b/c ms".
private val PING_SUMMARY = Regex("""(?:rtt|round-trip) min/avg/max\S* = ([0-9.]+)/([0-9.]+)/([0-9.]+)""")

/** Pure: the average from a ping summary line, rounded to whole ms; no such line → null. */
fun parsePingAverageMs(output: String): Long? {
    val match = PING_SUMMARY.find(output) ?: return null
    return match.groupValues[2].toDoubleOrNull()?.roundToLong()
}

/**
 * Android's own ping program: three echoes 0.2 s apart, two seconds at most, killed after 2.5 s.
 * The host goes as its own argument, never through a shell. Anything that goes wrong is null: a
 * ping must never crash playback.
 */
class ShellPinger : Pinger {
    override suspend fun averageMs(host: String): Long? = withContext(Dispatchers.IO) {
        // A host that looks like an option would be read as one.
        if (host.isEmpty() || host.startsWith('-')) return@withContext null
        try {
            run(host)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun run(host: String): Long? = coroutineScope {
        val process = ProcessBuilder(PING, "-n", "-c", "3", "-i", "0.2", "-w", "2", host)
            .redirectErrorStream(true)
            .start()
        try {
            val output = async(Dispatchers.IO) { process.inputStream.bufferedReader().use { it.readText() } }
            if (!process.waitFor(PING_KILL_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            // Killing the program closes its output, so the read ends.
            withTimeoutOrNull(PING_READ_MS) { output.await() }?.let(::parsePingAverageMs)
        } finally {
            process.destroy()
        }
    }

    private companion object {
        const val PING = "/system/bin/ping"
    }
}
