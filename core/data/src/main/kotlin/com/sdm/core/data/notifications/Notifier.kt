package com.sdm.core.data.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sdm.core.common.Formatters
import com.sdm.core.common.I18n
import com.sdm.core.data.execution.NotificationActionReceiver
import com.sdm.domain.Download
import com.sdm.domain.LiveProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** All notifications (spec section 22): per-download progress with actions, grouped completion and errors. */
@Singleton
class Notifier @Inject constructor(@ApplicationContext private val context: Context) {
    private val nm = NotificationManagerCompat.from(context)
    private val lastUpdate = ConcurrentHashMap<Long, Long>()

    init { createChannels() }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val m = context.getSystemService(NotificationManager::class.java)
        m.createNotificationChannels(listOf(
            NotificationChannel(CH_ACTIVE, I18n.tr("Active downloads"), NotificationManager.IMPORTANCE_LOW).apply { description = I18n.tr("Progress, speed and remaining time"); setShowBadge(false) },
            NotificationChannel(CH_DONE, I18n.tr("Completed downloads"), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CH_ERROR, I18n.tr("Download problems"), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CH_INFO, I18n.tr("Reminders and rules"), NotificationManager.IMPORTANCE_LOW),
        ))
    }

    fun canNotify(): Boolean = (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && nm.areNotificationsEnabled()

    private fun appIntent(downloadId: Long?): PendingIntent {
        val i = context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            downloadId?.let { putExtra(EXTRA_DOWNLOAD_ID, it) }
        }
        return PendingIntent.getActivity(context, (downloadId ?: 0L).toInt(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(downloadId: Long, action: String): PendingIntent {
        val i = Intent(context, NotificationActionReceiver::class.java).setAction(action).putExtra(EXTRA_DOWNLOAD_ID, downloadId)
        return PendingIntent.getBroadcast(context, (downloadId * 8 + action.hashCode() % 8).toInt(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Persistent notification used by the foreground service / UIDT job while downloads run. */
    fun leaseNotification(active: Int, speedBps: Long): Notification =
        NotificationCompat.Builder(context, CH_ACTIVE)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(I18n.tr(if (active == 1) "Downloading 1 file" else "Downloading $active files"))
            .setContentText(Formatters.speed(speedBps))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setContentIntent(appIntent(null))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    @SuppressLint("MissingPermission")
    fun progress(d: Download, live: LiveProgress?, force: Boolean = false) {
        if (!canNotify()) return
        val now = System.currentTimeMillis()
        if (!force && now - (lastUpdate[d.id] ?: 0) < 1000) return
        lastUpdate[d.id] = now
        val done = live?.downloadedBytes ?: d.downloadedBytes
        val total = live?.totalBytes ?: d.totalBytes
        val pct = Formatters.percent(done, total)
        val text = buildString {
            append(Formatters.bytes(done)); append(" / "); append(Formatters.bytes(total))
            live?.let { append(" · "); append(Formatters.speed(it.speedBps)); it.etaSeconds?.let { s -> append(" · "); append(Formatters.duration(s)); append(" left") } }
        }
        val n = NotificationCompat.Builder(context, CH_ACTIVE)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(d.fileName)
            .setContentText(I18n.tr(text))
            .setSubText("$pct%")
            .setProgress(100, pct, total == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP)
            .setContentIntent(appIntent(d.id))
            .addAction(0, I18n.tr("Pause"), action(d.id, ACTION_PAUSE))
            .addAction(0, I18n.tr("Cancel"), action(d.id, ACTION_CANCEL))
            .build()
        nm.notify(idFor(d.id), n)
    }

    @SuppressLint("MissingPermission")
    fun paused(d: Download, reason: String) {
        if (!canNotify()) return
        nm.notify(idFor(d.id), NotificationCompat.Builder(context, CH_ACTIVE)
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle(d.fileName)
            .setContentText(I18n.tr("$reason · ${d.progressPercent}%"))
            .setGroup(GROUP)
            .setContentIntent(appIntent(d.id))
            .addAction(0, I18n.tr("Resume"), action(d.id, ACTION_RESUME))
            .addAction(0, I18n.tr("Cancel"), action(d.id, ACTION_CANCEL))
            .build())
    }

    @SuppressLint("MissingPermission")
    fun completed(d: Download, fileUri: String?) {
        cancel(d.id)
        if (!canNotify()) return
        val open = fileUri?.let { uri ->
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), d.mimeType ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Uri.parse(uri).scheme == "file") null else PendingIntent.getActivity(context, d.id.toInt(), Intent.createChooser(i, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } ?: appIntent(d.id)
        nm.notify(idFor(d.id) + DONE_OFFSET, NotificationCompat.Builder(context, CH_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(I18n.tr("Download complete"))
            .setContentText("${d.fileName} · ${Formatters.bytes(d.totalBytes)}")
            .setAutoCancel(true)
            .setGroup(GROUP_DONE)
            .setContentIntent(open)
            .build())
    }

    @SuppressLint("MissingPermission")
    fun failed(d: Download, title: String, message: String) {
        cancel(d.id)
        if (!canNotify()) return
        nm.notify(idFor(d.id) + DONE_OFFSET, NotificationCompat.Builder(context, CH_ERROR)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(I18n.tr(title) + ": ${d.fileName}")
            .setContentText(I18n.tr(message))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setGroup(GROUP_DONE)
            .setContentIntent(appIntent(d.id))
            .addAction(0, I18n.tr("Retry"), action(d.id, ACTION_RETRY))
            .build())
    }

    @SuppressLint("MissingPermission")
    fun info(id: Int, title: String, text: String) {
        if (!canNotify()) return
        nm.notify(INFO_BASE + id, NotificationCompat.Builder(context, CH_INFO)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(I18n.tr(title))
            .setContentText(I18n.tr(text))
            .setAutoCancel(true)
            .setContentIntent(appIntent(null))
            .build())
    }

    fun cancel(downloadId: Long) {
        lastUpdate.remove(downloadId)
        nm.cancel(idFor(downloadId))
    }

    private fun idFor(downloadId: Long) = (BASE + downloadId % 100_000).toInt()

    companion object {
        const val CH_ACTIVE = "sdm_active"; const val CH_DONE = "sdm_done"; const val CH_ERROR = "sdm_error"; const val CH_INFO = "sdm_info"
        const val GROUP = "sdm_downloads"; const val GROUP_DONE = "sdm_finished"
        const val LEASE_NOTIFICATION_ID = 42
        const val EXTRA_DOWNLOAD_ID = "com.sdm.extra.DOWNLOAD_ID"
        const val ACTION_PAUSE = "com.sdm.action.PAUSE"; const val ACTION_RESUME = "com.sdm.action.RESUME"
        const val ACTION_CANCEL = "com.sdm.action.CANCEL"; const val ACTION_RETRY = "com.sdm.action.RETRY"
        private const val BASE = 1000L; private const val DONE_OFFSET = 200_000; private const val INFO_BASE = 900_000
    }
}
