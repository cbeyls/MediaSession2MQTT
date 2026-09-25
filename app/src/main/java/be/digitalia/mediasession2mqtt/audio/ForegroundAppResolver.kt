package be.digitalia.mediasession2mqtt.audio

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves the foreground app package using UsageStatsManager and app labels using PackageManager.
 * Only queried on demand when a media event is received (no polling).
 * Requires: adb shell appops set be.digitalia.mediasession2mqtt GET_USAGE_STATS allow
 */
@Inject
@SingleIn(AppScope::class)
class ForegroundAppResolver(private val context: Context) {
    private val appLabelCache = hashMapOf<String, String>()

    private val isUsageStatsAllowed: Boolean
        get() {
            val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            @Suppress("DEPRECATION")
            val mode = appOpsManager.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                context.applicationInfo.uid,
                context.packageName
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }

    /**
     * Returns the package of the last app moved to foreground, or null if unknown.
     */
    suspend fun getForegroundPackage(): String? = withContext(Dispatchers.IO) {
        if (!isUsageStatsAllowed) {
            Log.w(TAG, "Usage stats access not granted, foreground app unknown")
            return@withContext null
        }
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        // Use an unbounded end time: if the device clock once jumped into the future, the current
        // usage stats bucket starts in the future and recent events are only returned if the range overlaps it
        val events = usageStatsManager.queryEvents(now - USAGE_EVENTS_WINDOW_MILLIS, Long.MAX_VALUE)
        val event = UsageEvents.Event()
        var packageName: String? = null
        var eventsCount = 0
        while (events.getNextEvent(event)) {
            eventsCount++
            @Suppress("DEPRECATION")
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                packageName = event.packageName
            }
        }
        Log.i(TAG, "Foreground package: $packageName ($eventsCount usage events)")
        packageName
    }

    /**
     * Returns true if the package is a home screen app (launcher), which may play audio trailers.
     */
    suspend fun isLauncher(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.queryIntentActivities(homeIntent, 0)
            .any { it.activityInfo.packageName == packageName }
    }

    /**
     * Returns the user-visible label of the app, or the package name if unavailable.
     */
    suspend fun getAppLabel(packageName: String): String = withContext(Dispatchers.IO) {
        synchronized(appLabelCache) { appLabelCache[packageName] }?.let { return@withContext it }
        val packageManager = context.packageManager
        val label = try {
            packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
        synchronized(appLabelCache) { appLabelCache[packageName] = label }
        label
    }

    companion object {
        private const val TAG = "ForegroundAppResolver"
        private const val USAGE_EVENTS_WINDOW_MILLIS = 24 * 60 * 60 * 1000L
    }
}
