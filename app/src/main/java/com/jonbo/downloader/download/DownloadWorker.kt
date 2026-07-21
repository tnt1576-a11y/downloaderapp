package com.jonbo.downloader.download

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
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

    /** Whatever yt-dlp said about not being able to merge, if anything. */
    @Volatile
    private var mergeWarning: String? = null

    @Volatile
    private var speed: String = ""

    @Volatile
    private var eta: String = ""

    /**
     * Smoothed transfer rate in bytes/s.
     *
     * yt-dlp fetches YouTube in ~10MB chunks to sidestep throttling, and each chunk is a new
     * connection starting from TCP slow-start — so its reported rate saws between ~20KiB/s and
     * full line speed every few seconds. Measured on one download: 0.02 → 4.3 → 0.02 → 4.1
     * MiB/s, over and over. Showing that raw number reads as a broken connection, when the
     * effective throughput is steady. An exponential moving average shows what's actually
     * being achieved, which is what every browser download UI does too.
     */
    @Volatile
    private var emaBytesPerSec: Double = 0.0

    @Volatile
    private var streamTotalBytes: Double = 0.0

    /**
     * Which part of the work is running: "Video", "Audio", "Combining" or "Converting".
     *
     * HD downloads are two separate transfers (video stream, then audio stream), so the raw
     * progress bar fills to 100% and then starts over — which reads as "it downloaded twice".
     * Naming the phase is what makes that comprehensible.
     */
    @Volatile
    private var stage: String = ""

    @Volatile
    private var streamsSeen: Int = 0

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

            val files = workDir.walkTopDown()
                .filter { it.isFile && it.extension != "part" }
                .toList()
            if (files.isEmpty()) error("yt-dlp finished but produced no file")

            val produced = files.maxByOrNull { it.length() }!!

            // Without ffmpeg yt-dlp does not fail: it warns, skips merging and exits 0,
            // sometimes leaving both streams and sometimes falling back to a single
            // video-only format. Counting files misses the second case, so check the actual
            // result — a video we hand back must really contain an audio track.
            val expectsAudio = inputData.getBoolean(KEY_EXPECT_AUDIO, true)
            if (!audioOnly && expectsAudio && !hasAudioTrack(produced)) {
                error(
                    "The download has no audio track" +
                        (mergeWarning?.let { ": $it" } ?: " — ffmpeg did not merge the streams") +
                        ". Nothing was saved."
                )
            }

            val uri = MediaSaver.publish(applicationContext, produced, audioOnly)

            HistoryStore.add(
                applicationContext,
                HistoryEntry(
                    id = id.toString(),
                    title = title,
                    url = url,
                    thumbnail = inputData.getString(KEY_THUMB),
                    uri = uri.toString(),
                    savedAt = System.currentTimeMillis(),
                    audioOnly = audioOnly,
                    quality = inputData.getString(KEY_LABEL).orEmpty(),
                ),
            )

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

    /**
     * True when the file really carries an audio stream. Uses the platform extractor rather
     * than trusting yt-dlp's exit code, which is 0 even when merging was skipped.
     */
    private fun hasAudioTrack(file: File): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).any { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            }
        } catch (e: Exception) {
            // Unreadable container (mkv/webm variants) — don't block a download over it.
            Log.w(TAG, "Could not inspect tracks in ${file.name}", e)
            true
        } finally {
            runCatching { extractor.release() }
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
            // Fragmented streams (Twitch VODs, HLS/DASH sites) are thousands of small files;
            // fetching a few at once is the difference between minutes and an hour. No effect
            // on YouTube's ranged downloads.
            addOption("--concurrent-fragments", "4")
            if (inputData.getBoolean(KEY_MERGE, false) && !audioOnly) {
                addOption("--merge-output-format", "mp4")
            }
            // Music mode: hand back an mp3 that looks right in a music player, with the
            // video's own thumbnail as cover art and the title/artist filled in.
            if (audioOnly && inputData.getBoolean(KEY_MP3, false)) {
                addOption("--extract-audio")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "0")
                addOption("--embed-thumbnail")
                addOption("--add-metadata")
            }
        }

        var lastShownPercent = -1
        try {
            runInterruptible(Dispatchers.IO) {
                YoutubeDL.getInstance().execute(request, id.toString()) { progress, _, line ->
                    val percent = progress.coerceIn(0f, 100f)

                    // yt-dlp prints e.g. "[download] 12.5% of 143.55MiB at 4.61MiB/s ETA 00:27".
                    SPEED.find(line)?.let {
                        val raw = it.groupValues[1].toDouble() * unitBytes(it.groupValues[2])
                        emaBytesPerSec = if (emaBytesPerSec <= 0) raw
                        else EMA_WEIGHT * raw + (1 - EMA_WEIGHT) * emaBytesPerSec
                        speed = formatRate(emaBytesPerSec)
                    }
                    SIZE.find(line)?.let {
                        streamTotalBytes = it.groupValues[1].toDouble() * unitBytes(it.groupValues[2])
                    }
                    // ETA from the smoothed rate; yt-dlp's own swings as wildly as its speed.
                    eta = if (emaBytesPerSec > 0 && streamTotalBytes > 0 && percent < 100f) {
                        val remaining = streamTotalBytes * (1 - percent / 100.0)
                        formatEta((remaining / emaBytesPerSec).toLong())
                    } else {
                        ""
                    }

                    when {
                        // Each stream announces itself once; the first is video, the second audio.
                        line.contains("Destination:") -> {
                            streamsSeen++
                            if (inputData.getBoolean(KEY_MERGE, false) && !audioOnly) {
                                stage = if (streamsSeen == 1) "Video" else "Audio"
                            }
                        }

                        line.contains("[Merger]") -> stage = "Combining"
                        line.contains("[ExtractAudio]") -> stage = "Converting"
                    }

                    setProgressAsync(
                        workDataOf(
                            KEY_TITLE to title,
                            KEY_PROGRESS to percent,
                            KEY_SPEED to speed,
                            KEY_ETA to eta,
                            KEY_STAGE to stage,
                        )
                    )

                    val rounded = percent.roundToInt()
                    if (rounded != lastShownPercent) {
                        lastShownPercent = rounded
                        runCatching { notifier.notify(notificationId, buildNotification(percent)) }
                    }
                    if (line.isNotBlank()) {
                        Log.v(TAG, line)
                        // yt-dlp only WARNs when it cannot merge, so keep the reason around
                        // to put in the error the user actually sees.
                        if (MERGE_TROUBLE.containsMatchIn(line)) {
                            mergeWarning = line.trim().removePrefix("WARNING:").trim().take(200)
                        }
                    }
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
            .setContentText(
                when {
                    indeterminate -> "Starting…"
                    stage.isNotBlank() -> "$stage · ${percent.roundToInt()}%"
                    else -> "${percent.roundToInt()}%"
                }
            )
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

    private fun friendlyError(e: Exception): String = FriendlyError.of(e.message)

    companion object {
        private const val TAG = "DownloadWorker"

        const val KEY_SPEED = "speed"
        const val KEY_ETA = "eta"
        const val KEY_STAGE = "stage"

        private val SPEED = Regex("""\bat\s+([\d.]+)\s*([KMG]?)i?B/s""", RegexOption.IGNORE_CASE)
        private val SIZE = Regex("""\bof\s+~?([\d.]+)\s*([KMG]?)i?B\b""", RegexOption.IGNORE_CASE)

        /** How quickly the average follows the raw rate; lower is smoother. */
        private const val EMA_WEIGHT = 0.18

        private fun unitBytes(unit: String): Double = when (unit.uppercase()) {
            "K" -> 1024.0
            "M" -> 1024.0 * 1024
            "G" -> 1024.0 * 1024 * 1024
            else -> 1.0
        }

        private fun formatRate(bytesPerSec: Double): String {
            val mib = bytesPerSec / (1024 * 1024)
            return if (mib >= 1) String.format(java.util.Locale.US, "%.1f MB/s", mib)
            else String.format(java.util.Locale.US, "%.0f KB/s", bytesPerSec / 1024)
        }

        private fun formatEta(seconds: Long): String {
            if (seconds <= 0 || seconds > 6 * 3600) return ""
            val m = seconds / 60
            val s = seconds % 60
            return String.format(java.util.Locale.US, "%d:%02d", m, s)
        }

        /** Lines yt-dlp emits when the merge step cannot run. */
        private val MERGE_TROUBLE =
            Regex("""ffmpeg|ffprobe|won't be merged|not be merged|not installed""", RegexOption.IGNORE_CASE)

        const val TAG_DOWNLOAD = "download"
        const val KEY_EXPECT_AUDIO = "expect_audio"
        const val KEY_THUMB = "thumbnail"
        const val KEY_LABEL = "quality_label"
        const val KEY_MP3 = "to_mp3"

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
