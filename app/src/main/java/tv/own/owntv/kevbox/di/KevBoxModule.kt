// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.di

import android.content.Context
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import tv.own.owntv.BuildConfig
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.network.ConnectivityObserver
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.core.repository.SourceTester
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.kevbox.KevBoxCoordinator
import tv.own.owntv.kevbox.KevBoxViewModel
import tv.own.owntv.kevbox.access.AccessMonitor
import tv.own.owntv.kevbox.epg.GuideFeeds
import tv.own.owntv.kevbox.access.PrefsVerdictPrefs
import tv.own.owntv.kevbox.errors.AccountCheck
import tv.own.owntv.kevbox.errors.ErrorReporter
import tv.own.owntv.kevbox.errors.ErrorSender
import tv.own.owntv.kevbox.errors.ErrorWatchers
import tv.own.owntv.kevbox.errors.PlaybackScene
import tv.own.owntv.kevbox.errors.secretWordsOf
import tv.own.owntv.kevbox.expiry.ExpiryMonitor
import tv.own.owntv.kevbox.expiry.PrefsExpiryPrefs
import tv.own.owntv.kevbox.playlist.CoreLocalSources
import tv.own.owntv.kevbox.playlist.LocalSources
import tv.own.owntv.kevbox.playlist.ManagedSourceStore
import tv.own.owntv.kevbox.playlist.PlaylistReconciler
import tv.own.owntv.kevbox.playlist.PrefsManagedSourceStore
import tv.own.owntv.kevbox.refresh.CoreSyncSteps
import tv.own.owntv.kevbox.refresh.PlaylistRefreshSetting
import tv.own.owntv.kevbox.refresh.PlaylistUpdater
import tv.own.owntv.kevbox.session.KevBoxAuth
import tv.own.owntv.kevbox.session.KeystoreTokenCipher
import tv.own.owntv.kevbox.session.PrefsSessionStore
import tv.own.owntv.kevbox.session.SessionStore
import tv.own.owntv.kevbox.supabase.SupabaseApi
import tv.own.owntv.player.LivePreviewEngine
import tv.own.owntv.player.OwnTVPlayer

private const val ERROR_QUEUE_FILE = "kevbox_live_errors.json"

