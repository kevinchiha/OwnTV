// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import tv.own.owntv.core.sports.SportsConfig
import tv.own.owntv.core.sports.WatchFilter

class SportsDefaultsTest {

    @Test
    fun `a profile that never chose languages starts on Any`() {
        val fresh = SportsDefaults.withKevBoxDefaults(SportsConfig())

        assertEquals(emptySet<String>(), fresh.languages)
        assertEquals(emptySet<String>(), fresh.preferredLanguages("en"))
        assertEquals(emptySet<String>(), WatchFilter.from(fresh, "en", emptyList()).languages)
        // Stored and read back, the choice stays Any instead of falling back to the app's language.
        assertEquals(emptySet<String>(), SportsConfig.fromJson(fresh.toJson().toString()).languages)
    }

    @Test
    fun `a profile that chose its own languages keeps them`() {
        val chosen = SportsConfig(languages = setOf("AR", "FR"))

        assertSame(chosen, SportsDefaults.withKevBoxDefaults(chosen))
    }
}
