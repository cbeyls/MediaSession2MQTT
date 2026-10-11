package be.digitalia.mediasession2mqtt.connectivity

import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.annotation.RequiresApi
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart

/**
 * ConnectivityChecker using modern implementation based on the default NetworkCallback.
 */
@RequiresApi(Build.VERSION_CODES.N)
@Inject
class DefaultConnectivityChecker(
    private val connectivityManager: ConnectivityManager
) :  ConnectivityChecker {
    private val isActiveNetworkReachable: Boolean
        get() {
            val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            return networkCapabilities != null &&
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override val isActiveNetworkConnectedFlow: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val isReachable = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                trySend(isReachable)
            }

            override fun onLost(network: Network) {
                trySend(false)
            }
        }

        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
        } catch (_: SecurityException) {
        }
        awaitClose {
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (_: IllegalArgumentException) {
            } catch (_: SecurityException) {
            }
        }
    }
        .conflate()
        .onStart { emit(isActiveNetworkReachable) }
        .distinctUntilChanged()
}
