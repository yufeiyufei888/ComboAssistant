package com.yufei.comboassistant

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.yufei.comboassistant.data.GlobalSettings
import com.yufei.comboassistant.domain.Combo
import com.yufei.comboassistant.domain.GestureSegment
import com.yufei.comboassistant.domain.MacroTimeline
import com.yufei.comboassistant.domain.PointerStroke
import com.yufei.comboassistant.domain.ScreenOrientation
import com.yufei.comboassistant.domain.TouchSample
import com.yufei.comboassistant.ui.theme.ComboAssistantTheme

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
@Preview(name = "compact-light", widthDp = 320, heightDp = 640, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "compact-dark", widthDp = 320, heightDp = 640, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "compact-font-1.5", widthDp = 320, heightDp = 640, fontScale = 1.5f)
@Preview(name = "phone-light", widthDp = 393, heightDp = 873, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "phone-dark", widthDp = 393, heightDp = 873, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "phone-font-1.5", widthDp = 393, heightDp = 873, fontScale = 1.5f)
@Preview(name = "landscape-light", widthDp = 873, heightDp = 393, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "landscape-dark", widthDp = 873, heightDp = 393, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "landscape-font-1.5", widthDp = 873, heightDp = 393, fontScale = 1.5f)
annotation class MainScreenScreenshotMatrix

@PreviewTest
@MainScreenScreenshotMatrix
@Composable
fun MainScreenPreviewScreenshotTest() {
    val combo = Combo(
        id = "preview",
        name = "闪避接反击",
        targetPackage = "com.example.game",
        orientation = ScreenOrientation.LANDSCAPE,
        recordedWidth = 2400,
        recordedHeight = 1080,
        speed = 1.5f,
        repeatCount = 3,
        timeline = MacroTimeline(
            segments = listOf(
                GestureSegment(
                    gapBeforeMs = 100L,
                    strokes = listOf(
                        PointerStroke(
                            pointerId = 0,
                            startOffsetMs = 0L,
                            durationMs = 120L,
                            samples = listOf(
                                TouchSample(0L, 0.2f, 0.7f),
                                TouchSample(120L, 0.6f, 0.4f),
                            ),
                        ),
                    ),
                ),
            ),
        ),
        createdAt = 1L,
        updatedAt = 1L,
    )
    ComboAssistantTheme {
        MainScreen(
            state = MainUiState(
                combos = listOf(combo),
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
