package com.yufei.comboassistant

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityUiAutomatorTest {
    @Test
    fun launchesDisclosureFlowEndToEnd() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val device = UiDevice.getInstance(instrumentation)
        val packageName = context.packageName

        device.pressHome()
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )

        device.wait(Until.hasObject(By.pkg(packageName).depth(0)), 5_000L)
        assertNotNull(device.wait(Until.findObject(By.text("连招助手")), 5_000L))
        assertNotNull(device.findObject(By.textContains("触控服务")))
        assertNotNull(device.findObject(By.textContains("权限用途")))
    }
}
