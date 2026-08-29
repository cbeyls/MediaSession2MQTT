package be.digitalia.mediasession2mqtt

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import be.digitalia.mediasession2mqtt.homeassistant.Sensor
import be.digitalia.mediasession2mqtt.homeassistant.createSensorDiscoveryConfiguration
import be.digitalia.mediasession2mqtt.mediasession.CurrentMediaControllerDetector
import be.digitalia.mediasession2mqtt.mediasession.metadataFlow
import be.digitalia.mediasession2mqtt.mediasession.playbackInfoFlow
import be.digitalia.mediasession2mqtt.mediasession.playbackStateFlow
import be.digitalia.mediasession2mqtt.mqtt.MQTTPublishClient
import be.digitalia.mediasession2mqtt.mqtt.MQTTQoSLevel
import be.digitalia.mediasession2mqtt.mqtt.tryConnectAndPublish
import be.digitalia.mediasession2mqtt.mqttmediaplayer.MQTTMediaMetadata
import be.digitalia.mediasession2mqtt.mqttmediaplayer.MQTTPlaybackState
import be.digitalia.mediasession2mqtt.mqttmediaplayer.getPlayingPositionDrift
import be.digitalia.mediasession2mqtt.mqttmediaplayer.toMQTTPlaybackStateOrNull
import be.digitalia.mediasession2mqtt.mqttmediaplayer.toMediaDurationInMillis
import be.digitalia.mediasession2mqtt.mqttmediaplayer.toMediaTitle
import be.digitalia.mediasession2mqtt.service.MediaSessionListenerService
import be.digitalia.mediasession2mqtt.settings.SettingsProvider
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Inject
class MainWorker(
    private val context: Context,
    private val currentMediaControllerDetector: CurrentMediaControllerDetector,
    private val settingsProvider: SettingsProvider,
    private val mqttClientFactory: MQTTPublishClient.Factory
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val volumeRefreshFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val localVolumeChangeFlow: Flow<Int> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
            }
        }
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        trySend(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }.conflate().distinctUntilChanged()

    private val applicationIdFlow: Flow<String> =
        currentMediaControllerDetector.currentMediaController.map { mediaController ->
            mediaController?.packageName.orEmpty()
        }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val playbackStateFlow: Flow<MQTTPlaybackState> =
        currentMediaControllerDetector.currentMediaController.flatMapLatest { mediaController ->
            when (mediaController) {
                null -> flowOf(MQTTPlaybackState.Idle)
                else -> mediaController.playbackStateFlow
                    .distinctUntilChanged { old, new ->
                        // Only report playback state changes or drifting positions while playing
                        val oldState = old?.state ?: PlaybackState.STATE_NONE
                        val newState = new?.state ?: PlaybackState.STATE_NONE
                        oldState == newState && abs(getPlayingPositionDrift(old, new)) < POSITION_DRIFT_THRESHOLD_MILLIS
                    }
                    .mapNotNull { it?.toMQTTPlaybackStateOrNull() }
            }
        }.buffer(Channel.RENDEZVOUS)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val mediaMetadataFlow: Flow<MQTTMediaMetadata> =
        currentMediaControllerDetector.currentMediaController.flatMapLatest { mediaController ->
            when (mediaController) {
                null -> flowOf(MQTTMediaMetadata())
                else -> mediaController.metadataFlow
                    .map {
                        MQTTMediaMetadata(
                            title = it.toMediaTitle(),
                            durationInMillis = it.toMediaDurationInMillis()
                        )
                    }
            }
        }.buffer(Channel.RENDEZVOUS)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val playbackActionsFlow: Flow<Long> =
        currentMediaControllerDetector.currentMediaController.flatMapLatest { mediaController ->
            when (mediaController) {
                null -> flowOf(0L)
                else -> mediaController.playbackStateFlow.map { it?.actions ?: 0L }
            }
        }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val playbackInfoFlow: Flow<MediaController.PlaybackInfo?> =
        currentMediaControllerDetector.currentMediaController.flatMapLatest { mediaController ->
            when (mediaController) {
                null -> flowOf(null)
                else -> mediaController.playbackInfoFlow
            }
        }.distinctUntilChanged { old, new ->
            old?.currentVolume == new?.currentVolume &&
                old?.maxVolume == new?.maxVolume &&
                old?.volumeControl == new?.volumeControl
        }

    private suspend fun monitorSettings() {
        settingsProvider.connectionSettings.collectLatest { connectionSettings ->
            if (connectionSettings != null) {
                val client = mqttClientFactory.create(connectionSettings)
                val subscribeClient = mqttClientFactory.create(connectionSettings)
                try {
                    settingsProvider.messageSettings.collectLatest { (qosLevel, deviceId) ->
                        coroutineScope {
                            launch {
                                settingsProvider.isMediaControlEnabled.collectLatest { isEnabled ->
                                    if (isEnabled) {
                                        listenForMediaCommands(subscribeClient, qosLevel, deviceId)
                                    } else {
                                        subscribeClient.disconnectQuietly()
                                    }
                                }
                            }
                            launch { publishHassConfigurationIfEnabled(client, qosLevel, deviceId) }
                            launch { publishApplicationId(client, qosLevel, deviceId) }
                            launch { publishPlaybackState(client, qosLevel, deviceId) }
                            launch { publishPlaybackActions(client, qosLevel, deviceId) }
                            launch { publishPlaybackInfo(client, qosLevel, deviceId) }
                            launch { publishMediaControlEnabled(client, qosLevel, deviceId) }
                            launch { publishMediaMetadata(client, qosLevel, deviceId) }
                        }
                    }
                } finally {
                    client.disconnectQuietly()
                    subscribeClient.disconnectQuietly()
                }
            }
        }
    }

    private suspend fun listenForMediaCommands(
        client: MQTTPublishClient,
        qosLevel: MQTTQoSLevel,
        deviceId: Int
    ) {
        while (true) {
            try {
                client.listen(
                    qosLevel = qosLevel,
                    topicFilter = "$ROOT_TOPIC/$deviceId/#"
                ) { topic, payload ->
                    handleMediaCommand(deviceId, topic, payload)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                client.disconnectQuietly()
                delay(MQTT_COMMAND_RECONNECT_DELAY_MILLIS)
            }
        }
    }

    private fun handleMediaCommand(deviceId: Int, topic: String, payload: String) {
        val controller = currentMediaControllerDetector.currentMediaController.value ?: return
        val commandPrefix = "$ROOT_TOPIC/$deviceId/$COMMAND_SUB_TOPIC/"

        // Backward compatibility with the original seek-only command topic.
        if (topic == "$ROOT_TOPIC/$deviceId/$SEEK_SUB_TOPIC") {
            payload.toLongOrNull()?.takeIf { it >= 0L }?.let(controller.transportControls::seekTo)
            return
        }

        if (!topic.startsWith(commandPrefix)) return
        when (topic.removePrefix(commandPrefix)) {
            "play" -> controller.transportControls.play()
            "pause" -> controller.transportControls.pause()
            "playPause" -> {
                if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    controller.transportControls.pause()
                } else {
                    controller.transportControls.play()
                }
            }
            "stop" -> controller.transportControls.stop()
            "next" -> controller.transportControls.skipToNext()
            "previous" -> controller.transportControls.skipToPrevious()
            "fastForward" -> controller.transportControls.fastForward()
            "rewind" -> controller.transportControls.rewind()
            "seek" -> payload.toLongOrNull()?.takeIf { it >= 0L }?.let(controller.transportControls::seekTo)
            "volume" -> {
                setVolume(controller, payload)
                volumeRefreshFlow.tryEmit(Unit)
            }
            "volumeUp" -> {
                adjustVolume(controller, AudioManager.ADJUST_RAISE)
                volumeRefreshFlow.tryEmit(Unit)
            }
            "volumeDown" -> {
                adjustVolume(controller, AudioManager.ADJUST_LOWER)
                volumeRefreshFlow.tryEmit(Unit)
            }
            "mute" -> {
                adjustVolume(controller, AudioManager.ADJUST_MUTE)
                volumeRefreshFlow.tryEmit(Unit)
            }
            "unmute" -> {
                adjustVolume(controller, AudioManager.ADJUST_UNMUTE)
                volumeRefreshFlow.tryEmit(Unit)
            }
        }
    }

    private fun setVolume(controller: MediaController, payload: String) {
        val info = controller.playbackInfo
        if (info.volumeControl != VolumeProvider.VOLUME_CONTROL_ABSOLUTE) return
        val normalized = payload.toFloatOrNull()?.coerceIn(0f, 1f) ?: return
        if (info.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL) {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                (normalized * maxVolume).roundToInt(),
                0
            )
        } else {
            controller.setVolumeTo((normalized * info.maxVolume).roundToInt(), 0)
        }
    }

    private fun adjustVolume(controller: MediaController, direction: Int) {
        val info = controller.playbackInfo
        if (info.volumeControl == VolumeProvider.VOLUME_CONTROL_FIXED) return
        if (info.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ||
                direction != AudioManager.ADJUST_MUTE && direction != AudioManager.ADJUST_UNMUTE
            ) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
            } else if (direction == AudioManager.ADJUST_MUTE) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ||
            direction != AudioManager.ADJUST_MUTE && direction != AudioManager.ADJUST_UNMUTE
        ) {
            controller.adjustVolume(direction, 0)
        } else if (direction == AudioManager.ADJUST_MUTE) {
            controller.setVolumeTo(0, 0)
        }
    }

    private suspend fun publishHassConfigurationIfEnabled(
        client: MQTTPublishClient,
        qosLevel: MQTTQoSLevel,
        deviceId: Int
    ) {
        settingsProvider.isHassIntegrationEnabled.collectLatest { isEnabled ->
            if (isEnabled) {
                for (sensor in HASS_SENSORS) {
                    val discoveryConfig = createSensorDiscoveryConfiguration(
                        deviceId = deviceId,
                        sensor = sensor,
                        sensorTopic = "$ROOT_TOPIC/$deviceId/${sensor.subTopic}"
                    )
                    client.tryConnectAndPublish(
                        qosLevel,
                        "$HASS_ROOT_TOPIC/${sensor.type}/${sensor.getUniqueId(deviceId)}/config",
                        discoveryConfig
                    )
                }
            }
        }
    }

    private suspend fun publishApplicationId(
        client: MQTTPublishClient,
        qosLevel: MQTTQoSLevel,
        deviceId: Int
    ) {
        applicationIdFlow.collect { applicationId ->
            client.tryConnectAndPublish(
                qosLevel,
                "$ROOT_TOPIC/$deviceId/$APPLICATION_ID_SUB_TOPIC",
                applicationId
            )
        }
    }

    private suspend fun publishPlaybackState(
        client: MQTTPublishClient,
        qosLevel: MQTTQoSLevel,
        deviceId: Int
    ) {
        playbackStateFlow.fold(null as MQTTPlaybackState?) { previousPlaybackState, playbackState ->
            val name = playbackState.name
            if (previousPlaybackState?.name != name) {
                client.tryConnectAndPublish(
                    qosLevel,
                    "$ROOT_TOPIC/$deviceId/$PLAYBACK_STATE_SUB_TOPIC",
                    name
                )
            }
            val positionInMillis = playbackState.positionInMillis
            if (previousPlaybackState?.positionInMillis != positionInMillis) {
                client.tryConnectAndPublish(
                    qosLevel,
                    "$ROOT_TOPIC/$deviceId/$PLAYBACK_POSITION_SUB_TOPIC",
                    if (positionInMillis < 0) "" else positionInMillis.toString()
                )
            }
            playbackState
        }
    }

    private suspend fun publishPlaybackActions(
        client: MQTTPublishClient,
        qosLevel: MQTTQoSLevel,
        deviceId: Int
    ) {
        playbackActionsFlow.collect { actions ->
            client.tryConnectAndPublish(
                qosLevel,
                "$ROOT_TOPIC/$deviceId/$PLAYBACK_ACTIONS_SUB_TOPIC",
                actions.toString()
            )
        }
    }

    private suspend fun publishPlaybackInfo(
        client: MQTTPublishClient,
        qosLevel: MQTTQoSLevel,
        deviceId: Int
    ) {
        merge(
            playbackInfoFlow,
            volumeRefreshFlow.map {
                currentMediaControllerDetector.currentMediaController.value?.playbackInfo
            },
            localVolumeChangeFlow.map {
                currentMediaControllerDetector.currentMediaController.value?.playbackInfo
            }
        ).collect { info ->
            val volumeLevel = if (info != null && info.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL) {
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                if (maxVolume > 0) {
                    audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
                } else {
                    null
                }
            } else if (info != null && info.maxVolume > 0) {
                info.currentVolume.toFloat() / info.maxVolume
            } else {
                null
            }
            client.tryConnectAndPublish(
                qosLevel,
                "$ROOT_TOPIC/$deviceId/$VOLUME_LEVEL_SUB_TOPIC",
                volumeLevel?.toString().orEmpty()
            )
            client.tryConnectAndPublish(
                qosLevel,
                "$ROOT_TOPIC/$deviceId/$VOLUME_CONTROL_SUB_TOPIC",
                when (info?.volumeControl) {
                    VolumeProvider.VOLUME_CONTROL_ABSOLUTE -> "absolute"
                    VolumeProvider.VOLUME_CONTROL_RELATIVE -> "relative"
                    VolumeProvider.VOLUME_CONTROL_FIXED -> "fixed"
                    else -> ""
                }
            )
            val muted = if (info != null && info.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    audioManager.isStreamMute(AudioManager.STREAM_MUSIC) ||
                        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
                } else {
                    audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
                }
            } else {
                info?.let { it.currentVolume == 0 }
            }
            client.tryConnectAndPublish(
                qosLevel,
                "$ROOT_TOPIC/$deviceId/$VOLUME_MUTED_SUB_TOPIC",
                muted?.toString().orEmpty()
            )
        }
    }

    private suspend fun publishMediaControlEnabled(
        client: MQTTPublishClient,
        qosLevel: MQTTQoSLevel,
        deviceId: Int
    ) {
        settingsProvider.isMediaControlEnabled.collectLatest { enabled ->
            client.tryConnectAndPublish(
                qosLevel,
                "$ROOT_TOPIC/$deviceId/$MEDIA_CONTROL_ENABLED_SUB_TOPIC",
                enabled.toString()
            )
        }
    }

    private suspend fun publishMediaMetadata(
        client: MQTTPublishClient,
        qosLevel: MQTTQoSLevel,
        deviceId: Int
    ) {
        mediaMetadataFlow.fold(null as MQTTMediaMetadata?) { previousMediaMetadata, mediaMetadata ->
            val title = mediaMetadata.title
            if (previousMediaMetadata?.title != title) {
                client.tryConnectAndPublish(
                    qosLevel,
                    "$ROOT_TOPIC/$deviceId/$MEDIA_TITLE_SUB_TOPIC",
                    title
                )
            }
            val durationInMillis = mediaMetadata.durationInMillis
            if (previousMediaMetadata?.durationInMillis != durationInMillis) {
                client.tryConnectAndPublish(
                    qosLevel,
                    "$ROOT_TOPIC/$deviceId/$MEDIA_DURATION_SUB_TOPIC",
                    durationInMillis
                )
            }
            mediaMetadata
        }
    }

    fun start() {
        coroutineScope.launch {
            monitorSettings()
        }
        // Watchdog to attempt rebinding MediaSessionListenerService when disconnected
        coroutineScope.launch {
            currentMediaControllerDetector.isListening.collectLatest { isListening ->
                if (!isListening) {
                    delay(AUTO_REBIND_SERVICE_DELAY_MILLIS)
                    MediaSessionListenerService.requestRebind(context)
                }
            }
        }
    }

    companion object {
        private const val POSITION_DRIFT_THRESHOLD_MILLIS = 1000L
        private const val AUTO_REBIND_SERVICE_DELAY_MILLIS = 2000L
        private const val MQTT_COMMAND_RECONNECT_DELAY_MILLIS = 2000L

        private const val ROOT_TOPIC = "mediaSession"
        private const val APPLICATION_ID_SUB_TOPIC = "applicationId"
        private const val PLAYBACK_STATE_SUB_TOPIC = "playbackState"
        private const val PLAYBACK_POSITION_SUB_TOPIC = "playbackPosition"
        private const val PLAYBACK_ACTIONS_SUB_TOPIC = "playbackActions"
        private const val MEDIA_TITLE_SUB_TOPIC = "mediaTitle"
        private const val MEDIA_DURATION_SUB_TOPIC = "mediaDuration"
        private const val VOLUME_LEVEL_SUB_TOPIC = "volumeLevel"
        private const val VOLUME_CONTROL_SUB_TOPIC = "volumeControl"
        private const val VOLUME_MUTED_SUB_TOPIC = "volumeMuted"
        private const val MEDIA_CONTROL_ENABLED_SUB_TOPIC = "mediaControlEnabled"
        private const val COMMAND_SUB_TOPIC = "command"
        private const val SEEK_SUB_TOPIC = "seek"

        private const val HASS_ROOT_TOPIC = "homeassistant"
        private val HASS_SENSORS = listOf(
            Sensor(
                name = "Playback State",
                serializedName = "playback_state",
                icon = "mdi:play-pause",
                subTopic = PLAYBACK_STATE_SUB_TOPIC
            ),
            Sensor(
                name = "Playback Position",
                serializedName = "playback_position",
                icon = "mdi:progress-clock",
                subTopic = PLAYBACK_POSITION_SUB_TOPIC,
                deviceClass = "duration",
                unitOfMeasurement = "ms"
            ),
            Sensor(
                name = "Playback Actions",
                serializedName = "playback_actions",
                icon = "mdi:gesture-tap",
                subTopic = PLAYBACK_ACTIONS_SUB_TOPIC
            ),
            Sensor(
                name = "Application Id",
                serializedName = "application_id",
                icon = "mdi:application",
                subTopic = APPLICATION_ID_SUB_TOPIC
            ),
            Sensor(
                name = "Media Title",
                serializedName = "media_title",
                icon = "mdi:information",
                subTopic = MEDIA_TITLE_SUB_TOPIC
            ),
            Sensor(
                name = "Media Duration",
                serializedName = "media_duration",
                icon = "mdi:clock",
                subTopic = MEDIA_DURATION_SUB_TOPIC,
                deviceClass = "duration",
                unitOfMeasurement = "ms"
            ),
            Sensor(
                name = "Volume Level",
                serializedName = "volume_level",
                icon = "mdi:volume-high",
                subTopic = VOLUME_LEVEL_SUB_TOPIC
            ),
            Sensor(
                name = "Volume Control",
                serializedName = "volume_control",
                icon = "mdi:volume-source",
                subTopic = VOLUME_CONTROL_SUB_TOPIC
            ),
            Sensor(
                name = "Volume Muted",
                serializedName = "volume_muted",
                icon = "mdi:volume-off",
                subTopic = VOLUME_MUTED_SUB_TOPIC
            ),
            Sensor(
                name = "Media Control Enabled",
                serializedName = "media_control_enabled",
                icon = "mdi:remote",
                subTopic = MEDIA_CONTROL_ENABLED_SUB_TOPIC
            )
        )
    }
}
