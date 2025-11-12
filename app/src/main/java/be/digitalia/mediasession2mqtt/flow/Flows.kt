package be.digitalia.mediasession2mqtt.flow

import kotlinx.coroutines.flow.Flow

/**
 * Collect a Flow and allow comparing the current value to the previous one to detect changes.
 */
suspend fun <T : Any> Flow<T>.collectWithPrevious(collector: suspend (previous: T?, current: T) -> Unit) {
    var previous: T? = null
    collect { current ->
        collector(previous, current)
        previous = current
    }
}