package com.jonbo.downloader.download

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the one-time initialisation of the bundled yt-dlp + ffmpeg binaries.
 *
 * Every entry point that touches the engine must call [ensureReady] first; the mutex makes it
 * safe to call from the UI and from a worker at the same time.
 */
object Ytdlp {

    private const val TAG = "Ytdlp"

    private val initLock = Mutex()

    @Volatile
    private var ready = false

    suspend fun ensureReady(context: Context) {
        if (ready) return
        initLock.withLock {
            if (ready) return
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().init(context)
                FFmpeg.getInstance().init(context)
                Log.i(TAG, "engine ready, yt-dlp ${installedVersion(context) ?: "(bundled)"}")
            }
            ready = true
        }
    }

    /** Whatever yt-dlp we last pulled, or null when we're still on the APK's bundled copy. */
    fun installedVersion(context: Context): String? =
        runCatching { YoutubeDL.getInstance().version(context) }.getOrNull()

    /**
     * Whether the ffmpeg binary yt-dlp shells out to is actually present. Without it yt-dlp
     * silently skips merging separate video and audio streams, so this is worth showing.
     */
    fun ffmpegStatus(context: Context): String = runCatching {
        val binary = java.io.File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val unpacked = java.io.File(
            context.noBackupFilesDir,
            "youtubedl-android/packages/ffmpeg",
        )
        when {
            !binary.exists() -> "ffmpeg missing (${binary.parentFile?.name})"
            !unpacked.exists() -> "ffmpeg not unpacked"
            else -> "ffmpeg ready"
        }
    }.getOrElse { "ffmpeg unknown: ${it.message}" }

    sealed interface UpdateResult {
        data class Updated(val version: String?) : UpdateResult
        data object AlreadyCurrent : UpdateResult
        data class Failed(val message: String) : UpdateResult
    }

    /**
     * Downloads the latest yt-dlp release and replaces the bundled engine.
     *
     * This is the one action in the app that fetches code and then executes it, and the library
     * does NOT verify a signature or checksum on the download (transport is HTTPS to GitHub).
     * It is therefore deliberately user-initiated only — never called on launch.
     */
    suspend fun update(context: Context): UpdateResult {
        ensureReady(context)
        return withContext(Dispatchers.IO) {
            try {
                val status = YoutubeDL.getInstance().updateYoutubeDL(context)
                val version = installedVersion(context)
                Log.i(TAG, "yt-dlp update: $status -> $version")
                if (status?.name == "ALREADY_UP_TO_DATE") {
                    UpdateResult.AlreadyCurrent
                } else {
                    UpdateResult.Updated(version)
                }
            } catch (e: Exception) {
                Log.w(TAG, "yt-dlp update failed", e)
                UpdateResult.Failed(e.message?.lineSequence()?.firstOrNull()?.take(200) ?: "Failed")
            }
        }
    }
}
