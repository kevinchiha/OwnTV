// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject
import tv.own.owntv.core.database.dao.SourceDao

/**
 * True until some playlist on this TV has finished a sync: right after sign-in, while the playlist is
 * added and its channels download for the first time. No playlist at all counts too, because the
 * KevBox gate only opens once one is assigned, so an empty list means it is about to be added. The
 * grid shows a spinner then, instead of upstream's "No EPG added" prompt, which would point a member
 * at a screen they have no use for.
 */
@Composable
fun rememberPlaylistFirstSync(): Boolean {
    val sourceDao: SourceDao = koinInject()
    val flow = remember(sourceDao) {
        sourceDao.observeAll().map { all -> all.none { it.lastSyncAt != null } }.distinctUntilChanged()
    }
    // True until the first read: a spinner for a frame is better than the wrong message for a frame.
    val pending by flow.collectAsState(initial = true)
    return pending
}
