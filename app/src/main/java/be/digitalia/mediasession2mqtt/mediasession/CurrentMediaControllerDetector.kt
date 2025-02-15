package be.digitalia.mediasession2mqtt.mediasession

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CurrentMediaControllerDetector @Inject constructor(private val mediaSessionManager: MediaSessionManager) {
    private val activeSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateActiveSessions(controllers.orEmpty())
    }

    // Each session can be uniquely identified using its token
    private val activeControllersMap = hashMapOf<MediaSession.Token, MediaControllerCallback>()

    private val _isListening = MutableStateFlow(false)
    val isListening = _isListening.asStateFlow()

    private val _currentMediaController = MutableStateFlow<MediaController?>(null)
    val currentMediaController = _currentMediaController.asStateFlow()

    private inner class MediaControllerCallback(val mediaController: MediaController) : MediaController.Callback() {
        override fun onSessionDestroyed() {
            // Garbage collection
            unregister()
            activeControllersMap -= mediaController.sessionToken
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (state.isPlaybackActive) {
                // The latest controller to reach active playback state
                // becomes the current controller, if it's not already
                _currentMediaController.value = mediaController
            }
        }

        fun unregister() {
            mediaController.unregisterCallback(this)
        }
    }

    fun startListening(componentName: ComponentName) {
        try {
            val controllers = mediaSessionManager.getActiveSessions(componentName)
            _isListening.value = true
            updateActiveSessions(controllers)
            mediaSessionManager.addOnActiveSessionsChangedListener(
                activeSessionsListener,
                componentName
            )
        } catch (ignore: SecurityException) {
            // No permission granted to listen to notifications
        }
    }

    fun stopListening() {
        if (_isListening.value) {
            _currentMediaController.value = null
            for (controller in activeControllersMap.values) {
                controller.unregister()
            }
            activeControllersMap.clear()
            mediaSessionManager.removeOnActiveSessionsChangedListener(activeSessionsListener)
            _isListening.value = false
        }
    }

    private val PlaybackState?.isPlaybackActive: Boolean
        get() {
            if (this == null) {
                return false
            }
            return when (state) {
                PlaybackState.STATE_FAST_FORWARDING,
                PlaybackState.STATE_REWINDING,
                PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
                PlaybackState.STATE_SKIPPING_TO_NEXT,
                PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
                PlaybackState.STATE_BUFFERING,
                PlaybackState.STATE_CONNECTING,
                PlaybackState.STATE_PLAYING -> true

                else -> false
            }
        }

    private fun updateActiveSessions(controllers: List<MediaController>) {
        if (controllers.isEmpty()) {
            _currentMediaController.value = null
        } else {
            val currentSessionToken = _currentMediaController.value?.sessionToken
            var hasCurrentController = false
            var newActivePlaybackController: MediaController? = null
            for (controller in controllers) {
                // Use sessionToken to uniquely identify the controllers and avoid duplicates
                val sessionToken = controller.sessionToken
                if (sessionToken in activeControllersMap) {
                    // Check if the current controller (if any) is still present
                    if (!hasCurrentController && sessionToken == currentSessionToken) {
                        hasCurrentController = true
                    }
                } else {
                    // Register new controller
                    val callback = MediaControllerCallback(controller)
                    activeControllersMap[sessionToken] = callback
                    controller.registerCallback(callback)
                    // If playback is active, select it immediately as new current controller
                    if (controller.playbackState.isPlaybackActive) {
                        newActivePlaybackController = controller
                    }
                }
            }

            if (newActivePlaybackController != null) {
                _currentMediaController.value = newActivePlaybackController
            } else if (!hasCurrentController) {
                // Select the first controller by default
                _currentMediaController.value = activeControllersMap[controllers[0].sessionToken]?.mediaController
            }
        }
    }
}