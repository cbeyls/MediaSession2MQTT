package be.digitalia.mediasession2mqtt.connectivity

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart

/**
 * Detects general connectivity changes.
 *
 * The logic is loosely based on NetworkStateTracker from the WorkManager library to work around bugs and limitations.
 */
@Inject
class ConnectivityChecker(
    private val context: Context,
    private val connectivityManager: ConnectivityManager,
) {
    @Suppress("DEPRECATION")
    val isActiveNetworkConnected: Boolean
        get() {
            val activeNetworkInfo = connectivityManager.activeNetworkInfo
            return activeNetworkInfo != null && activeNetworkInfo.isConnected
        }

    @Suppress("DEPRECATION")
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    val isActiveNetworkConnectedFlow: Flow<Boolean> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        // Modern code using ConnectivityManager.NetworkCallback
        callbackFlow {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    trySend(isActiveNetworkConnected)
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
    } else {
        // Legacy code using a BroadcastReceiver
        callbackFlow {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    trySend(isActiveNetworkConnected)
                }
            }
            context.registerReceiver(receiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
            awaitClose {
                context.unregisterReceiver(receiver)
            }
        }
    }
        .conflate()
        .onStart { emit(isActiveNetworkConnected) }
        .distinctUntilChanged()
}
