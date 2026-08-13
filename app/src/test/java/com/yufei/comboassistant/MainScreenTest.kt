package com.yufei.comboassistant

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.runtime.mutableStateOf
import com.yufei.comboassistant.data.GlobalSettings
import com.yufei.comboassistant.ui.theme.ComboAssistantTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun accessibilityButtonRequiresExplicitDisclosure() {
        val accepted = mutableStateOf(false)
        composeRule.setContent {
            ComboAssistantTheme {
                MainScreen(
                    state = MainUiState(settings = GlobalSettings(disclosureAccepted = accepted.value)),
                    serviceEnabled = false,
                    usageAccessGranted = false,
                    onAcceptDisclosure = { accepted.value = it },
                    onOpenAccessibility = {},
                    onSetFloatingBall = {},
                    onSetButtonsHidden = {},
                    onSetEnhancedForegroundDetection = {},
                    onOpenUsageAccess = {},
                    onSaveCombo = {},
                    onDeleteCombo = {},
                    onOpenTouchTest = {},
                    showDebugTools = false,
                    appVersion = "0.2.0-beta.1-debug",
                )
            }
        }

        composeRule.onNodeWithTag("open_accessibility").assertIsNotEnabled()
        composeRule.onNodeWithTag("disclosure_checkbox").performClick()
        composeRule.onNodeWithTag("open_accessibility").assertIsEnabled()
    }

    @Test
    fun comboListOpensEditorWithPersistedValues() {
        composeRule.setContent {
            ComboAssistantTheme {
                MainScreen(
                    state = MainUiState(
                        combos = listOf(testCombo()),
                        settings = GlobalSettings(disclosureAccepted = true),
                    ),
                    serviceEnabled = true,
                    usageAccessGranted = true,
                    onAcceptDisclosure = {},
                    onOpenAccessibility = {},
                    onSetFloatingBall = {},
                    onSetButtonsHidden = {},
                    onSetEnhancedForegroundDetection = {},
                    onOpenUsageAccess = {},
                    onSaveCombo = {},
                    onDeleteCombo = {},
                    onOpenTouchTest = {},
                    showDebugTools = true,
                    appVersion = "0.2.0-beta.1-debug",
                )
            }
        }

        composeRule.onNodeWithTag("combo_list").performScrollToIndex(4)
        composeRule.onNodeWithText("测试连招").assertIsDisplayed()
        composeRule.onNodeWithText("编辑").performClick()
        composeRule.onNodeWithText("编辑连招").assertIsDisplayed()
        composeRule.onNodeWithText("重复次数 1–999").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun enhancedDetectionExplainsAndOpensUsageAccessSettings() {
        val enhanced = mutableStateOf(false)
        var openSettingsCount = 0
        composeRule.setContent {
            ComboAssistantTheme {
                MainScreen(
                    state = MainUiState(
                        settings = GlobalSettings(
                            disclosureAccepted = true,
                            enhancedForegroundDetection = enhanced.value,
                        ),
                    ),
                    serviceEnabled = true,
                    usageAccessGranted = false,
                    onAcceptDisclosure = {},
                    onOpenAccessibility = {},
                    onSetFloatingBall = {},
                    onSetButtonsHidden = {},
                    onSetEnhancedForegroundDetection = { enhanced.value = it },
                    onOpenUsageAccess = { openSettingsCount += 1 },
                    onSaveCombo = {},
                    onDeleteCombo = {},
                    onOpenTouchTest = {},
                    showDebugTools = false,
                    appVersion = "0.2.0-beta.1-debug",
                )
            }
        }

        composeRule.onNodeWithTag("combo_list").performScrollToIndex(2)
        composeRule.onNodeWithText("使用情况访问权限：未授权").assertIsDisplayed()
        composeRule.onNodeWithTag("enhanced_foreground_detection").performClick()
        composeRule.runOnIdle { assertTrue(enhanced.value) }
        composeRule.onNodeWithTag("open_usage_access").performClick()
        composeRule.runOnIdle { assertEquals(1, openSettingsCount) }
    }

    @Test
    fun usageGuideClearlyDescribesOfflineContinuousRecording() {
        composeRule.setContent {
            ComboAssistantTheme {
                MainScreen(
                    state = MainUiState(),
                    serviceEnabled = false,
                    usageAccessGranted = false,
                    onAcceptDisclosure = {},
                    onOpenAccessibility = {},
                    onSetFloatingBall = {},
                    onSetButtonsHidden = {},
                    onSetEnhancedForegroundDetection = {},
                    onOpenUsageAccess = {},
                    onSaveCombo = {},
                    onDeleteCombo = {},
                    onOpenTouchTest = {},
                    showDebugTools = false,
                    appVersion = "0.2.0-beta.1-debug",
                )
            }
        }

        composeRule.onNodeWithText(
            "3. 录制期间触摸由透明录制层接收，游戏不会同步响应。点击“结束并保存”后统一生成连招，不会自动试播。",
        ).performScrollTo().assertIsDisplayed()
    }
}
