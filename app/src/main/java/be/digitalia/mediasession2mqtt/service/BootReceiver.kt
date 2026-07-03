package be.digitalia.mediasession2mqtt.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService

/**
 * Works around a well-known Android bug where the [NotificationListenerService] is not
 * automatically re-bound after a device reboot. When this happens the app silently stops
 * receiving media session updates until the notification access permission is manually
 * toggled off and on again (a frequent problem on Android TV).
 *
 * On boot we force the system to rebind the listener:
 *  1. Disabling then re-enabling the service component. This is the programmatic equivalent
 *     of the manual permission toggle and reliably triggers a rebind, including on the
 *     devices where the official API alone is not enough. It does not affect the granted
 *     notification-access permission (that grant is stored separately, by component name).
 *  2. [NotificationListenerService.requestRebind] — the official API (API 24+) as a final nudge.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val component = ComponentName(context, MediaSessionListenerService::class.java)

        // (1) Forceful rebind: toggle the component off and back on.
        runCatching {
            val pm = context.packageManager
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }

        // (2) Ask the system to rebind the listener (available since API 24).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                NotificationListenerService.requestRebind(component)
            }
        }
    }
}
