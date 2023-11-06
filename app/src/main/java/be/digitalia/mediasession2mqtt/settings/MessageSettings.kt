package be.digitalia.mediasession2mqtt.settings

import be.digitalia.mediasession2mqtt.mqtt.MQTTQoSLevel

data class MessageSettings(
    val qosLevel: MQTTQoSLevel, val deviceId: Int
)