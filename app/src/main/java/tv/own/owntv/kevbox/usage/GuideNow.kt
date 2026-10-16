// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.usage

import kotlinx.coroutines.flow.first
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.live.LiveEpgReader
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.parser.XtEpgEntry
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository

/**
 * The stored guide's on-now entry for a channel; null with none. Never asks the provider.
 *
 * It reads the guide the way `LiveViewModel.nowPlayingFor` does: Core's `nowProgrammesFor`, with the
 * active profile's Live customizations (its guide matches and per-channel shifts) and the TV's guide
 * time offset, read as `MiniGuideLoader` reads them. Never `nowNext`, which can call the provider.
 *
 * Koin uses the public constructor; tests use this one, with Core's reads as functions.
 */
class GuideNow internal constructor(
    private val programmes: suspend (List<ChannelEntity>, SectionCustomizations, Int) -> Map<Long, XtEpgEntry>,
    private val customizations: suspend () -> SectionCustomizations,
    private val offsetMinutes: suspend () -> Int,
) {
    constructor(
        reader: LiveEpgReader,
        customize: CustomizationStore,
        settings: SettingsRepository,
        sourceDao: SourceDao,
    ) : this(
        programmes = reader::nowProgrammesFor,
        customizations = {
            val profileId = activeProfileSources(settings, sourceDao).first().profileId
            if (profileId < 0) SectionCustomizations() else customize.observe(profileId, MediaType.LIVE).first()
        },
        offsetMinutes = { settings.epgOffsetMinutes.first() },
    )

    suspend fun nowOn(channel: ChannelEntity): XtEpgEntry? =
        programmes(listOf(channel), customizations(), offsetMinutes())[channel.id]
}
