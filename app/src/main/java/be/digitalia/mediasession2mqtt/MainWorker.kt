package be.digitalia.mediasession2mqtt

import be.digitalia.mediasession2mqtt.homeassistant.Sensor
import be.digitalia.mediasession2mqtt.homeassistant.createSensorDiscoveryConfiguration
import be.digitalia.mediasession2mqtt.mediasession.CurrentMediaControllerDetector
import be.digitalia.mediasession2mqtt.mediasession.metadataFlow
import be.digitalia.mediasession2mqtt.mediasession.playbackStateFlow
import be.digitalia.mediasession2mqtt.mqtt.MQTTPublishClient
import be.digitalia.mediasession2mqtt.mqtt.MQTTQoSLevel
import be.digitalia.mediasession2mqtt.mqtt.tryConnectAndPublish
import be.digitalia.mediasession2mqtt.mqttmediaplayer.MQTTPlaybackState
import be.digitalia.mediasession2mqtt.mqttmediaplayer.toMQTTPlaybackStateOrNull
import be.digitalia.mediasession2mqtt.mqttmediaplayer.toMediaTitle
import be.digitalia.mediasession2mqtt.settings.SettingsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

class MainWorker @Inject constructor(
    currentMediaControllerDetector: CurrentMediaControllerDetector,
    private val settingsProvider: SettingsProvider,
    private val mqttClientFactory: MQTTPublishClient.Factory
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
                null -> flowOf(MQTTPlaybackState.idle)
                else -> mediaController.playbackStateFlow
                    .map { it.toMQTTPlaybackStateOrNull() }
                    .filterNotNull()
            }
        }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val mediaTitleFlow: Flow<String> =
        currentMediaControllerDetector.currentMediaController.flatMapLatest { mediaController ->
            when (mediaController) {
                null -> flowOf("")
                else -> mediaController.metadataFlow
                    .map { it.toMediaTitle() }
            }
        }.distinctUntilChanged()

    private suspend fun monitorSettings() {
        settingsProvider.connectionSettings.collectLatest { connectionSettings ->
            if (connectionSettings != null) {
                val client = mqttClientFactory.create(connectionSettings)
                try {
                    settingsProvider.messageSettings.collectLatest { (qosLevel, deviceId) ->
                        coroutineScope {
                            launch { publishHassConfigurationIfEnabled(client, qosLevel, deviceId) }
                            launch { publishApplicationId(client, qosLevel, deviceId) }
                            launch { publishPlaybackState(client, qosLevel, deviceId) }
                            launch { publishMediaTitle(client, qosLevel, deviceId) }
                        }
                    }
                } finally {
                    client.disconnectQuietly()
                }
            }
        }
    }

    private suspend fun publishHassConfigurationIfEnabled(
        client: MQTTPublishClient,
        qosLevel: MQTTQoSLevel,
        deviceId: Int
    ) {
        settingsProvider.isHassIntegrationEnabled.collect { isEnabled ->
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
        playbackStateFlow.collect { playbackState ->
            client.tryConnectAndPublish(
                qosLevel,
                "$ROOT_TOPIC/$deviceId/$PLAYBACK_STATE_SUB_TOPIC",
                playbackState.name
            )
        }
    }

    private suspend fun publishMediaTitle(
        client: MQTTPublishClient,
        qosLevel: MQTTQoSLevel,
        deviceId: Int
    ) {
        mediaTitleFlow.collect { mediaTitle ->
            client.tryConnectAndPublish(
                qosLevel,
                "$ROOT_TOPIC/$deviceId/$MEDIA_TITLE_SUB_TOPIC",
                mediaTitle
            )
        }
    }

    fun start() {
        coroutineScope.launch {
            monitorSettings()
        }
    }

    companion object {
        private const val ROOT_TOPIC = "mediaSession"
        private const val APPLICATION_ID_SUB_TOPIC = "applicationId"
        private const val PLAYBACK_STATE_SUB_TOPIC = "playbackState"
        private const val MEDIA_TITLE_SUB_TOPIC = "mediaTitle"

        private const val HASS_ROOT_TOPIC = "homeassistant"
        private val HASS_SENSORS = listOf(
            Sensor(
                name = "Playback State",
                serializedName = "playback_state",
                icon = "mdi:play-pause",
                subTopic = PLAYBACK_STATE_SUB_TOPIC
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
            )
        )
    }
}