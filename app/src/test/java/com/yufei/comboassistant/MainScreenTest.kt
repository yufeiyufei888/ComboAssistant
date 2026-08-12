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
                    onAcceptDisclosure = { accepted.value = it },
                    onOpenAccessibility = {},
                    onSetFloatingBall = {},
                    onSetButtonsHidden = {},
                    onSaveCombo = {},
                    onDeleteCombo = {},
                    onOpenTouchTest = {},
                    showDebugTools = false,
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
                    onAcceptDisclosure = {},
                    onOpenAccessibility = {},
                    onSetFloatingBall = {},
                    onSetButtonsHidden = {},
                    onSaveCombo = {},
                    onDeleteCombo = {},
                    onOpenTouchTest = {},
                    showDebugTools = true,
                )
            }
        }

        composeRule.onNodeWithTag("combo_list").performScrollToIndex(4)
        composeRule.onNodeWithText("测试连招").assertIsDisplayed()
        composeRule.onNodeWithText("编辑").performClick()
        composeRule.onNodeWithText("编辑连招").assertIsDisplayed()
        composeRule.onNodeWithText("重复次数 1–999").performScrollTo().assertIsDisplayed()
    }
}
