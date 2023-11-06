package be.digitalia.mediasession2mqtt.mqtt

data class MQTTConnectionSettings(
    val protocolVersion: ProtocolVersion,
    val hostname: String,
    val port: Int,
    val authentication: Authentication?   // Optional authentication
) {
    enum class ProtocolVersion {
        MQTT3_1_1,
        MQTT5
    }

    data class Authentication(
        val username: String,
        val password: String
    )
}