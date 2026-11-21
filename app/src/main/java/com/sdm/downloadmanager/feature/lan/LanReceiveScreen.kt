package com.sdm.downloadmanager.feature.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import com.sdm.core.designsystem.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sdm.core.common.FileNames
import com.sdm.core.data.download.DownloadRepository
import com.sdm.core.data.download.NewDownload
import com.sdm.core.data.lan.LanLinkServer
import com.sdm.core.designsystem.component.EmptyState
import com.sdm.downloadmanager.ui.LocalAppActions
import com.sdm.downloadmanager.ui.SdmScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress
import javax.inject.Inject

data class LanUi(val address: String? = null, val code: String = "", val received: Int = 0, val error: String? = null)

@HiltViewModel
class LanReceiveViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloads: DownloadRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(LanUi())
    val ui = _ui.asStateFlow()
    private var server: LanLinkServer? = null

    init { if (com.sdm.core.common.Entitlements.premium) start() }

    private fun localAddress(): InetAddress? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net)
        if (caps == null || !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return null
        return cm.getLinkProperties(net)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    }

    private suspend fun receive(links: List<String>): Int {
        var added = 0
        for (url in links) {
            val name = FileNames.sanitize(FileNames.fromUrl(url) ?: "download")
            if (downloads.findDuplicate(url, name, null) != null) continue
            runCatching { downloads.enqueue(NewDownload(url = url, fileName = name)); added++ }
        }
        _ui.value = _ui.value.copy(received = _ui.value.received + added)
        return added
    }

    private fun start() {
        val address = localAddress() ?: run { _ui.value = LanUi(error = "Connect the phone to Wi-Fi (the same network as the computer)."); return }
        val code = LanLinkServer.newCode()
        // Port 8765 when free, otherwise one the system picks.
        val s = listOf(PORT, 0).firstNotNullOfOrNull { port ->
            runCatching { LanLinkServer(address, port, code, ::receive).also { it.start(viewModelScope) } }.getOrNull()
        } ?: run { _ui.value = LanUi(error = "Could not open the local page on this network."); return }
        server = s
        _ui.value = LanUi(address = "http://${address.hostAddress}:${s.localPort}", code = code)
    }

    override fun onCleared() { server?.stop() }

    companion object { const val PORT = 8765 }
}

@Composable
fun LanReceiveScreen(vm: LanReceiveViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()
    val app = LocalAppActions.current
    SdmScaffold(title = "Send from computer", onBack = { app.nav.popBackStack() }) { p ->
        if (!com.sdm.downloadmanager.ui.LocalPremium.current) {
            com.sdm.downloadmanager.ui.PremiumUpsell(com.sdm.core.common.PremiumFeature.LAN, Modifier.padding(p).padding(16.dp))
            return@SdmScaffold
        }
        if (ui.address == null) {
            EmptyState(Icons.Outlined.WifiOff, "Not on Wi-Fi", ui.error ?: "Connect to Wi-Fi first.", Modifier.padding(p))
            return@SdmScaffold
        }
        Column(Modifier.fillMaxSize().padding(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("On a computer connected to the same Wi-Fi, open this address in a browser, paste links and enter the code.", style = MaterialTheme.typography.bodyMedium)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Address", style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(ui.address!!, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace) }
                    Text("Code", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                    Text(ui.code.chunked(3).joinToString(" "), fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }
            if (ui.received > 0) Text("${ui.received} link(s) received and added to Downloads.", color = MaterialTheme.colorScheme.primary)
            Text("The page works only while this screen is open. Anyone on the same network who knows the code could send links, so close this screen when you're done.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
