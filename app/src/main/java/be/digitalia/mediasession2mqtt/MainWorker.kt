package be.digitalia.mediasession2mqtt

import android.content.Context
import android.media.session.PlaybackState
import be.digitalia.mediasession2mqtt.audio.AudioPlaybackDetector
import be.digitalia.mediasession2mqtt.connectivity.ConnectivityChecker
import be.digitalia.mediasession2mqtt.homeassistant.DeviceInfo
import be.digitalia.mediasession2mqtt.homeassistant.Sensor
import be.digitalia.mediasession2mqtt.homeassistant.createSensorDiscoveryConfiguration
import be.digitalia.mediasession2mqtt.mediasession.CurrentMediaControllerDetector
import be.digitalia.mediasession2mqtt.mediasession.metadataFlow
import be.digitalia.mediasession2mqtt.mediasession.playbackStateFlow
import be.digitalia.mediasession2mqtt.mediastate.MediaState
import be.digitalia.mediasession2mqtt.mediastate.MediaStateProvider
import be.digitalia.mediasession2mqtt.mqtt.KMQTTClient
import be.digitalia.mediasession2mqtt.mqtt.MQTTAvailability
import be.digitalia.mediasession2mqtt.mqtt.MQTTPublishClient
import be.digitalia.mediasession2mqtt.mqtt.MQTTQoSLevel
import be.digitalia.mediasession2mqtt.mqtt.tryConnectAndPublish
import be.digitalia.mediasession2mqtt.mqtt.tryKeepAlive
import be.digitalia.mediasession2mqtt.mqttmediaplayer.MQTTMediaMetadata
import be.digitalia.mediasession2mqtt.mqttmediaplayer.MQTTPlaybackState
import be.digitalia.mediasession2mqtt.mqttmediaplayer.getPlayingPositionDrift
import be.digitalia.mediasession2mqtt.mqttmediaplayer.toMQTTPlaybackStateOrNull
import be.digitalia.mediasession2mqtt.mqttmediaplayer.toMediaDurationInMillis
import be.digitalia.mediasession2mqtt.mqttmediaplayer.toMediaTitle
import be.digitalia.mediasession2mqtt.service.MediaSessionListenerService
import be.digitalia.mediasession2mqtt.settings.MessageSettings
import be.digitalia.mediasession2mqtt.settings.SettingsProvider
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlin.math.abs

