// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.home

import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.core.settings.SettingsRepository.GuideSort

/** The two rows KevBox Live puts on the TV home screen, in the order they are created. */
enum class HomeRow(val providerId: String) {
    RECENT("kevbox-live:recent"),
    FAVORITES("kevbox-live:favorites"),
}

/**
 * One card in a row, as it should be stored in the system TV provider.
 * [key] is the program's internal provider id: `ch:<sourceId>:<remoteId or name>`.
 */
data class HomeCard(
    val key: String,
    val title: String,
    val description: String?,
    val artUri: String?,
    val intentUri: String,
    val weight: Int,
)

/**
 * One card already in the provider. [rowIds] holds every provider row stored under the card's key:
 * the first is kept, the rest are duplicates to delete.
 */
data class ExistingProgram(
    val rowIds: List<Long>,
    val title: String?,
    val description: String?,
    val artUri: String?,
    val intentUri: String?,
    val weight: Int,
)

/** What a sync writes: rows to add, kept rows to rewrite (row id with its new card), row ids to delete. */
data class HomeDiff(
    val insert: List<HomeCard>,
    val update: List<Pair<Long, HomeCard>>,
    val delete: List<Long>,
)

/**
 * Works out what each row holds and what to change in the provider. Pure: the caller reads the
 * database, the guide and the logo cache, and turns a channel into its card link.
 *
 * Shared parameters:
 * - `liveSourceIds`: the active profile's live sources; channels from any other source are dropped.
 * - `customizations`: the profile's Live customizations; hidden channels are dropped, renames
 *   become titles.
 * - `nowTitles`: the title of the programme on now, by channel id, from the stored guide.
 * - `fitted`: the fitted 16:9 logo for a raw logo URL once it exists, null before.
 * - `link`: the card's intent URI for the original (not renamed) channel.
 */
object HomeRows {
    const val MAX_CARDS = 15

    /**
     * Recent channels: [history] is newest first; the row keeps that order. Channels in
     * [hiddenCategoryIds] are dropped, as the in-player history list (LiveViewModel.historyChannels)
     * drops them: the profile's hidden categories, and the adult ones on a Kids profile.
     */
    fun recent(
        history: List<ChannelEntity>,
        liveSourceIds: Set<Long>,
        customizations: SectionCustomizations,
        hiddenCategoryIds: Set<Long>,
        nowTitles: Map<Long, String>,
        fitted: (String) -> String?,
        link: (ChannelEntity) -> String,
    ): List<HomeCard> {
        val shown = visible(history, liveSourceIds, customizations).filter {
            it.categoryId == null || it.categoryId !in hiddenCategoryIds
        }
        return cards(shown, customizations, nowTitles, fitted, link)
    }

    /**
     * Favorites: every channel in [channels] is a favourite; the row follows the guide's Order setting.
     * No category filter, as the guide's Favorites category: EpgViewModel skips hidden categories, the
     * Kids adult ones included, for its member lists (Favorites, History, custom categories).
     */
    fun favorites(
        channels: List<ChannelEntity>,
        sort: GuideSort,
        liveSourceIds: Set<Long>,
        customizations: SectionCustomizations,
        nowTitles: Map<Long, String>,
        fitted: (String) -> String?,
        link: (ChannelEntity) -> String,
    ): List<HomeCard> {
        // Renames apply before sorting, as in the guide; the original channel is kept for the key and link.
        val named = visible(channels, liveSourceIds, customizations).map { it to displayName(it, customizations) }
        val byAlpha = compareBy<Pair<ChannelEntity, String>> { it.second.lowercase() }
        val byProvider = compareBy<Pair<ChannelEntity, String>>(
            { it.first.sourceId }, { it.first.sortOrder }, { it.second.lowercase() },
        )
        // Copies EpgViewModel's guideOrder. Every channel here is a favourite, so FAVORITES is provider order.
        val order = when (sort) {
            GuideSort.ALPHA -> byAlpha
            GuideSort.PROVIDER -> byProvider
            GuideSort.CATCHUP -> compareByDescending<Pair<ChannelEntity, String>> { it.first.catchup }.then(byProvider)
            GuideSort.FAVORITES -> byProvider
            GuideSort.LIVE_TV -> byProvider
        }
        return cards(named.sortedWith(order).map { it.first }, customizations, nowTitles, fitted, link)
    }

    /** Provider changes that turn [existing] into [wanted]; unchanged cards write nothing. */
    fun diff(wanted: List<HomeCard>, existing: Map<String, ExistingProgram>): HomeDiff {
        val insert = mutableListOf<HomeCard>()
        val update = mutableListOf<Pair<Long, HomeCard>>()
        val delete = mutableListOf<Long>()
        val wantedKeys = HashSet<String>()
        for (card in wanted) {
            if (!wantedKeys.add(card.key)) continue
            val stored = existing[card.key]
            val kept = stored?.rowIds?.firstOrNull()
            if (stored == null || kept == null) {
                insert += card
                continue
            }
            if (!sameAs(stored, card)) update += kept to card
            delete += stored.rowIds.drop(1)
        }
        for ((key, stored) in existing) {
            if (key !in wantedKeys) delete += stored.rowIds
        }
        return HomeDiff(insert, update, delete)
    }

    private fun sameAs(stored: ExistingProgram, card: HomeCard): Boolean =
        stored.title == card.title &&
            stored.description == card.description &&
            stored.artUri == card.artUri &&
            stored.intentUri == card.intentUri &&
            stored.weight == card.weight

    private fun visible(
        channels: List<ChannelEntity>,
        liveSourceIds: Set<Long>,
        customizations: SectionCustomizations,
    ): List<ChannelEntity> = channels.filter {
        it.sourceId in liveSourceIds && !customizations.hiddenItems.containsKey(CustomizeKeys.channel(it))
    }

    private fun displayName(ch: ChannelEntity, customizations: SectionCustomizations): String =
        customizations.itemNames[CustomizeKeys.channel(ch)] ?: ch.name

    private fun cards(
        channels: List<ChannelEntity>,
        customizations: SectionCustomizations,
        nowTitles: Map<Long, String>,
        fitted: (String) -> String?,
        link: (ChannelEntity) -> String,
    ): List<HomeCard> = channels
        .distinctBy { keyOf(it) }
        .take(MAX_CARDS)
        .mapIndexed { index, ch ->
            HomeCard(
                key = keyOf(ch),
                title = displayName(ch, customizations),
                description = nowTitles[ch.id]?.trim()?.takeIf { it.isNotEmpty() },
                artUri = rawLogo(ch)?.let { fitted(it) ?: it },
                intentUri = link(ch),
                weight = MAX_CARDS - index,
            )
        }

    /** `ch:` plus the Core customization key, which is `<sourceId>:<remoteId or name>`. */
    private fun keyOf(ch: ChannelEntity): String = "ch:" + CustomizeKeys.channel(ch)

    /** The logo Core's own TV home row uses: the guide's icon or the playlist's, web URLs only. */
    private fun rawLogo(ch: ChannelEntity): String? =
        ch.displayLogoUrl?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
}
