// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.release

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.kevbox.release.SilentUpdateDownload.Plan

class SilentUpdateDownloadTest {

    @Test
    fun `nothing on disk starts from zero`() {
        assertEquals(0L, SilentUpdateDownload.startAt(have = 0, expectedTotal = null))
        assertEquals(0L, SilentUpdateDownload.startAt(have = 0, expectedTotal = 500))
    }

    @Test
    fun `a partial file resumes from its end`() {
        assertEquals(200L, SilentUpdateDownload.startAt(have = 200, expectedTotal = 500))
        assertEquals(200L, SilentUpdateDownload.startAt(have = 200, expectedTotal = null))
    }

    @Test
    fun `a file of the full size is whole, a longer one starts over`() {
        assertNull(SilentUpdateDownload.startAt(have = 500, expectedTotal = 500))
        assertEquals(0L, SilentUpdateDownload.startAt(have = 600, expectedTotal = 500))
    }

    @Test
    fun `206 from where the file ends appends`() {
        assertEquals(Plan.Append(500), SilentUpdateDownload.plan(200, 500, 206, "bytes 200-499/500", 300))
        assertEquals(Plan.Append(500), SilentUpdateDownload.plan(200, null, 206, "bytes 200-499/500", 300))
        assertEquals(Plan.Append(500), SilentUpdateDownload.plan(200, 500, 206, "bytes 200-499/*", 300))
        assertEquals(Plan.Append(null), SilentUpdateDownload.plan(200, null, 206, "bytes 200-499/*", 300))
    }

    @Test
    fun `206 from elsewhere, of another size, or unreadable starts over`() {
        assertEquals(Plan.Retry, SilentUpdateDownload.plan(200, 500, 206, "bytes 0-499/500", 500))
        assertEquals(Plan.Retry, SilentUpdateDownload.plan(200, 500, 206, "bytes 200-599/600", 400))
        assertEquals(Plan.Retry, SilentUpdateDownload.plan(200, 500, 206, null, 300))
        assertEquals(Plan.Retry, SilentUpdateDownload.plan(200, 500, 206, "200-499/500", 300))
    }

    @Test
    fun `200 is the whole file again, with or without a length`() {
        assertEquals(Plan.Restart(500), SilentUpdateDownload.plan(200, 500, 200, null, 500))
        assertEquals(Plan.Restart(500), SilentUpdateDownload.plan(0, null, 200, null, 500))
        assertEquals(Plan.Restart(null), SilentUpdateDownload.plan(0, null, 200, null, -1))
    }

    @Test
    fun `416 starts over, other statuses keep the partial file`() {
        assertEquals(Plan.Retry, SilentUpdateDownload.plan(500, 500, 416, "bytes */500", 0))
        assertEquals(Plan.Fail, SilentUpdateDownload.plan(200, 500, 404, null, 0))
        assertEquals(Plan.Fail, SilentUpdateDownload.plan(200, 500, 503, null, 0))
    }

    @Test
    fun `content range parse`() {
        assertEquals(SilentUpdateDownload.ContentRange(200, 500), SilentUpdateDownload.parseContentRange("bytes 200-499/500"))
        assertEquals(SilentUpdateDownload.ContentRange(7, null), SilentUpdateDownload.parseContentRange(" bytes 7-9/* "))
        assertNull(SilentUpdateDownload.parseContentRange("bytes */500"))
        assertNull(SilentUpdateDownload.parseContentRange(""))
        assertNull(SilentUpdateDownload.parseContentRange(null))
    }

    @Test
    fun `a bounded read keeps a small body and refuses a big one`() {
        val small = ByteArray(10) { it.toByte() }
        assertArrayEquals(small, SilentUpdateDownload.readAtMost(ByteArrayInputStream(small), limit = 10))
        assertArrayEquals(ByteArray(0), SilentUpdateDownload.readAtMost(ByteArrayInputStream(ByteArray(0)), limit = 10))
        assertNull(SilentUpdateDownload.readAtMost(ByteArrayInputStream(ByteArray(11)), limit = 10))
    }

    @Test
    fun `a bounded read stops one byte past the limit`() {
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int = 0.also { served++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int = len.also { served += it }
        }
        assertNull(SilentUpdateDownload.readAtMost(endless, limit = 100_000))
        assertEquals(100_001L, served)
    }

    @Test
    fun `a copy copies everything`() = runBlocking {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        assertEquals(200_000L, SilentUpdateDownload.copyCancellable(ByteArrayInputStream(data), out))
        assertArrayEquals(data, out.toByteArray())
    }

    @Test
    fun `a cancelled copy stops before the next buffer`() = runBlocking {
        var reads = 0
        val job = launch {
            val me = coroutineContext.job
            val endless = object : InputStream() {
                override fun read(): Int = 0
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    reads++
                    me.cancel()
                    return len
                }
            }
            SilentUpdateDownload.copyCancellable(endless, ByteArrayOutputStream())
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, reads)
    }
}
