@file:Suppress("DEPRECATION")

package be.digitalia.mediasession2mqtt.settings

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import be.digitalia.mediasession2mqtt.R
import be.digitalia.mediasession2mqtt.mqtt.MQTTConnectionSettings
import be.digitalia.mediasession2mqtt.mqtt.MQTTQoSLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsProvider @Inject constructor(context: Context) {
    init {
        PreferenceManager.setDefaultValues(context, R.xml.settings, false)
    }

    private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    private val SharedPreferences.connectionSettings: MQTTConnectionSettings?
        get() {
            getBoolean(PreferenceKeys.ENABLED, false) || return null

            val protocolVersionString = getString(PreferenceKeys.PROTOCOL_VERSION, null)
            val protocolVersion =
                if (protocolVersionString == "5") MQTTConnectionSettings.ProtocolVersion.MQTT5 else MQTTConnectionSettings.ProtocolVersion.MQTT3_1_1
            val hostName = getString(PreferenceKeys.HOSTNAME, null)
            if (hostName.isNullOrEmpty()) {
                return null
            }
            val port = getString(PreferenceKeys.PORT, null).orEmpty().toIntOrNull() ?: DEFAULT_PORT

            val username = getString(PreferenceKeys.USERNAME, null)
            val password = getString(PreferenceKeys.PASSWORD, null)
            val authentication = if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
                null
            } else {
                MQTTConnectionSettings.Authentication(username, password)
            }

            return MQTTConnectionSettings(
                protocolVersion = protocolVersion,
                hostname = hostName,
                port = port,
                authentication = authentication
            )
        }

    private val SharedPreferences.messageSettings: MessageSettings
        get() {
            val qosLevel = getString(PreferenceKeys.QOS_LEVEL, null).orEmpty().toIntOrNull()
                ?: DEFAULT_QOS_LEVEL
            val deviceId = getString(PreferenceKeys.DEVICE_ID, null).orEmpty().toIntOrNull()
                ?: DEFAULT_DEVICE_ID
            return MessageSettings(
                qosLevel = MQTTQoSLevel.entries[qosLevel], deviceId = deviceId
            )
        }

    val connectionSettings: Flow<MQTTConnectionSettings?>
        get() = sharedPreferences.getAsFlow({ key ->
            when (key) {
                PreferenceKeys.ENABLED, PreferenceKeys.HOSTNAME, PreferenceKeys.PORT, PreferenceKeys.USERNAME, PreferenceKeys.PASSWORD -> true
                else -> false
            }
        }, { connectionSettings }).distinctUntilChanged()

    val isHassIntegrationEnabled: Flow<Boolean>
        get() = sharedPreferences.getAsFlow({ key -> key == PreferenceKeys.HASS_INTEGRATION_ENABLED },
            { getBoolean(PreferenceKeys.HASS_INTEGRATION_ENABLED, false) })

    val messageSettings: Flow<MessageSettings>
        get() = sharedPreferences.getAsFlow({ key -> key == PreferenceKeys.DEVICE_ID || key == PreferenceKeys.QOS_LEVEL },
            { messageSettings }).distinctUntilChanged()

    companion object {
        private const val DEFAULT_PORT = 1883
        private const val DEFAULT_DEVICE_ID = 1
        private const val DEFAULT_QOS_LEVEL = 0
    }
}
