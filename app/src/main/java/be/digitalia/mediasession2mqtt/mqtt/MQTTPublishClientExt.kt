package be.digitalia.mediasession2mqtt.mqtt

import kotlinx.coroutines.CancellationException

suspend fun MQTTPublishClient.testConnection() {
    try {
        connect()
    } finally {
        disconnectQuietly()
    }
}

/**
 * Swallows exceptions and returns true in case of success
 */
suspend fun MQTTPublishClient.tryConnectAndPublish(
    qosLevel: MQTTQoSLevel,
    topic: String,
    payload: String
): Boolean {
    return try {
        connectAndPublish(qosLevel, topic, payload)
        true
    } catch (e: Exception) {
        if (e is CancellationException) {
            throw e
        }
        false
    }
}