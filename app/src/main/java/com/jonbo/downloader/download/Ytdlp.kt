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
                Log.i(TAG, "engine ready, yt-dlp ${YoutubeDL.getInstance().version(context)}")
            }
            ready = true
        }
    }

    /**
     * Intentionally absent: [YoutubeDL.updateYoutubeDL]. It downloads a yt-dlp binary from
     * GitHub with no signature or checksum verification and runs it through ProcessBuilder.
     * This app only ever executes the engine bundled in the APK, so the dependency version in
     * app/build.gradle.kts is the single source of truth for which yt-dlp is running.
     */
}
