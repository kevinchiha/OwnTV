// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.refresh

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelStepTest {

    @Test
    fun `nothing under way starts a download and waits for it`() {
        assertEquals(ChannelStep.START_AND_WAIT, channelStep(online = true, downloading = false))
    }

    @Test
    fun `a download already making progress is waited for, not thrown away`() {
        // An automatic refresh halfway through 20,000 channels on a slow TV.
        assertEquals(ChannelStep.WAIT, channelStep(online = true, downloading = true))
    }

    @Test
    fun `offline, the download is queued for later and the guide step goes on at once`() {
        // Waiting would leave the row on "Updating…" for the whole cap.
        assertEquals(ChannelStep.START, channelStep(online = false, downloading = false))
        assertEquals(ChannelStep.START, channelStep(online = false, downloading = true))
    }
}
