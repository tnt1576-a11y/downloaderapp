package com.jonbo.downloader.download

import android.content.Context
import com.jonbo.downloader.Source
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/** One row in the quality picker. [selector] is passed straight to yt-dlp's `-f`. */
data class QualityOption(
    val label: String,
    val detail: String,
    val selector: String,
    val needsMerge: Boolean,
    val audioOnly: Boolean = false,
)

data class VideoDetails(
    val url: String,
    val title: String,
    val thumbnail: String?,
    val uploader: String?,
    val durationSeconds: Int,
    val options: List<QualityOption>,
)

/** yt-dlp reports a missing stream as the literal string "none". */
private fun String?.isNone(): Boolean = this == null || isBlank() || this == "none"

/** Wraps `yt-dlp --dump-json` and turns the raw format list into something pickable. */
object VideoInfoRepo {

    suspend fun fetch(context: Context, url: String, source: Source): VideoDetails {
        Ytdlp.ensureReady(context)

        val info: VideoInfo = withContext(Dispatchers.IO) {
            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--socket-timeout", "20")
            }
            YoutubeDL.getInstance().getInfo(request)
        }

        return VideoDetails(
            url = url,
            title = info.title ?: info.fulltitle ?: "Untitled",
            thumbnail = info.thumbnail,
            uploader = info.uploader,
            durationSeconds = info.duration,
            options = if (source.pickQuality) buildQualityOptions(info) else listOf(bestOption()),
        )
    }

    private fun bestOption() = QualityOption(
        label = "Best available",
        detail = "Highest quality yt-dlp can find",
        selector = "bestvideo*+bestaudio/best",
        needsMerge = true,
    )

    private fun buildQualityOptions(info: VideoInfo): List<QualityOption> {
        val formats = info.formats.orEmpty()

        // Best audio stream to pair with video-only renditions (everything above 720p on YouTube).
        val bestAudio = formats
            .filter { it.vcodec.isNone() && !it.acodec.isNone() }
            .maxByOrNull { it.tbr + if (it.ext == "m4a") 100_000 else 0 }

        val perResolution = formats
            .filter { it.height > 0 && !it.vcodec.isNone() }
            .groupBy { it.height }
            .mapNotNull { (height, candidates) ->
                val best = candidates.maxByOrNull(::compatibilityScore) ?: return@mapNotNull null
                val formatId = best.formatId ?: return@mapNotNull null
                val hasAudio = !best.acodec.isNone()

                val selector = when {
                    hasAudio -> formatId
                    bestAudio?.formatId != null -> "$formatId+${bestAudio.formatId}"
                    else -> "$formatId+bestaudio"
                }
                val bytes = sizeOf(best) + if (hasAudio) 0L else sizeOf(bestAudio)

                QualityOption(
                    label = "${height}p" + if (best.fps > 30) "${best.fps}" else "",
                    detail = listOfNotNull(
                        best.ext?.uppercase(Locale.US),
                        formatBytes(bytes),
                    ).joinToString(" · "),
                    selector = selector,
                    needsMerge = !hasAudio,
                )
            }
            .sortedByDescending { it.label.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

        val audioOnly = bestAudio?.formatId?.let {
            QualityOption(
                label = "Audio only",
                detail = listOfNotNull(
                    bestAudio.ext?.uppercase(Locale.US),
                    formatBytes(sizeOf(bestAudio)),
                ).joinToString(" · "),
                selector = it,
                needsMerge = false,
                audioOnly = true,
            )
        }

        // If the format list came back empty (rare, but possible), "best" still works.
        if (perResolution.isEmpty()) return listOfNotNull(bestOption(), audioOnly)

        return listOf(bestOption()) + perResolution + listOfNotNull(audioOnly)
    }

    /** Prefer widely-playable mp4/avc over the same resolution in vp9/av1, then higher bitrate. */
    private fun compatibilityScore(format: VideoFormat): Int {
        var score = format.tbr
        if (format.ext == "mp4") score += 100_000
        if (format.vcodec?.startsWith("avc") == true) score += 50_000
        return score
    }

    private fun sizeOf(format: VideoFormat?): Long {
        if (format == null) return 0L
        return if (format.fileSize > 0) format.fileSize else format.fileSizeApproximate
    }

    fun formatBytes(bytes: Long): String? {
        if (bytes <= 0) return null
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 1024) String.format(Locale.US, "%.2f GB", mb / 1024)
        else "${mb.roundToInt()} MB"
    }

    fun formatDuration(seconds: Int): String? {
        if (seconds <= 0) return null
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }
}
