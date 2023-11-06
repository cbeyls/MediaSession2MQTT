package be.digitalia.mediasession2mqtt.inject

import android.content.Context

interface ApplicationComponentProvider {
    val applicationComponent: ApplicationComponent
}

val Context.applicationComponent
    get() = (applicationContext as ApplicationComponentProvider).applicationComponent