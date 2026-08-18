package com.speakupi

import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.app.ActivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.speakupi.data.SettingsRepository
import com.speakupi.service.ListenerForegroundService
import com.speakupi.util.NotificationAccessUtils
import com.speakupi.util.UiTextTranslator
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var notificationSendPermissionButton: MaterialButton
    private lateinit var notificationSendPermissionExplanation: TextView
    private lateinit var notificationReadPermissionExplanation: TextView
    private lateinit var notificationReadPermissionButton: MaterialButton
    private lateinit var batteryUsageExplanation: TextView
    private lateinit var batteryUsageButton: MaterialButton
    private lateinit var receivedAnnouncementsSwitch: MaterialSwitch
    private lateinit var customMessageHeading: TextView
    private lateinit var customMessageInput: EditText
    private lateinit var customMessageSaveButton: MaterialButton
    private lateinit var reportButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settingsRepository = SettingsRepository(this)
        ContextCompat.startForegroundService(this, Intent(this, ListenerForegroundService::class.java))

        notificationSendPermissionButton = findViewById(R.id.notificationSendPermissionButton)
        notificationSendPermissionExplanation = findViewById(R.id.notificationSendPermissionExplanation)
        notificationReadPermissionExplanation = findViewById(R.id.notificationReadPermissionExplanation)
        notificationReadPermissionButton = findViewById(R.id.notificationReadPermissionButton)
        batteryUsageExplanation = findViewById(R.id.batteryUsageExplanation)
        batteryUsageButton = findViewById(R.id.batteryUsageButton)

        notificationSendPermissionButton.setOnClickListener {
            openAppNotificationSettings()
        }
        notificationReadPermissionButton.setOnClickListener {
            openNotificationAccessSettings()
        }
        batteryUsageButton.setOnClickListener {
            showBatteryOptimizationGuideDialog()
        }

        receivedAnnouncementsSwitch = findViewById(R.id.switchReceivedAnnouncements)
        receivedAnnouncementsSwitch.isChecked = settingsRepository.isReceivedAnnouncementsEnabled()
        receivedAnnouncementsSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.setReceivedAnnouncementsEnabled(isChecked)
        }

        customMessageHeading = findViewById(R.id.customMessageHeading)
        customMessageInput = findViewById(R.id.customMessageInput)
        customMessageSaveButton = findViewById(R.id.customMessageSaveButton)
        reportButton = findViewById(R.id.reportButton)
        reportButton.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPORT_URL)))
        }
        customMessageInput.setText(settingsRepository.getCustomAnnouncementMessage())
        customMessageSaveButton.setOnClickListener {
            settingsRepository.setCustomAnnouncementMessage(customMessageInput.text?.toString().orEmpty())
            UiTextTranslator.translate(this, getString(R.string.custom_message_saved)) { translated ->
                Toast.makeText(this, translated, Toast.LENGTH_SHORT).show()
            }
        }

        applyTranslatedUiText()
    }

    override fun onResume() {
        super.onResume()
        maybeRequestPostNotificationsPermission()
        updatePermissionButtons()
    }

    private fun maybeRequestPostNotificationsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }

        val permissionState = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS
        )
        if (permissionState == PackageManager.PERMISSION_GRANTED) {
            return
        }

        if (settingsRepository.hasRequestedPostNotificationsPermission()) {
            return
        }

        settingsRepository.setPostNotificationsPermissionRequested(true)
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
    }

    private fun openNotificationAccessSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

        if (intent.resolveActivity(packageManager) == null) {
            Toast.makeText(this, "Select SpeakUPI.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_SETTINGS))
            return
        }

        try {
            Toast.makeText(this, "Select SpeakUPI.", Toast.LENGTH_LONG).show()
            startActivity(intent)

            Handler(Looper.getMainLooper()).postDelayed({
                if (!NotificationAccessUtils.isNotificationAccessEnabled(this)) {
                    Toast.makeText(this, "Select \"Allow notification access\"", Toast.LENGTH_LONG).show()
                }
            }, 2000)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Could not open Notification access settings. Please open Settings manually and search for Notification access.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updatePermissionButtons() {
        val notificationSendPermissionMissing = needsNotificationSendPermission()
        notificationSendPermissionButton.visibility = if (notificationSendPermissionMissing) {
            View.VISIBLE
        } else {
            View.GONE
        }
        notificationSendPermissionExplanation.visibility = if (notificationSendPermissionMissing) {
            View.VISIBLE
        } else {
            View.GONE
        }

        val notificationReadAccessMissing = !NotificationAccessUtils.isNotificationAccessEnabled(this)
        notificationReadPermissionExplanation.visibility = if (notificationReadAccessMissing) {
            View.VISIBLE
        } else {
            View.GONE
        }
        notificationReadPermissionButton.visibility = if (notificationReadAccessMissing) {
            View.VISIBLE
        } else {
            View.GONE
        }

        val batteryOptimizationEnabled = needsUnrestrictedBatteryUsage()
        batteryUsageExplanation.visibility = if (batteryOptimizationEnabled) {
            View.VISIBLE
        } else {
            View.GONE
        }
        batteryUsageButton.visibility = if (batteryOptimizationEnabled) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun needsUnrestrictedBatteryUsage(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false
        }

        val powerManager = getSystemService(PowerManager::class.java) ?: return false
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            return false
        }

        // Some OEMs don't reflect "Unrestricted" via isIgnoringBatteryOptimizations,
        // but they do clear the app's background restriction state.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val activityManager = getSystemService(ActivityManager::class.java)
            if (activityManager != null && !activityManager.isBackgroundRestricted) {
                return false
            }
        }

        return true
    }

    private fun needsNotificationSendPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return false
        }

        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED
    }

    private fun openAppNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }

        if (intent.resolveActivity(packageManager) != null) {
            Toast.makeText(this, "Select \"All SpeakUPI notifications\"", Toast.LENGTH_LONG).show()
            startActivity(intent)
            return
        }

        Toast.makeText(this, "Select \"All SpeakUPI notifications\"", Toast.LENGTH_LONG).show()
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        )
    }

    private fun showBatteryOptimizationGuideDialog() {
        val guide = getBatteryGuide()
        val openSettingsLabel = getString(R.string.battery_guide_open_settings)

        UiTextTranslator.translateList(this, listOf(guide.title, guide.instructions, openSettingsLabel)) { translated ->
            MaterialAlertDialogBuilder(this)
                .setTitle(translated[0])
                .setMessage(translated[1])
                .setPositiveButton(translated[2]) { _, _ ->
                    openBestAvailableBatterySettings(guide.settingsIntents)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun applyTranslatedUiText() {
        val sourceTexts = listOf(
            getString(R.string.received_announcements_toggle),
            getString(R.string.notification_send_permission_button),
            getString(R.string.notification_read_permission_button),
            getString(R.string.battery_usage_explanation),
            getString(R.string.battery_usage_button),
            getString(R.string.custom_message_heading),
            getString(R.string.custom_message_hint),
            getString(R.string.custom_message_save_button),
            getString(R.string.report_button)
        )

        UiTextTranslator.translateList(this, sourceTexts) { translated ->
            receivedAnnouncementsSwitch.text = translated[0]
            notificationSendPermissionButton.text = translated[1]
            notificationReadPermissionButton.text = translated[2]
            batteryUsageExplanation.text = translated[3]
            batteryUsageButton.text = translated[4]
            customMessageHeading.text = translated[5]
            customMessageInput.hint = translated[6]
            customMessageSaveButton.text = translated[7]
            reportButton.text = translated[8]
        }
    }

    private fun openBestAvailableBatterySettings(candidates: List<Intent>) {
        for (intent in candidates) {
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                return
            }
        }

        startActivity(appDetailsIntent())
    }

    private fun getBatteryGuide(): BatteryGuide {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.US)
        val settingsIntents = mutableListOf<Intent>()

        val genericIntents = listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            appDetailsIntent()
        )

        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> {
                settingsIntents += listOf(
                    Intent().setComponent(
                        ComponentName(
                            "com.miui.securitycenter",
                            "com.miui.powercenter.PowerSettings"
                        )
                    ),
                    Intent().setComponent(
                        ComponentName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity"
                        )
                    )
                )
                settingsIntents += genericIntents
                BatteryGuide(
                    title = "Battery settings for Xiaomi/Redmi/POCO",
                    instructions = "To improve screen-off reliability:\n\n" +
                        "1. Open Battery settings for this app and choose Unrestricted / No restrictions.\n" +
                        "2. Enable Auto-start for SpeakUPI.\n" +
                        "3. Keep notification access enabled.",
                    settingsIntents = settingsIntents
                )
            }

            manufacturer.contains("oppo") || manufacturer.contains("realme") -> {
                settingsIntents += listOf(
                    Intent().setComponent(
                        ComponentName(
                            "com.coloros.oppoguardelf",
                            "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"
                        )
                    ),
                    Intent().setComponent(
                        ComponentName(
                            "com.coloros.safecenter",
                            "com.coloros.safecenter.startupapp.StartupAppListActivity"
                        )
                    )
                )
                settingsIntents += genericIntents
                BatteryGuide(
                    title = "Battery settings for OPPO/realme",
                    instructions = "To improve screen-off reliability:\n\n" +
                        "1. Set SpeakUPI to allow background activity and Unrestricted battery usage.\n" +
                        "2. Enable Auto-launch / Startup for SpeakUPI.\n" +
                        "3. Keep notification access enabled.",
                    settingsIntents = settingsIntents
                )
            }

            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
                settingsIntents += listOf(
                    Intent().setComponent(
                        ComponentName(
                            "com.iqoo.secure",
                            "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
                        )
                    ),
                    Intent().setComponent(
                        ComponentName(
                            "com.vivo.permissionmanager",
                            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                        )
                    )
                )
                settingsIntents += genericIntents
                BatteryGuide(
                    title = "Battery settings for vivo/iQOO",
                    instructions = "To improve screen-off reliability:\n\n" +
                        "1. Allow background activity for SpeakUPI.\n" +
                        "2. Disable battery optimization restrictions for SpeakUPI.\n" +
                        "3. Enable startup permission (if available).",
                    settingsIntents = settingsIntents
                )
            }

            manufacturer.contains("oneplus") -> {
                settingsIntents += listOf(
                    Intent().setComponent(
                        ComponentName(
                            "com.oneplus.security",
                            "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                        )
                    )
                )
                settingsIntents += genericIntents
                BatteryGuide(
                    title = "Battery settings for OnePlus",
                    instructions = "To improve screen-off reliability:\n\n" +
                        "1. Set app battery usage to Unrestricted for SpeakUPI.\n" +
                        "2. Allow Auto-launch / background activity where available.\n" +
                        "3. Keep notification access enabled.",
                    settingsIntents = settingsIntents
                )
            }

            manufacturer.contains("samsung") -> {
                settingsIntents += genericIntents
                BatteryGuide(
                    title = "Battery settings for Samsung",
                    instructions = "To improve screen-off reliability:\n\n" +
                        "1. In Battery settings, set SpeakUPI to Unrestricted.\n" +
                        "2. Remove SpeakUPI from Sleeping apps / Deep sleeping apps.\n" +
                        "3. Keep notification access enabled.",
                    settingsIntents = settingsIntents
                )
            }

            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                settingsIntents += listOf(
                    Intent().setComponent(
                        ComponentName(
                            "com.huawei.systemmanager",
                            "com.huawei.systemmanager.optimize.process.ProtectActivity"
                        )
                    )
                )
                settingsIntents += genericIntents
                BatteryGuide(
                    title = "Battery settings for HUAWEI/HONOR",
                    instructions = "To improve screen-off reliability:\n\n" +
                        "1. Add SpeakUPI to Protected apps / App launch management.\n" +
                        "2. Disable automatic battery restrictions for SpeakUPI.\n" +
                        "3. Keep notification access enabled.",
                    settingsIntents = settingsIntents
                )
            }

            else -> {
                settingsIntents += genericIntents
                BatteryGuide(
                    title = "Allow unrestricted battery usage",
                    instructions = "To improve screen-off reliability:\n\n" +
                        "1. Open battery settings for SpeakUPI.\n" +
                        "2. Set battery usage to Unrestricted (or disable battery optimization).\n" +
                        "3. If your phone has Auto-start/background controls, allow SpeakUPI.",
                    settingsIntents = settingsIntents
                )
            }
        }
    }

    private fun appDetailsIntent(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
    }

    private data class BatteryGuide(
        val title: String,
        val instructions: String,
        val settingsIntents: List<Intent>
    )

    companion object {
        private const val REPORT_URL = "https://forms.gle/XnsvqLHUGeCMVyhw8"
        private const val REQUEST_POST_NOTIFICATIONS = 1001
    }
}
