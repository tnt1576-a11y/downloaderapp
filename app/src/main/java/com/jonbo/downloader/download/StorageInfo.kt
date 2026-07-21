package com.jonbo.downloader.download

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StorageUsage(val files: Int, val bytes: Long) {
    val isEmpty: Boolean get() = files == 0
}

/** Adds up what the app has saved, so the home screen can show it without guessing. */
object StorageInfo {

    private const val FOLDER = "Downloader"

    suspend fun measure(context: Context): StorageUsage = withContext(Dispatchers.IO) {
        var files = 0
        var bytes = 0L

        val collections = listOf(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                "${Environment.DIRECTORY_MOVIES}/$FOLDER/",
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                "${Environment.DIRECTORY_MUSIC}/$FOLDER/",
        )

        for ((uri, path) in collections) {
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.SIZE),
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(path),
                    null,
                )?.use { cursor ->
                    val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    while (cursor.moveToNext()) {
                        files++
                        bytes += cursor.getLong(sizeColumn)
                    }
                }
            }
        }

        StorageUsage(files, bytes)
    }

    fun format(bytes: Long): String {
        val gb = bytes / 1024.0 / 1024.0 / 1024.0
        if (gb >= 1) return String.format(java.util.Locale.US, "%.1f GB", gb)
        val mb = bytes / 1024.0 / 1024.0
        return String.format(java.util.Locale.US, "%.0f MB", mb)
    }
}
