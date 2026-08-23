package be.digitalia.mediasession2mqtt.inject

import android.content.Context
import android.media.session.MediaSessionManager
import android.net.ConnectivityManager
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
}
