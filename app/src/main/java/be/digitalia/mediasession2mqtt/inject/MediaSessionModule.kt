package be.digitalia.mediasession2mqtt.inject

import android.content.Context
import android.media.session.MediaSessionManager
import dagger.Module
import dagger.Provides

@Module
object MediaSessionModule {
    @Provides
    fun provideMediaSessionManager(applicationContext: Context): MediaSessionManager {
        return applicationContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    }
}