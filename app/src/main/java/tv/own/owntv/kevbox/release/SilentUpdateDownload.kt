// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.release

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The download side of [SilentUpdater], kept free of Android and OkHttp so it runs as plain JVM tests.
 *
 * A 60 MB APK on a slow line can take longer than one WorkManager run (10 minutes), so the partial
 * file stays on disk between runs and the next run asks for the rest with `Range: bytes=<have>-`.
 */
object SilentUpdateDownload {
    /** What to do with the bytes already on disk, once the server answered. */
    sealed interface Plan {
        /** Add the body to the partial file. [total] is the full size, when anyone knows it. */
        data class Append(val total: Long?) : Plan

        /** The body is the whole file: write it over the partial one. */
        data class Restart(val total: Long?) : Plan

        /** Drop the partial file and ask again without a range. */
        data object Retry : Plan

        /** Keep the partial file and stop this run. */
        data object Fail : Plan
    }

    /**
     * Where the next request starts, given [have] bytes on disk and the full size [expectedTotal]
     * an earlier run learned. Null means the file on disk is already whole; 0 means start from
     * nothing (the caller drops a partial file that is longer than the full size).
     */
    fun startAt(have: Long, expectedTotal: Long?): Long? = when {
        have <= 0L -> 0L
        expectedTotal == null -> have
        have == expectedTotal -> null
        have > expectedTotal -> 0L
        else -> have
    }

    /**
     * Reads the answer to a request sent with `Range: bytes=<have>-` (no range when [have] is 0).
     * 206 resumes when its `Content-Range` starts at [have] and its full size agrees with
     * [expectedTotal]; 200 is the whole file again; 416 or any disagreement starts over; any other
     * status keeps the partial file for the next run.
     */
    fun plan(have: Long, expectedTotal: Long?, code: Int, contentRange: String?, contentLength: Long): Plan =
        when (code) {
            206 -> {
                val range = parseContentRange(contentRange)
                when {
                    range == null || range.start != have -> Plan.Retry
                    expectedTotal != null && range.total != null && range.total != expectedTotal -> Plan.Retry
                    else -> Plan.Append(range.total ?: expectedTotal)
                }
            }
            200 -> Plan.Restart(contentLength.takeIf { it > 0 })
            416 -> Plan.Retry
            else -> Plan.Fail
        }

    /** `bytes <start>-<end>/<total or *>`; [total] is null for `*`. */
    data class ContentRange(val start: Long, val total: Long?)

    private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)

    fun parseContentRange(header: String?): ContentRange? {
        val match = CONTENT_RANGE.matchEntire(header?.trim() ?: return null) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val total = match.groupValues[3].let { if (it == "*") null else it.toLongOrNull() ?: return null }
        return ContentRange(start, total)
    }

    /** The whole of [input] when it holds at most [limit] bytes, else null. Never reads past [limit] + 1. */
    fun readAtMost(input: InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        var count = 0L
        while (true) {
            val wanted = minOf(buffer.size.toLong(), limit + 1L - count).toInt()
            if (wanted <= 0) return null
            val n = input.read(buffer, 0, wanted)
            if (n < 0) return out.toByteArray()
            out.write(buffer, 0, n)
            count += n
        }
    }

    /**
     * Copies [input] to [output] and returns the byte count. Checks for cancellation before every
     * buffer, so a cancelled run stops within one read.
     */
    suspend fun copyCancellable(input: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var count = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer)
            if (n < 0) return count
            output.write(buffer, 0, n)
            count += n
        }
    }

    private const val BUFFER_BYTES = 64 * 1024
}
