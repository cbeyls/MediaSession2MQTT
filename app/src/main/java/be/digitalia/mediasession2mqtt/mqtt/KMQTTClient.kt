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
    private val availability: MQTTAvailability?,
    private val stableClientId: String,
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
            // Keep-alive is only needed to let the broker detect lost connections and publish the last will
            keepAlive = if (availability != null) KEEP_ALIVE_SECONDS else 0,
            webSocket = null,
            // With availability, use a stable client id so the broker immediately closes the previous session
            // (and publishes its last will) when reconnecting, before the new online message.
            // With a random client id, the stale session expires later and its offline will overwrites online.
            clientId = if (availability != null) stableClientId else null,
            userName = username,
            password = password,
            willTopic = availability?.topic,
            willPayload = availability?.offlinePayload?.encodeToByteArray()?.toUByteArray(),
            willRetain = availability != null,
            willQos = Qos.entries[(availability?.qosLevel ?: MQTTQoSLevel.QOS0).ordinal],
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

    override suspend fun connectAndPublish(qosLevel: MQTTQoSLevel, topic: String, payload: String) {
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
            client = createClientAndPublishOnline()
            ensureActive()
            client.publishAndStep(qosLevel, topic, payload)
        }
    }

    override suspend fun keepAlive() {
        withContext(dispatcher) {
            val client = currentClient?.takeIf { it.isRunning() }
            if (client != null) {
                try {
                    // Sends a ping request if the keep-alive delay is expired
                    client.step()
                    check(client.isRunning()) { "MQTT connection lost" }
                    return@withContext
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // At that point we are already disconnected, no need to call disconnect()
                }
            }
            createClientAndPublishOnline()
        }
    }

    private fun createClientAndPublishOnline(): MQTTClient {
        val client = createClient()
        // Process the CONNACK first, otherwise the next publication is only sent on the following step
        client.step()
        check(client.isRunning()) { "MQTT connection failed" }
        availability?.let {
            client.publishAndStep(it.qosLevel, it.topic, it.onlinePayload)
        }
        return client
    }

    private fun MQTTClient.publishAndStep(qosLevel: MQTTQoSLevel, topic: String, payload: String) {
        publish(
            true,
            Qos.entries[qosLevel.ordinal],
            topic,
            payload.encodeToByteArray().toUByteArray()
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
                        // The last will is not published by the broker after a graceful disconnection
                        availability?.let {
                            client.publishAndStep(it.qosLevel, it.topic, it.offlinePayload)
                        }
                        client.disconnect(ReasonCode.SUCCESS)
                    } catch (_: Exception) {
                    }
                }
                currentClient = null
            }
        }
    }

    class Factory(
        private val stableClientId: String,
        private val dispatcherProvider: () -> CoroutineDispatcher
    ) : MQTTPublishClient.Factory {
        override fun create(
            connectionSettings: MQTTConnectionSettings,
            availability: MQTTAvailability?
        ): MQTTPublishClient {
            return KMQTTClient(connectionSettings, availability, stableClientId, dispatcherProvider())
        }
    }

    companion object {
        /**
         * KMQTT only sends a ping when stepping between 90% and 100% of the keep-alive period
         * since the last activity, and closes the connection after that.
         * keepAlive() must be called at an interval shorter than 10% of this period.
         */
        const val KEEP_ALIVE_SECONDS = 120
        const val KEEP_ALIVE_STEP_INTERVAL_MILLIS = 10_000L
    }
}
