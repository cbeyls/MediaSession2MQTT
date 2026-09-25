package be.digitalia.mediasession2mqtt.mqtt

/**
 * Availability (birth and last will) messages, published retained on the same topic:
 * the online payload after each connection, the offline payload by the broker (last will)
 * when the connection is lost, or by the client before a graceful disconnection.
 */
data class MQTTAvailability(
    val topic: String,
    val qosLevel: MQTTQoSLevel,
    val onlinePayload: String = "online",
    val offlinePayload: String = "offline"
)
