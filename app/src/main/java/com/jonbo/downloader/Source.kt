package com.jonbo.downloader

/** A "function" of the app. Adding a new site later is just another entry here. */
enum class Source(
    val key: String,
    val label: String,
    val hint: String,
    /** YouTube offers real quality choices; Instagram serves a single rendition. */
    val pickQuality: Boolean,
    private val hosts: List<String>,
) {
    YOUTUBE(
        key = "youtube",
        label = "YouTube",
        hint = "Paste a YouTube video or Shorts link",
        pickQuality = true,
        hosts = listOf("youtube.com", "youtu.be", "m.youtube.com", "music.youtube.com"),
    ),
    INSTAGRAM(
        key = "instagram",
        label = "Instagram",
        hint = "Paste a Reel or post link",
        pickQuality = false,
        hosts = listOf("instagram.com", "instagr.am", "ddinstagram.com"),
    );

    fun matches(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return hosts.any { host == it || host.endsWith(".$it") }
    }

    companion object {
        fun fromKey(key: String?): Source = entries.firstOrNull { it.key == key } ?: YOUTUBE

        fun detect(url: String): Source? = entries.firstOrNull { it.matches(url) }

        private fun hostOf(url: String): String? = runCatching {
            val withScheme = if (url.contains("://")) url else "https://$url"
            java.net.URI(withScheme.trim()).host?.lowercase()?.removePrefix("www.")
        }.getOrNull()
    }
}

/** Pulls the first http(s) URL out of shared text, which is often "Watch this! <url>". */
fun extractUrl(text: String?): String? {
    if (text.isNullOrBlank()) return null
    return Regex("""https?://\S+""").find(text)?.value?.trimEnd('.', ',', ')', '"', '\'')
}
