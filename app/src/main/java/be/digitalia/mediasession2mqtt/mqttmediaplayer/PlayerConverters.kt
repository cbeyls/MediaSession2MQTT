package be.digitalia.mediasession2mqtt.mqttmediaplayer

import android.media.MediaMetadata
import android.media.session.PlaybackState

/**
 * Convert the MediaSession state to a simplified MQTT state.
 * Unsupported transient state values will return null and must not be reported.
 * Note: the buffering state is voluntarily ignored and not considered equal to playing
 * because some applications pre-buffer playback even before the user requests playing the content.
 */
fun PlaybackState?.toMQTTPlaybackStateOrNull(): MQTTPlaybackState? {
    if (this == null) {
        return null
    }
    return when (state) {
        PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR -> MQTTPlaybackState.Idle
        PlaybackState.STATE_PLAYING -> MQTTPlaybackState.Playing(position.toString())
        PlaybackState.STATE_PAUSED -> MQTTPlaybackState.Paused(position.toString())
        else -> null
    }
}

/**
 * Extract the current media title, or return an empty String if none is available.
 */
fun MediaMetadata?.toMediaTitle(): String {
    if (this == null) {
        return ""
    }
    // Use the display title as-is if available
    val displayTitle = getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
    if (!displayTitle.isNullOrEmpty()) {
        return displayTitle
    }
    val title = getString(MediaMetadata.METADATA_KEY_TITLE)
    if (title.isNullOrEmpty()) {
        return ""
    }
    // If we have a title, check if we also have an artist
    val artist = getString(MediaMetadata.METADATA_KEY_ARTIST)
    return if (artist.isNullOrEmpty()) title else "$artist - $title"
}

/**
 * Extract the media duration in milliseconds as a String, or return an empty String if unavailable.
 */
fun MediaMetadata?.toMediaDurationInMillis(): String {
    if (this == null) {
        return ""
    }
    val duration = getLong(MediaMetadata.METADATA_KEY_DURATION)
    return if (duration == 0L) "" else duration.toString()
}