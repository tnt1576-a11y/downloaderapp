package com.jonbo.downloader.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService

object Notifications {

    const val PROGRESS_CHANNEL = "downloads_progress"
    const val DONE_CHANNEL = "downloads_done"

    fun createChannels(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                PROGRESS_CHANNEL,
                "Download progress",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                DONE_CHANNEL,
                "Finished downloads",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }
}
