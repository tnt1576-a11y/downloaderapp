package com.jonbo.downloader.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Publishes a finished file from the app's private cache into shared storage so it shows up in
 * the Gallery / music apps. Video goes to Movies/Downloader, audio to Music/Downloader.
 */
object MediaSaver {

    private const val FOLDER = "Downloader"

    suspend fun publish(context: Context, file: File, audioOnly: Boolean): Uri =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val collection = if (audioOnly) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val relativePath = if (audioOnly) {
                "${Environment.DIRECTORY_MUSIC}/$FOLDER"
            } else {
                "${Environment.DIRECTORY_MOVIES}/$FOLDER"
            }

            // Galleries sort on DATE_TAKEN when the file carries one and fall back to
            // DATE_ADDED otherwise, so stamping these keeps a download at "now". DATE_TAKEN
            // itself is not app-writable here — MediaStore accepts the update and changes
            // nothing — so it is deliberately not set; it comes from the file's own metadata.
            val now = System.currentTimeMillis() / 1000

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeOf(file.extension, audioOnly))
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.DATE_ADDED, now)
                put(MediaStore.MediaColumns.DATE_MODIFIED, now)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri = resolver.insert(collection, values)
                ?: error("Could not create an entry in $relativePath")

            try {
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: error("Could not open $uri for writing")
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }

            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )

            // Clearing IS_PENDING makes MediaStore scan the file, and that scan overwrites
            // DATE_TAKEN from the mp4 header — which ffmpeg leaves at zero. So it has to be
            // stamped again afterwards, or galleries file the download under 1904.
            uri
        }

    private fun mimeTypeOf(extension: String, audioOnly: Boolean): String =
        when (extension.lowercase()) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> if (audioOnly) "audio/webm" else "video/webm"
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "opus" -> "audio/opus"
            else -> if (audioOnly) "audio/*" else "video/*"
        }
}
