// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.GlobalPreferences
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import tv.own.owntv.R

/**
 * The real [VpnEngine]: ics-openvpn's OpenVPN 2.x engine (extras/openvpn), whose service runs in
 * this app's main process, so its in-process status reports ([VpnStatus]) reach this class directly.
 *
 * [start] hands the engine a temporary profile built from [prepareOvpn]'s config, the login, and an
 * allowed-apps list of this app alone ("allowed" mode, so the engine calls
 * `VpnService.Builder.addAllowedApplication` for it only, AC-27). The engine saves a temporary
 * profile to a file; that file is deleted at once, so the password does not stay on disk and a
 * restart of the engine's service after the process dies finds no profile to reconnect with.
 *
 * Status reports map to [EngineEvent]s, one session per [start]:
 * - connected gives [EngineEvent.Up];
 * - login refused, config refused, the engine's process ending or its service dying give
 *   [EngineEvent.Failed], which ends the session's events;
 * - before the first Up every other report is ignored; after an Up, the first report that is not
 *   connected (reconnecting and every other connecting level, no network, paused, disconnected)
 *   gives one [EngineEvent.Down], then nothing until the next Up or Failed (AC-30).
 * [stop] ends the session: reports after it are dropped until the next [start], and the old
 * process's exit reports that arrive after that start are dropped too.
 *
 * Nothing here logs a login, a config or a host: only class names.
 */
