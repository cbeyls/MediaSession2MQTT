package be.digitalia.mediasession2mqtt.connectivity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart

/**
 * ConnectivityChecker using legacy BroadcastReceiver implementation.
 */
@Suppress("DEPRECATION")
@Inject
class LegacyConnectivityChecker(
    private val context: Context,
    private val connectivityManager: ConnectivityManager,
) : ConnectivityChecker {
    private val isActiveNetworkConnected: Boolean
        get() {
            val activeNetworkInfo = connectivityManager.activeNetworkInfo
            return activeNetworkInfo != null && activeNetworkInfo.isConnected
        }

    override val isActiveNetworkConnectedFlow: Flow<Boolean> = callbackFlow {
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
        .conflate()
        .onStart { emit(isActiveNetworkConnected) }
        .distinctUntilChanged()
}
