package tv.own.owntv.kevbox.session

import java.security.KeyStoreException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class KeystoreTokenCipherTest {

    /** A key slot backed by a plain JVM AES key; [failing] makes the next lookups throw. */
    private class FakeSlot(var key: SecretKey? = null) : KeySlot {
        var failing = false
        var created = 0

        override fun existing(): SecretKey? {
            if (failing) throw KeyStoreException("keystore busy")
            return key
        }

        override fun create(): SecretKey {
            created++
            return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
        }

        override fun delete() {
            key = null
        }
    }

    private val plain = "session".toByteArray()

    @Test
    fun `a transient Keystore error never replaces the existing key`() {
        val slot = FakeSlot()
        val cipher = KeystoreTokenCipher(slot)
        val sealed = cipher.encrypt(plain)
        val original = slot.key

        slot.failing = true
        try {
            cipher.encrypt(plain)
            fail("encrypt must fail while the Keystore cannot answer")
        } catch (_: KeyStoreException) {
            // expected: the save fails instead of sealing under a new key
        }
        slot.failing = false

        assertEquals(1, slot.created)
        assertSame(original, slot.key)
        assertArrayEquals(plain, cipher.decrypt(sealed))
    }

    @Test
    fun `a key is created only when the alias holds none, and later saves reuse it`() {
        val slot = FakeSlot()
        val cipher = KeystoreTokenCipher(slot)

        val first = cipher.encrypt(plain)
        val second = cipher.encrypt(plain)

        assertEquals(1, slot.created)
        assertArrayEquals(plain, cipher.decrypt(first))
        assertArrayEquals(plain, cipher.decrypt(second))
    }

    @Test
    fun `decrypt never creates a key and reads a missing key as gone`() {
        val sealed = KeystoreTokenCipher(FakeSlot()).encrypt(plain)
        val empty = FakeSlot()

        assertEquals(null, KeystoreTokenCipher(empty).decrypt(sealed))
        assertEquals(0, empty.created)
        assertNotNull(sealed)
    }
}
