@file:Suppress("DEPRECATION")

package be.digitalia.mediasession2mqtt.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.preference.EditTextPreference
import android.preference.ListPreference
import android.preference.PreferenceActivity
import android.provider.Settings
import android.widget.Toast
import be.digitalia.mediasession2mqtt.BuildConfig
import be.digitalia.mediasession2mqtt.R
import be.digitalia.mediasession2mqtt.inject.appGraph
import be.digitalia.mediasession2mqtt.mediasession.CurrentMediaControllerDetector
import be.digitalia.mediasession2mqtt.mqtt.MQTTPublishClient
import be.digitalia.mediasession2mqtt.mqtt.testConnection
import be.digitalia.mediasession2mqtt.settings.PreferenceKeys
import be.digitalia.mediasession2mqtt.settings.SettingsProvider
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@SuppressLint("ExportedPreferenceActivity")
class SettingsActivity : PreferenceActivity() {

    @Inject
    private lateinit var settingsProvider: SettingsProvider
    @Inject
    private lateinit var mqttClientFactory: MQTTPublishClient.Factory
    @Inject
    private lateinit var currentMediaControllerDetector: CurrentMediaControllerDetector

    @OptIn(ExperimentalCoroutinesApi::class)
    private val statusSummary: Flow<CharSequence> by lazy(LazyThreadSafetyMode.NONE) {
        currentMediaControllerDetector.isListening.flatMapLatest { isListening ->
            if (!isListening) {
                flowOf(getString(R.string.status_not_listening))
            } else {
                val listeningStatus = getString(R.string.status_listening)
                currentMediaControllerDetector.currentMediaController.map { mediaController ->
                    val currentSessionSummary = if (mediaController == null) {
                        getString(R.string.status_no_current_mediasession)
                    } else {
                        getString(
                            R.string.status_current_mediasession,
                            mediaController.packageName
                        )
                    }
                    listeningStatus + currentSessionSummary
                }
            }
        }.buffer(Channel.RENDEZVOUS)
    }

    private var currentCoroutineScope: CoroutineScope? = null

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        appGraph.inject(this)
        super.onCreate(savedInstanceState)

        addPreferencesFromResource(R.xml.settings)

        val unsetText = getString(R.string.unset)
        val clearTextFilter =
            { text: CharSequence? -> if (text.isNullOrEmpty()) unsetText else text }
        val passwordTextFilter =
            { text: CharSequence? -> if (text.isNullOrEmpty()) unsetText else String(CharArray(text.length) { '*' }) }

        setupListPreferenceSimpleSummaryProvider(PreferenceKeys.PROTOCOL_VERSION, clearTextFilter)
        setupEditTextPreferenceSimpleSummaryProvider(PreferenceKeys.HOSTNAME, clearTextFilter)
        setupEditTextPreferenceSimpleSummaryProvider(PreferenceKeys.PORT, clearTextFilter)
        setupEditTextPreferenceSimpleSummaryProvider(PreferenceKeys.USERNAME, clearTextFilter)
        setupEditTextPreferenceSimpleSummaryProvider(PreferenceKeys.PASSWORD, passwordTextFilter)

        setupListPreferenceSimpleSummaryProvider(PreferenceKeys.QOS_LEVEL, clearTextFilter)
        setupEditTextPreferenceSimpleSummaryProvider(PreferenceKeys.DEVICE_ID, clearTextFilter)

        setupConnectionTest()
        setupMediaControlPreference()
        setupNotificationListenerLink()
        populateVersion()
    }

    private fun setupMediaControlPreference() {
        findPreference(PreferenceKeys.MEDIA_CONTROL_ENABLED)?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == true) {
                requestBatteryOptimizationExemptionIfNeeded()
            }
            true
        }
    }

    private fun requestBatteryOptimizationExemptionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

        val requestIntent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(requestIntent)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(
                    this,
                    R.string.error_battery_optimization_settings_activity_not_found,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setupListPreferenceSimpleSummaryProvider(
        key: CharSequence,
        filter: (CharSequence?) -> CharSequence?
    ) {
        val preference = findPreference(key) as? ListPreference ?: return
        preference.summary = filter(preference.entry)
        preference.setOnPreferenceChangeListener { pref, newValue ->
            val listPreference = (pref as ListPreference)
            val valueIndex = listPreference.entryValues.indexOf(newValue).coerceAtLeast(0)
            pref.summary = listPreference.entries[valueIndex]
            true
        }
    }

    private fun setupEditTextPreferenceSimpleSummaryProvider(
        key: CharSequence,
        filter: (CharSequence?) -> CharSequence?
    ) {
        val preference = findPreference(key) as? EditTextPreference ?: return
        preference.summary = filter(preference.text)
        preference.setOnPreferenceChangeListener { pref, newValue ->
            pref.summary = filter(newValue as CharSequence?)
            true
        }
    }

    private fun setupConnectionTest() {
        findPreference(PreferenceKeys.TEST_CONNECTION)?.setOnPreferenceClickListener {
            currentCoroutineScope?.launch {
                val connectionSettings = settingsProvider.connectionSettings.first()

                val message = if (connectionSettings == null) {
                    getString(R.string.connection_test_invalid_settings)
                } else {
                    val client = mqttClientFactory.create(connectionSettings)
                    try {
                        client.testConnection()
                        getString(R.string.connection_test_success)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val errorMessage = e.message
                        if (errorMessage.isNullOrEmpty()) {
                            getString(R.string.connection_test_failure_generic)
                        } else {
                            getString(R.string.connection_test_failure_message, errorMessage)
                        }
                    }
                }

                Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_SHORT).show()
            }
            true
        }
    }

    private fun setupNotificationListenerLink() {
        findPreference(PreferenceKeys.OPEN_NOTIFICATION_LISTENER_SETTINGS)?.setOnPreferenceClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(
                    this,
                    R.string.error_notification_listener_settings_activity_not_found,
                    Toast.LENGTH_LONG
                ).show()
            }
            true
        }
    }

    private fun populateVersion() {
        findPreference(PreferenceKeys.VERSION)?.summary = BuildConfig.VERSION_NAME
    }

    override fun onStart() {
        super.onStart()
        // Manual lifecycle management, because PreferenceActivity doesn't inherit from androidx.activity.ComponentActivity
        val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        currentCoroutineScope = coroutineScope

        setupStatus(coroutineScope)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onStop() {
        currentCoroutineScope?.cancel()
        currentCoroutineScope = null
        super.onStop()
    }

    private fun setupStatus(coroutineScope: CoroutineScope) {
        val statusPreference = findPreference(PreferenceKeys.STATUS)
        coroutineScope.launch {
            statusSummary.collect {
                statusPreference.summary = it
            }
        }
    }
}
