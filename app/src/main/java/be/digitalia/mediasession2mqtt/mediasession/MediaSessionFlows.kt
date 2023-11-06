package be.digitalia.mediasession2mqtt.mediasession

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onStart

val MediaController.playbackStateFlow: Flow<PlaybackState?>
    get() {
        return callbackFlow {
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    trySend(state)
                }
            }
            registerCallback(callback)
            awaitClose { unregisterCallback(callback) }
        }.conflate().onStart {
            // By emitting the initial value here, we prevent the upstream callbackFlow from starting
            // if the Flow consumer is only interested in the first value
            emit(playbackState)
        }
    }

val MediaController.metadataFlow: Flow<MediaMetadata?>
    get() {
        return callbackFlow {
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    trySend(metadata)
                }
            }
            registerCallback(callback)
            awaitClose { unregisterCallback(callback) }
        }.conflate().onStart {
            // By emitting the initial value here, we prevent the upstream callbackFlow from starting
            // if the Flow consumer is only interested in the first value
            emit(metadata)
        }
    }