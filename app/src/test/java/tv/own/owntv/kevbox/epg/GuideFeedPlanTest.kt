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
}
