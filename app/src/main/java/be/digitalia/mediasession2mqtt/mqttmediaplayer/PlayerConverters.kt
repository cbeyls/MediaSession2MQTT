package be.digitalia.mediasession2mqtt.mqttmediaplayer

import android.media.MediaMetadata
import android.media.session.PlaybackState

/**
 * Convert the MediaSession state to a simplified MQTT state.
 * Unsupported transient state values will return null and must not be reported.
 * Note: the buffering state is voluntarily ignored and not considered equal to playing
 * because some applications pre-buffer playback even before the user requests playing the content.
 */
fun PlaybackState.toMQTTPlaybackStateOrNull(): MQTTPlaybackState? = when (state) {
    PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR -> MQTTPlaybackState.Idle
    PlaybackState.STATE_PLAYING -> MQTTPlaybackState.Playing(position)
    PlaybackState.STATE_PAUSED -> MQTTPlaybackState.Paused(position)
    else -> null
}

/**
 * Calculate the time difference between two playback positions by taking the position update time into account.
 * If any of the states is not playing, return 0.
 * If the playback position transitions from/to PLAYBACK_POSITION_UNKNOWN, return Long.MAX_VALUE.
 * The calculation assumes that old and new playback speeds are the same and a change of playback speed
 * will create an artificial drift.
 */
fun getPlayingPositionDrift(old: PlaybackState?, new: PlaybackState?): Long {
    if (old?.state != PlaybackState.STATE_PLAYING || new?.state != PlaybackState.STATE_PLAYING) {
        return 0L
    }
    val oldPosition = old.position
    val newPosition = new.position
    if ((oldPosition == PlaybackState.PLAYBACK_POSITION_UNKNOWN) !=
        (newPosition == PlaybackState.PLAYBACK_POSITION_UNKNOWN)
    ) {
        return Long.MAX_VALUE
    }
    return newPosition - oldPosition -
            ((new.lastPositionUpdateTime - old.lastPositionUpdateTime) * new.playbackSpeed).toLong()
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
    return if (duration > 0L) duration.toString() else ""
}
