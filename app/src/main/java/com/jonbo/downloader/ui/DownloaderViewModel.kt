package com.jonbo.downloader.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jonbo.downloader.Source
import com.jonbo.downloader.download.DownloadItem
import com.jonbo.downloader.download.DownloadsRepository
import com.jonbo.downloader.download.QualityOption
import com.jonbo.downloader.download.VideoDetails
import com.jonbo.downloader.download.VideoInfoRepo
import com.jonbo.downloader.download.Ytdlp
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

    /** yt-dlp engine state, driven by the explicit "check for update" button on the home screen. */
    var engineVersion by mutableStateOf<String?>(null)
        private set

    var engineUpdating by mutableStateOf(false)
        private set

    var engineMessage by mutableStateOf<String?>(null)
        private set

    private var openedFor: Source? = null
    private var fetchJob: Job? = null

    /** Shown under the engine version so a broken ffmpeg is visible instead of silent. */
    var engineDetail by mutableStateOf("")
        private set

    init {
        engineVersion = Ytdlp.installedVersion(app)
        // ffmpegStatus waits for init, so it has to run off the main thread.
        viewModelScope.launch { engineDetail = Ytdlp.ffmpegStatus(getApplication()) }
    }

    fun updateEngine() {
        if (engineUpdating) return
        engineUpdating = true
        engineMessage = null
        viewModelScope.launch {
            engineMessage = when (val result = Ytdlp.update(getApplication())) {
                is Ytdlp.UpdateResult.Updated -> {
                    engineVersion = result.version
                    "Updated to ${result.version ?: "latest"}"
                }

                Ytdlp.UpdateResult.AlreadyCurrent -> "Already up to date"
                is Ytdlp.UpdateResult.Failed -> "Update failed: ${result.message}"
            }
            engineDetail = Ytdlp.ffmpegStatus(getApplication())
            engineUpdating = false
        }
    }

    fun dismissEngineMessage() {
        engineMessage = null
    }

    /**
     * Called when a download screen appears; wipes state left over from the other source.
     * A null source is the auto-detect screen.
     */
    fun onScreenOpened(source: Source?) {
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

    /** [source] is null on the auto-detect screen, where the site comes from the link itself. */
    fun fetch(source: Source?) {
        val target = url.trim()
        if (target.isEmpty()) {
            fetchState = FetchState.Error("Paste a link first")
            return
        }

        val resolved = source ?: Source.detect(target)
        if (resolved == null) {
            fetchState = FetchState.Error(
                "That link isn't from a supported site. " +
                    Source.entries.joinToString(", ") { it.label } + " are supported."
            )
            return
        }

        fetchJob?.cancel()
        fetchState = FetchState.Loading
        fetchJob = viewModelScope.launch {
            fetchState = try {
                FetchState.Ready(VideoInfoRepo.fetch(getApplication(), target, resolved))
            } catch (e: Exception) {
                FetchState.Error(readableError(e))
            }
        }
    }

    fun retry(item: DownloadItem) {
        repo.retry(item)
    }

    fun download(option: QualityOption) {
        val details = (fetchState as? FetchState.Ready)?.details ?: return
        repo.enqueue(details.url, details.title, option, details.thumbnail)
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
