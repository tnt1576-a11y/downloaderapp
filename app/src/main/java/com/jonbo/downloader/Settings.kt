package com.jonbo.downloader

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * What a shared link should do without opening a picker. [ASK] keeps the old behaviour of
 * showing the quality list; the rest start downloading straight away.
 */
enum class ShareAction(val label: String, val selector: String?, val audioOnly: Boolean = false) {
    ASK("Ask me each time", null),
    BEST("Best quality", "bestvideo*+bestaudio/best"),
    HD1080("Up to 1080p", "bestvideo*[height<=1080]+bestaudio/best[height<=1080]/best"),
    HD720("Up to 720p", "bestvideo*[height<=720]+bestaudio/best[height<=720]/best"),
    AUDIO("Audio only", "bestaudio/best", audioOnly = true),
}

/**
 * Small preference store. SharedPreferences rather than DataStore: there are a handful of
 * scalar settings, they are read on the main thread at startup, and this avoids another
 * dependency.
 */
class Settings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("downloader", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "SYSTEM") }
            .getOrDefault(ThemeMode.SYSTEM)
    )
    val theme: StateFlow<ThemeMode> = _theme

    private val _shareAction = MutableStateFlow(
        runCatching { ShareAction.valueOf(prefs.getString(KEY_SHARE, null) ?: "ASK") }
            .getOrDefault(ShareAction.ASK)
    )
    val shareAction: StateFlow<ShareAction> = _shareAction

    private val _mp3 = MutableStateFlow(prefs.getBoolean(KEY_MP3, true))

    /** Convert audio-only downloads to mp3 with artwork, rather than leaving them as m4a. */
    val mp3Audio: StateFlow<Boolean> = _mp3

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    fun setShareAction(action: ShareAction) {
        _shareAction.value = action
        prefs.edit().putString(KEY_SHARE, action.name).apply()
    }

    fun setMp3Audio(enabled: Boolean) {
        _mp3.value = enabled
        prefs.edit().putBoolean(KEY_MP3, enabled).apply()
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_SHARE = "share_action"
        const val KEY_MP3 = "mp3_audio"
    }
}
