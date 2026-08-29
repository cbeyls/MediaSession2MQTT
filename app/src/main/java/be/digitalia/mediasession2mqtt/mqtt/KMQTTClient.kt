package be.digitalia.mediasession2mqtt.mqtt

import be.digitalia.mediasession2mqtt.BuildConfig
import io.github.davidepianca98.MQTTClient
import io.github.davidepianca98.mqtt.MQTTVersion
import io.github.davidepianca98.mqtt.packets.Qos
import io.github.davidepianca98.mqtt.packets.mqttv5.ReasonCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@OptIn(ExperimentalUnsignedTypes::class)
class KMQTTClient(
    private val connectionSettings: MQTTConnectionSettings,
    private val dispatcher: CoroutineDispatcher
) : MQTTPublishClient {

    private var currentClient: MQTTClient? = null

    private fun createClient(): MQTTClient {
        val mqttVersion = when (connectionSettings.protocolVersion) {
            MQTTConnectionSettings.ProtocolVersion.MQTT3_1_1 -> MQTTVersion.MQTT3_1_1
            MQTTConnectionSettings.ProtocolVersion.MQTT5 -> MQTTVersion.MQTT5
        }
        val authentication = connectionSettings.authentication
        val username = authentication?.username
        val password = authentication?.password?.encodeToByteArray()?.toUByteArray()
        return MQTTClient(
            mqttVersion = mqttVersion,
            address = connectionSettings.hostname,
            port = connectionSettings.port,
            tls = null,
            keepAlive = 0,
            webSocket = null,
            userName = username,
            password = password,
            connackTimeout = 10,
            connectTimeout = 10,
            debugLog = BuildConfig.DEBUG,
        ) { }.also {
            currentClient = it
        }
    }

    override suspend fun connect() {
        withContext(dispatcher) {
            if (currentClient != null) {
                disconnectQuietly()
            }
            createClient().step()
        }
    }

    override suspend fun connectAndPublish(qosLevel: MQTTQoSLevel, topic: String, payload: ByteArray) {
        withContext(dispatcher) {
            var client = currentClient?.takeIf { it.isRunning() }
            if (client != null) {
                // If already connected, try to publish and retry on error
                try {
                    client.step()
                    ensureActive()
                    client.publishAndStep(qosLevel, topic, payload)
                    return@withContext
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // At that point we are already disconnected, no need to call disconnect()
                }
            }

            // Not connected yet or disconnected: connect from scratch and publish
            client = createClient()
            ensureActive()
            client.publishAndStep(qosLevel, topic, payload)
        }
    }

    private fun MQTTClient.publishAndStep(qosLevel: MQTTQoSLevel, topic: String, payload: ByteArray) {
        publish(
            true,
            Qos.entries[qosLevel.ordinal],
            topic,
            payload.toUByteArray()
        )
        step()
        check(isRunning()) { "MQTT connection lost while publishing" }
    }

    override suspend fun disconnectQuietly() {
        withContext(NonCancellable + dispatcher) {
            currentClient?.let { client ->
                // If running is false, we are already disconnected
                if (client.isRunning()) {
                    try {
                        client.disconnect(ReasonCode.SUCCESS)
                    } catch (_: Exception) {
                    }
                }
                currentClient = null
            }
        }
    }

    class Factory(private val dispatcherProvider: () -> CoroutineDispatcher) : MQTTPublishClient.Factory {
        override fun create(connectionSettings: MQTTConnectionSettings): MQTTPublishClient {
            return KMQTTClient(connectionSettings, dispatcherProvider())
        }
    }
}
