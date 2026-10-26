package tv.own.owntv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.ui.stage.StageGlyph

class StageGlyphsTest {

    // Every core glyph but the phone-only two finds its OwnTVIcon by name; a renamed entry fails here, not on screen.
    @Test
    fun everyCoreGlyphButThePhoneOnesHasAnOwnTVIcon() = assertEquals(
        StageGlyph.entries.map { it.name } - setOf("PLUS", "MOON"),
        // KevBox: GEAR is KevBox's own cog on top of core's glyphs (StageGlyphs.kt), so it is left out here.
        StageGlyphPaths.keys.map { it.name } - "GEAR",
    )
}
