package be.digitalia.mediasession2mqtt.display

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart

@Inject
@SingleIn(AppScope::class)
class ScreenStateMonitor(
    private val displayManager: DisplayManager,
    private val powerManager: PowerManager
) {
    private val isScreenOn: Boolean
        get() {
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            val isDisplayOn = display == null || display.state == Display.STATE_ON
            return powerManager.isInteractive && isDisplayOn
        }

    val isScreenOnFlow: Flow<Boolean> = callbackFlow {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    trySend(isScreenOn)
                }
            }

            override fun onDisplayRemoved(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    trySend(isScreenOn)
                }
            }

            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    trySend(isScreenOn)
                }
            }
        }
        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        awaitClose {
            displayManager.unregisterDisplayListener(listener)
        }
    }
        .conflate()
        .onStart { emit(isScreenOn) }
        .distinctUntilChanged()
}
