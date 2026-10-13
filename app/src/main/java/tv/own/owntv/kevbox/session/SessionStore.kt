// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.session

import android.content.SharedPreferences
import java.util.Base64
import org.json.JSONException
import org.json.JSONObject

/** The signed-in member's Supabase session. [expiresAtMs] is the access token's expiry, wall clock. */
data class StoredSession(
    val userId: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
)

/** Seals and opens the session blob. [decrypt] returns null when the blob no longer opens. */
interface TokenCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(sealed: ByteArray): ByteArray?
}

interface SessionStore {
    fun load(): StoredSession?
    fun save(session: StoredSession)
    fun clear()
}

/**
 * Keeps the session as one encrypted blob (all five fields as JSON, sealed by [cipher], Base64) in
 * [prefs], which the caller opens as the [PREFS_NAME] file. A blob that no longer decrypts, for
 * example after the Keystore key was lost, loads as null so the TV shows sign-in instead of
 * crashing. allowBackup="false" in the manifest keeps the blob on the device.
 */
class PrefsSessionStore(
    private val prefs: SharedPreferences,
    private val cipher: TokenCipher,
) : SessionStore {

    override fun load(): StoredSession? {
        val encoded = prefs.getString(BLOB_KEY, null) ?: return null
        val sealed = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val plain = cipher.decrypt(sealed) ?: return null
        return try {
            val json = JSONObject(String(plain, Charsets.UTF_8))
            StoredSession(
                userId = json.getString(USER_ID),
                email = json.getString(EMAIL),
                accessToken = json.getString(ACCESS_TOKEN),
                refreshToken = json.getString(REFRESH_TOKEN),
                expiresAtMs = json.getLong(EXPIRES_AT_MS),
            )
        } catch (_: JSONException) {
            null
        }
    }

    override fun save(session: StoredSession) {
        val json = JSONObject()
            .put(USER_ID, session.userId)
            .put(EMAIL, session.email)
            .put(ACCESS_TOKEN, session.accessToken)
            .put(REFRESH_TOKEN, session.refreshToken)
            .put(EXPIRES_AT_MS, session.expiresAtMs)
        val sealed = cipher.encrypt(json.toString().toByteArray(Charsets.UTF_8))
        // commit, not apply: a rotated refresh token lost to a crash would sign the TV out.
        prefs.edit().putString(BLOB_KEY, Base64.getEncoder().encodeToString(sealed)).commit()
    }

    override fun clear() {
        prefs.edit().remove(BLOB_KEY).commit()
    }

    companion object {
        /** SharedPreferences file that holds the blob. */
        const val PREFS_NAME = "kevbox_live_session"

        private const val BLOB_KEY = "session"
        private const val USER_ID = "user_id"
        private const val EMAIL = "email"
        private const val ACCESS_TOKEN = "access_token"
        private const val REFRESH_TOKEN = "refresh_token"
        private const val EXPIRES_AT_MS = "expires_at_ms"
    }
}