@Inject
class MainWorker(
    private val context: Context,
    private val currentMediaControllerDetector: CurrentMediaControllerDetector,
    private val connectivityChecker: ConnectivityChecker,
    private val settingsProvider: SettingsProvider,
    private val mqttClientFactory: MQTTPublishClient.Factory,
    private val audioPlaybackDetector: AudioPlaybackDetector,
    private val mediaStateProvider: MediaStateProvider,
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

    private suspend fun monitorSettings() {
        // The publishing coroutine is only active when settings are valid and the active network is connected
        settingsProvider.connectionSettings
            .combine(connectivityChecker.isActiveNetworkConnectedFlow) { connectionSettings, isConnected ->
                if (isConnected) connectionSettings else null
            }
            .collectLatest { connectionSettings ->
                if (connectionSettings != null) {
                    // A new client is created when message settings change, because the availability topic depends on them
                    settingsProvider.messageSettings.collectLatest { messageSettings ->
                        val (qosLevel, deviceId) = messageSettings
                        val availability = MQTTAvailability(
                            topic = messageSettings.availabilityTopic,
                            qosLevel = qosLevel
                        )
                        val client = mqttClientFactory.create(connectionSettings, availability)
                        try {
                            coroutineScope {
                                launch { keepConnectionAlive(client) }
                                launch { publishHassConfigurationIfEnabled(client, messageSettings) }
                                launch { publishMediaState(client, qosLevel, messageSettings.mediaTopicPrefix) }
                                launch { publishApplicationId(client, qosLevel, deviceId) }
                                launch { publishPlaybackState(client, qosLevel, deviceId) }
                                launch { publishMediaMetadata(client, qosLevel, deviceId) }
                            }
                        } finally {
                            client.disconnectQuietly()
                        }
                    }
                }
            }
    }

    /**
     * Connects immediately (publishing the online status) and keeps the connection alive,
     * so the broker publishes the offline last will if the device disappears.
     */
    private suspend fun keepConnectionAlive(client: MQTTPublishClient) {
        while (true) {
            client.tryKeepAlive()
            delay(KMQTTClient.KEEP_ALIVE_STEP_INTERVAL_MILLIS)
        }
    }

    private suspend fun publishHassConfigurationIfEnabled(
        client: MQTTPublishClient,
        messageSettings: MessageSettings
    ) {
        val (qosLevel, deviceId, topicPrefix) = messageSettings
        // With a custom topic prefix, the device is named after its last segment (e.g. "chambre")
        val deviceInfo = if (topicPrefix.isEmpty()) {
            DeviceInfo(name = "$HASS_DEVICE_NAME $deviceId", identifier = "${HASS_DEVICE_NAME}_$deviceId")
        } else {
            DeviceInfo(
                name = topicPrefix.substringAfterLast('/'),
                identifier = "${HASS_DEVICE_NAME}_${topicPrefix.toSlug()}"
            )
        }
        val legacyUniqueIdPrefix = "mediasession_$deviceId"
        val mediaUniqueIdPrefix = if (topicPrefix.isEmpty()) legacyUniqueIdPrefix else "mediasession_${topicPrefix.toSlug()}"
        settingsProvider.isHassIntegrationEnabled.collectLatest { isEnabled ->
            if (isEnabled) {
                val availabilityTopic = messageSettings.availabilityTopic
                publishHassSensors(client, qosLevel, deviceInfo, HASS_SENSORS, legacyUniqueIdPrefix, "$ROOT_TOPIC/$deviceId", availabilityTopic)
                publishHassSensors(client, qosLevel, deviceInfo, HASS_MEDIA_SENSORS, mediaUniqueIdPrefix, messageSettings.mediaTopicPrefix, availabilityTopic)
            }
        }
    }

    private suspend fun publishHassSensors(
        client: MQTTPublishClient,
        qosLevel: MQTTQoSLevel,
        deviceInfo: DeviceInfo,
        sensors: List<Sensor>,
        uniqueIdPrefix: String,
        topicPrefix: String,
        availabilityTopic: String
    ) {
        for (sensor in sensors) {
            val uniqueId = sensor.getUniqueId(uniqueIdPrefix)
            val discoveryConfig = createSensorDiscoveryConfiguration(
                deviceInfo = deviceInfo,
                sensor = sensor,
                sensorUniqueId = uniqueId,
                sensorTopic = "$topicPrefix/${sensor.subTopic}",
                availabilityTopic = availabilityTopic
            )
            client.tryConnectAndPublish(
                qosLevel,
                "$HASS_ROOT_TOPIC/${sensor.type}/$uniqueId/config",
                discoveryConfig
            )
        }
    }

    /**
     * Publishes the unified media state (MediaSession + audio fallback).
     * Details are published before the state so they are up to date when automations trigger on the state.
     */
    private suspend fun publishMediaState(
        client: MQTTPublishClient,
        qosLevel: MQTTQoSLevel,
        topicPrefix: String
    ) {
        mediaStateProvider.mediaState.fold(null as MediaState?) { previous, mediaState ->
            suspend fun publishIfChanged(subTopic: String, value: String, previousValue: String?) {
                if (value != previousValue) {
                    client.tryConnectAndPublish(qosLevel, "$topicPrefix/$subTopic", value)
                }
            }
            publishIfChanged(MEDIA_PACKAGE_SUB_TOPIC, mediaState.packageName, previous?.packageName)
            publishIfChanged(MEDIA_APP_SUB_TOPIC, mediaState.appName, previous?.appName)
            publishIfChanged(MEDIA_CURRENT_TITLE_SUB_TOPIC, mediaState.title, previous?.title)
            publishIfChanged(MEDIA_ARTIST_SUB_TOPIC, mediaState.artist, previous?.artist)
            publishIfChanged(MEDIA_METHOD_SUB_TOPIC, mediaState.method.mqttValue, previous?.method?.mqttValue)
            publishIfChanged(MEDIA_STATE_SUB_TOPIC, mediaState.status.mqttValue, previous?.status?.mqttValue)
            publishIfChanged(
                MEDIA_ACTIVE_SUB_TOPIC,
                mediaState.isActive.toOnOff(),
                previous?.isActive?.toOnOff()
            )
            mediaState
        }
    }

    private val MessageSettings.mediaTopicPrefix: String
        get() = if (topicPrefix.isEmpty()) "$ROOT_TOPIC/$deviceId" else topicPrefix

    private val MessageSettings.availabilityTopic: String
        get() = "$mediaTopicPrefix/$STATUS_SUB_TOPIC"

    private fun String.toSlug(): String = lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    private fun Boolean.toOnOff(): String = if (this) PAYLOAD_ON else PAYLOAD_OFF

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
        audioPlaybackDetector.start()
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

        private const val ROOT_TOPIC = "mediaSession"
        private const val APPLICATION_ID_SUB_TOPIC = "applicationId"
        private const val PLAYBACK_STATE_SUB_TOPIC = "playbackState"
        private const val PLAYBACK_POSITION_SUB_TOPIC = "playbackPosition"
        private const val MEDIA_TITLE_SUB_TOPIC = "mediaTitle"
        private const val MEDIA_DURATION_SUB_TOPIC = "mediaDuration"

        private const val STATUS_SUB_TOPIC = "status"
        private const val MEDIA_STATE_SUB_TOPIC = "media/state"
        private const val MEDIA_ACTIVE_SUB_TOPIC = "media/active"
        private const val MEDIA_PACKAGE_SUB_TOPIC = "media/package"
        private const val MEDIA_APP_SUB_TOPIC = "media/app"
        private const val MEDIA_CURRENT_TITLE_SUB_TOPIC = "media/title"
        private const val MEDIA_ARTIST_SUB_TOPIC = "media/artist"
        private const val MEDIA_METHOD_SUB_TOPIC = "media/method"
        private const val PAYLOAD_ON = "ON"
        private const val PAYLOAD_OFF = "OFF"

        private const val HASS_ROOT_TOPIC = "homeassistant"
        private const val HASS_DEVICE_NAME = "MediaSession2MQTT"
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
            )
        )
        private val HASS_MEDIA_SENSORS = listOf(
            Sensor(
                name = "Média actif",
                serializedName = "media_active",
                icon = "mdi:play-circle",
                subTopic = MEDIA_ACTIVE_SUB_TOPIC,
                type = "binary_sensor",
                payloadOn = PAYLOAD_ON,
                payloadOff = PAYLOAD_OFF
            ),
            Sensor(
                name = "État média",
                serializedName = "media_state",
                icon = "mdi:play-pause",
                subTopic = MEDIA_STATE_SUB_TOPIC
            ),
            Sensor(
                name = "Application média",
                serializedName = "media_app",
                icon = "mdi:application",
                subTopic = MEDIA_APP_SUB_TOPIC
            ),
            Sensor(
                name = "Package média",
                serializedName = "media_package",
                icon = "mdi:package-variant",
                subTopic = MEDIA_PACKAGE_SUB_TOPIC
            ),
            Sensor(
                name = "Titre média",
                serializedName = "media_current_title",
                icon = "mdi:information",
                subTopic = MEDIA_CURRENT_TITLE_SUB_TOPIC
            ),
            Sensor(
                name = "Artiste média",
                serializedName = "media_artist",
                icon = "mdi:account-music",
                subTopic = MEDIA_ARTIST_SUB_TOPIC
            ),
            Sensor(
                name = "Méthode de détection",
                serializedName = "media_method",
                icon = "mdi:radar",
                subTopic = MEDIA_METHOD_SUB_TOPIC
            )
        )
    }
}
