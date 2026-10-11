package be.digitalia.mediasession2mqtt.connectivity

import kotlinx.coroutines.flow.Flow

/**
 * Detects general connectivity changes.
 */
interface ConnectivityChecker {
    val isActiveNetworkConnectedFlow: Flow<Boolean>
}
