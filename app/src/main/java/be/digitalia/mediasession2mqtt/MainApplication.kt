package be.digitalia.mediasession2mqtt

import android.app.Application
import be.digitalia.mediasession2mqtt.inject.ApplicationComponent
import be.digitalia.mediasession2mqtt.inject.ApplicationComponentProvider
import be.digitalia.mediasession2mqtt.inject.DaggerApplicationComponent

class MainApplication : Application(), ApplicationComponentProvider {

    override val applicationComponent: ApplicationComponent by lazy(LazyThreadSafetyMode.NONE) {
        DaggerApplicationComponent.builder()
            .applicationContext(this)
            .build()
    }

    override fun onCreate() {
        super.onCreate()

        applicationComponent.mainWorker().start()
    }
}