package com.yufei.comboassistant

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccessibilitySupportTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication() as Context

    @Test
    fun serviceStatusKeepsSystemEnablementSeparateFromRuntimeConnection() {
        assertEquals(
            ComboServiceStatus.DISABLED,
            resolveComboServiceStatus(systemEnabled = false, serviceConnected = false),
        )
        assertEquals(
            ComboServiceStatus.DISABLED,
            resolveComboServiceStatus(systemEnabled = false, serviceConnected = true),
        )
        assertEquals(
            ComboServiceStatus.ENABLED_WAITING_FOR_CONNECTION,
            resolveComboServiceStatus(systemEnabled = true, serviceConnected = false),
        )
        assertEquals(
            ComboServiceStatus.CONNECTED,
            resolveComboServiceStatus(systemEnabled = true, serviceConnected = true),
        )
    }

    @Test
    fun hyperOsAutostartUsesExplicitComponentThenAppDetailsFallback() {
        val intents = createHyperOsAutostartSettingsIntents(context)

        assertEquals(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
            intents.first().component,
        )
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intents.last().action)
        assertEquals("package:${context.packageName}", intents.last().dataString)
    }

    @Test
    fun hyperOsBatteryUsesExplicitComponentAndGenericFallbacks() {
        val intents = createHyperOsBatterySettingsIntents(context)

        assertEquals(
            ComponentName(
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
            ),
            intents.first().component,
        )
        assertEquals(context.packageName, intents.first().getStringExtra("package_name"))
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, intents[1].action)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intents.last().action)
        assertEquals("package:${context.packageName}", intents.last().dataString)
    }

    @Test
    fun settingsLauncherFallsBackAfterUnavailableDestination() {
        val attempted = mutableListOf<String?>()
        val intents = listOf(Intent("first"), Intent("fallback"))

        val launched = launchFirstAvailableSettings(intents) { intent ->
            attempted += intent.action
            if (intent.action == "first") throw ActivityNotFoundException("not available")
        }

        assertTrue(launched)
        assertEquals(listOf("first", "fallback"), attempted)
    }

    @Test
    fun settingsLauncherReportsWhenEveryDestinationFails() {
        val launched = launchFirstAvailableSettings(
            listOf(Intent("first"), Intent("fallback")),
        ) { throw SecurityException("blocked") }

        assertFalse(launched)
    }
}
