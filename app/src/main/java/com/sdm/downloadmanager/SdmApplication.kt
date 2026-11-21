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
    // Created before the coordinator starts so queued downloads see the right Premium state.
    @Inject lateinit var premium: com.sdm.downloadmanager.premium.PremiumRepository

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        com.sdm.core.common.SourcePolicy.blockVideoPlatforms = BuildConfig.PLAY_STORE
        // Recovers interrupted downloads and starts the dispatcher (spec section 6.4).
        coordinator.start()
        widgets.start()
    }
}
