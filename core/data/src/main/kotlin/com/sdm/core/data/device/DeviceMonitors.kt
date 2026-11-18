package com.sdm.core.data.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class NetworkState(
    val connected: Boolean,
    val unmetered: Boolean,
    val wifi: Boolean,
    val cellular: Boolean,
    val validated: Boolean,
) {
    val label: String get() = when {
        !connected -> "Offline"
        wifi -> if (unmetered) "Wi-Fi" else "Wi-Fi (metered)"
        cellular -> "Mobile data"
        unmetered -> "Connected (unmetered)"
        else -> "Connected"
    }

    companion object { val OFFLINE = NetworkState(false, false, false, false, false) }
}

@Singleton
class NetworkMonitor @Inject constructor(@ApplicationContext context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(current())
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    init {
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { _state.value = current() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { _state.value = from(caps) }
                override fun onLost(network: Network) { _state.value = current() }
            })
        }
    }

    fun current(): NetworkState {
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return NetworkState.OFFLINE
        return from(caps)
    }

    private fun from(caps: NetworkCapabilities) = NetworkState(
        connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
        cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
    )
}

data class BatteryState(val percent: Int, val charging: Boolean, val powerSave: Boolean)

@Singleton
class BatteryMonitor @Inject constructor(@ApplicationContext private val context: Context) {
    private val _state = MutableStateFlow(BatteryState(100, true, false))
    val state: StateFlow<BatteryState> = _state.asStateFlow()

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) { update(intent) }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        val sticky = ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        sticky?.let { update(it) }
    }

    private fun update(intent: Intent) {
        val pm = context.getSystemService(PowerManager::class.java)
        if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            _state.value = BatteryState(if (level < 0) _state.value.percent else level * 100 / scale, charging, pm.isPowerSaveMode)
        } else {
            _state.value = _state.value.copy(powerSave = pm.isPowerSaveMode)
        }
    }
}

data class StorageStats(val freeBytes: Long, val totalBytes: Long) {
    val usedBytes: Long get() = totalBytes - freeBytes
}

@Singleton
class StorageInfo @Inject constructor(@ApplicationContext private val context: Context) {
    /** Free/total space of the volume holding public Downloads (the default destination). */
    fun primary(): StorageStats {
        val dir = context.getExternalFilesDir(null) ?: Environment.getDataDirectory()
        return runCatching { StatFs(dir.path).let { StorageStats(it.availableBytes, it.totalBytes) } }.getOrDefault(StorageStats(0, 0))
    }
}
