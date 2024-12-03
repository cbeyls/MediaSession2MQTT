package be.digitalia.mediasession2mqtt.mqtt

import MQTTClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import mqtt.MQTTVersion
import mqtt.packets.Qos
import mqtt.packets.mqttv5.ReasonCode

@OptIn(ExperimentalUnsignedTypes::class)
class KMQTTClient(
    private val connectionSettings: MQTTConnectionSettings,
    private val dispatcher: CoroutineDispatcher
) : MQTTPublishClient {

    private var currentClient: MQTTClient? = null

    private fun getConnectedClient(forceNewInstance: Boolean): MQTTClient {
        // Create the client lazily (simple implementation for single thread)
        val client = currentClient.takeUnless { forceNewInstance }
            ?: createClient().also { currentClient = it }
        client.step()
        return client
    }

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
            password = password
        ) { }
    }

    override suspend fun connect() {
        withContext(dispatcher) {
            getConnectedClient(false)
        }
    }

    override suspend fun connectAndPublish(qosLevel: MQTTQoSLevel, topic: String, payload: String) {
        withContext(dispatcher) {
            val client = try {
                getConnectedClient(false)
            } catch (e: Exception) {
                if (e is CancellationException) {
                    throw e
                }
                // At that point we are already disconnected, no need to call disconnect()
                // Try to auto-reconnect from scratch
                getConnectedClient(true)
            }
            ensureActive()
            client.publish(
                true,
                Qos.entries[qosLevel.ordinal],
                topic,
                payload.encodeToByteArray().toUByteArray()
            )
            client.step()
        }
    }

    override suspend fun disconnectQuietly() {
        withContext(NonCancellable + dispatcher) {
            currentClient?.let { client ->
                // If running is false, we are already disconnected
                if (client.isRunning()) {
                    try {
                        client.disconnect(ReasonCode.SUCCESS)
                    } catch (ignore: Exception) {
                    }
                }
                currentClient = null
            }
        }
    }

    class Factory(private val dispatcher: CoroutineDispatcher) : MQTTPublishClient.Factory {
        override fun create(connectionSettings: MQTTConnectionSettings): MQTTPublishClient {
            return KMQTTClient(connectionSettings, dispatcher)
        }
    }
}