package be.digitalia.mediasession2mqtt.inject

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.session.MediaSessionManager
import android.net.ConnectivityManager
import android.os.PowerManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides

@BindingContainer
@ContributesTo(AppScope::class)
object ServiceProviders {
    @Provides
    fun provideMediaSessionManager(applicationContext: Context): MediaSessionManager {
        return applicationContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    }

    @Provides
    fun provideConnectivityManager(applicationContext: Context): ConnectivityManager {
        return applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    @Provides
    fun provideDisplayManager(applicationContext: Context): DisplayManager {
        return applicationContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    @Provides
    fun providePowerManager(applicationContext: Context): PowerManager {
        return applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    }
}
