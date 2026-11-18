package com.sdm.core.data.execution

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sdm.core.data.download.DownloadCoordinator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Wakes SDM for scheduled starts, retries and post-boot resumption (spec section 13). */
@Singleton
class WakeupScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    @Volatile private var lastScheduled: Long? = null

    fun scheduleAt(time: Long?, exact: Boolean) {
        if (time == lastScheduled) return
        lastScheduled = time
        val wm = WorkManager.getInstance(context)
        if (time == null) {
            wm.cancelUniqueWork(WORK_SCHEDULED)
            alarmManager().cancel(alarmIntent())
            return
        }
        val delayMs = (time - System.currentTimeMillis()).coerceAtLeast(0)
        wm.enqueueUniqueWork(
            WORK_SCHEDULED, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ResumeWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
        if (exact && canUseExactAlarms()) {
            runCatching { alarmManager().setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, alarmIntent()) }
        }
    }

    fun canUseExactAlarms(): Boolean = Build.VERSION.SDK_INT < 31 || alarmManager().canScheduleExactAlarms()

    fun resumeSoon() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_RESUME, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ResumeWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    private fun alarmManager() = context.getSystemService(AlarmManager::class.java)

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 7, Intent(context, AlarmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val WORK_SCHEDULED = "sdm_scheduled_wakeup"
        const val WORK_RESUME = "sdm_resume"
    }
}

/**
 * Runs inside WorkManager's execution window when SDM was woken from the background. Downloads
 * progress and checkpoint; if they are still running when the window ends, the worker asks to be
 * run again so the transfer continues in the next window.
 */
@HiltWorker
class ResumeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: DownloadCoordinator,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        coordinator.start()
        coordinator.signal()
        delay(3_000)
        val deadline = System.currentTimeMillis() + 8 * 60 * 1000
        while (coordinator.activeCount > 0 && System.currentTimeMillis() < deadline) delay(5_000)
        return if (coordinator.activeCount > 0) Result.retry() else Result.success()
    }
}
