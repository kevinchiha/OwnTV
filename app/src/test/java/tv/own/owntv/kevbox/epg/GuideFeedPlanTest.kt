// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.epg

import org.junit.Assert.assertEquals
import org.junit.Test

class GuideFeedPlanTest {
    private val url = "http://line.example/xmltv.php?username=u&password=p"

    @Test
    fun missingFeedIsAdded() =
        assertEquals(listOf(GuideFeedStep.Add(url)), planGuideFeeds(listOf(url), emptyList()))

    @Test
    fun feedThatNeverDownloadedIsDownloaded() =
        assertEquals(listOf(GuideFeedStep.Download(7)), planGuideFeeds(listOf(url), listOf(GuideFeed(7, url, lastSyncAt = null))))

    @Test
    fun feedThatAlreadyDownloadedIsLeftToCoresRefresh() =
        assertEquals(emptyList<GuideFeedStep>(), planGuideFeeds(listOf(url), listOf(GuideFeed(7, url, lastSyncAt = 1_000L))))

    @Test
    fun theSameUrlTwiceIsAddedOnce() =
        assertEquals(listOf(GuideFeedStep.Add(url)), planGuideFeeds(listOf(url, url), emptyList()))

    @Test
    fun otherFeedsAreLeftAlone() =
        assertEquals(
            listOf(GuideFeedStep.Add(url)),
            planGuideFeeds(listOf(url), listOf(GuideFeed(3, "http://other.example/guide.xml", lastSyncAt = null))),
        )

    @Test
    fun noGuideUrlMeansNothingToDo() =
        assertEquals(emptyList<GuideFeedStep>(), planGuideFeeds(emptyList(), emptyList()))

    private val newUrl = "http://new-line.example/xmltv.php?username=u&password=p"
    private val handAdded = "http://other.example/guide.xml"

    @Test
    fun aServerChangeRemovesTheOldFeed() =
        assertEquals(
            listOf(7L),
            planGuideFeedSwap(
                oldGuideUrls = listOf(url),
                newGuideUrls = listOf(newUrl),
                feeds = listOf(GuideFeed(7, url, 1_000L), GuideFeed(3, handAdded, 1_000L)),
            ),
        )

    @Test
    fun sameUrlsRemoveNothing() =
        assertEquals(
            emptyList<Long>(),
            planGuideFeedSwap(listOf(url), listOf(url), listOf(GuideFeed(7, url, 1_000L), GuideFeed(3, handAdded, null))),
        )

    @Test
    fun aUrlInBothListsStays() {
        val kept = "http://epg.example/one.xml"
        val dropped = "http://epg.example/two.xml"
        val added = "http://epg.example/three.xml"
        // A comma-separated url-tvg, split into its feeds as Core's guideUrls does.
        val oldUrls = "$kept,$dropped".split(",")
        val newUrls = "$kept,$added".split(",")
        assertEquals(
            listOf(2L),
            planGuideFeedSwap(oldUrls, newUrls, listOf(GuideFeed(1, kept, 1_000L), GuideFeed(2, dropped, 1_000L))),
        )
    }

    @Test
    fun automaticIdsMatchWholeUrlsOnly() =
        assertEquals(
            setOf(7L),
            automaticGuideIds(
                feeds = listOf(
                    GuideFeed(7, url, 1_000L),
                    // Starts like the playlist's guide URL, but the member added it by hand.
                    GuideFeed(8, "$url&type=full", 1_000L),
                    GuideFeed(9, url.substringBefore("?"), null),
                    GuideFeed(3, handAdded, null),
                ),
                playlistGuideUrls = listOf(url),
            ),
        )
}
