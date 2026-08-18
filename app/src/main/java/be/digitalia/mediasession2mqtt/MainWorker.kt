package be.digitalia.mediasession2mqtt

import android.content.Context
import be.digitalia.mediasession2mqtt.homeassistant.Sensor
import be.digitalia.mediasession2mqtt.homeassistant.createSensorDiscoveryConfiguration
import be.digitalia.mediasession2mqtt.mediasession.CurrentMediaControllerDetector
import be.digitalia.mediasession2mqtt.mediasession.metadataFlow
import be.digitalia.mediasession2mqtt.mediasession.playbackStateFlow
import be.digitalia.mediasession2mqtt.mqtt.MQTTPublishClient
import be.digitalia.mediasession2mqtt.mqtt.MQTTQoSLevel
import be.digitalia.mediasession2mqtt.mqtt.publishWithRetry
import be.digitalia.mediasession2mqtt.mqttmediaplayer.MQTTMediaMetadata
import be.digitalia.mediasession2mqtt.mqttmediaplayer.MQTTPlaybackState
import be.digitalia.mediasession2mqtt.mqttmediaplayer.toMQTTPlaybackStateOrNull
import be.digitalia.mediasession2mqtt.mqttmediaplayer.toMediaDurationInMillis
import be.digitalia.mediasession2mqtt.mqttmediaplayer.toMediaTitle
import be.digitalia.mediasession2mqtt.service.MediaSessionListenerService
import be.digitalia.mediasession2mqtt.settings.SettingsProvider
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

@Inject
class MainWorker(
    private val context: Context,
    private val currentMediaControllerDetector: CurrentMediaControllerDetector,
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
                null -> flowOf(MQTTPlaybackState.Idle)
                else -> mediaController.playbackStateFlow.mapNotNull { it.toMQTTPlaybackStateOrNull() }
            }
        }

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
        }

    private suspend fun monitorSettings() {
        settingsProvider.connectionSettings.collectLatest { connectionSettings ->
            android.util.Log.d(TAG, "connectionSettings emission: ${connectionSettings != null}")
            if (connectionSettings != null) {
                val client = mqttClientFactory.create(connectionSettings)
                try {
                    settingsProvider.messageSettings.collectLatest { (qosLevel, deviceId) ->
                        android.util.Log.d(TAG, "messageSettings emission, starting publishers")
                        coroutineScope {
                            launch { runPublisher("hassConfig") { publishHassConfigurationIfEnabled(client, qosLevel, deviceId) } }
                            launch { runPublisher("applicationId") { publishApplicationId(client, qosLevel, deviceId) } }
                            launch { runPublisher("playbackState") { publishPlaybackState(client, qosLevel, deviceId) } }
                            launch { runPublisher("mediaMetadata") { publishMediaMetadata(client, qosLevel, deviceId) } }
                        }
                    }
                } finally {
                    client.disconnectQuietly()
                }
            }
        }
    }

    private suspend fun runPublisher(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "publisher $name terminated", e)
            throw e
        } finally {
            android.util.Log.w(TAG, "publisher $name exited")
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
                    client.publishWithRetry(
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
        applicationIdFlow.collectLatest { applicationId ->
            client.publishWithRetry(
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
        coroutineScope {
            launch {
                playbackStateFlow.map { it.name }.distinctUntilChanged().collectLatest { name ->
                    client.publishWithRetry(
                        qosLevel,
                        "$ROOT_TOPIC/$deviceId/$PLAYBACK_STATE_SUB_TOPIC",
                        name
                    )
                }
            }
            launch {
                playbackStateFlow.map { it.positionInMillis }.distinctUntilChanged()
                    .collectLatest { positionInMillis ->
                        client.publishWithRetry(
                            qosLevel,
                            "$ROOT_TOPIC/$deviceId/$PLAYBACK_POSITION_SUB_TOPIC",
                            positionInMillis
                        )
                    }
            }
        }
    }

    private suspend fun publishMediaMetadata(
        client: MQTTPublishClient,
        qosLevel: MQTTQoSLevel,
        deviceId: Int
    ) {
        coroutineScope {
            launch {
                mediaMetadataFlow.map { it.title }.distinctUntilChanged().collectLatest { title ->
                    client.publishWithRetry(
                        qosLevel,
                        "$ROOT_TOPIC/$deviceId/$MEDIA_TITLE_SUB_TOPIC",
                        title
                    )
                }
            }
            launch {
                mediaMetadataFlow.map { it.durationInMillis }.distinctUntilChanged()
                    .collectLatest { durationInMillis ->
                        client.publishWithRetry(
                            qosLevel,
                            "$ROOT_TOPIC/$deviceId/$MEDIA_DURATION_SUB_TOPIC",
                            durationInMillis
                        )
                    }
            }
        }
    }

    fun start() {
        // Both loops are restarted after an unexpected failure: an exception escaping from a
        // media session flow (e.g. when notification access is revoked) or the MQTT client must
        // not permanently stop the publishing pipeline while the process keeps running
        coroutineScope.launchSupervised("monitorSettings") {
            monitorSettings()
        }
        // Watchdog to attempt rebinding MediaSessionListenerService when disconnected
        coroutineScope.launchSupervised("listenerWatchdog") {
            currentMediaControllerDetector.isListening.collectLatest { isListening ->
                if (!isListening) {
                    delay(AUTO_REBIND_SERVICE_DELAY_MILLIS)
                    MediaSessionListenerService.requestRebind(context)
                }
            }
        }
    }

    private fun CoroutineScope.launchSupervised(name: String, block: suspend () -> Unit) {
        launch {
            while (true) {
                try {
                    android.util.Log.d(TAG, "$name starting")
                    block()
                    android.util.Log.w(TAG, "$name completed unexpectedly")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    android.util.Log.e(TAG, "$name failed, restarting in ${RESTART_DELAY_MILLIS}ms", e)
                }
                delay(RESTART_DELAY_MILLIS)
            }
        }
    }

    companion object {
        private const val TAG = "MediaSession2MQTT"

        private const val AUTO_REBIND_SERVICE_DELAY_MILLIS = 2000L
        private const val RESTART_DELAY_MILLIS = 5000L

        private const val ROOT_TOPIC = "mediaSession"
        private const val APPLICATION_ID_SUB_TOPIC = "applicationId"
        private const val PLAYBACK_STATE_SUB_TOPIC = "playbackState"
        private const val PLAYBACK_POSITION_SUB_TOPIC = "playbackPosition"
        private const val MEDIA_TITLE_SUB_TOPIC = "mediaTitle"
        private const val MEDIA_DURATION_SUB_TOPIC = "mediaDuration"

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
    }
}
