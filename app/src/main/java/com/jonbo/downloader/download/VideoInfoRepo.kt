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
    /**
     * Whether the site actually offered any audio. Plenty of Reels and X posts are silent by
     * design, so the worker must only treat a missing audio track as a failure when there
     * was audio to be had in the first place.
     */
    val expectsAudio: Boolean = true,
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

/**
 * The number people mean by "1080p": the shorter side. Portrait clips (TikTok, Reels, Shorts)
 * are 1080x1920, and labelling those "1920p" would be wrong.
 */
private fun VideoFormat.qualityLines(): Int = if (width in 1 until height) width else height

/** Wraps `yt-dlp --dump-json` and turns the raw format list into something pickable. */
object VideoInfoRepo {

    /** Above this we're paying for surround audio nobody wants on a phone. */
    private const val MAX_AUDIO_KBPS = 140

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
            options = if (source.pickQuality) {
                buildQualityOptions(info)
            } else {
                listOf(bestOption(hasAudio(info)))
            },
        )
    }

    /** Did the site offer any audio at all for this item? */
    private fun hasAudio(info: VideoInfo): Boolean =
        info.formats.orEmpty().any { !it.acodec.isNone() }

    private fun bestOption(expectsAudio: Boolean) = QualityOption(
        label = "Best available",
        detail = "Highest quality yt-dlp can find",
        selector = "bestvideo*+bestaudio/best",
        needsMerge = true,
        expectsAudio = expectsAudio,
    )

    private fun buildQualityOptions(info: VideoInfo): List<QualityOption> {
        val formats = info.formats.orEmpty()
        val expectsAudio = hasAudio(info)

        // Audio stream to pair with video-only renditions (everything above 720p on YouTube).
        val bestAudio = pickAudio(formats)

        val perResolution = formats
            .filter { it.height > 0 && !it.vcodec.isNone() }
            .groupBy { it.qualityLines() }
            .mapNotNull { (lines, candidates) ->
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
                    label = "${lines}p" + if (best.fps > 30) "${best.fps}" else "",
                    detail = listOfNotNull(
                        best.ext?.uppercase(Locale.US),
                        formatBytes(bytes),
                    ).joinToString(" · "),
                    selector = selector,
                    needsMerge = !hasAudio,
                    expectsAudio = expectsAudio,
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
        if (perResolution.isEmpty()) return listOfNotNull(bestOption(expectsAudio), audioOnly)

        return listOf(bestOption(expectsAudio)) + perResolution + listOfNotNull(audioOnly)
    }

    /**
     * Picks the audio track to merge with video-only streams.
     *
     * Deliberately not "highest bitrate": YouTube also serves ~384kbps surround tracks, and
     * taking those made a 144p download 29MB of audio against 4MB of video. Stereo ~128kbps
     * is plenty next to a phone-sized picture, so take the best track at or below that and
     * only go higher when nothing smaller exists.
     */
    private fun pickAudio(formats: List<VideoFormat>): VideoFormat? {
        val audio = formats.filter { it.vcodec.isNone() && !it.acodec.isNone() }
        if (audio.isEmpty()) return null

        // m4a/AAC plays everywhere; fall back to whatever the site offers.
        val preferred = audio.filter { it.ext == "m4a" }.ifEmpty { audio }

        return preferred.filter { it.tbr in 1..MAX_AUDIO_KBPS }.maxByOrNull { it.tbr }
            ?: preferred.minByOrNull { if (it.tbr > 0) it.tbr else Int.MAX_VALUE }
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
