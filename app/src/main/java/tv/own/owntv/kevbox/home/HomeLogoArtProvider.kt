// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.home

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves HomeLogoArt's logo cards to the launcher, which runs outside the app and so needs a public
 * read-only URI (authority `<applicationId>.kevbox-home-art`).
 *
 * Copies the safety rules of Core's LiveLogoArtProvider: read mode only, one path segment, and that
 * segment must be a hash-named PNG (64 lowercase hex digits, then `.png`) inside HomeLogoArt's own
 * cache directory. Every other request gets FileNotFoundException, so no other app-private file
 * can be reached. Nothing can be listed, written or deleted.
 */
class HomeLogoArtProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = MIME_PNG

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != READ_ONLY) throw FileNotFoundException(uri.toString())
        val name = uri.pathSegments.singleOrNull()
        if (name == null || !isArtName(name)) throw FileNotFoundException(uri.toString())
        val context = requireNotNull(context)
        val file = File(HomeLogoArt.directory(context), name)
        if (!file.isFile) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    companion object {
        private const val MIME_PNG = "image/png"
        private const val READ_ONLY = "r"
        private const val PNG = ".png"
        private const val HASH_LENGTH = 64

        /** True only for a file name HomeLogoArt writes: a SHA-256 in lowercase hex, then `.png`. */
        fun isArtName(name: String): Boolean =
            name.length == HASH_LENGTH + PNG.length &&
                name.endsWith(PNG) &&
                name.dropLast(PNG.length).all { it in '0'..'9' || it in 'a'..'f' }
    }
}
