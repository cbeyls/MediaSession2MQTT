package be.digitalia.mediasession2mqtt.mqtt

import io.github.davidepianca98.MQTTClient
import io.github.davidepianca98.mqtt.MQTTVersion
import io.github.davidepianca98.mqtt.packets.Qos
import io.github.davidepianca98.mqtt.packets.mqttv5.ReasonCode
import java.io.IOException
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

    private fun getConnectedClient(forceNewInstance: Boolean): MQTTClient {
        // Create the client lazily (simple implementation for single thread)
        var client = currentClient.takeUnless { forceNewInstance }
            ?: run {
                android.util.Log.d("MediaSession2MQTT", "creating new MQTT client (forceNew=$forceNewInstance)")
                createClient().also { currentClient = it }
            }
        client.step()
        if (!client.isRunning()) {
            // A stopped client silently ignores step() and drops published messages,
            // so it must be detected and replaced with a new connected instance
            android.util.Log.d("MediaSession2MQTT", "client not running, creating replacement")
            client = createClient().also { currentClient = it }
            client.step()
        }
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
            keepAlive = KEEP_ALIVE_SECONDS,
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
            try {
                publishAndStep(getConnectedClient(false), qosLevel, topic, payload)
            } catch (e: Exception) {
                if (e is CancellationException) {
                    throw e
                }
                // At that point we are already disconnected, no need to call disconnect()
                // The current connection may also be half-open after a silent network loss:
                // in all cases, retry once from scratch with a new connection
                ensureActive()
                publishAndStep(getConnectedClient(true), qosLevel, topic, payload)
            }
        }
    }

    private fun publishAndStep(client: MQTTClient, qosLevel: MQTTQoSLevel, topic: String, payload: String) {
        client.publish(
            true,
            Qos.entries[qosLevel.ordinal],
            topic,
            payload.encodeToByteArray().toUByteArray()
        )
        client.step()
        if (!client.isRunning()) {
            // The connection died during the publish and the message may have been dropped:
            // report the failure so the caller can retry
            currentClient = null
            throw IOException("MQTT connection lost while publishing")
        }
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

    class Factory(private val dispatcher: CoroutineDispatcher) : MQTTPublishClient.Factory {
        override fun create(connectionSettings: MQTTConnectionSettings): MQTTPublishClient {
            return KMQTTClient(connectionSettings, dispatcher)
        }
    }

    companion object {
        // Advertise a keep alive interval so the broker eventually drops dead connections
        // instead of keeping half-open sessions alive forever
        private const val KEEP_ALIVE_SECONDS = 60
    }
}