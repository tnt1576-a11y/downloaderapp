package com.jonbo.downloader.download

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDate
import java.time.temporal.ChronoUnit

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
     *
     * Calls [ensureReady] first: the ffmpeg package is unpacked during init, so reading this
     * before init has run would always report "not unpacked".
     */
    suspend fun ffmpegStatus(context: Context): String {
        runCatching { ensureReady(context) }
        return runCatching {
            val binary = java.io.File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
            if (binary.exists()) "ffmpeg ready" else "ffmpeg missing"
        }.getOrElse { "ffmpeg unknown: ${it.message}" }
    }

    sealed interface UpdateResult {
        data class Updated(val version: String?, val verified: Boolean) : UpdateResult
        data object AlreadyCurrent : UpdateResult
        data class Failed(val message: String) : UpdateResult
    }

    /**
     * How old the running engine is, in days, from yt-dlp's YYYY.MM.DD version. Null when we
     * are still on the bundled copy or the version doesn't parse.
     */
    fun engineAgeDays(version: String?): Long? {
        val match = Regex("""(\d{4})\.(\d{2})\.(\d{2})""").find(version.orEmpty()) ?: return null
        val (y, m, d) = match.destructured
        return runCatching {
            ChronoUnit.DAYS.between(
                LocalDate.of(y.toInt(), m.toInt(), d.toInt()),
                LocalDate.now(),
            )
        }.getOrNull()
    }

    /**
     * Checks the freshly written binary against the SHA-256 that yt-dlp publishes for that
     * release.
     *
     * The wrapper downloads over HTTPS but verifies nothing, so a corrupted or substituted
     * binary would simply be executed. yt-dlp publishes SHA2-256SUMS (and signatures) for every
     * release, and this compares against that. Returns null when it matches, otherwise a
     * description of what went wrong.
     */
    private fun verifyAgainstUpstream(context: Context, version: String?): String? {
        if (version.isNullOrBlank()) return "no version reported"

        val binary = File(context.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp")
        if (!binary.exists()) return "downloaded binary not found"

        val expected = runCatching {
            val url = URL("https://github.com/yt-dlp/yt-dlp/releases/download/$version/SHA2-256SUMS")
            (url.openConnection() as HttpURLConnection).run {
                connectTimeout = 15_000
                readTimeout = 15_000
                inputStream.bufferedReader().use { it.readText() }
            }
        }.getOrElse { return "could not fetch upstream checksums (${it.message})" }
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.endsWith(" yt-dlp") }
            ?.substringBefore(' ')
            ?: return "upstream checksums did not list yt-dlp"

        val actual = MessageDigest.getInstance("SHA-256").let { digest ->
            binary.inputStream().use { stream ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

        return if (actual.equals(expected, ignoreCase = true)) {
            null
        } else {
            "checksum mismatch (expected ${expected.take(12)}…, got ${actual.take(12)}…)"
        }
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
                    // The library verifies nothing about what it just downloaded, so do it here.
                    val problem = verifyAgainstUpstream(context, version)
                    if (problem != null) Log.w(TAG, "engine verification: $problem")
                    UpdateResult.Updated(version, verified = problem == null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "yt-dlp update failed", e)
                UpdateResult.Failed(e.message?.lineSequence()?.firstOrNull()?.take(200) ?: "Failed")
            }
        }
    }
}
