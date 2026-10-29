// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.home

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import tv.own.owntv.MainActivity
import tv.own.owntv.R
import tv.own.owntv.core.brand.AppIconSwitcher
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.launcher.LauncherDeepLink

/** The system TV provider, as [HomeRowsSync] uses it. [HomeChannels] is the real one. */
interface HomeProvider {
    /** False on a TV without the leanback feature: nothing is read or written there. */
    fun supported(): Boolean

    /**
     * The provider row id of each [HomeRow]'s channel, made when missing (RECENT before FAVORITES).
     * Deletes every other channel of the package.
     */
    fun ensureChannels(): Map<HomeRow, Long>

    /** The programs stored in each channel, keyed by internal provider id. */
    fun programs(channels: Map<HomeRow, Long>): Map<HomeRow, Map<String, ExistingProgram>>

    /** Writes [diff] into [channelId]. */
    fun apply(channelId: Long, diff: HomeDiff)
}

/** The 16:9 logo cards, by raw logo URL. [HomeLogoArt] is the real one. */
interface HomeArt {
    /** The card's `content://` URI once its file exists, null before. */
    fun fitted(raw: String): String?

    /** Starts writing the missing cards off the main thread; [onReady] runs once at least one was written. */
    fun prepare(raws: List<String>, onReady: suspend () -> Unit)
}

/**
 * The two KevBox Live channels in the system TV provider and their programs.
 *
 * Copies KevBox TV's AndroidTvChannelManager: channel ids kept in prefs, channels found again by
 * internal provider id, programs read with no selection clause (Fire OS rejects any) and sorted
 * into channels in memory.
 */
class HomeChannels(private val context: Context) : HomeProvider {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val resolver get() = context.contentResolver

    override fun supported(): Boolean =
        runCatching { context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }.getOrDefault(false)

    override fun ensureChannels(): Map<HomeRow, Long> {
        val stored = queryChannels()
        val ids = LinkedHashMap<HomeRow, Long>()
        // Once a channel can't be made, no later one is made in this sync, so FAVORITES never takes a
        // lower id than RECENT; channels that already exist are still used. The next sync tries again.
        var canMake = true
        for (row in HomeRow.entries) {
            val kept = prefs.getLong(prefKey(row), NO_ID).takeIf { id -> stored.any { it.id == id && it.providerId == row.providerId } }
                ?: stored.firstOrNull { it.providerId == row.providerId }?.id
            if (kept == null && !canMake) continue
            val id = kept ?: insertChannel(row)
            if (id == null) {
                canMake = false
                continue
            }
            if (kept != null) renameIfNeeded(id, row, stored.first { it.id == id }.displayName)
            prefs.edit().putLong(prefKey(row), id).apply()
            ids[row] = id
        }
        // Core's own "Recent live" (internal id "owntv:…") and any duplicate of ours.
        for (channel in stored) {
            if (channel.id in ids.values) continue
            runCatching { resolver.delete(TvContractCompat.buildChannelUri(channel.id), null, null) }
                .onFailure { Log.w(TAG, "home rows: channel delete failed ${it.javaClass.simpleName}") }
        }
        return ids
    }

    override fun programs(channels: Map<HomeRow, Long>): Map<HomeRow, Map<String, ExistingProgram>> {
        val rowOf = channels.entries.associate { (row, id) -> id to row }
        val found = HashMap<HomeRow, MutableList<Pair<Long, Stored>>>()
        // No cursor is a failed read, not an empty one: going on would insert every card again.
        val cursor = resolver.query(TvContractCompat.PreviewPrograms.CONTENT_URI, PROGRAM_COLUMNS, null, null, null)
            ?: throw IllegalStateException("preview program query returned no cursor")
        cursor.use { c ->
            val id = c.getColumnIndexOrThrow(TvContract.PreviewPrograms._ID)
            val channel = c.getColumnIndexOrThrow(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID)
            val key = c.getColumnIndexOrThrow(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID)
            val title = c.getColumnIndexOrThrow(TvContract.PreviewPrograms.COLUMN_TITLE)
            val description = c.getColumnIndexOrThrow(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION)
            val art = c.getColumnIndexOrThrow(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI)
            val intent = c.getColumnIndexOrThrow(TvContract.PreviewPrograms.COLUMN_INTENT_URI)
            val weight = c.getColumnIndexOrThrow(TvContract.PreviewPrograms.COLUMN_WEIGHT)
            while (c.moveToNext()) {
                val row = rowOf[c.getLong(channel)] ?: continue
                found.getOrPut(row) { mutableListOf() } += c.getLong(id) to Stored(
                    // A program with no key is never wanted, so diff deletes it.
                    key = c.getString(key).orEmpty(),
                    title = c.getString(title),
                    description = c.getString(description),
                    artUri = c.getString(art),
                    intentUri = c.getString(intent),
                    weight = if (c.isNull(weight)) 0 else c.getInt(weight),
                )
            }
        }
        return channels.keys.associateWith { row ->
            // The oldest row of a key is the one kept.
            (found[row] ?: emptyList()).sortedBy { it.first }.groupBy { it.second.key }.mapValues { (_, rows) ->
                val first = rows.first().second
                ExistingProgram(rows.map { it.first }, first.title, first.description, first.artUri, first.intentUri, first.weight)
            }
        }
    }

