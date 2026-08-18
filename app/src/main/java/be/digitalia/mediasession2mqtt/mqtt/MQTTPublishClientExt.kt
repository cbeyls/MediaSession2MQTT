package be.digitalia.mediasession2mqtt.mqtt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

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

/**
 * Publish and keep retrying with increasing delays until it succeeds or the coroutine is cancelled.
 * Call from collectLatest so that a newer value cancels the pending retries of an older one.
 */
suspend fun MQTTPublishClient.publishWithRetry(
    qosLevel: MQTTQoSLevel,
    topic: String,
    payload: String
) {
    var attemptIndex = 0
    while (!tryConnectAndPublish(qosLevel, topic, payload)) {
        delay(RETRY_DELAYS_MILLIS[attemptIndex])
        if (attemptIndex < RETRY_DELAYS_MILLIS.lastIndex) {
            attemptIndex++
        }
    }
}

private val RETRY_DELAYS_MILLIS = longArrayOf(1_000L, 2_000L, 5_000L, 15_000L, 30_000L)