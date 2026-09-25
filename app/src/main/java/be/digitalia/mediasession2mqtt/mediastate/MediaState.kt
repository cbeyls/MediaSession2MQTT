package be.digitalia.mediasession2mqtt.mediastate

enum class MediaPlaybackStatus(val mqttValue: String) {
    PLAYING("playing"),
    PAUSED("paused"),
    IDLE("idle")
}

enum class MediaDetectionMethod(val mqttValue: String) {
    MEDIA_SESSION("mediasession"),
    AUDIO_CALLBACK("audio_callback"),
    NONE("none")
}

/**
 * Unified media state, resolved from MediaSession or from the audio playback fallback.
 */
data class MediaState(
    val status: MediaPlaybackStatus,
    val packageName: String = "",
    val appName: String = "",
    val title: String = "",
    val artist: String = "",
    val method: MediaDetectionMethod = MediaDetectionMethod.NONE
) {
    val isActive: Boolean
        get() = status == MediaPlaybackStatus.PLAYING

    companion object {
        val IDLE = MediaState(MediaPlaybackStatus.IDLE)
    }
}
