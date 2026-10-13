// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateProfileAttacherTest {
    private val apk = "https://github.com/kevinchiha/OwnTV/releases/download/v5.1.0.6/KevBoxLive.apk"

    @Test
    fun `Android 12 and later take the profile named after the APK`() {
        assertEquals(
            "https://github.com/kevinchiha/OwnTV/releases/download/v5.1.0.6/KevBoxLive.dm",
            UpdateProfileAttacher.dexMetadataUrl(apk, 31),
        )
        assertEquals(
            "https://github.com/kevinchiha/OwnTV/releases/download/v5.1.0.6/KevBoxLive.dm",
            UpdateProfileAttacher.dexMetadataUrl(apk, 35),
        )
    }

    @Test
    fun `Android 9 to 11 take the older profile format`() {
        assertEquals(
            "https://github.com/kevinchiha/OwnTV/releases/download/v5.1.0.6/KevBoxLive-api28.dm",
            UpdateProfileAttacher.dexMetadataUrl(apk, 28),
        )
        assertEquals(
            "https://github.com/kevinchiha/OwnTV/releases/download/v5.1.0.6/KevBoxLive-api28.dm",
            UpdateProfileAttacher.dexMetadataUrl(apk, 30),
        )
    }

    @Test
    fun `the x86_64 build has its own profile`() {
        assertEquals(
            "https://github.com/kevinchiha/OwnTV/releases/download/v5.1.0.6/KevBoxLive-x86_64.dm",
            UpdateProfileAttacher.dexMetadataUrl(apk.replace("KevBoxLive.apk", "KevBoxLive-x86_64.apk"), 33),
        )
    }

    @Test
    fun `no profile before Android 9 or for a link that is not an APK`() {
        assertNull(UpdateProfileAttacher.dexMetadataUrl(apk, 27))
        assertNull(UpdateProfileAttacher.dexMetadataUrl("https://example.test/file.zip", 31))
    }
}
