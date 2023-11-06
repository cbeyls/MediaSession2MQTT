package be.digitalia.mediasession2mqtt.settings

import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onStart

/**
 * Extension to read or observe SharedPreferences values as a Kotlin Flow
 */

inline fun <T> SharedPreferences.getAsFlow(
    crossinline keyComparator: (key: String?) -> Boolean,
    crossinline valueProvider: SharedPreferences.() -> T
): Flow<T> {
    return callbackFlow {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, listenerKey ->
                if (keyComparator(listenerKey)) {
                    trySend(sharedPreferences.valueProvider())
                }
            }
        registerOnSharedPreferenceChangeListener(listener)
        awaitClose { unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().onStart {
        // By emitting the initial value here, we prevent the upstream callbackFlow from starting
        // if the Flow consumer is only interested in the first value
        emit(valueProvider())
    }
}