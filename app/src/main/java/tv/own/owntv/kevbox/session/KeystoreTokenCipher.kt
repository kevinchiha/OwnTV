// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where the session key lives. [existing] returns null only when no key exists under the alias;
 * a Keystore that cannot answer throws instead, so a passing glitch never looks like "no key".
 */
internal interface KeySlot {
    fun existing(): SecretKey?
    fun create(): SecretKey
    fun delete()
}

/**
 * AES-256-GCM under an Android Keystore key that never leaves the device. A sealed blob is the
 * 12-byte IV followed by the ciphertext and tag. [decrypt] returns null for any failure (key gone,
 * blob damaged) and never creates a key, so a lost key reads as signed out.
 *
 * [encrypt] creates a key only when the alias holds none. A Keystore error throws out of
 * [encrypt], so the save fails and the earlier blob still opens: replacing the key on a transient
 * error would silently sign the TV out. The one exception is a key the system has invalidated for
 * good, which is deleted and recreated once.
 */
class KeystoreTokenCipher internal constructor(private val keys: KeySlot) : TokenCipher {

    constructor() : this(AndroidKeySlot)

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, keys.existing() ?: keys.create())
        } catch (_: KeyPermanentlyInvalidatedException) {
            // Blobs sealed under this key are unreadable anyway; a fresh key lets the save succeed.
            keys.delete()
            cipher.init(Cipher.ENCRYPT_MODE, keys.create())
        }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(sealed: ByteArray): ByteArray? {
        if (sealed.size <= IV_BYTES) return null
        return try {
            val key = keys.existing() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        } catch (_: Exception) {
            // Keystore errors and tag mismatches surface as many exception types; all mean "gone".
            null
        }
    }

    private object AndroidKeySlot : KeySlot {
        override fun existing(): SecretKey? {
            val store = keyStore()
            if (!store.containsAlias(ALIAS)) return null
            return store.getKey(ALIAS, null) as? SecretKey
                ?: throw KeyStoreException()
        }

        override fun create(): SecretKey {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_BITS)
                    .build(),
            )
            return generator.generateKey()
        }

        override fun delete() {
            keyStore().deleteEntry(ALIAS)
        }

        private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "kevbox_live_session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val IV_BYTES = 12
    }
}
