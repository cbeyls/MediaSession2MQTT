package be.digitalia.mediasession2mqtt.mqtt

interface MQTTPublishClient {
    /**
     * Throw an Exception in case of failure.
     */
    suspend fun connect()

    /**
     * Connect if not currently connected, and publish.
     * Throw an Exception in case of any failure.
     */
    suspend fun connectAndPublish(qosLevel: MQTTQoSLevel, topic: String, payload: String)

    /**
     * Connect if not currently connected, and process the MQTT keep-alive (send a ping if needed).
     * Must be called periodically when availability is enabled, so the broker can detect lost connections.
     * Throw an Exception in case of any failure.
     */
    suspend fun keepAlive()

    /**
     * Disconnect if currently connected.
     * Exceptions must be swallowed.
     * Implementation must be non-cancellable in order to properly disconnect in case of cancellation.
     */
    suspend fun disconnectQuietly()

    interface Factory {
        fun create(
            connectionSettings: MQTTConnectionSettings,
            availability: MQTTAvailability? = null
        ): MQTTPublishClient
    }
}
