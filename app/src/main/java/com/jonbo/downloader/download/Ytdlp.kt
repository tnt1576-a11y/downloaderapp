package com.jonbo.downloader.download

import android.content.Context
import android.util.Log
import com.jonbo.downloader.R
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

    /**
     * The engine the APK itself carries. The wrapper library's own copy is from late 2025 and
     * YouTube no longer serves it video; app/src/main/res/raw/ytdlp overrides that resource
     * with this release, verified against yt-dlp's published SHA-256
     * (495be29ff4d9d4e9be7eabdfef225221e5d5282e77f2f505abc6dca80349f3fd) before bundling.
     * Keep this constant in step whenever that file is replaced.
     */
    const val BUNDLED_VERSION = "2026.07.04"

    private val initLock = Mutex()

    @Volatile
    private var ready = false

    suspend fun ensureReady(context: Context) {
        if (ready) return
        initLock.withLock {
            if (ready) return
            withContext(Dispatchers.IO) {
                dropStaleExtraction(context)
                YoutubeDL.getInstance().init(context)
                FFmpeg.getInstance().init(context)
                Log.i(TAG, "engine ready, yt-dlp ${installedVersion(context) ?: "(bundled)"}")
            }
            ready = true
        }
    }

    private fun enginePrefs(context: Context) =
        context.getSharedPreferences("engine", Context.MODE_PRIVATE)

    /**
     * The engine we last installed *and verified*, or null when the APK's own bundled copy is
     * what's running. Deliberately our own record rather than the library's: the library
     * writes its version whether or not anything checked the bytes, so trusting it would let
     * an unverified binary present itself as installed.
     */
    fun installedVersion(context: Context): String? =
        enginePrefs(context).getString(KEY_VERIFIED, null)

    /** What the library thinks is installed — used only to decide about re-extraction. */
    private fun libraryReportedVersion(context: Context): String? =
        runCatching { YoutubeDL.getInstance().version(context) }.getOrNull()

    /**
     * The library only extracts its bundled engine when the target folder is missing, so an
     * install that upgraded from an older APK would keep running the old copy it extracted
     * back then. When the APK's bundled version changes and the user has never run the
     * updater (which writes a version), drop the old extraction so init unpacks the new one.
     * An engine the updater installed is left alone — it's at least this new.
     */
    private fun dropStaleExtraction(context: Context) {
        val prefs = enginePrefs(context)
        if (prefs.getString(KEY_EXTRACTED_FOR, null) == BUNDLED_VERSION) return

        // Only wipe when nothing newer was deliberately installed. Checking both records
        // avoids downgrading someone who updated before this app tracked its own version.
        if (installedVersion(context) == null && libraryReportedVersion(context) == null) {
            runCatching {
                File(context.noBackupFilesDir, "youtubedl-android/yt-dlp").deleteRecursively()
            }.onFailure { Log.w(TAG, "could not drop stale engine extraction", it) }
        }
        prefs.edit().putString(KEY_EXTRACTED_FOR, BUNDLED_VERSION).apply()
    }

    private const val KEY_EXTRACTED_FOR = "bundled_extracted_for"
    private const val KEY_VERIFIED = "verified_version"

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
        /** Installed and checksum-verified. There is no unverified success case. */
        data class Updated(val version: String?) : UpdateResult
        data object AlreadyCurrent : UpdateResult

        /** Downloaded but failed verification; it was discarded and the bundle restored. */
        data class Rejected(val reason: String) : UpdateResult
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

    /** Where the engine binary lives once unpacked or updated. */
    private fun binaryFile(context: Context) =
        File(context.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The SHA-256 yt-dlp publishes for the `yt-dlp` asset of a release, or null. */
    private fun upstreamSha256(version: String): String? = runCatching {
        val url = URL("https://github.com/yt-dlp/yt-dlp/releases/download/$version/SHA2-256SUMS")
        val body = (url.openConnection() as HttpURLConnection).run {
            connectTimeout = 15_000
            readTimeout = 15_000
            inputStream.bufferedReader().use { it.readText() }
        }
        body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.endsWith(" yt-dlp") }
            ?.substringBefore(' ')
    }.getOrNull()

    /**
     * Overwrites the engine with the copy compiled into this APK, whose hash was checked
     * against upstream before it was bundled. Used to undo an update we could not verify,
     * so a rejected download is never left sitting there to be executed.
     */
    private fun restoreBundled(context: Context): Boolean = runCatching {
        val target = binaryFile(context)
        target.parentFile?.mkdirs()
        context.resources.openRawResource(R.raw.ytdlp).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        target.setExecutable(true, false)
        enginePrefs(context).edit().remove(KEY_VERIFIED).apply()
        true
    }.getOrElse {
        Log.e(TAG, "could not restore the bundled engine", it)
        false
    }

    /**
     * Downloads the latest yt-dlp, then keeps it only if it matches the SHA-256 that yt-dlp
     * publishes for that release.
     *
     * The wrapper library downloads over HTTPS and verifies nothing, so a corrupted or
     * substituted binary would simply be executed. yt-dlp publishes SHA2-256SUMS for every
     * release, so there is a published digest to check against — and this fails closed: if the
     * digest doesn't match, or can't be fetched at all, the download is discarded and the
     * engine bundled in the APK is put back. A version is only ever recorded as installed
     * after it has been verified.
     */
    suspend fun update(context: Context): UpdateResult {
        ensureReady(context)
        return withContext(Dispatchers.IO) {
            try {
                val status = YoutubeDL.getInstance().updateYoutubeDL(context)
                if (status?.name == "ALREADY_UP_TO_DATE") {
                    return@withContext UpdateResult.AlreadyCurrent
                }

                val version = runCatching { YoutubeDL.getInstance().version(context) }.getOrNull()
                val binary = binaryFile(context)

                if (version.isNullOrBlank() || !binary.exists()) {
                    restoreBundled(context)
                    return@withContext UpdateResult.Rejected(
                        "The update did not report a version, so it could not be verified."
                    )
                }

                val expected = upstreamSha256(version)
                if (expected == null) {
                    restoreBundled(context)
                    return@withContext UpdateResult.Rejected(
                        "Could not reach yt-dlp's published checksums for $version, so the " +
                            "download was discarded rather than trusted."
                    )
                }

                val actual = sha256(binary)
                if (!actual.equals(expected, ignoreCase = true)) {
                    Log.e(TAG, "engine checksum mismatch: expected $expected, got $actual")
                    restoreBundled(context)
                    return@withContext UpdateResult.Rejected(
                        "The downloaded engine did not match yt-dlp's published checksum. It " +
                            "has been discarded and the bundled engine restored."
                    )
                }

                Log.i(TAG, "yt-dlp $version verified against upstream SHA-256")
                enginePrefs(context).edit().putString(KEY_VERIFIED, version).apply()
                UpdateResult.Updated(version)
            } catch (e: Exception) {
                Log.w(TAG, "yt-dlp update failed", e)
                UpdateResult.Failed(e.message?.lineSequence()?.firstOrNull()?.take(200) ?: "Failed")
            }
        }
    }
}
