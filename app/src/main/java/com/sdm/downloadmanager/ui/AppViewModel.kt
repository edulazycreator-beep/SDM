package com.sdm.downloadmanager.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sdm.core.data.clipboard.ClipboardInspector
import com.sdm.core.data.settings.AppSettings
import com.sdm.core.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A request to open the add-download dialog, from a paste, share, clipboard or the browser. */
data class AddRequest(
    val urls: List<String>,
    val headers: Map<String, String> = emptyMap(),
    val sourcePage: String? = null,
    val contentDisposition: String? = null,
    val mime: String? = null,
    val sizeHint: Long? = null,
    val fileNameHint: String? = null,
    /** The browser saw this URL used as a video stream (HLS playlist). */
    val streamHint: Boolean = false,
    /** Page or video title, used to name stream downloads. */
    val titleHint: String? = null,
)

@HiltViewModel
class AppViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val clipboard: ClipboardInspector,
) : ViewModel() {
    val settings: StateFlow<AppSettings?> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _addRequest = MutableStateFlow<AddRequest?>(null)
    val addRequest = _addRequest.asStateFlow()

    private val _clipboardSuggestion = MutableStateFlow<String?>(null)
    val clipboardSuggestion = _clipboardSuggestion.asStateFlow()

    private val _openDownload = MutableStateFlow<Long?>(null)
    val openDownload = _openDownload.asStateFlow()

    fun onWindowFocused() {
        viewModelScope.launch { clipboard.checkForNewUrl()?.let { _clipboardSuggestion.value = it } }
    }

    fun dismissClipboard() { _clipboardSuggestion.value = null }

    fun requestAdd(r: AddRequest) { _addRequest.value = r }
    fun dismissAdd() { _addRequest.value = null }

    /** User-triggered clipboard scan (spec section 9). */
    fun pasteFromClipboard(onEmpty: () -> Unit) {
        val urls = clipboard.readAllUrls()
        if (urls.isEmpty()) onEmpty() else requestAdd(AddRequest(urls))
    }

    fun openDownload(id: Long) { _openDownload.value = id }
    fun consumeOpenDownload() { _openDownload.value = null }

    fun completeOnboarding() = viewModelScope.launch { settingsRepo.update { it.copy(onboardingDone = true) } }
}
