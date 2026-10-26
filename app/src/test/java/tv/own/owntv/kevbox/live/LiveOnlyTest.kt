// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.nav.MainSection
import tv.own.owntv.core.live.ProviderName

class LiveOnlyTest {

    @Test
    fun categoryKeepsTheProvidersCountryPrefix() =
        assertEquals(ProviderName("FR| FRANCE HD", emptyList(), null), LiveOnly.categoryName("FR| FRANCE HD"))

    @Test
    fun categoryKeepsSuperscriptTagsInTheNameInsteadOfBadges() =
        assertEquals(ProviderName("FR| FRANCE VIP ʳᴬᵂ", emptyList(), null), LiveOnly.categoryName("FR| FRANCE VIP ʳᴬᵂ"))

    @Test
    fun categoryOnlyLosesOuterSpaces() =
        assertEquals("24/7 BEIN CINEMA", LiveOnly.categoryName("  24/7 BEIN CINEMA ").name)

    @Test
    fun menuShowsLiveTvAndNoOtherBrowseSection() =
        assertEquals(setOf(MainSection.LIVE_TV), LiveOnly.browseSections)
}
