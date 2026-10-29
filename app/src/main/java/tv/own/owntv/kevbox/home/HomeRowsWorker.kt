// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.home

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import org.koin.core.context.GlobalContext

/**
 * One sync of the TV home screen's rows, with the app closed too, so the programme-on-now lines
 * stay current. WorkManager's default factory builds it (Core's KoinWorkerFactory returns null for
 * classes it does not know), and the run is the Koin [HomeRowsSync], so it shares that sync's lock.
 *
 * No network constraint: the sync reads local data only, and the fitted-logo fetch is best effort.
 */
class HomeRowsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        GlobalContext.getOrNull()?.get<HomeRowsSync>()?.syncNow()
        // syncNow never throws, and the next run tries again: a retry would only add runs.
        return Result.success()
    }

    companion object {
        const val PERIODIC_WORK = "kevbox-home-rows"
        const val NOW_WORK = "kevbox-home-rows-now"
        private const val TAG = "KevBoxHomeRows"
        private const val PERIOD_MINUTES = 30L

        /** Every 30 minutes. KEEP: a later start leaves the job, and its next run, as they are. */
        fun schedule(context: Context) = guarded {
            val request = PeriodicWorkRequestBuilder<HomeRowsWorker>(PERIOD_MINUTES, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** One run as soon as WorkManager can; a run already waiting is kept, not doubled. */
        fun runNow(context: Context) = guarded {
            val request = OneTimeWorkRequestBuilder<HomeRowsWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW_WORK, ExistingWorkPolicy.KEEP, request)
        }

        /** Scheduling trouble never reaches the app: the next start, boot or provider broadcast tries again. */
        private inline fun guarded(block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "home rows: schedule failed ${e.javaClass.simpleName}")
            }
        }
    }
}
