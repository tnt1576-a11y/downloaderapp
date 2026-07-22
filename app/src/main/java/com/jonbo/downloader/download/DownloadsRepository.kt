package com.jonbo.downloader.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Everything needed to run a download again, recovered from the work's tags. */
data class DownloadRequest(
    val url: String,
    val title: String,
    val option: QualityOption,
    val thumbnail: String?,
)

data class DownloadItem(
    val id: UUID,
    val title: String,
    val thumbnail: String?,
    val progress: Float,
    /** Live transfer rate, e.g. "4.61MiB/s". Empty unless the download is running. */
    val speed: String,
    /** Remaining time as yt-dlp reports it, e.g. "00:27". */
    val eta: String,
    /** Which part is running: "Video", "Audio", "Combining", "Converting", or empty. */
    val stage: String,
    val state: Status,
    val uri: String?,
    val error: String?,
    /** Present when we can rebuild the job, which is what powers "Retry". */
    val request: DownloadRequest?,
) {
    enum class Status { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

    val isActive: Boolean get() = state == Status.QUEUED || state == Status.RUNNING
    val canRetry: Boolean
        get() = request != null && (state == Status.FAILED || state == Status.CANCELLED)
}

class DownloadsRepository(context: Context) {

    private val workManager = WorkManager.getInstance(context)

    fun enqueue(
        url: String,
        title: String,
        option: QualityOption,
        thumbnail: String?,
        mp3: Boolean = false,
    ): UUID {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_URL to url,
                    DownloadWorker.KEY_SELECTOR to option.selector,
                    DownloadWorker.KEY_TITLE to title,
                    DownloadWorker.KEY_MERGE to option.needsMerge,
                    DownloadWorker.KEY_AUDIO_ONLY to option.audioOnly,
                    DownloadWorker.KEY_EXPECT_AUDIO to option.expectsAudio,
                    DownloadWorker.KEY_THUMB to thumbnail,
                    DownloadWorker.KEY_LABEL to option.label,
                    DownloadWorker.KEY_MP3 to (mp3 && option.audioOnly),
                )
            )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            // Queued items retry until the single download slot frees. Linear/10s keeps a
            // playlist moving; the default exponential backoff starts at 30s and doubles,
            // which would leave the tail of a long queue idle for many minutes.
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(DownloadWorker.TAG_DOWNLOAD)
            // WorkInfo exposes tags but not input data, so the details we need for ordering
            // and for retrying a finished job ride along as tags.
            .addTag("$TS${System.currentTimeMillis()}")
            .addTag("$URL$url")
            .addTag("$TITLE$title")
            .addTag("$SELECTOR${option.selector}")
            .addTag("$LABEL${option.label}")
            .addTag("$MERGE${option.needsMerge}")
            .addTag("$AUDIO${option.audioOnly}")
            .addTag("$EXPECT${option.expectsAudio}")
            .apply { if (!thumbnail.isNullOrBlank()) addTag("$THUMB$thumbnail") }
            .build()

        workManager.enqueue(request)
        return request.id
    }

    fun retry(item: DownloadItem): UUID? {
        val request = item.request ?: return null
        return enqueue(request.url, request.title, request.option, request.thumbnail)
    }

    val downloads: Flow<List<DownloadItem>> =
        workManager.getWorkInfosByTagFlow(DownloadWorker.TAG_DOWNLOAD)
            .map { infos -> infos.sortedByDescending(::enqueuedAt).map(::toItem) }

    fun cancel(id: UUID) {
        workManager.cancelWorkById(id)
    }

    /** Drops finished/failed rows from the list (files are already saved to storage). */
    fun clearFinished() {
        workManager.pruneWork()
    }

    private fun WorkInfo.tag(prefix: String): String? =
        tags.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)

    private fun enqueuedAt(info: WorkInfo): Long = info.tag(TS)?.toLongOrNull() ?: 0L

    private fun toItem(info: WorkInfo): DownloadItem {
        val title = info.tag(TITLE)
            ?: info.progress.getString(DownloadWorker.KEY_TITLE)
            ?: info.outputData.getString(DownloadWorker.KEY_TITLE)
            ?: "Video"

        val url = info.tag(URL)
        val selector = info.tag(SELECTOR)
        val thumbnail = info.tag(THUMB)
        val request = if (url != null && selector != null) {
            DownloadRequest(
                url = url,
                title = title,
                thumbnail = thumbnail,
                option = QualityOption(
                    label = info.tag(LABEL).orEmpty(),
                    detail = "",
                    selector = selector,
                    needsMerge = info.tag(MERGE).toBoolean(),
                    audioOnly = info.tag(AUDIO).toBoolean(),
                    expectsAudio = info.tag(EXPECT)?.toBoolean() ?: true,
                ),
            )
        } else {
            null
        }

        return DownloadItem(
            id = info.id,
            title = title,
            thumbnail = thumbnail,
            progress = info.progress.getFloat(DownloadWorker.KEY_PROGRESS, 0f),
            speed = info.progress.getString(DownloadWorker.KEY_SPEED).orEmpty(),
            eta = info.progress.getString(DownloadWorker.KEY_ETA).orEmpty(),
            stage = info.progress.getString(DownloadWorker.KEY_STAGE).orEmpty(),
            state = when (info.state) {
                WorkInfo.State.RUNNING -> DownloadItem.Status.RUNNING
                WorkInfo.State.SUCCEEDED -> DownloadItem.Status.DONE
                WorkInfo.State.FAILED -> DownloadItem.Status.FAILED
                WorkInfo.State.CANCELLED -> DownloadItem.Status.CANCELLED
                else -> DownloadItem.Status.QUEUED
            },
            uri = info.outputData.getString(DownloadWorker.KEY_URI),
            error = info.outputData.getString(DownloadWorker.KEY_ERROR),
            request = request,
        )
    }

    private companion object {
        const val TS = "ts:"
        const val URL = "url:"
        const val TITLE = "title:"
        const val SELECTOR = "sel:"
        const val LABEL = "label:"
        const val MERGE = "merge:"
        const val AUDIO = "audio:"
        const val EXPECT = "expect:"
        const val THUMB = "thumb:"
    }
}
