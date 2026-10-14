// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.epg

/** An entry in Core's EPG source list, reduced to what [planGuideFeeds] reads. */
data class GuideFeed(val id: Long, val url: String, val lastSyncAt: Long?)

/** One thing to do so the playlist's own guide feed is in Core's EPG list and has downloaded once. */
sealed interface GuideFeedStep {
    /** Add the feed to the EPG list, then download it. */
    data class Add(val url: String) : GuideFeedStep

    /** The feed is listed but has never downloaded: download it now. */
    data class Download(val feedId: Long) : GuideFeedStep
}

/**
 * What it takes for every one of [guideUrls] (the playlist's guide feeds, as Core works them out) to
 * be listed in [feeds] and downloaded once. A feed that has downloaded before is left alone: Core's
 * auto-refresh keeps it current from then on.
 */
fun planGuideFeeds(guideUrls: List<String>, feeds: List<GuideFeed>): List<GuideFeedStep> =
    guideUrls.distinct().mapNotNull { url ->
        val feed = feeds.firstOrNull { it.url == url }
        when {
            feed == null -> GuideFeedStep.Add(url)
            feed.lastSyncAt == null -> GuideFeedStep.Download(feed.id)
            else -> null
        }
    }

/**
 * Ids of feeds whose URL the old source used and the new one does not. When a playlist's server,
 * username or password changes, its old guide URL stops working; this names the feeds to remove.
 * URLs match whole and exact, as [planGuideFeeds] matches them, so a URL in both lists stays.
 */
fun planGuideFeedSwap(oldGuideUrls: List<String>, newGuideUrls: List<String>, feeds: List<GuideFeed>): List<Long> {
    val dropped = oldGuideUrls.toSet() - newGuideUrls.toSet()
    return feeds.filter { it.url in dropped }.map { it.id }
}

/**
 * Ids of the feeds that are one of the playlists' own guide URLs: the automatic guide, which
 * [GuideFeeds] adds and the member does not manage by hand. Whole URLs only: a feed whose URL merely
 * starts like a playlist's is the member's own.
 */
fun automaticGuideIds(feeds: List<GuideFeed>, playlistGuideUrls: Collection<String>): Set<Long> {
    val urls = playlistGuideUrls.toSet()
    return feeds.filter { it.url in urls }.mapTo(mutableSetOf()) { it.id }
}
