// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.sports

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sports.SportsConfig

/**
 * KevBox's Sports defaults on top of Core's. Core ranks the app's own language first until a profile
 * picks commentary languages, and with strict filtering on that hides every other language: an
 * English TV saw none of the Arabic beIN channels. KevBox starts every profile on Any language
 * instead. A profile that picked its own languages keeps them; one that never did (null) gets an
 * empty set, which Core reads as Any and stores, so this runs once per profile and new profiles too.
 */
class SportsDefaults(
    private val settings: SettingsRepository,
    private val profileDao: ProfileDao,
    private val scope: CoroutineScope,
) {
    @Volatile private var started = false

    /** Once, from OwnTVApp. Applies to every profile now and to each one created later. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            profileDao.observeAll().map { all -> all.map { it.id }.sorted() }.distinctUntilChanged().collect { ids ->
                ids.forEach { id ->
                    try {
                        settings.updateSportsConfig(id, ::withKevBoxDefaults)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // The next change to the profile list tries again.
                    }
                }
            }
        }
    }

    companion object {
        /** [config] with Any language when it never chose one; unchanged otherwise. */
        fun withKevBoxDefaults(config: SportsConfig): SportsConfig =
            if (config.languages == null) config.copy(languages = emptySet()) else config
    }
}
