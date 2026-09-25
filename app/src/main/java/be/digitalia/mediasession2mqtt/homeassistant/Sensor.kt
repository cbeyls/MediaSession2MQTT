package be.digitalia.mediasession2mqtt.homeassistant

class Sensor(
    val name: String,
    private val serializedName: String,
    val icon: String,
    val subTopic: String,
    val deviceClass: String? = null,
    val unitOfMeasurement: String? = null,
    val type: String = "sensor",
    val payloadOn: String? = null,
    val payloadOff: String? = null
) {
    fun getUniqueId(uniqueIdPrefix: String): String {
        return "${uniqueIdPrefix}_$serializedName"
    }
}
