// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.release

import android.app.Activity
import android.app.Application
import android.content.Context
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
 * Schedules [SilentUpdateWorker]: every 6 hours with a network, and once 15 minutes after the
 * app leaves the screen. Also keeps the set of started [MainActivity] instances (its colour
 * subclasses included), which [activityStarted] reads for the idle rule.
 */
class SilentUpdateScheduler(
    private val application: Application,
    private val workManager: () -> WorkManager,
) {
    private val startedActivities = Collections.synchronizedSet(HashSet<Activity>())
    private val begun = AtomicBoolean(false)

    /** True while any MainActivity is between onStart and onStop. */
    val activityStarted: () -> Boolean = { startedActivities.isNotEmpty() }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            if (activity is MainActivity) startedActivities.add(activity)
        }

        override fun onActivityStopped(activity: Activity) {
            if (activity !is MainActivity) return
            // Removed on every stop, a rotation's too, so the set stays exact.
            startedActivities.remove(activity)
            if (!activity.isChangingConfigurations) enqueueSoon()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    /** Registers the callbacks and the periodic job. Once per process. */
    fun start() {
        if (!begun.compareAndSet(false, true)) return
        application.registerActivityLifecycleCallbacks(callbacks)
        guarded {
            val request = PeriodicWorkRequestBuilder<SilentUpdateWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(networkNeeded())
                .build()
            // KEEP: an app start never pushes the next run back.
            workManager().enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }

    private fun enqueueSoon() = guarded {
        val request = OneTimeWorkRequestBuilder<SilentUpdateWorker>()
            .setInitialDelay(SOON_DELAY_MINUTES, TimeUnit.MINUTES)
            .setConstraints(networkNeeded())
            .build()
        // REPLACE: the 15 minutes count from the latest stop.
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
