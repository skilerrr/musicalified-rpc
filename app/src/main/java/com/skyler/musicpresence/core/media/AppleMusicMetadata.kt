package com.skyler.musicpresence.core.media

data class AppleMusicTrack(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val positionMs: Long,
    val isPlaying: Boolean,
    val artworkUrl: String? = null,
    val trackUrl: String? = null
) {
    val cacheKey: String
        get() = "${title.trim().lowercase()} - ${artist.trim().lowercase()}"

    val isValid: Boolean
        get() = title.isNotBlank() && artist.isNotBlank()
}
