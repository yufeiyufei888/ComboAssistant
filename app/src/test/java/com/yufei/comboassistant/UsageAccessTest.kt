package com.yufei.comboassistant

import android.app.AppOpsManager
import android.content.Context
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UsageAccessTest {
    @Test
    fun usageAccessIntentTargetsThisPackage() {
        val context = RuntimeEnvironment.getApplication() as Context

        val intent = createUsageAccessSettingsIntent(context)

        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, intent.action)
        assertEquals("package:${context.packageName}", intent.dataString)
    }

    @Test
    fun usageAccessCheckReflectsAppOpsGrant() {
        val context = RuntimeEnvironment.getApplication() as Context
        val appOps = requireNotNull(context.getSystemService(AppOpsManager::class.java))
        val shadow = shadowOf(appOps)

        shadow.setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            context.applicationInfo.uid,
            context.packageName,
            AppOpsManager.MODE_IGNORED,
        )
        assertFalse(isUsageAccessGranted(context))

        shadow.setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            context.applicationInfo.uid,
            context.packageName,
            AppOpsManager.MODE_ALLOWED,
        )
        assertTrue(isUsageAccessGranted(context))
    }
}
