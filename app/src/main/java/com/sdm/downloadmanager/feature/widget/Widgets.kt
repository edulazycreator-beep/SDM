package com.sdm.downloadmanager.feature.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import com.sdm.core.common.Formatters
import com.sdm.core.data.download.DownloadRepository
import com.sdm.domain.DownloadState
import com.sdm.domain.LiveProgress
import com.sdm.domain.Download
import com.sdm.downloadmanager.ACTION_PASTE_LINK
import com.sdm.downloadmanager.MainActivity
import com.sdm.downloadmanager.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Home-screen widget: active downloads, overall progress and speed, with pause/resume all. */
@AndroidEntryPoint
class DownloadsWidget : AppWidgetProvider() {
    @Inject lateinit var repo: DownloadRepository
    @Inject lateinit var updater: WidgetUpdater

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { updater.refresh() }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_PAUSE_ALL, ACTION_RESUME_ALL -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try { if (intent.action == ACTION_PAUSE_ALL) repo.pauseAll() else repo.resumeAll(); updater.refresh() } finally { pending.finish() }
                }
            }
        }
    }

    companion object {
        const val ACTION_PAUSE_ALL = "com.sdm.downloadmanager.widget.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.sdm.downloadmanager.widget.RESUME_ALL"
    }
}

/** Keeps widgets current while SDM runs (at most every 2 seconds). */
@Singleton
class WidgetUpdater @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val repo: DownloadRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(repo.downloads, repo.live) { d, l -> d to l }.conflate().collect { (d, l) ->
                if (ids().isNotEmpty()) render(d, l)
                delay(2_000)
            }
        }
    }

    fun refresh() = scope.launch { render(repo.downloads.first(), repo.live.value) }

    private fun ids(): IntArray = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, DownloadsWidget::class.java))

    private fun render(all: List<Download>, live: Map<Long, LiveProgress>) {
        val ids = ids()
        if (ids.isEmpty()) return
        val active = all.filter { it.state.isActive || it.state == DownloadState.QUEUED || it.state == DownloadState.WAITING_FOR_NETWORK }
        val paused = all.count { it.state == DownloadState.PAUSED }
        val done = active.sumOf { live[it.id]?.downloadedBytes ?: it.downloadedBytes }
        val total = active.sumOf { live[it.id]?.totalBytes ?: it.totalBytes ?: 0 }
        val speed = active.sumOf { live[it.id]?.speedBps ?: 0 }
        val v = RemoteViews(context.packageName, R.layout.widget_downloads)
        v.setTextViewText(R.id.widget_title, when {
            active.isNotEmpty() -> "${active.size} downloading"
            paused > 0 -> "$paused paused"
            else -> "No active downloads"
        })
        v.setTextViewText(R.id.widget_detail, when {
            active.isNotEmpty() -> (active.first().fileName.take(40)) + " · " + Formatters.speed(speed) + if (total > 0) " · ${Formatters.percent(done, total)}%" else ""
            else -> "Tap to open SDM"
        })
        v.setProgressBar(R.id.widget_progress, 1000, if (total > 0) (done * 1000 / total).toInt() else 0, active.isNotEmpty() && total <= 0)
        v.setViewVisibility(R.id.widget_progress, if (active.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        v.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags))
        val toggle = if (active.isNotEmpty()) DownloadsWidget.ACTION_PAUSE_ALL else DownloadsWidget.ACTION_RESUME_ALL
        v.setTextViewText(R.id.widget_toggle, if (active.isNotEmpty()) "Pause all" else "Resume all")
        v.setViewVisibility(R.id.widget_toggle, if (active.isEmpty() && paused == 0) android.view.View.GONE else android.view.View.VISIBLE)
        v.setOnClickPendingIntent(R.id.widget_toggle, PendingIntent.getBroadcast(context, 1, Intent(context, DownloadsWidget::class.java).setAction(toggle), flags))
        v.setOnClickPendingIntent(R.id.widget_paste, PendingIntent.getActivity(context, 2, pasteIntent(context), flags))
        AppWidgetManager.getInstance(context).updateAppWidget(ids, v)
    }
}

fun pasteIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).setAction(ACTION_PASTE_LINK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

/** Quick Settings tile: opens SDM and adds the link in the clipboard. */
class PasteLinkTile : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = pasteIntent(this)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
    }
}
