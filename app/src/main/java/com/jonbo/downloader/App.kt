package com.jonbo.downloader

import android.app.Application
import com.jonbo.downloader.download.Notifications
import com.jonbo.downloader.download.Ytdlp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)

        // Unpacking the python/ffmpeg payload takes a few seconds on first launch, so get it
        // out of the way immediately instead of making the first download wait for it.
        //
        // Deliberately NOT calling yt-dlp's self-updater on launch: it fetches a binary from
        // GitHub and runs it. Updating is a button, and what it downloads is checksum-verified
        // against yt-dlp's published SHA-256 before it is kept. See Ytdlp.update.
        appScope.launch {
            runCatching { Ytdlp.ensureReady(this@App) }
        }
    }
}
