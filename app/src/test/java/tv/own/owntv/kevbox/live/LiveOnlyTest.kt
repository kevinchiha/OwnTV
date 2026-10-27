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
    fun liveTvIsTheOnlyFixedBrowseSection() =
        assertEquals(setOf(MainSection.LIVE_TV), LiveOnly.browseSections)

    @Test
    fun menuShowsSportsAfterLiveTvByDefault() =
        assertEquals(setOf(MainSection.LIVE_TV, MainSection.SPORTS), LiveOnly.menuSections(emptySet()))

    @Test
    fun sportsLeavesTheMenuWhenTheMemberHidesIt() =
        assertEquals(setOf(MainSection.LIVE_TV), LiveOnly.menuSections(setOf(MainSection.SPORTS.name)))

    @Test
    fun hiddenNamesOfRemovedSectionsChangeNothing() =
        assertEquals(
            setOf(MainSection.LIVE_TV, MainSection.SPORTS),
            LiveOnly.menuSections(setOf(MainSection.HOME.name, MainSection.LIVE_TV.name, "NOT_A_SECTION")),
        )

    @Test
    fun sportsOpensEvenWhenHiddenFromTheMenu() {
        assertEquals(MainSection.SPORTS, LiveOnly.sectionToOpen(MainSection.SPORTS))
        assertEquals(MainSection.LIVE_TV, LiveOnly.sectionToOpen(MainSection.LIVE_TV))
    }

    @Test
    fun removedSectionsStillOpenLiveTv() =
        listOf(MainSection.HOME, MainSection.MOVIES, MainSection.SERIES, MainSection.EPG)
            .forEach { assertEquals(it.name, MainSection.LIVE_TV, LiveOnly.sectionToOpen(it)) }

    @Test
    fun pinnedItemsOpenAsAsked() =
        listOf(MainSection.SEARCH, MainSection.SETTINGS, MainSection.MORE)
            .forEach { assertEquals(it.name, it, LiveOnly.sectionToOpen(it)) }
}
