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
        // Deliberately NOT calling yt-dlp's self-updater: it fetches a binary from GitHub with
        // no signature or checksum check and then executes it. We only ever run the engine
        // that shipped inside this APK. See the version pin in app/build.gradle.kts.
        appScope.launch {
            runCatching { Ytdlp.ensureReady(this@App) }
        }
    }
}
