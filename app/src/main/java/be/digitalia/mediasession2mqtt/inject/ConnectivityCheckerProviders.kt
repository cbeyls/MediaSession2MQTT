package be.digitalia.mediasession2mqtt.inject

import android.os.Build
import be.digitalia.mediasession2mqtt.connectivity.ConnectivityChecker
import be.digitalia.mediasession2mqtt.connectivity.DefaultConnectivityChecker
import be.digitalia.mediasession2mqtt.connectivity.LegacyConnectivityChecker
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides

@BindingContainer
@ContributesTo(AppScope::class)
object ConnectivityCheckerProviders {
    @Provides
    fun provideConnectivityChecker(
        defaultConnectivityCheckerProvider: () -> DefaultConnectivityChecker,
        legacyConnectivityCheckerProvider: () -> LegacyConnectivityChecker,
    ): ConnectivityChecker {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            defaultConnectivityCheckerProvider()
        } else {
            legacyConnectivityCheckerProvider()
        }
    }
}
