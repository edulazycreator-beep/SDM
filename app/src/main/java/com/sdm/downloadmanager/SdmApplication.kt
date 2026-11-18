package com.sdm.downloadmanager

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.sdm.core.data.download.DownloadCoordinator
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SdmApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var coordinator: DownloadCoordinator
    @Inject lateinit var widgets: com.sdm.downloadmanager.feature.widget.WidgetUpdater

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Recovers interrupted downloads and starts the dispatcher (spec section 6.4).
        coordinator.start()
        widgets.start()
    }
}
