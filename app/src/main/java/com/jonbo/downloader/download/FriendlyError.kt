package com.jonbo.downloader.download

/**
 * Turns yt-dlp's raw stderr into something worth showing a person.
 *
 * yt-dlp's messages are accurate but written for a terminal: they name extractors, quote HTTP
 * codes and suggest command line flags. Each entry here pairs a recognisable fragment with a
 * plain sentence and, where there is one, the action actually worth taking. Anything we don't
 * recognise falls through to the tidied original rather than a vague "something went wrong".
 */
object FriendlyError {

    private data class Rule(val match: Regex, val message: String)

    private val rules = listOf(
        Rule(
            Regex("sign in to confirm .*(bot|not a robot)", RegexOption.IGNORE_CASE),
            "The site is asking to confirm you're not a bot. This usually clears up on a " +
                "different network, or after a while.",
        ),
        Rule(
            Regex("captcha", RegexOption.IGNORE_CASE),
            "The site answered with a captcha challenge instead of the video. Try a " +
                "different network, or again later.",
        ),
        Rule(
            Regex("(age.?restricted|confirm your age|inappropriate for some users)", RegexOption.IGNORE_CASE),
            "This post is age restricted, and the app doesn't sign in, so it can't be " +
                "downloaded.",
        ),
        Rule(
            Regex("(private (video|account)|login required|requires authentication|not authorized)", RegexOption.IGNORE_CASE),
            "This post is private. Only public posts can be downloaded, since the app " +
                "never signs in to your accounts.",
        ),
        Rule(
            Regex("(not available in your country|geo.?restricted|geo.?block|blocked it in your country)", RegexOption.IGNORE_CASE),
            "This post isn't available in your region. A different network or VPN region " +
                "may work.",
        ),
        Rule(
            Regex("(members[- ]only|premium members|paid content|subscribe to)", RegexOption.IGNORE_CASE),
            "This is members-only or paid content, so it can't be downloaded.",
        ),
        Rule(
            Regex("(video unavailable|has been removed|no longer available|does not exist|404)", RegexOption.IGNORE_CASE),
            "That post doesn't exist any more, or the link is wrong.",
        ),
        Rule(
            Regex("no video could be found", RegexOption.IGNORE_CASE),
            "There's no video in that post — it may be a photo, or the video may sit " +
                "behind a link inside the post.",
        ),
        Rule(
            Regex("(unsupported url|no suitable extractor)", RegexOption.IGNORE_CASE),
            "The engine doesn't recognise that link.",
        ),
        Rule(
            Regex("requested format .*not available", RegexOption.IGNORE_CASE),
            "That quality isn't offered any more. Fetch the link again to see current " +
                "options.",
        ),
        Rule(
            Regex("(unable to download webpage|urlopen error|timed out|connection (reset|refused)|network is unreachable|temporary failure in name resolution)", RegexOption.IGNORE_CASE),
            "Couldn't reach the site. Check your connection and try again.",
        ),
        Rule(
            Regex("(no space left|enospc)", RegexOption.IGNORE_CASE),
            "The phone is out of storage space.",
        ),
        Rule(
            Regex("(this live event|is live|premieres in)", RegexOption.IGNORE_CASE),
            "That's a live or upcoming stream, which this app doesn't record.",
        ),
    )

    /** [raw] is an exception message or a block of yt-dlp output. */
    fun of(raw: String?): String {
        val text = raw.orEmpty()
        rules.firstOrNull { it.match.containsMatchIn(text) }?.let { return it.message }
        return tidy(text)
    }

    /** Strips yt-dlp's prefixes and picks the line most likely to say what went wrong. */
    private fun tidy(raw: String): String {
        val line = raw.lineSequence().lastOrNull { it.contains("ERROR:") }
            ?: raw.lineSequence().lastOrNull { it.isNotBlank() }
            ?: return "Couldn't read that link"
        return line
            .substringAfter("ERROR:")
            .replace(Regex("^\\s*\\[[^]]+]\\s*"), "") // drop the "[youtube] id:" prefix
            .replace(Regex("; please report this issue.*", RegexOption.IGNORE_CASE), "")
            .trim()
            .take(300)
            .ifBlank { "Couldn't read that link" }
    }
}
