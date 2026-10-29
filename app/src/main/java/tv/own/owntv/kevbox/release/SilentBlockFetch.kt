// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.release

import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * One fetch of a release's `kevbox-silent.json`, kept free of Android so it runs as a plain JVM test.
 *
 * [SilentUpdater] keeps the two outcomes apart. A file that arrived but does not parse means "no
 * block", as a release without the asset does, and Core's card takes over. A fetch that failed says
 * nothing about the release: the run keeps the record of the last check and installs nothing, so one
 * network blip never brings the card up on every box waiting for the rollout.
 */
sealed interface SilentBlockFetch {
    /** The file arrived whole; [block] is null when it is not a valid [SilentBlock]. */
    data class Read(val block: SilentBlock?) : SilentBlockFetch

    /** A bad URL, no answer, an HTTP error, a file over [MAX_BYTES], or any other exception but a cancel. */
    data object Failed : SilentBlockFetch

    companion object {
        const val MAX_BYTES = 64 * 1024

        fun fetch(http: OkHttpClient, url: String, userAgent: String): SilentBlockFetch {
            return try {
                // Inside the try: a URL OkHttp cannot read throws here.
                val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful || resp.body.contentLength() > MAX_BYTES) return Failed
                    val bytes = resp.body.byteStream().use { SilentUpdateDownload.readAtMost(it, MAX_BYTES) }
                        ?: return Failed
                    Read(SilentBlock.parse(String(bytes, Charsets.UTF_8)))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Failed
            }
        }
    }
}
