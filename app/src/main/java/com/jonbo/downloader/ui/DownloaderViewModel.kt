package com.jonbo.downloader.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jonbo.downloader.Settings
import com.jonbo.downloader.Source
import com.jonbo.downloader.download.DownloadItem
import com.jonbo.downloader.download.DownloadsRepository
import com.jonbo.downloader.download.FriendlyError
import com.jonbo.downloader.download.HistoryEntry
import com.jonbo.downloader.download.HistoryStore
import com.jonbo.downloader.download.PlaylistDetails
import com.jonbo.downloader.download.PlaylistRepo
import com.jonbo.downloader.download.QualityOption
import com.jonbo.downloader.download.StorageInfo
import com.jonbo.downloader.download.StorageUsage
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

    /** The link isn't from a site we list, but yt-dlp may still know it. */
    data class Unsupported(val message: String) : FetchState

    data class Playlist(val details: PlaylistDetails) : FetchState
}

class DownloaderViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = DownloadsRepository(app)

    val settings = Settings(app)

    val history = HistoryStore.entries

    var storage by mutableStateOf(StorageUsage(0, 0))
        private set

    /** Non-null when the running engine is old enough to start breaking sites. */
    var engineStaleDays by mutableStateOf<Long?>(null)
        private set

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
        // No recorded version means the engine is the copy shipped inside this APK,
        // which has a known version — so staleness can be judged for it too.
        engineVersion = Ytdlp.installedVersion(app) ?: "${Ytdlp.BUNDLED_VERSION} (bundled)"
        refreshStaleness()
        HistoryStore.load(app)
        // ffmpegStatus waits for init, so it has to run off the main thread.
        viewModelScope.launch { engineDetail = Ytdlp.ffmpegStatus(getApplication()) }
        refreshStorage()
    }

    fun refreshStorage() {
        viewModelScope.launch { storage = StorageInfo.measure(getApplication()) }
    }

    private fun refreshStaleness() {
        // engineAgeDays finds the YYYY.MM.DD inside the string, so "(bundled)" suffixes are fine.
        val age = Ytdlp.engineAgeDays(engineVersion)
        engineStaleDays = age?.takeIf { it > STALE_AFTER_DAYS }
    }

    fun deleteHistoryEntry(entry: HistoryEntry, alsoFile: Boolean) {
        viewModelScope.launch {
            if (alsoFile) {
                HistoryStore.deleteFile(getApplication(), entry)
            } else {
                HistoryStore.remove(getApplication(), entry.id)
            }
            refreshStorage()
        }
    }

    fun clearHistory() {
        viewModelScope.launch { HistoryStore.clear(getApplication()) }
    }

    /** Re-runs a past download from the history screen. */
    fun redownload(entry: HistoryEntry) {
        url = entry.url
        fetchState = FetchState.Idle
    }

    fun updateEngine() {
        if (engineUpdating) return
        engineUpdating = true
        engineMessage = null
        viewModelScope.launch {
            engineMessage = when (val result = Ytdlp.update(getApplication())) {
                is Ytdlp.UpdateResult.Updated -> {
                    engineVersion = result.version
                    refreshStaleness()
                    "Updated to ${result.version ?: "latest"} · checksum verified"
                }

                Ytdlp.UpdateResult.AlreadyCurrent -> "Already up to date"

                is Ytdlp.UpdateResult.Rejected -> {
                    // The bundled engine is back in place, so reflect that.
                    engineVersion = "${Ytdlp.BUNDLED_VERSION} (bundled)"
                    refreshStaleness()
                    result.reason
                }

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

    /**
     * [source] is null on the auto-detect screen, where the site comes from the link itself.
     * [force] runs an unrecognised link through yt-dlp anyway — it knows far more sites than
     * the app lists.
     */
    fun fetch(source: Source?, force: Boolean = false) {
        val target = url.trim()
        if (target.isEmpty()) {
            fetchState = FetchState.Error("Paste a link first")
            return
        }

        val resolved = source ?: Source.detect(target)
        if (resolved == null && !force) {
            fetchState = FetchState.Unsupported(
                "That link isn't from one of the listed sites, but the engine supports over a " +
                    "thousand others."
            )
            return
        }

        fetchJob?.cancel()
        fetchState = FetchState.Loading
        fetchJob = viewModelScope.launch {
            fetchState = try {
                if (PlaylistRepo.looksLikePlaylist(target)) {
                    val playlist = PlaylistRepo.fetch(getApplication(), target)
                    // A "list=" link can still point at a single video.
                    if (playlist.entries.size > 1) {
                        FetchState.Playlist(playlist)
                    } else {
                        FetchState.Ready(single(target, resolved))
                    }
                } else {
                    FetchState.Ready(single(target, resolved))
                }
            } catch (e: Exception) {
                FetchState.Error(readableError(e))
            }
        }
    }

    private suspend fun single(target: String, resolved: Source?) =
        VideoInfoRepo.fetch(getApplication(), target, resolved ?: Source.YOUTUBE)

    /** Queues every entry in a playlist at best quality. */
    fun downloadPlaylist(details: PlaylistDetails) {
        val option = QualityOption(
            label = "Best available",
            detail = "",
            selector = "bestvideo*+bestaudio/best",
            needsMerge = true,
        )
        details.entries.forEach { entry ->
            repo.enqueue(entry.url, entry.title, option, entry.thumbnail, settings.mp3Audio.value)
        }
        reset()
    }

    fun retry(item: DownloadItem) {
        repo.retry(item)
    }

    fun download(option: QualityOption) {
        val details = (fetchState as? FetchState.Ready)?.details ?: return
        repo.enqueue(details.url, details.title, option, details.thumbnail, settings.mp3Audio.value)
        reset()
    }

    /**
     * Shared-link path. When a default quality is set, this queues the download immediately
     * and reports true so the caller can stay out of the way.
     */
    fun quickDownload(link: String, source: Source?): Boolean {
        val action = settings.shareAction.value
        val selector = action.selector ?: return false

        viewModelScope.launch {
            val details = runCatching {
                VideoInfoRepo.fetch(getApplication(), link, source ?: Source.YOUTUBE)
            }.getOrNull()

            repo.enqueue(
                url = link,
                title = details?.title ?: "Video",
                option = QualityOption(
                    label = action.label,
                    detail = "",
                    selector = selector,
                    needsMerge = !action.audioOnly,
                    audioOnly = action.audioOnly,
                    expectsAudio = details?.hasAudio ?: true,
                ),
                thumbnail = details?.thumbnail,
                mp3 = settings.mp3Audio.value,
            )
        }
        return true
    }

    fun cancel(id: UUID) = repo.cancel(id)

    fun clearFinished() = repo.clearFinished()

    fun reset() {
        fetchJob?.cancel()
        url = ""
        fetchState = FetchState.Idle
    }

    private fun readableError(e: Exception): String = FriendlyError.of(e.message)

    private companion object {
        /** yt-dlp releases weekly-ish; past this, sites start breaking. */
        const val STALE_AFTER_DAYS = 70L
    }
}