/** KevBox Live's bindings, on top of upstream's modules. Every store is a private prefs file. */
val kevboxModule = module {
    fun Context.prefs(name: String) = getSharedPreferences(name, Context.MODE_PRIVATE)

    single { SupabaseApi(get(), BuildConfig.KEVBOX_SUPABASE_URL, BuildConfig.KEVBOX_SUPABASE_ANON_KEY) }
    single<SessionStore> {
        PrefsSessionStore(androidContext().prefs(PrefsSessionStore.PREFS_NAME), KeystoreTokenCipher())
    }
    single { KevBoxAuth(get(), get(), now = System::currentTimeMillis) }
    single { tv.own.owntv.kevbox.errors.DeviceId(androidContext().prefs(tv.own.owntv.kevbox.errors.DeviceId.PREFS_NAME)) }
    single {
        tv.own.owntv.kevbox.usage.LiveCheckin(
            auth = get(),
            api = get(),
            deviceId = get(),
            appVersion = BuildConfig.VERSION_NAME,
            scope = get(),
            now = System::currentTimeMillis,
        )
    }
    single {
        // noBackupFilesDir: the queue is this TV's alone and must never ride a backup to another box.
        val auth = get<KevBoxAuth>()
        tv.own.owntv.kevbox.errors.ErrorQueue(
            file = java.io.File(androidContext().noBackupFilesDir, ERROR_QUEUE_FILE),
            now = System::currentTimeMillis,
            currentUserId = { auth.userId.value },
        )
    }
    single {
        tv.own.owntv.kevbox.errors.ErrorSender(
            queue = get(),
            auth = get(),
            api = get(),
            deviceId = get(),
            appVersion = BuildConfig.VERSION_NAME,
            scope = get(),
            now = System::currentTimeMillis,
        )
    }
    single {
        val xtream = get<XtreamClient>()
        AccountCheck(fetch = { source -> xtream.fetchAccountStatus(source) }, now = System::currentTimeMillis)
    }
    single {
        val auth = get<KevBoxAuth>()
        val sessions = get<SessionStore>()
        val reconciler = get<PlaylistReconciler>()
        val sourceDao = get<SourceDao>()
        val settings = get<SettingsRepository>()
        val connectivity = get<ConnectivityObserver>()
        val sender = get<ErrorSender>()
        val currentSource: suspend () -> SourceEntity? = { reconciler.currentSourceId()?.let { sourceDao.getById(it) } }
        ErrorReporter(
            queue = get(),
            kick = sender::kick,
            startSender = sender::start,
            accountCheck = get(),
            currentSource = currentSource,
            sourceById = { id -> sourceDao.getById(id) },
            proxyUsable = { settings.proxyConfig.first().usable },
            isOnline = connectivity::isOnlineNow,
            // A rotated Supabase session has no signal of its own; the reporter re-reads it per entry too.
            secretChanges = merge(auth.email, auth.userId, sourceDao.observeAll()),
            readSecrets = { secretWordsOf(currentSource(), auth.email.value, sessions.load()) },
            scope = get(),
            now = System::currentTimeMillis,
        )
    }
    single { PlaybackScene() }
    single {
        // Lazy: the shell builds the engines; the watchers only read them once it has shown.
        val koin = getKoin()
        ErrorWatchers(
            reporter = get(),
            livePreview = lazy { koin.get<LivePreviewEngine>() },
            player = lazy { koin.get<OwnTVPlayer>() },
            scene = get(),
            catalogSync = get(),
            sourceRepository = get(),
            sourceDao = get(),
            epgStore = get(),
            reconciler = get(),
            scope = get(),
        )
    }

    single { GuideFeeds(get(), get(), get(), get(), get(), scope = get()) }
    single<LocalSources> { CoreLocalSources(get(), get(), get(), get(), get(), get()) }
    single<ManagedSourceStore> { PrefsManagedSourceStore(androidContext().prefs(PrefsManagedSourceStore.PREFS_NAME)) }
    single { PlaylistReconciler(get(), get()) }
    single { tv.own.owntv.kevbox.release.UpdateProfileAttacher(androidContext(), get(), get(), scope = get()) }

    single {
        val sourceDao = get<SourceDao>()
        val tester = get<SourceTester>()
        ExpiryMonitor(
            test = { id -> sourceDao.getById(id)?.let { tester.test(it) } },
            now = System::currentTimeMillis,
            today = { LocalDate.now() },
            prefs = PrefsExpiryPrefs(androidContext().prefs(PrefsExpiryPrefs.PREFS_NAME)),
        )
    }
    single {
        val auth = get<KevBoxAuth>()
        AccessMonitor(fetch = { auth.verdict() }, prefs = PrefsVerdictPrefs(androidContext().prefs(PrefsVerdictPrefs.PREFS_NAME)))
    }

    single {
        KevBoxCoordinator(
            auth = get(),
            reconciler = get(),
            expiry = get(),
            access = get(),
            profileDao = get(),
            // Core's shared "something is playing" signal. OwnTVShell attaches the full-screen
            // player's engine (mpv, or the Live engine when a channel plays on it) and, during
            // Multiview, the tile with the sound. The Live list's small preview pane is never
            // attached, so an expiry check can run while only that preview plays.
            playbackActive = get<tv.own.owntv.player.PlaybackSession>().active,
            scope = get(),
            now = System::currentTimeMillis,
            proxy = tv.own.owntv.kevbox.network.ManagedProxy(tv.own.owntv.kevbox.network.CoreProxyTarget(get())),
        )
    }
    single {
        PlaylistRefreshSetting(androidContext().prefs(PlaylistRefreshSetting.PREFS_NAME), get(), get(), scope = get())
    }
    single { CoreSyncSteps(get(), get(), get(), get(), get(), get(), get(), get()) }
    single {
        val coordinator = get<KevBoxCoordinator>()
        val reconciler = get<PlaylistReconciler>()
        val steps = get<CoreSyncSteps>()
        PlaylistUpdater(
            checkPlaylist = { coordinator.checkPlaylistNow() },
            currentSourceId = { reconciler.currentSourceId() },
            syncChannels = steps::syncChannels,
            syncGuides = steps::syncGuides,
            scope = get(),
        )
    }
    single {
        tv.own.owntv.kevbox.network.DnsDefault(get(), androidContext().prefs(tv.own.owntv.kevbox.network.DnsDefault.PREFS_NAME))
    }
    viewModelOf(::KevBoxViewModel)
}
