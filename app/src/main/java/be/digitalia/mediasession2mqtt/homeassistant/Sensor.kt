package be.digitalia.mediasession2mqtt.homeassistant

class Sensor(
    val name: String,
    private val serializedName: String,
    val icon: String,
    val subTopic: String,
    val deviceClass: String? = null,
    val unitOfMeasurement: String? = null
) {
    val type: String
        get() = "sensor"

    fun getUniqueId(deviceId: Int): String {
        return "mediasession_${deviceId}_$serializedName"
    }
}