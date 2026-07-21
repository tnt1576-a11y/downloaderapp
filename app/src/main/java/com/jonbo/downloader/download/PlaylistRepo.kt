package com.jonbo.downloader.download

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class PlaylistEntry(
    val title: String,
    val url: String,
    val thumbnail: String?,
)

data class PlaylistDetails(
    val title: String,
    val entries: List<PlaylistEntry>,
)

/**
 * Lists what's in a playlist without fetching each video's formats.
 *
 * `--flat-playlist` makes yt-dlp print one line of JSON per entry straight from the playlist
 * page, so a 200-video playlist costs one request instead of 200. The trade-off is that entries
 * carry no format list, which is why every entry downloads at "best" rather than a chosen
 * quality — probing each one would take minutes.
 */
object PlaylistRepo {

    /**
     * Cheap check on the link itself. Probing every link to find out would add a slow yt-dlp
     * round trip to every single fetch, so only links that look like playlists get probed.
     */
    fun looksLikePlaylist(url: String): Boolean {
        val lower = url.lowercase()
        return "list=" in lower ||
            "/playlist" in lower ||
            "/sets/" in lower ||
            "/album/" in lower
    }

    suspend fun fetch(context: Context, url: String): PlaylistDetails =
        withContext(Dispatchers.IO) {
            Ytdlp.ensureReady(context)

            val request = YoutubeDLRequest(url).apply {
                addOption("--flat-playlist")
                addOption("--dump-json")
                addOption("--yes-playlist")
                addOption("--socket-timeout", "20")
            }
            val output = YoutubeDL.getInstance().execute(request).out

            var playlistTitle = "Playlist"
            val entries = output.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("{") }
                .mapNotNull { line ->
                    runCatching {
                        val o = JSONObject(line)
                        o.optString("playlist_title").takeIf { it.isNotBlank() }
                            ?.let { playlistTitle = it }

                        val entryUrl = o.optString("url").takeIf { it.isNotBlank() }
                            ?: o.optString("webpage_url").takeIf { it.isNotBlank() }
                            ?: o.optString("id").takeIf { it.isNotBlank() }
                            ?: return@runCatching null

                        PlaylistEntry(
                            title = o.optString("title").ifBlank { "Untitled" },
                            url = entryUrl,
                            thumbnail = o.optString("thumbnail").takeIf { it.isNotBlank() },
                        )
                    }.getOrNull()
                }
                .toList()

            PlaylistDetails(title = playlistTitle, entries = entries)
        }
}
