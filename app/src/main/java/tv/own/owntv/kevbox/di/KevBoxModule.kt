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
import tv.own.owntv.kevbox.errors.LadderEvents
import tv.own.owntv.kevbox.errors.PlaybackScene
import tv.own.owntv.kevbox.errors.secretWordsOf
import tv.own.owntv.kevbox.expiry.ExpiryMonitor
import tv.own.owntv.kevbox.expiry.PrefsExpiryPrefs
import tv.own.owntv.kevbox.network.ProxyInUse
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
private const val DIARY_QUEUE_FILE = "kevbox_live_diary.json"
private const val DIARY_OPEN_FILE = "kevbox_live_diary_open.json"

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
        // noBackupFilesDir, like the error queue: this TV's alone, never in a backup.
        val dir = androidContext().noBackupFilesDir
        tv.own.owntv.kevbox.usage.DiaryQueue(
            file = java.io.File(dir, DIARY_QUEUE_FILE),
            openFile = java.io.File(dir, DIARY_OPEN_FILE),
        )
    }
    single {
        tv.own.owntv.kevbox.usage.DiarySender(
            queue = get(),
            auth = get(),
            api = get(),
            deviceId = get(),
            appVersion = BuildConfig.VERSION_NAME,
            scope = get(),
            now = System::currentTimeMillis,
            zone = { java.time.ZoneId.systemDefault() },
        )
    }
    // The one proxy flag error entries and play notes report: Core's stored setting, which its
    // ProxyConfigHolder routes every request by.
    single {
        val settings = get<SettingsRepository>()
        ProxyInUse(config = { settings.proxyConfig.first() })
    }
    single {
        val proxyInUse = get<ProxyInUse>()
        tv.own.owntv.kevbox.usage.PlayNotes(
            auth = get(),
            api = get(),
            deviceId = get(),
            appVersion = BuildConfig.VERSION_NAME,
            proxyInUse = proxyInUse::current,
            scope = get(),
            now = System::currentTimeMillis,
            zone = { java.time.ZoneId.systemDefault() },
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
        val connectivity = get<ConnectivityObserver>()
        val sender = get<ErrorSender>()
        val proxyInUse = get<ProxyInUse>()
        val currentSource: suspend () -> SourceEntity? = { reconciler.currentSourceId()?.let { sourceDao.getById(it) } }
        ErrorReporter(
            queue = get(),
            kick = sender::kick,
            startSender = sender::start,
            accountCheck = get(),
            currentSource = currentSource,
            sourceById = { id -> sourceDao.getById(id) },
            proxyUsable = proxyInUse::current,
            isOnline = connectivity::isOnlineNow,
            // sessionVersion moves when a refresh rotates the tokens, which leaves the email and id alone.
            secretChanges = merge(auth.email, auth.userId, auth.sessionVersion, sourceDao.observeAll()),
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
            // LiveViewModel's host feeds Core's engine ladder in here (0.3 AC-10).
            ladderEvents = LadderEvents.events,
        )
    }

    single {
        // Lazy, like the error watchers: the engines are read only once the shell has shown.
        val koin = getKoin()
        tv.own.owntv.kevbox.usage.PlayWatcher(
            notes = get(),
            reporter = get(),
            scene = get(),
            livePreview = lazy { koin.get<LivePreviewEngine>() },
            player = lazy { koin.get<OwnTVPlayer>() },
            scope = get(),
            // Monotonic: a wall-clock jump never proves or cuts a 30-second play.
            now = android.os.SystemClock::elapsedRealtime,
        )
    }

    // The watching diary (0.5): the last press, the stored guide's on-now entry, and the watcher.
    single { tv.own.owntv.kevbox.usage.LastPress(now = System::currentTimeMillis) }
    single { tv.own.owntv.kevbox.usage.GuideNow(get(), get(), get(), get()) }
    single {
        // Lazy, like the play watcher: the engines are read only once the shell has shown.
        val koin = getKoin()
        tv.own.owntv.kevbox.usage.DiaryWatcher(
            cutter = tv.own.owntv.kevbox.usage.StretchCutter { java.util.UUID.randomUUID().toString() },
            queue = get(),
            sender = get(),
            lastPress = get(),
            guide = get(),
            auth = get(),
            scene = get(),
            livePreview = lazy { koin.get<LivePreviewEngine>() },
            player = lazy { koin.get<OwnTVPlayer>() },
            proxyInUse = get(),
            application = androidContext() as android.app.Application,
            scope = get(),
            // Stamps on the wall clock, lengths on the monotonic one (see DiaryWatcher).
            now = System::currentTimeMillis,
            elapsed = android.os.SystemClock::elapsedRealtime,
        )
    }

    // LiveOnSat's football listings, read from kevbox.dev for the Sports page's Find channels.
    single { tv.own.owntv.kevbox.sports.FootballListings(get(), get(), now = System::currentTimeMillis) }
    single<tv.own.owntv.kevbox.sports.ListedChannelReads> {
        tv.own.owntv.kevbox.sports.RoomListedChannelReads(get(), get(), get(), get(), get(), get())
    }
    // The listed channels SportsViewModel and SportsPlayback put first in Find channels' list.
    single { tv.own.owntv.kevbox.sports.ListedChannels(get(), get()) }
    // KevBox's Sports defaults (Any commentary language), started from OwnTVApp.
    single { tv.own.owntv.kevbox.sports.SportsDefaults(get(), get(), scope = get()) }
    // Replaces Core's SportsSource (this module loads after Core's, and Koin keeps the last definition):
    // the same ESPN source, with "today" asked by date so a Lebanese morning sees that night's games.
    single<tv.own.owntv.core.sports.SportsSource> {
        tv.own.owntv.kevbox.sports.TodayDatedSportsSource(tv.own.owntv.core.sports.EspnSportsSource(get()))
    }

    single { GuideFeeds(get(), get(), get(), get(), get(), scope = get()) }
    // One show finder for both Search's TV guide tab and the guide's own search.
    single { tv.own.owntv.kevbox.live.ProgrammeTitles(get()) }
    single { tv.own.owntv.kevbox.live.ShowSearch(get(), get(), get()) }
    single<LocalSources> { CoreLocalSources(get(), get(), get(), get(), get(), get(), get()) }
    single<ManagedSourceStore> { PrefsManagedSourceStore(androidContext().prefs(PrefsManagedSourceStore.PREFS_NAME)) }
    single { PlaylistReconciler(get(), get()) }
    single { tv.own.owntv.kevbox.release.UpdateProfileAttacher(androidContext(), get(), get(), scope = get()) }
    // Silent self-update on Android 12+: the prefs file the result receiver opens too, the scheduler
    // OwnTVApp starts, and the one updater both WorkManager jobs run.
    single { tv.own.owntv.kevbox.release.SilentUpdatePrefs.open(androidContext()) }
    single {
        val app = androidContext() as android.app.Application
        tv.own.owntv.kevbox.release.SilentUpdateScheduler(app, workManager = { androidx.work.WorkManager.getInstance(app) })
    }
    single {
        val scheduler = get<tv.own.owntv.kevbox.release.SilentUpdateScheduler>()
        val auth = get<KevBoxAuth>()
        val session = get<tv.own.owntv.player.PlaybackSession>()
        tv.own.owntv.kevbox.release.SilentUpdater(
            context = androidContext(),
            client = get(),
            prefs = get(),
            signedInEmail = { auth.email.value },
            activityStarted = scheduler.activityStarted,
            playing = { session.active.value },
            // Idle on the monotonic clock; the wall clock only stands in for a missing server Date.
            lastPressElapsedMs = get<tv.own.owntv.kevbox.usage.LastPress>()::elapsedAt,
            elapsedNow = android.os.SystemClock::elapsedRealtime,
            now = System::currentTimeMillis,
            // Blank means GitHub's latest release; only a debug build can point it elsewhere.
            feedUrl = BuildConfig.KEVBOX_UPDATE_FEED,
        )
    }

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
            checkinOnForeground = get<tv.own.owntv.kevbox.usage.LiveCheckin>()::onForeground,
            playNotesOnForeground = get<tv.own.owntv.kevbox.usage.PlayNotes>()::onForeground,
        )
    }
    // KevBox Setup's login file (kevbox-setup docs/login-file.md), checked from MainActivity.onResume.
    single {
        val context = androidContext()
        val auth = get<KevBoxAuth>()
        val coordinator = get<KevBoxCoordinator>()
        tv.own.owntv.kevbox.session.LoginFileSignIn(
            dir = { context.getExternalFilesDir(null) },
            now = System::currentTimeMillis,
            signedInEmail = { auth.email.value },
            signOut = coordinator::signOut,
            signIn = coordinator::signIn,
            scope = get(),
            io = kotlinx.coroutines.Dispatchers.IO,
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
