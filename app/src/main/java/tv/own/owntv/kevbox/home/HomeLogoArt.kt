// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.own.owntv.kevbox.release.SilentUpdateDownload

/**
 * The home screen logo cards: each channel logo drawn whole onto an opaque 16:9 PNG, on the dark
 * plate, or on the light plate when the logo's ink would vanish on the dark one (HomeLogoPlate).
 * HomeLogoArtProvider serves the files to the launcher.
 *
 * Replaces Core's LiveLogoPosterArt, whose fitted file is transparent: on a launcher's dark card a
 * dark-ink logo (France 2's black "2") disappears. The limits are Core's: an 8 s download, 5 MB at
 * most, files kept 7 days and 200 at most, written to a temporary file and renamed into place.
 */
class HomeLogoArt(private val context: Context, client: OkHttpClient) : HomeArt {
    private val client = client.newBuilder().callTimeout(DOWNLOAD_TIMEOUT_S, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    override fun fitted(raw: String): String? {
        val file = fileFor(raw)
        return if (file.isFile && System.currentTimeMillis() - file.lastModified() <= MAX_AGE_MS) uriFor(file) else null
    }

    override fun prepare(raws: List<String>, onReady: suspend () -> Unit) {
        val missing = raws.distinct().filter { fitted(it) == null && inFlight.add(key(it)) }
        if (missing.isEmpty()) return
        scope.launch {
            var wrote = false
            try {
                val written = missing.filter { write(it) }.mapTo(HashSet()) { fileFor(it).name }
                wrote = written.isNotEmpty()
                if (wrote) trim(keep = written)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // This scope has no handler: an escaped error would crash the app.
                Log.w(TAG, "home rows: logo art failed ${e.javaClass.simpleName}")
            } finally {
                missing.forEach { inFlight.remove(key(it)) }
            }
            if (wrote) onReady()
        }
    }

    /** Downloads, draws and stores one card. False when any step fails; the next sync tries again. */
    private fun write(raw: String): Boolean {
        val bytes = runCatching { download(raw) }.getOrNull() ?: return false
        val logo = decodeCapped(bytes) ?: return false
        val card = runCatching { draw(logo) }.getOrNull()
        logo.recycle()
        card ?: return false
        val destination = fileFor(raw)
        val temporary = File(directory(context), destination.name + TEMP_SUFFIX)
        val written = runCatching {
            directory(context).mkdirs()
            val compressed = FileOutputStream(temporary).use { card.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
            // renameTo replaces an older card in one step, so the launcher never finds the file missing.
            compressed && temporary.renameTo(destination)
        }.getOrDefault(false)
        card.recycle()
        if (!written) temporary.delete()
        return written
    }

    private fun download(raw: String): ByteArray? =
        client.newCall(Request.Builder().url(raw).build()).execute().use { resp ->
            if (!resp.isSuccessful || resp.body.contentLength() > MAX_DOWNLOAD_BYTES) return null
            resp.body.byteStream().use { SilentUpdateDownload.readAtMost(it, MAX_DOWNLOAD_BYTES) }
        }

    /** Decodes at a size no bigger than the card needs, so a huge logo cannot exhaust memory. */
    private fun decodeCapped(bytes: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= CARD_WIDTH || bounds.outHeight / (sample * 2) >= CARD_WIDTH) sample *= 2
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                // Pixels must be readable for the ink measurement.
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    }.getOrNull()

    /** The opaque card: the plate the logo's ink calls for, then the logo fitted whole inside. */
    private fun draw(logo: Bitmap): Bitmap {
        val ink = HomeLogoPlate.ink(logo.width, logo.height) { x, y -> logo.getPixel(x, y) }
        val card = Bitmap.createBitmap(CARD_WIDTH, CARD_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(card)
        canvas.drawColor(HomeLogoPlate.plateFor(ink))
        val box = HomeLogoPlate.fit(logo.width, logo.height, CARD_WIDTH, CARD_HEIGHT, BOX_X, BOX_Y)
        canvas.drawBitmap(logo, null, Rect(box.left, box.top, box.right, box.bottom), Paint(Paint.FILTER_BITMAP_FLAG))
        card.setHasAlpha(false)
        return card
    }

    /**
     * Oldest first out: files past 7 days, and any beyond the newest 200. The cards just written
     * ([keep]) always stay and count first: with the clock set back, older cards carry later dates,
     * and a fresh card deleted here would be fetched again on every sync.
     */
    private fun trim(keep: Set<String>) {
        val files = directory(context).listFiles { f -> f.name.endsWith(PNG) }
            ?.sortedWith(compareByDescending<File> { it.name in keep }.thenByDescending { it.lastModified() }) ?: return
        val now = System.currentTimeMillis()
        files.forEachIndexed { index, file ->
            if (file.name !in keep && (index >= MAX_FILES || now - file.lastModified() > MAX_AGE_MS)) file.delete()
        }
    }

    private fun fileFor(raw: String): File = File(directory(context), key(raw) + PNG)

    private fun uriFor(file: File): String = "content://" + context.packageName + AUTHORITY_SUFFIX + "/" + file.name

    companion object {
        private const val TAG = "KevBoxHomeRows"
        private const val DIRECTORY = "kevbox-home-art"
        private const val AUTHORITY_SUFFIX = ".kevbox-home-art"
        private const val PNG = ".png"
        private const val TEMP_SUFFIX = ".tmp"
        private const val PNG_QUALITY = 100

        /** Bumped when the drawing changes, so every card gets a new file name and the launcher reloads it. */
        private const val STYLE = "v1"

        private const val DOWNLOAD_TIMEOUT_S = 8L
        private const val MAX_DOWNLOAD_BYTES = 5 * 1024 * 1024
        private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_FILES = 200

        /** 16:9; the logo fills at most 72% of the width and 60% of the height, centred. */
        private const val CARD_WIDTH = 1280
        private const val CARD_HEIGHT = 720
        private const val BOX_X = 0.72f
        private const val BOX_Y = 0.6f

        /** The directory HomeLogoArtProvider serves, and the only one. */
        fun directory(context: Context): File = File(context.cacheDir, DIRECTORY)

        /** SHA-256 in lowercase hex, the only file name HomeLogoArtProvider accepts. */
        private fun key(raw: String): String =
            MessageDigest.getInstance("SHA-256").digest("$STYLE|$raw".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
