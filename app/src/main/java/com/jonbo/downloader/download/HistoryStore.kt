package com.jonbo.downloader.download

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryEntry(
    val id: String,
    val title: String,
    val url: String,
    val thumbnail: String?,
    val uri: String?,
    val savedAt: Long,
    val audioOnly: Boolean,
    val quality: String,
)

/**
 * A durable list of what has been downloaded.
 *
 * The Downloads section on the home screen is derived from WorkManager, which prunes finished
 * work and is cleared by "Clear finished" — fine for showing what is happening now, useless as
 * a record. This keeps its own copy in a JSON file. A few hundred rows of scalars doesn't
 * warrant Room and the KSP plugin that comes with it.
 */
object HistoryStore {

    private const val TAG = "HistoryStore"
    private const val FILE = "history.json"
    private const val LIMIT = 500

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries

    @Volatile
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _entries.value = runCatching { read(file(context)) }.getOrElse {
                Log.w(TAG, "Could not read history", it)
                emptyList()
            }
            loaded = true
        }
    }

    suspend fun add(context: Context, entry: HistoryEntry) = withContext(Dispatchers.IO) {
        load(context)
        // Newest first, and one row per saved file.
        val merged = (listOf(entry) + _entries.value.filterNot { it.uri == entry.uri })
            .take(LIMIT)
        _entries.value = merged
        write(file(context), merged)
    }

    suspend fun remove(context: Context, id: String) = withContext(Dispatchers.IO) {
        load(context)
        val merged = _entries.value.filterNot { it.id == id }
        _entries.value = merged
        write(file(context), merged)
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        _entries.value = emptyList()
        write(file(context), emptyList())
    }

    /** Removes the saved media as well as the row. Returns false if the file was already gone. */
    suspend fun deleteFile(context: Context, entry: HistoryEntry): Boolean =
        withContext(Dispatchers.IO) {
            val deleted = entry.uri?.let {
                runCatching { context.contentResolver.delete(Uri.parse(it), null, null) > 0 }
                    .getOrDefault(false)
            } ?: false
            remove(context, entry.id)
            deleted
        }

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun read(file: File): List<HistoryEntry> {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                HistoryEntry(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    url = o.optString("url"),
                    thumbnail = o.optString("thumbnail").takeIf { it.isNotBlank() },
                    uri = o.optString("uri").takeIf { it.isNotBlank() },
                    savedAt = o.optLong("savedAt"),
                    audioOnly = o.optBoolean("audioOnly"),
                    quality = o.optString("quality"),
                )
            }.getOrNull()
        }
    }

    private fun write(file: File, entries: List<HistoryEntry>) {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("title", e.title)
                    put("url", e.url)
                    put("thumbnail", e.thumbnail.orEmpty())
                    put("uri", e.uri.orEmpty())
                    put("savedAt", e.savedAt)
                    put("audioOnly", e.audioOnly)
                    put("quality", e.quality)
                }
            )
        }
        runCatching { file.writeText(array.toString()) }
            .onFailure { Log.w(TAG, "Could not write history", it) }
    }
}
