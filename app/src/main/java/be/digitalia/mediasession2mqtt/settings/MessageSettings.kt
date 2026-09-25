package be.digitalia.mediasession2mqtt.settings

import be.digitalia.mediasession2mqtt.mqtt.MQTTQoSLevel

data class MessageSettings(
    val qosLevel: MQTTQoSLevel,
    val deviceId: Int,
    /**
     * Root topic for the unified media topics (e.g. "androidtv/chambre"), or empty to use the default one.
     */
    val topicPrefix: String
)