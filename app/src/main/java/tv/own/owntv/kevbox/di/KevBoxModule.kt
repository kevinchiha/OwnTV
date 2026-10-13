// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.di

import android.content.Context
import java.time.LocalDate
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import tv.own.owntv.BuildConfig
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.repository.SourceTester
import tv.own.owntv.kevbox.KevBoxCoordinator
import tv.own.owntv.kevbox.KevBoxViewModel
import tv.own.owntv.kevbox.access.AccessMonitor
import tv.own.owntv.kevbox.access.PrefsVerdictPrefs
import tv.own.owntv.kevbox.expiry.ExpiryMonitor
import tv.own.owntv.kevbox.expiry.PrefsExpiryPrefs
import tv.own.owntv.kevbox.playlist.CoreLocalSources
import tv.own.owntv.kevbox.playlist.LocalSources
import tv.own.owntv.kevbox.playlist.ManagedSourceStore
import tv.own.owntv.kevbox.playlist.PlaylistReconciler
import tv.own.owntv.kevbox.playlist.PrefsManagedSourceStore
import tv.own.owntv.kevbox.session.KevBoxAuth
import tv.own.owntv.kevbox.session.KeystoreTokenCipher
import tv.own.owntv.kevbox.session.PrefsSessionStore
import tv.own.owntv.kevbox.session.SessionStore
import tv.own.owntv.kevbox.supabase.SupabaseApi

/** KevBox Live's bindings, on top of upstream's modules. Every store is a private prefs file. */
val kevboxModule = module {
    fun Context.prefs(name: String) = getSharedPreferences(name, Context.MODE_PRIVATE)

    single { SupabaseApi(get(), BuildConfig.KEVBOX_SUPABASE_URL, BuildConfig.KEVBOX_SUPABASE_ANON_KEY) }
    single<SessionStore> {
        PrefsSessionStore(androidContext().prefs(PrefsSessionStore.PREFS_NAME), KeystoreTokenCipher())
    }
    single { KevBoxAuth(get(), get(), now = System::currentTimeMillis) }

    single<LocalSources> { CoreLocalSources(get(), get(), get(), get(), get()) }
    single<ManagedSourceStore> { PrefsManagedSourceStore(androidContext().prefs(PrefsManagedSourceStore.PREFS_NAME)) }
    single { PlaylistReconciler(get(), get()) }

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
        )
    }
    viewModelOf(::KevBoxViewModel)
}
