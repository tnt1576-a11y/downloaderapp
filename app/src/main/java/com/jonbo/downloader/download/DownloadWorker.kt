package com.jonbo.downloader.download

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import kotlin.math.roundToInt

/**
 * Runs one yt-dlp download as expedited foreground work, then publishes the result to shared
 * storage. Progress is reported both to a notification and to the UI via [setProgressAsync].
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val notificationId = id.hashCode()
    private val notifier = NotificationManagerCompat.from(applicationContext)

    private val title get() = inputData.getString(KEY_TITLE).orEmpty().ifBlank { "Video" }
    private val audioOnly get() = inputData.getBoolean(KEY_AUDIO_ONLY, false)

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0f)

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Missing link"))
        val selector = inputData.getString(KEY_SELECTOR) ?: "best"

        setForeground(foregroundInfo(0f))

        val workDir = File(applicationContext.cacheDir, "downloads/$id").apply { mkdirs() }

        return try {
            Ytdlp.ensureReady(applicationContext)
            runDownload(url, selector, workDir)

            val produced = workDir.walkTopDown()
                .filter { it.isFile && it.extension != "part" }
                .maxByOrNull { it.length() }
                ?: error("yt-dlp finished but produced no file")

            val uri = MediaSaver.publish(applicationContext, produced, audioOnly)
            notifyFinished(uri)

            Result.success(workDataOf(KEY_TITLE to title, KEY_URI to uri.toString()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for $url", e)
            Result.failure(workDataOf(KEY_TITLE to title, KEY_ERROR to friendlyError(e)))
        } finally {
            workDir.deleteRecursively()
            notifier.cancel(notificationId)
        }
    }

    private suspend fun runDownload(url: String, selector: String, workDir: File) {
        val request = YoutubeDLRequest(url).apply {
            addOption("-f", selector)
            addOption("-o", "${workDir.absolutePath}/%(title).100s.%(ext)s")
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--restrict-filenames")
            addOption("--socket-timeout", "20")
            addOption("--retries", "5")
            if (inputData.getBoolean(KEY_MERGE, false) && !audioOnly) {
                addOption("--merge-output-format", "mp4")
            }
        }

        var lastShownPercent = -1
        try {
            runInterruptible(Dispatchers.IO) {
                YoutubeDL.getInstance().execute(request, id.toString()) { progress, _, line ->
                    val percent = progress.coerceIn(0f, 100f)
                    setProgressAsync(workDataOf(KEY_TITLE to title, KEY_PROGRESS to percent))

                    val rounded = percent.roundToInt()
                    if (rounded != lastShownPercent) {
                        lastShownPercent = rounded
                        runCatching { notifier.notify(notificationId, buildNotification(percent)) }
                    }
                    if (line.isNotBlank()) Log.v(TAG, line)
                }
            }
        } catch (e: InterruptedException) {
            YoutubeDL.getInstance().destroyProcessById(id.toString())
            throw CancellationException("Download cancelled")
        }
    }

    private fun foregroundInfo(percent: Float) = ForegroundInfo(
        notificationId,
        buildNotification(percent),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    private fun buildNotification(percent: Float): Notification {
        val indeterminate = percent <= 0f
        return NotificationCompat.Builder(applicationContext, Notifications.PROGRESS_CHANNEL)
            .setContentTitle(title)
            .setContentText(if (indeterminate) "Starting…" else "${percent.roundToInt()}%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.roundToInt(), indeterminate)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                androidx.work.WorkManager.getInstance(applicationContext)
                    .createCancelPendingIntent(id),
            )
            .build()
    }

    private fun notifyFinished(uri: Uri) {
        val open = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pending = PendingIntent.getActivity(
            applicationContext,
            notificationId,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, Notifications.DONE_CHANNEL)
            .setContentTitle("Saved")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { notifier.notify(notificationId + 1, notification) }
    }

    /** yt-dlp's stderr is verbose; surface the last "ERROR:" line, which is the useful bit. */
    private fun friendlyError(e: Exception): String {
        val raw = e.message.orEmpty()
        val errorLine = raw.lineSequence().lastOrNull { it.contains("ERROR:") }
        return (errorLine ?: raw.lineSequence().lastOrNull { it.isNotBlank() } ?: "Download failed")
            .substringAfter("ERROR:")
            .trim()
            .take(300)
    }

    companion object {
        private const val TAG = "DownloadWorker"

        const val TAG_DOWNLOAD = "download"

        const val KEY_URL = "url"
        const val KEY_SELECTOR = "selector"
        const val KEY_TITLE = "title"
        const val KEY_MERGE = "merge"
        const val KEY_AUDIO_ONLY = "audio_only"
        const val KEY_PROGRESS = "progress"
        const val KEY_URI = "uri"
        const val KEY_ERROR = "error"
    }
}
