package be.digitalia.mediasession2mqtt.inject

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import be.digitalia.mediasession2mqtt.mqtt.KMQTTClient
import be.digitalia.mediasession2mqtt.mqtt.MQTTPublishClient
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

@BindingContainer
@ContributesTo(AppScope::class)
object MQTTPublishClientProviders {
    @Provides
    @SingleIn(AppScope::class)
    @SuppressLint("HardwareIds")
    fun provideMQTTPublishClientFactory(applicationContext: Context): MQTTPublishClient.Factory {
        // ANDROID_ID is unique per device and app signing key, and stable across app restarts
        val androidId = Settings.Secure.getString(applicationContext.contentResolver, Settings.Secure.ANDROID_ID)
        // Use a single thread per client because the KMQTT client is not fully thread safe
        return KMQTTClient.Factory(
            stableClientId = "ms2mqtt_${androidId.orEmpty()}",
            dispatcherProvider = { Executors.newSingleThreadExecutor().asCoroutineDispatcher() }
        )
    }
}