    override fun apply(channelId: Long, diff: HomeDiff) {
        for (id in diff.delete) write { resolver.delete(TvContractCompat.buildPreviewProgramUri(id), null, null) }
        for ((id, card) in diff.update) write { resolver.update(TvContractCompat.buildPreviewProgramUri(id), values(channelId, card), null, null) }
        for (card in diff.insert) write { resolver.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, values(channelId, card)) }
    }

    /**
     * A card's link: Core's `owntv://play/live` deep link in an ACTION_VIEW intent bound to this app,
     * so the launcher never shows an app chooser. The shell resolves it against the active profile.
     */
    fun link(channel: ChannelEntity): String = intentUri(
        LauncherDeepLink.Live(sourceId = channel.sourceId, remoteId = channel.remoteId, name = channel.name).toUri(),
    )

    private fun intentUri(data: Uri): String =
        Intent(Intent.ACTION_VIEW, data)
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .toUri(Intent.URI_INTENT_SCHEME)

    // Lint's false alarm: these setters are public methods PreviewProgram.Builder inherits from
    // BasePreviewProgram.Builder and BaseProgram.Builder, which are marked library-only. Reached
    // through the public subclass, they are the documented way to build a program.
    @SuppressLint("RestrictedApi")
    private fun values(channelId: Long, card: HomeCard): ContentValues {
        val builder = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
            .setTitle(card.title)
            .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
            .setIntentUri(Uri.parse(card.intentUri))
            .setInternalProviderId(card.key)
            .setWeight(card.weight)
        card.description?.let { builder.setDescription(it) }
        card.artUri?.let { builder.setPosterArtUri(Uri.parse(it)) }
        return builder.build().toContentValues().apply {
            // An update rewrites every column, so a value that went away is cleared, never left behind.
            if (card.description == null) putNull(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION)
            if (card.artUri == null) putNull(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI)
            putNull(TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS)
            putNull(TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS)
        }
    }

    private fun queryChannels(): List<StoredChannel> {
        val result = mutableListOf<StoredChannel>()
        // No cursor is a failed read, not "no channels": going on would make both channels again.
        val cursor = resolver.query(TvContractCompat.Channels.CONTENT_URI, CHANNEL_COLUMNS, null, null, null)
            ?: throw IllegalStateException("channel query returned no cursor")
        cursor.use { c ->
            val id = c.getColumnIndexOrThrow(TvContractCompat.Channels._ID)
            val providerId = c.getColumnIndexOrThrow(TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID)
            val name = c.getColumnIndexOrThrow(TvContractCompat.Channels.COLUMN_DISPLAY_NAME)
            // The provider returns this package's channels only.
            while (c.moveToNext()) result += StoredChannel(c.getLong(id), c.getString(providerId), c.getString(name))
        }
        return result
    }

    private fun insertChannel(row: HomeRow): Long? {
        val channel = Channel.Builder()
            .setType(TvContractCompat.Channels.TYPE_PREVIEW)
            .setDisplayName(displayName(row))
            .setAppLinkIntentUri(Uri.parse(intentUri(LauncherDeepLink.OpenLiveSection.toUri())))
            .setInternalProviderId(row.providerId)
            .build()
        val uri = resolver.insert(TvContractCompat.Channels.CONTENT_URI, channel.toContentValues()) ?: return null
        val id = ContentUris.parseId(uri)
        writeLogo(id)
        return id
    }

    /** A language change renames the channel; the same name writes nothing. */
    private fun renameIfNeeded(id: Long, row: HomeRow, stored: String?) {
        val name = displayName(row)
        if (name == stored) return
        val values = ContentValues().apply { put(TvContractCompat.Channels.COLUMN_DISPLAY_NAME, name) }
        write { resolver.update(TvContractCompat.buildChannelUri(id), values, null, null) }
    }

    private fun displayName(row: HomeRow): String = context.getString(
        when (row) {
            HomeRow.RECENT -> R.string.kevbox_home_row_recent
            HomeRow.FAVORITES -> R.string.kevbox_home_row_favorites
        },
    )

    /** The app's banner, as Core's own row uses; written once, when the channel is made. */
    private fun writeLogo(channelId: Long) {
        runCatching {
            val banner = AppIconSwitcher.applied(context).banner
            val bitmap = ContextCompat.getDrawable(context, banner)?.toBitmap() ?: return
            ChannelLogoUtils.storeChannelLogo(context, channelId, bitmap)
        }.onFailure { Log.w(TAG, "home rows: logo failed ${it.javaClass.simpleName}") }
    }

    /** One provider write; a failure is logged and the next sync tries again. */
    private inline fun write(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "home rows: write failed ${e.javaClass.simpleName}")
        }
    }

    private data class StoredChannel(val id: Long, val providerId: String?, val displayName: String?)

    private data class Stored(
        val key: String,
        val title: String?,
        val description: String?,
        val artUri: String?,
        val intentUri: String?,
        val weight: Int,
    )

    companion object {
        const val PREFS_NAME = "kevbox_home_rows"
        private const val TAG = "KevBoxHomeRows"
        private const val NO_ID = -1L

        private fun prefKey(row: HomeRow) = row.providerId

        private val CHANNEL_COLUMNS = arrayOf(
            TvContractCompat.Channels._ID,
            TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID,
            TvContractCompat.Channels.COLUMN_DISPLAY_NAME,
        )

        private val PROGRAM_COLUMNS = arrayOf(
            TvContract.PreviewPrograms._ID,
            TvContract.PreviewPrograms.COLUMN_CHANNEL_ID,
            TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID,
            TvContract.PreviewPrograms.COLUMN_TITLE,
            TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
            TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
            TvContract.PreviewPrograms.COLUMN_INTENT_URI,
            TvContract.PreviewPrograms.COLUMN_WEIGHT,
        )
    }
}

/**
 * Runs [action] each time a MainActivity (its colour subclasses included) stops, a configuration
 * change aside: Home, another app, the screen going off. Copies SilentUpdateScheduler's callbacks.
 */
fun onMainActivityStop(application: Application, action: () -> Unit) {
    application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStopped(activity: Activity) {
            if (activity is MainActivity && !activity.isChangingConfigurations) action()
        }

        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    })
}
