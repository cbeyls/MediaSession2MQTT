package be.digitalia.mediasession2mqtt.inject

import android.content.Context
import be.digitalia.mediasession2mqtt.MainWorker
import be.digitalia.mediasession2mqtt.service.MediaSessionListenerService
import be.digitalia.mediasession2mqtt.ui.SettingsActivity
import dagger.BindsInstance
import dagger.Component
import javax.inject.Singleton

@Component(modules = [MediaSessionModule::class, MQTTPublishClientModule::class])
@Singleton
interface ApplicationComponent {
    val mainWorker: MainWorker
    fun inject(settingsActivity: SettingsActivity)
    fun inject(mediaSessionListenerService: MediaSessionListenerService)

    @Component.Builder
    interface Builder {
        @BindsInstance
        fun applicationContext(applicationContext: Context): Builder
        fun build(): ApplicationComponent
    }
}