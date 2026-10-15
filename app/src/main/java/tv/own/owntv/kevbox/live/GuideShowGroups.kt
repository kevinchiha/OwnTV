// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import tv.own.owntv.core.database.entity.ChannelEntity

/*
 * The guide's own search (the "Search guide channels" field in Live TV) finds a channel by its name or
 * by a show it carries (Kevin, 2026-10-14). The grid then lists three groups: the name matches, the
 * channels with a matching show on now, and those with one starting in the next 24 hours.
 */

/**
 * A heading over one group of the guide grid: [count] channels from position [firstIndex] in the grid's
 * channel list. The heading is not a row of its own; the grid draws it above the row at [firstIndex],
 * so row numbers keep counting channels only.
 */
data class GuideHeading(val kind: Kind, val firstIndex: Int, val count: Int) {
    enum class Kind { NAMES, ON_NOW, LATER }
}

/** The grid's channels in group order, and a heading for each group that has any. */
data class GuideGroups(val channels: List<ChannelEntity>, val headings: List<GuideHeading>)

/**
 * Puts the guide search's channels in their groups: [nameMatches] in the order given, then the channels
 * with a show on now in [shows]' order (channel order), then those with a show later, by their earliest
 * matching start ([ShowMatches.later] is sorted by start). A channel shows once, in the first group it
 * qualifies for, and one [inCategory] rejects shows nowhere. A group left with no channel has no heading.
 */
fun groupGuideChannels(
    nameMatches: List<ChannelEntity>,
    shows: ShowMatches,
    inCategory: (ChannelEntity) -> Boolean,
): GuideGroups {
    val placed = HashSet<Long>()
    val channels = ArrayList<ChannelEntity>()
    val headings = ArrayList<GuideHeading>()
    fun group(kind: GuideHeading.Kind, candidates: List<ChannelEntity>) {
        val first = channels.size
        for (ch in candidates) {
            if (ch.id in placed || !inCategory(ch)) continue
            placed += ch.id
            channels += ch
        }
        if (channels.size > first) headings += GuideHeading(kind, first, channels.size - first)
    }
    group(GuideHeading.Kind.NAMES, nameMatches)
    group(GuideHeading.Kind.ON_NOW, shows.onNow.map { it.channel })
    group(GuideHeading.Kind.LATER, shows.later.map { it.channel })
    return GuideGroups(channels, headings)
}
