package com.govorun.echo.audio

/**
 * The sound files a note may hold: Echo's own `.ogg`, older `.m4a`, and imported audio kept as it came. One list for the import,
 * the share sheet and the copy to a folder, so a kept format is never sent out under the wrong type.
 */
internal object AudioFormats {
    private val MIME =
        mapOf(
            "ogg" to "audio/ogg",
            "opus" to "audio/ogg",
            "oga" to "audio/ogg",
            "m4a" to "audio/mp4",
            "aac" to "audio/aac",
            "mp3" to "audio/mpeg",
            "amr" to "audio/amr",
            "awb" to "audio/amr-wb",
            "3gp" to "audio/3gpp",
            "webm" to "audio/webm",
        )

    /** Extensions of sound files a note may hold; for patterns, `ogg|opus|...`. */
    val EXTENSIONS: Set<String> = MIME.keys
    val PATTERN: String = EXTENSIONS.joinToString("|")

    fun mimeOf(extension: String): String = MIME[extension.lowercase()] ?: "application/octet-stream"

    /** The extension a shared file goes under: from its name when that is a known one, otherwise from the type the sender gave. */
    fun extensionFor(
        displayName: String?,
        type: String?,
    ): String? {
        displayName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it in EXTENSIONS }?.let { return it }
        return when (type?.lowercase()?.substringBefore(';')) {
            "audio/ogg", "audio/opus", "application/ogg" -> "ogg"
            "audio/mp4", "audio/x-m4a", "audio/m4a" -> "m4a"
            "audio/aac", "audio/aacp" -> "aac"
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/amr" -> "amr"
            "audio/amr-wb" -> "awb"
            "audio/3gpp" -> "3gp"
            "audio/webm" -> "webm"
            else -> null
        }
    }

    /**
     * Codecs kept as they came: compressed, played by every Android phone, seekable. Uncompressed (WAV) or lossless audio is
     * large, and a video carries pictures nobody asked for: those are compressed to Opus instead.
     */
    fun keepsAsIs(codecMime: String): Boolean = codecMime.lowercase() in KEPT_CODECS

    private val KEPT_CODECS = setOf("audio/mp4a-latm", "audio/opus", "audio/vorbis", "audio/mpeg", "audio/3gpp", "audio/amr-wb")
}
