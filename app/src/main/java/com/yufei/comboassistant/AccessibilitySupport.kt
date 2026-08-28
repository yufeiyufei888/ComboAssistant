package com.yufei.comboassistant

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

enum class ComboServiceStatus {
    DISABLED,
    ENABLED_WAITING_FOR_CONNECTION,
    CONNECTED,
}

fun readComboServiceStatus(
    context: Context,
    serviceConnected: Boolean,
): ComboServiceStatus = resolveComboServiceStatus(
    systemEnabled = isComboServiceEnabled(context),
    serviceConnected = serviceConnected,
)

internal fun resolveComboServiceStatus(
    systemEnabled: Boolean,
    serviceConnected: Boolean,
): ComboServiceStatus = when {
    !systemEnabled -> ComboServiceStatus.DISABLED
    serviceConnected -> ComboServiceStatus.CONNECTED
    else -> ComboServiceStatus.ENABLED_WAITING_FOR_CONNECTION
}

fun isComboServiceEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        ?: return false
    return runCatching {
        manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            it.resolveInfo.serviceInfo.packageName == context.packageName &&
                it.resolveInfo.serviceInfo.name.endsWith("ComboAccessibilityService")
        }
    }.getOrDefault(false)
}

fun createHyperOsAutostartSettingsIntents(context: Context): List<Intent> = listOf(
    Intent().setComponent(
        ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
        ),
    ),
    createApplicationDetailsSettingsIntent(context),
)

fun createHyperOsBatterySettingsIntents(context: Context): List<Intent> {
    val appLabel = runCatching {
        context.applicationInfo.loadLabel(context.packageManager).toString()
    }.getOrDefault(context.packageName)
    return listOf(
        Intent().apply {
            component = ComponentName(
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
            )
            putExtra("package_name", context.packageName)
            putExtra("package_label", appLabel)
        },
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        createApplicationDetailsSettingsIntent(context),
    )
}

internal fun launchFirstAvailableSettings(
    intents: List<Intent>,
    launch: (Intent) -> Unit,
): Boolean {
    intents.forEach { intent ->
        try {
            launch(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            // Try the next OEM or platform fallback.
        } catch (_: SecurityException) {
            // Some HyperOS builds keep the component private; continue to a public setting.
        }
    }
    return false
}

private fun createApplicationDetailsSettingsIntent(context: Context): Intent = Intent(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    Uri.parse("package:${context.packageName}"),
)
