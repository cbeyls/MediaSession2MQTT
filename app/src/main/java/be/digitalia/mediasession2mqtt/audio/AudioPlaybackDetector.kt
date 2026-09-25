package be.digitalia.mediasession2mqtt.audio

import android.annotation.TargetApi
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Detects active media audio playback of any app using AudioManager.AudioPlaybackCallback.
 * Used as a fallback for apps which don't publish a MediaSession (e.g. IPTV players).
 *
 * Non-privileged apps receive anonymized configurations (uid, pid and player type are hidden),
 * so this detector only reports whether media audio is playing, not which app is playing it.
 */
@Inject
@SingleIn(AppScope::class)
class AudioPlaybackDetector(private val audioManager: AudioManager) {
    private var isStarted = false

    private val _isMediaAudioActive = MutableStateFlow(false)
    val isMediaAudioActive: StateFlow<Boolean> = _isMediaAudioActive.asStateFlow()

    fun start() {
        if (isStarted) {
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.w(TAG, "AudioPlaybackCallback requires API 26, fallback disabled")
            return
        }
        isStarted = true
        registerCallback()
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun registerCallback() {
        onConfigurationsChanged(audioManager.activePlaybackConfigurations)
        audioManager.registerAudioPlaybackCallback(object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
                onConfigurationsChanged(configs)
            }
        }, Handler(Looper.getMainLooper()))
        Log.i(TAG, "AudioPlaybackCallback registered")
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun onConfigurationsChanged(configs: List<AudioPlaybackConfiguration>) {
        val isActive = configs.any { it.isActiveMediaPlayback }
        Log.i(
            TAG,
            "Audio configurations changed: mediaAudioActive=$isActive players=" +
                    configs.joinToString(prefix = "[", postfix = "]") {
                        "piid=${it.callHidden("getPlayerInterfaceId")} usage=${it.audioAttributes.usage}" +
                                " content=${it.audioAttributes.contentType} state=${it.callHidden("getPlayerState")}"
                    }
        )
        _isMediaAudioActive.value = isActive
    }

    @get:TargetApi(Build.VERSION_CODES.O)
    private val AudioPlaybackConfiguration.isActiveMediaPlayback: Boolean
        get() {
            if (audioAttributes.usage != AudioAttributes.USAGE_MEDIA) {
                return false
            }
            // The player state is a hidden API. On Fire OS 7 only active players are reported,
            // so if the state is not accessible, assume the player is started.
            val state = callHidden("getPlayerState") as? Int ?: return true
            return state == PLAYER_STATE_STARTED
        }

    /**
     * Calls a hidden (@SystemApi) getter by reflection, or returns null if not accessible.
     */
    private fun Any.callHidden(methodName: String): Any? {
        return try {
            javaClass.getMethod(methodName).invoke(this)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "AudioPlaybackDetector"

        // AudioPlaybackConfiguration.PLAYER_STATE_STARTED (hidden)
        private const val PLAYER_STATE_STARTED = 2
    }
}