class IcsVpnEngine(context: Context) : VpnEngine {
    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, THREAD_NAME).apply { isDaemon = true } }
    private val mutableEvents = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<EngineEvent> = mutableEvents.asSharedFlow()

    private val lock = Any()
    private var prepared = false
    private var session = 0
    private var active = false
    private var up = false
    private var failed = false
    private var processMayRun = false
    private var awaitOldExit = false
    private var bound = false
    private var binder: IOpenVPNServiceInternal? = null
    private var stopWhenBound = false

    private val stateListener = object : VpnStatus.StateListener {
        override fun updateState(
            state: String?,
            logmessage: String?,
            localizedResId: Int,
            level: ConnectionStatus?,
            intent: Intent?,
        ) = onReport(state, level)

        override fun setConnectedVPN(uuid: String?) = Unit
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val stopNow = synchronized(lock) {
                binder = IOpenVPNServiceInternal.Stub.asInterface(service)
                val stop = stopWhenBound
                stopWhenBound = false
                stop
            }
            if (stopNow) worker.execute { stopProcess() }
        }

        override fun onServiceDisconnected(name: ComponentName?) = serviceLost()

        override fun onBindingDied(name: ComponentName?) {
            synchronized(lock) { bound = false }
            runCatching { app.unbindService(this) }
            serviceLost()
        }
    }

    override fun start(host: String, config: String, username: String, password: String) {
        prepareOnce()
        val mine = synchronized(lock) {
            session++
            active = true
            up = false
            failed = false
            awaitOldExit = processMayRun
            processMayRun = true
            stopWhenBound = false
            session
        }
        worker.execute { launch(mine, host, config, username, password) }
    }

    override fun stop() {
        synchronized(lock) {
            session++
            active = false
            up = false
        }
        worker.execute {
            stopProcess()
            deleteTemporaryProfile()
        }
    }

    private fun launch(mine: Int, host: String, config: String, username: String, password: String) {
        val profile = try {
            buildProfile(host, config, username, password)
        } catch (e: ConfigParser.ConfigParseError) {
            Log.w(TAG, "vpn: config refused ${e.javaClass.simpleName}")
            null
        } catch (e: IOException) {
            Log.w(TAG, "vpn: config unreadable ${e.javaClass.simpleName}")
            null
        }
        if (profile == null || profile.checkProfile(app) != de.blinkt.openvpn.R.string.no_error_found) {
            fail(mine)
            return
        }
        if (!isCurrent(mine)) return
        try {
            // Load the engine's profile list before setting the temporary profile, so a later load
            // cannot replace it, then remove the file the engine writes for it.
            ProfileManager.getInstance(app)
            ProfileManager.setTemporaryProfile(app, profile)
            deleteTemporaryProfile()
            bindOnce()
            VPNLaunchHelper.startOpenVpn(profile, app, START_REASON, true)
        } catch (e: RuntimeException) {
            Log.w(TAG, "vpn: start failed ${e.javaClass.simpleName}")
            fail(mine)
        }
    }

    private fun buildProfile(host: String, config: String, username: String, password: String): VpnProfile {
        val parser = ConfigParser()
        parser.parseConfig(StringReader(prepareOvpn(config)))
        return parser.convertProfile().apply {
            mName = host
            mUsername = username
            mPassword = password
            mAllowedAppsVpnAreDisallowed = false
            mAllowedAppsVpn = hashSetOf(app.packageName)
            mAllowAppVpnBypass = false
        }
    }

    private fun onReport(state: String?, level: ConnectionStatus?) {
        val exited = state == STATE_NO_PROCESS
        var stopAgain = false
        val event: EngineEvent? = synchronized(lock) {
            if (exited) processMayRun = false
            when {
                !active -> {
                    // A process still talking after stop() (it was starting as stop() ran) is stopped again.
                    stopAgain = !exited && level != null && level != ConnectionStatus.LEVEL_NOTCONNECTED
                    null
                }
                failed -> null
                awaitOldExit && level == ConnectionStatus.LEVEL_NOTCONNECTED -> {
                    // The previous session's process winding down; its exit closes that wait.
                    if (exited) awaitOldExit = false
                    null
                }
                else -> {
                    awaitOldExit = false
                    mapReport(exited, level)
                }
            }
        }
        if (event != null) mutableEvents.tryEmit(event)
        if (stopAgain) worker.execute { stopProcess() }
    }

    /** Called under [lock] for a report of the current session. */
    private fun mapReport(exited: Boolean, level: ConnectionStatus?): EngineEvent? = when {
        exited || level == ConnectionStatus.LEVEL_AUTH_FAILED -> {
            failed = true
            up = false
            EngineEvent.Failed
        }
        level == ConnectionStatus.LEVEL_CONNECTED -> if (up) {
            null
        } else {
            up = true
            EngineEvent.Up
        }
        up -> {
            up = false
            EngineEvent.Down
        }
        else -> null
    }

    private fun fail(mine: Int) {
        val emit = synchronized(lock) {
            if (!active || session != mine || failed) return@synchronized false
            failed = true
            up = false
            true
        }
        if (emit) mutableEvents.tryEmit(EngineEvent.Failed)
    }

    private fun isCurrent(mine: Int): Boolean = synchronized(lock) { active && session == mine }

    private fun serviceLost() {
        val mine = synchronized(lock) {
            binder = null
            processMayRun = false
            session
        }
        fail(mine)
    }

    /** Asks the engine's process to stop, or remembers to once the service is bound. Worker thread. */
    private fun stopProcess() {
        val service = synchronized(lock) {
            if (binder == null && bound) stopWhenBound = true
            binder
        } ?: return
        try {
            service.stopVPN(false)
        } catch (e: RemoteException) {
            Log.w(TAG, "vpn: stop failed ${e.javaClass.simpleName}")
        }
    }

    /** Worker thread. The binding is kept for the app's life; it is how [stop] reaches the engine. */
    private fun bindOnce() {
        val bind = synchronized(lock) {
            if (bound) return@synchronized false
            bound = true
            true
        }
        if (!bind) return
        val intent = Intent(app, OpenVPNService::class.java).setAction(OpenVPNService.START_SERVICE)
        if (!app.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            synchronized(lock) { bound = false }
            runCatching { app.unbindService(connection) }
        }
    }

    private fun deleteTemporaryProfile() {
        for (suffix in TEMPORARY_PROFILE_SUFFIXES) app.deleteFile(TEMPORARY_PROFILE + suffix)
    }

    /**
     * What the engine's own Application class did at startup that its service needs in one process:
     * - the notification channels it posts to, created here first at the lowest importance with the
     *   app's name for them (AC-34); a channel's importance is fixed at creation;
     * - the engine's global settings, which its managed-config check sets and the service reads on
     *   every notification: set to that check's defaults for a device with no managed config (no
     *   minimal UI, no forced connection, no initial import).
     * Its strict mode, first-launch setting, locale handling, managed-config import and
     * cross-process status binding are not needed and are left out.
     */
    private fun prepareOnce() {
        synchronized(lock) {
            if (prepared) return
            prepared = true
        }
        val manager = app.getSystemService(NotificationManager::class.java)
        val name = app.getString(R.string.kevbox_vpn_channel)
        for (id in ENGINE_CHANNELS) {
            manager?.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_MIN))
        }
        GlobalPreferences.setInstance(false, false, false)
        VpnStatus.addStateListener(stateListener)
    }

    private companion object {
        const val TAG = "KevBoxVpn"
        const val THREAD_NAME = "KevBoxVpnEngine"
        const val START_REASON = "KevBox Live"
        const val STATE_NO_PROCESS = "NOPROCESS"
        const val TEMPORARY_PROFILE = "temporary-vpn-profile"
        val TEMPORARY_PROFILE_SUFFIXES = listOf(".vp", ".cp", ".cpold")
        val ENGINE_CHANNELS = listOf(
            OpenVPNService.NOTIFICATION_CHANNEL_BG_ID,
            OpenVPNService.NOTIFICATION_CHANNEL_NEWSTATUS_ID,
            OpenVPNService.NOTIFICATION_CHANNEL_USERREQ_ID,
        )
    }
}
