package be.digitalia.mediasession2mqtt.homeassistant

import android.os.Build
import android.util.JsonWriter
import java.io.StringWriter

/**
 * Home Assistant device grouping all the sensors.
 */
class DeviceInfo(
    val name: String,
    val identifier: String
)

private fun JsonWriter.writeDeviceInfo(deviceInfo: DeviceInfo) {
    beginObject()

    name("name")
    value(deviceInfo.name)
    name("manufacturer")
    value(Build.MANUFACTURER)
    name("model")
    value(Build.MODEL)
    name("identifiers")
    beginArray()
    value(deviceInfo.identifier)
    endArray()

    endObject()
}

private fun JsonWriter.writeSensor(
    deviceInfo: DeviceInfo,
    sensor: Sensor,
    sensorUniqueId: String,
    sensorTopic: String,
    availabilityTopic: String
) {
    beginObject()

    name("name")
    value(sensor.name)
    name("unique_id")
    value(sensorUniqueId)
    name("icon")
    value(sensor.icon)
    name("state_topic")
    value(sensorTopic)
    name("availability_topic")
    value(availabilityTopic)
    name("device")
    writeDeviceInfo(deviceInfo)
    sensor.deviceClass?.let {
        name("device_class")
        value(it)
    }
    sensor.unitOfMeasurement?.let {
        name("unit_of_measurement")
        value(it)
    }
    sensor.payloadOn?.let {
        name("payload_on")
        value(it)
    }
    sensor.payloadOff?.let {
        name("payload_off")
        value(it)
    }

    endObject()
}

fun createSensorDiscoveryConfiguration(
    deviceInfo: DeviceInfo,
    sensor: Sensor,
    sensorUniqueId: String,
    sensorTopic: String,
    availabilityTopic: String
): String {
    val writer = StringWriter()
    JsonWriter(writer).use {
        it.writeSensor(
            deviceInfo = deviceInfo,
            sensor = sensor,
            sensorUniqueId = sensorUniqueId,
            sensorTopic = sensorTopic,
            availabilityTopic = availabilityTopic
        )
    }
    return writer.toString()
}
