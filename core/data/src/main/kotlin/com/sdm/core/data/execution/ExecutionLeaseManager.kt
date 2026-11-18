package com.sdm.core.data.execution

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sdm.core.data.notifications.Notifier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the process allowed to run while downloads are active (ADR-0003):
 * user-initiated data transfer job on Android 14+, dataSync foreground service on 8-13,
 * and best effort (WorkManager windows) when Android forbids both from the background.
 */
@Singleton
class ExecutionLeaseManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notifier: Notifier,
) {
    enum class Mode(val label: String) { NONE("Idle"), UIDT("User-initiated transfer job"), FOREGROUND_SERVICE("Foreground service"), BEST_EFFORT("Best effort (background limits)") }

    private val _mode = MutableStateFlow(Mode.NONE)
    val mode: StateFlow<Mode> = _mode.asStateFlow()
    @Volatile private var job: Pair<JobService, JobParameters>? = null

    @Synchronized
    fun update(active: Int, speedBps: Long) {
        if (active <= 0) { release(); return }
        when (_mode.value) {
            Mode.NONE, Mode.BEST_EFFORT -> acquire()
            else -> Unit
        }
        if (_mode.value == Mode.FOREGROUND_SERVICE || _mode.value == Mode.UIDT) refresh(active, speedBps)
    }

    private fun refresh(active: Int, speedBps: Long) {
        if (!notifier.canNotify()) return
        runCatching { NotificationManagerCompat.from(context).notify(Notifier.LEASE_NOTIFICATION_ID, notifier.leaseNotification(active, speedBps)) }
    }

    private fun acquire() {
        if (Build.VERSION.SDK_INT >= 34 && tryUserInitiatedJob()) { _mode.value = Mode.UIDT; return }
        if (tryForegroundService()) { _mode.value = Mode.FOREGROUND_SERVICE; return }
        _mode.value = Mode.BEST_EFFORT
    }

    @androidx.annotation.RequiresApi(34)
    private fun tryUserInitiatedJob(): Boolean = runCatching {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val info = JobInfo.Builder(JOB_ID, ComponentName(context, TransferJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .build()
        scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
    }.getOrDefault(false)

    private fun tryForegroundService(): Boolean = runCatching {
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        true
    }.getOrDefault(false)

    @Synchronized
    fun release() {
        when (_mode.value) {
            Mode.UIDT -> {
                job?.let { (service, params) -> runCatching { service.jobFinished(params, false) } }
                runCatching { context.getSystemService(JobScheduler::class.java).cancel(JOB_ID) }
            }
            Mode.FOREGROUND_SERVICE -> runCatching { context.stopService(Intent(context, DownloadService::class.java)) }
            else -> Unit
        }
        job = null
        _mode.value = Mode.NONE
        NotificationManagerCompat.from(context).cancel(Notifier.LEASE_NOTIFICATION_ID)
    }

    fun onJobStarted(service: JobService, params: JobParameters) { job = service to params }

    fun onLeaseEnded() {
        job = null
        _mode.value = Mode.NONE
    }

    companion object { const val JOB_ID = 4201 }
}
