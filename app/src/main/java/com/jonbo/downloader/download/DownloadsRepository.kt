package com.jonbo.downloader.download

import android.content.Context
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

data class DownloadItem(
    val id: UUID,
    val title: String,
    val progress: Float,
    val state: Status,
    val uri: String?,
    val error: String?,
) {
    enum class Status { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

    val isActive: Boolean get() = state == Status.QUEUED || state == Status.RUNNING
}

class DownloadsRepository(context: Context) {

    private val workManager = WorkManager.getInstance(context)

    fun enqueue(url: String, title: String, option: QualityOption): UUID {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_URL to url,
                    DownloadWorker.KEY_SELECTOR to option.selector,
                    DownloadWorker.KEY_TITLE to title,
                    DownloadWorker.KEY_MERGE to option.needsMerge,
                    DownloadWorker.KEY_AUDIO_ONLY to option.audioOnly,
                )
            )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(DownloadWorker.TAG_DOWNLOAD)
            // WorkInfo carries no enqueue timestamp, so smuggle one through a tag to keep the
            // on-screen list in the order the downloads were started.
            .addTag("$TS_TAG_PREFIX${System.currentTimeMillis()}")
            .build()

        workManager.enqueue(request)
        return request.id
    }

    val downloads: Flow<List<DownloadItem>> =
        workManager.getWorkInfosByTagFlow(DownloadWorker.TAG_DOWNLOAD)
            .map { infos -> infos.sortedByDescending(::enqueuedAt).map(::toItem) }

    fun cancel(id: UUID) {
        workManager.cancelWorkById(id)
    }

    /** Drops finished/failed rows from the list (they are already saved to storage). */
    fun clearFinished() {
        workManager.pruneWork()
    }

    private fun enqueuedAt(info: WorkInfo): Long =
        info.tags.firstOrNull { it.startsWith(TS_TAG_PREFIX) }
            ?.removePrefix(TS_TAG_PREFIX)
            ?.toLongOrNull()
            ?: 0L

    private fun toItem(info: WorkInfo): DownloadItem {
        val title = info.progress.getString(DownloadWorker.KEY_TITLE)
            ?: info.outputData.getString(DownloadWorker.KEY_TITLE)
            ?: "Video"

        return DownloadItem(
            id = info.id,
            title = title,
            progress = info.progress.getFloat(DownloadWorker.KEY_PROGRESS, 0f),
            state = when (info.state) {
                WorkInfo.State.RUNNING -> DownloadItem.Status.RUNNING
                WorkInfo.State.SUCCEEDED -> DownloadItem.Status.DONE
                WorkInfo.State.FAILED -> DownloadItem.Status.FAILED
                WorkInfo.State.CANCELLED -> DownloadItem.Status.CANCELLED
                else -> DownloadItem.Status.QUEUED
            },
            uri = info.outputData.getString(DownloadWorker.KEY_URI),
            error = info.outputData.getString(DownloadWorker.KEY_ERROR),
        )
    }

    private companion object {
        const val TS_TAG_PREFIX = "ts:"
    }
}
