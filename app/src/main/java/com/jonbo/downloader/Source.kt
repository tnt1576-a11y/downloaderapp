package com.jonbo.downloader

/** A site the app can download from. Adding another one is a single entry here. */
enum class Source(
    val key: String,
    val label: String,
    val hint: String,
    /** Sites that serve a real ladder of resolutions get a quality picker. */
    val pickQuality: Boolean,
    private val hosts: List<String>,
) {
    YOUTUBE(
        key = "youtube",
        label = "YouTube",
        hint = "Paste a YouTube video or Shorts link",
        pickQuality = true,
        hosts = listOf("youtube.com", "youtu.be"),
    ),
    INSTAGRAM(
        key = "instagram",
        label = "Instagram",
        hint = "Paste a Reel or post link",
        pickQuality = false,
        hosts = listOf("instagram.com", "instagr.am", "ddinstagram.com"),
    ),
    X(
        key = "x",
        label = "X",
        hint = "Paste a post link from X (Twitter)",
        pickQuality = true,
        hosts = listOf("x.com", "twitter.com", "fxtwitter.com", "vxtwitter.com"),
    ),
    TIKTOK(
        key = "tiktok",
        label = "TikTok",
        hint = "Paste a TikTok video link",
        pickQuality = true,
        hosts = listOf("tiktok.com"),
    );

    fun matches(url: String): Boolean {
        val host = hostOf(url) ?: return false
        // Covers subdomains too: m./music./vm./vt. all end with ".<host>".
        return hosts.any { host == it || host.endsWith(".$it") }
    }

    companion object {
        /** Route key for the "figure it out from the link" screen. */
        const val AUTO_KEY = "auto"

        /** Null means the auto-detect screen rather than a specific site. */
        fun fromKey(key: String?): Source? = entries.firstOrNull { it.key == key }

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
