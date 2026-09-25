package be.digitalia.mediasession2mqtt.mediastate

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.util.Log
import be.digitalia.mediasession2mqtt.audio.AudioPlaybackDetector
import be.digitalia.mediasession2mqtt.audio.ForegroundAppResolver
import be.digitalia.mediasession2mqtt.mediasession.CurrentMediaControllerDetector
import be.digitalia.mediasession2mqtt.mediasession.metadataFlow
import be.digitalia.mediasession2mqtt.mediasession.playbackStateFlow
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.transformLatest

/**
 * Combines the current MediaSession and the audio playback fallback into a single MediaState:
 * 1. A MediaSession is playing: use it (method = mediasession)
 * 2. Otherwise, media audio is playing: use the foreground app (method = audio_callback),
 *    unless the foreground app is the launcher
 * 3. Otherwise, a MediaSession is paused: report it as paused (method = mediasession)
 * 4. Otherwise: idle
 */
@Inject
class MediaStateProvider(
    private val currentMediaControllerDetector: CurrentMediaControllerDetector,
    private val audioPlaybackDetector: AudioPlaybackDetector,
    private val foregroundAppResolver: ForegroundAppResolver
) {
    private class SessionSnapshot(
        val packageName: String,
        val status: MediaPlaybackStatus,
        val title: String,
        val artist: String
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val sessionSnapshotFlow: Flow<SessionSnapshot?> =
        currentMediaControllerDetector.currentMediaController.flatMapLatest { mediaController ->
            if (mediaController == null) {
                flowOf(null)
            } else {
                val statusFlow = mediaController.playbackStateFlow.mapNotNull { it.toStatusOrNull() }
                combine(statusFlow, mediaController.metadataFlow) { status, metadata ->
                    SessionSnapshot(
                        packageName = mediaController.packageName,
                        status = status,
                        title = metadata.title,
                        artist = metadata.artist
                    )
                }
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val rawMediaState: Flow<MediaState> =
        combine(sessionSnapshotFlow, audioPlaybackDetector.isMediaAudioActive) { session, isAudioActive ->
            session to isAudioActive
        }.mapLatest { (session, isAudioActive) ->
            if (session?.status == MediaPlaybackStatus.PLAYING) {
                return@mapLatest session.toMediaState()
            }
            if (isAudioActive) {
                val packageName = foregroundAppResolver.getForegroundPackage().orEmpty()
                // Ignore audio played by the home screen (e.g. trailers in the Fire TV launcher banner)
                if (packageName.isNotEmpty() && foregroundAppResolver.isLauncher(packageName)) {
                    Log.i(TAG, "Ignoring media audio while launcher $packageName is in foreground")
                } else {
                    // Only reuse the session metadata if it belongs to the same app
                    val sameAppSession = session?.takeIf { it.packageName == packageName }
                    return@mapLatest MediaState(
                        status = MediaPlaybackStatus.PLAYING,
                        packageName = packageName,
                        appName = if (packageName.isEmpty()) "" else foregroundAppResolver.getAppLabel(packageName),
                        title = sameAppSession?.title.orEmpty(),
                        artist = sameAppSession?.artist.orEmpty(),
                        method = MediaDetectionMethod.AUDIO_CALLBACK
                    )
                }
            }
            if (session?.status == MediaPlaybackStatus.PAUSED) session.toMediaState() else MediaState.IDLE
        }

    /**
     * Debounced media state:
     * - the first state is delayed to let the MediaSession listener connect after the app starts
     * - leaving the playing state is delayed to ignore short interruptions (channel zapping, session re-creation)
     * - other changes are delayed a little to let multiple simultaneous events settle
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val mediaState: Flow<MediaState> = flow {
        var lastState: MediaState? = null
        emitAll(rawMediaState.distinctUntilChanged().transformLatest { state ->
            val previousState = lastState
            val delayMillis = when {
                previousState == null -> STARTUP_DELAY_MILLIS
                previousState.isActive && !state.isActive -> STOP_DELAY_MILLIS
                else -> SETTLE_DELAY_MILLIS
            }
            delay(delayMillis)
            if (state != previousState) {
                Log.i(TAG, "Media state: $state")
                lastState = state
                emit(state)
            }
        })
    }

    private suspend fun SessionSnapshot.toMediaState(): MediaState {
        return MediaState(
            status = status,
            packageName = packageName,
            appName = foregroundAppResolver.getAppLabel(packageName),
            title = title,
            artist = artist,
            method = MediaDetectionMethod.MEDIA_SESSION
        )
    }

    /**
     * Unsupported transient states (buffering, etc.) return null and must be ignored.
     * A playing state with a speed of 0 is considered paused.
     */
    private fun PlaybackState?.toStatusOrNull(): MediaPlaybackStatus? {
        if (this == null) {
            return MediaPlaybackStatus.IDLE
        }
        return when (state) {
            PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR -> MediaPlaybackStatus.IDLE
            PlaybackState.STATE_PLAYING -> if (playbackSpeed == 0f) {
                Log.i(TAG, "Session is playing with speed 0, considered paused")
                MediaPlaybackStatus.PAUSED
            } else {
                MediaPlaybackStatus.PLAYING
            }

            PlaybackState.STATE_PAUSED -> MediaPlaybackStatus.PAUSED
            else -> null
        }
    }

    private val MediaMetadata?.title: String
        get() {
            if (this == null) {
                return ""
            }
            return getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).takeUnless { it.isNullOrEmpty() }
                ?: getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        }

    private val MediaMetadata?.artist: String
        get() {
            if (this == null) {
                return ""
            }
            return getString(MediaMetadata.METADATA_KEY_ARTIST).takeUnless { it.isNullOrEmpty() }
                ?: getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
        }

    companion object {
        private const val TAG = "MediaStateProvider"

        private const val STARTUP_DELAY_MILLIS = 3000L
        private const val STOP_DELAY_MILLIS = 5000L
        private const val SETTLE_DELAY_MILLIS = 1000L
    }
}
