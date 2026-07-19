package be.digitalia.mediasession2mqtt.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Ensures MediaSessionListenerService is bound when the device boots.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            MediaSessionListenerService.requestRebind(context)
        }
    }
}