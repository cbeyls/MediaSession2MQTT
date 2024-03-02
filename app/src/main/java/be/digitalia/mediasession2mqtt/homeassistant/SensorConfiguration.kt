package be.digitalia.mediasession2mqtt.homeassistant

import android.os.Build
import android.util.JsonWriter
import java.io.StringWriter

private const val DEVICE_NAME = "MediaSession2MQTT"

private fun JsonWriter.writeDeviceInfo(deviceId: Int) {
    beginObject()

    name("name")
    value("$DEVICE_NAME $deviceId")
    name("manufacturer")
    value(Build.MANUFACTURER)
    name("model")
    value(Build.MODEL)
    name("identifiers")
    beginArray()
    value("${DEVICE_NAME}_$deviceId")
    endArray()

    endObject()
}

private fun JsonWriter.writeSensor(
    deviceId: Int,
    sensorName: String,
    sensorUniqueId: String,
    sensorIcon: String,
    sensorTopic: String
) {
    beginObject()

    name("name")
    value(sensorName)
    name("unique_id")
    value(sensorUniqueId)
    name("icon")
    value(sensorIcon)
    name("state_topic")
    value(sensorTopic)
    name("device")
    writeDeviceInfo(deviceId)

    endObject()
}

fun createSensorDiscoveryConfiguration(deviceId: Int, sensor: Sensor, sensorTopic: String): String {
    val writer = StringWriter()
    JsonWriter(writer).use {
        it.writeSensor(
            deviceId = deviceId,
            sensorName = sensor.name,
            sensorUniqueId = sensor.getUniqueId(deviceId),
            sensorIcon = sensor.icon,
            sensorTopic = sensorTopic
        )
    }
    return writer.toString()
}