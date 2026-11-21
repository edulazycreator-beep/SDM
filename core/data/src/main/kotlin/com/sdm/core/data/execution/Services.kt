package com.sdm.core.data.execution

import android.app.Service
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.sdm.core.data.di.AppScope
import com.sdm.core.data.download.DownloadCoordinator
import com.sdm.core.data.notifications.Notifier
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Android 14+: user-initiated data transfer job holding the execution lease. */
@AndroidEntryPoint
class TransferJobService : JobService() {
    @Inject lateinit var lease: ExecutionLeaseManager
    @Inject lateinit var coordinator: DownloadCoordinator
    @Inject lateinit var notifier: Notifier

    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            setNotification(params, Notifier.LEASE_NOTIFICATION_ID, notifier.leaseNotification(coordinator.activeCount.coerceAtLeast(1), coordinator.totalSpeedBps), JOB_END_NOTIFICATION_POLICY_REMOVE)
        }
        lease.onJobStarted(this, params)
        coordinator.start()
        coordinator.signal()
        if (coordinator.activeCount == 0) { lease.release(); return false }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        lease.onLeaseEnded()
        val reason = if (Build.VERSION.SDK_INT >= 31) " (stop reason ${params.stopReason})" else ""
        coordinator.onLeaseRevoked("Android stopped the transfer job$reason")
        return false
    }
}

/** Android 8-13 (and fallback): dataSync foreground service holding the execution lease. */
@AndroidEntryPoint
class DownloadService : Service() {
    @Inject lateinit var lease: ExecutionLeaseManager
    @Inject lateinit var coordinator: DownloadCoordinator
    @Inject lateinit var notifier: Notifier

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, Notifier.LEASE_NOTIFICATION_ID, notifier.leaseNotification(coordinator.activeCount.coerceAtLeast(1), coordinator.totalSpeedBps), type)
        coordinator.start()
        coordinator.signal()
        return START_NOT_STICKY
    }

    /** Android 15+: the 6-hour dataSync budget is used up. Stop within seconds; downloads checkpoint and re-queue. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        coordinator.onLeaseRevoked("Foreground service time limit reached")
        lease.onLeaseEnded()
        stopSelf()
    }

    override fun onDestroy() {
        lease.onLeaseEnded()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {
    @Inject lateinit var coordinator: DownloadCoordinator
    @Inject @AppScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(Notifier.EXTRA_DOWNLOAD_ID, -1)
        if (id < 0) return
        val pending = goAsync()
        coordinator.start()
        scope.launch {
            try {
                when (intent.action) {
                    Notifier.ACTION_PAUSE -> coordinator.pause(id)
                    Notifier.ACTION_RESUME, Notifier.ACTION_RETRY -> coordinator.resume(id)
                    Notifier.ACTION_CANCEL -> coordinator.cancel(id, deletePartial = true)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** Reboot / app update: restore state, then resume through WorkManager (Android 15 forbids dataSync FGS from boot). */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var coordinator: DownloadCoordinator
    @Inject lateinit var wakeups: WakeupScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        coordinator.start()
        wakeups.resumeSoon()
    }
}

/** Exact alarm for a scheduled start; alarms are an allowed way to start a foreground service. */
@AndroidEntryPoint
class AlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var coordinator: DownloadCoordinator
    @Inject lateinit var wakeups: WakeupScheduler

    override fun onReceive(context: Context, intent: Intent) {
        coordinator.start()
        coordinator.signal()
        wakeups.resumeSoon()
    }
}
