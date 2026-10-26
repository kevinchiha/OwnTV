package tv.own.owntv.ui.components

import tv.own.owntv.core.ui.stage.StageGlyph

/**
 * The Stage line icons, as SVG path data on the same 24×24 grid [OwnTVIcon] draws on. The
 * paths live in core ([StageGlyph], shared with the phone); its names are the [OwnTVIcon] entries. The
 * phone-only marks (PLUS, MOON) have no entry here, so the TV keeps drawing ADD and BEDTIME its own way.
 */
internal val StageGlyphPaths: Map<OwnTVIcon, String> = StageGlyph.entries.mapNotNull { glyph ->
    OwnTVIcon.entries.firstOrNull { it.name == glyph.name }?.let { it to glyph.path }
}.toMap() +
    // KevBox: a real cog for GEAR (the menu's Settings item, Kevin 2026-10-14); core's StageGlyph has
    // no GEAR, and OwnTVIcon's own drawing read as a sun.
    (OwnTVIcon.GEAR to "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2zM9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z") // cog
