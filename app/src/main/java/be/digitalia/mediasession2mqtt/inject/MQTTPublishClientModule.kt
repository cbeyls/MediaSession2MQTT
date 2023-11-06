package be.digitalia.mediasession2mqtt.inject

import be.digitalia.mediasession2mqtt.mqtt.KMQTTClient
import be.digitalia.mediasession2mqtt.mqtt.MQTTPublishClient
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import javax.inject.Singleton

@Module
object MQTTPublishClientModule {
    @Provides
    @Singleton
    fun provideMQTTPublishClientFactory(): MQTTPublishClient.Factory {
        // Use a single thread because the KMQTT client is not fully thread safe
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        return KMQTTClient.Factory(dispatcher)
    }
}