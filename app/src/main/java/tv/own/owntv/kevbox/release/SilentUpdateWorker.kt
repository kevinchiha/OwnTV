// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.release

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.koin.core.context.GlobalContext
import tv.own.owntv.MainActivity

/**
 * One silent update run. WorkManager's default factory builds it (Core's KoinWorkerFactory returns
 * null for classes it does not know), and the run itself is the Koin [SilentUpdater], so a periodic
 * and a one-time run that overlap share its lock.
 */
class SilentUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        GlobalContext.get().get<SilentUpdater>().run()
        // The next periodic run tries again; a retry here would only add runs.
        return Result.success()
    }
}

/**
 * Schedules [SilentUpdateWorker] on Android 12 and later (nothing earlier can install silently):
 * every 6 hours with a network, and once 15 minutes after the app leaves the screen. Also keeps the set of started [MainActivity] instances (its colour
 * subclasses included), which [activityStarted] reads for the idle rule, and stores each stop's time
 * on the monotonic clock ([elapsedNow]) in [prefs], for the 15 minutes out of sight that rule asks.
 */
class SilentUpdateScheduler(
    private val application: Application,
    private val prefs: SilentUpdatePrefs,
    private val elapsedNow: () -> Long,
    private val workManager: () -> WorkManager,
) {
    private val startedActivities = Collections.synchronizedSet(HashSet<Activity>())
    private val begun = AtomicBoolean(false)

    /**
     * True while any MainActivity is between onStart and onStop, and before [start] registered the
     * callbacks: with visibility unknown the box counts as on screen, as on KevBox TV.
     */
    val activityStarted: () -> Boolean = { !begun.get() || startedActivities.isNotEmpty() }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            if (activity is MainActivity) startedActivities.add(activity)
        }

        override fun onActivityStopped(activity: Activity) {
            if (activity !is MainActivity) return
            // Removed on every stop, a rotation's too, so the set stays exact.
            startedActivities.remove(activity)
            if (!activity.isChangingConfigurations) {
                prefs.stopped(elapsedNow())
                enqueueSoon()
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    /** Registers the callbacks and the periodic job. Once per process; nothing below Android 12. */
    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!begun.compareAndSet(false, true)) return
        application.registerActivityLifecycleCallbacks(callbacks)
        guarded {
            val request = PeriodicWorkRequestBuilder<SilentUpdateWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(networkNeeded())
                .build()
            // UPDATE: a changed period or constraint reaches boxes that already have the job, an
            // unchanged request changes nothing, and neither pushes the next run back.
            workManager().enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }

    private fun enqueueSoon() = guarded {
        val request = OneTimeWorkRequestBuilder<SilentUpdateWorker>()
            .setInitialDelay(SOON_DELAY_MINUTES, TimeUnit.MINUTES)
            .setConstraints(networkNeeded())
            .build()
        // REPLACE, as on KevBox TV: each leave starts the 15 minutes again. A run it cancels stops
        // between two buffers, installs nothing, and the next run resumes its download.
        workManager().enqueueUniqueWork(SOON_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    private fun networkNeeded() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Scheduling trouble never reaches the app: the next start or stop tries again. */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "silent update: schedule failed ${e.javaClass.simpleName}")
        }
    }

    companion object {
        const val PERIODIC_WORK = "kevbox-silent-update"
        const val SOON_WORK = "kevbox-silent-update-soon"
        private const val TAG = "KevBoxSilentUpdate"
        private const val PERIOD_HOURS = 6L
        private const val SOON_DELAY_MINUTES = 15L
    }
}
