package be.digitalia.mediasession2mqtt.mqttmediaplayer

sealed interface MQTTPlaybackState {
    val name: String
    val positionInMillis: String

    data object Idle : MQTTPlaybackState {
        override val name: String
            get() = "idle"
        override val positionInMillis: String
            get() = ""
    }

    data class Playing(override val positionInMillis: String) : MQTTPlaybackState {
        override val name: String
            get() = "playing"
    }

    data class Paused(override val positionInMillis: String) : MQTTPlaybackState {
        override val name: String
            get() = "paused"
    }
}