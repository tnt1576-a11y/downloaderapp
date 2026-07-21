package com.jonbo.downloader.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jonbo.downloader.Source
import com.jonbo.downloader.download.DownloadsRepository
import com.jonbo.downloader.download.QualityOption
import com.jonbo.downloader.download.VideoDetails
import com.jonbo.downloader.download.VideoInfoRepo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface FetchState {
    data object Idle : FetchState
    data object Loading : FetchState
    data class Error(val message: String) : FetchState
    data class Ready(val details: VideoDetails) : FetchState
}

class DownloaderViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = DownloadsRepository(app)

    val downloads = repo.downloads.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    var url by mutableStateOf("")
        private set

    var fetchState by mutableStateOf<FetchState>(FetchState.Idle)
        private set

    private var openedFor: Source? = null
    private var fetchJob: Job? = null

    /** Called when a download screen appears; wipes state left over from the other source. */
    fun onScreenOpened(source: Source) {
        if (openedFor != source) {
            openedFor = source
            reset()
        }
    }

    // Not named setUrl(): that clashes with the setter Compose generates for `var url`.
    fun onUrlChanged(value: String) {
        url = value.trim()
        // Any edit invalidates the info we fetched for the previous link.
        if (fetchState !is FetchState.Loading) fetchState = FetchState.Idle
    }

    /** Entry point for links arriving from the system share sheet. */
    fun acceptSharedLink(link: String, source: Source) {
        openedFor = source
        fetchJob?.cancel()
        url = link
        fetchState = FetchState.Idle
        fetch(source)
    }

    fun fetch(source: Source) {
        val target = url.trim()
        if (target.isEmpty()) {
            fetchState = FetchState.Error("Paste a link first")
            return
        }
        fetchJob?.cancel()
        fetchState = FetchState.Loading
        fetchJob = viewModelScope.launch {
            fetchState = try {
                FetchState.Ready(VideoInfoRepo.fetch(getApplication(), target, source))
            } catch (e: Exception) {
                FetchState.Error(readableError(e))
            }
        }
    }

    fun download(option: QualityOption) {
        val details = (fetchState as? FetchState.Ready)?.details ?: return
        repo.enqueue(details.url, details.title, option)
        reset()
    }

    fun cancel(id: UUID) = repo.cancel(id)

    fun clearFinished() = repo.clearFinished()

    fun reset() {
        fetchJob?.cancel()
        url = ""
        fetchState = FetchState.Idle
    }

    private fun readableError(e: Exception): String {
        val raw = e.message.orEmpty()
        val line = raw.lineSequence().lastOrNull { it.contains("ERROR:") }
            ?: raw.lineSequence().lastOrNull { it.isNotBlank() }
            ?: "Could not read that link"
        return line.substringAfter("ERROR:").trim().take(300)
    }
}
