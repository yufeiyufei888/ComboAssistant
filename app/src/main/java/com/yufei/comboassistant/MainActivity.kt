package com.yufei.comboassistant

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yufei.comboassistant.domain.Combo
import com.yufei.comboassistant.ui.theme.ComboAssistantTheme
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import kotlin.math.roundToInt

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val serviceEnabled = mutableStateOf(false)
    private val usageAccessGranted = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ComboAssistantTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                MainScreen(
                    state = state,
                    serviceEnabled = serviceEnabled.value,
                    usageAccessGranted = usageAccessGranted.value,
                    onAcceptDisclosure = viewModel::setDisclosureAccepted,
                    onOpenAccessibility = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onSetFloatingBall = viewModel::setFloatingBallEnabled,
                    onSetButtonsHidden = viewModel::setButtonsHidden,
                    onSetEnhancedForegroundDetection = viewModel::setEnhancedForegroundDetection,
                    onOpenUsageAccess = ::openUsageAccessSettings,
                    onSaveCombo = viewModel::save,
                    onDeleteCombo = viewModel::delete,
                    onOpenTouchTest = {
                        startActivity(Intent(this, TouchTestActivity::class.java))
                    },
                    showDebugTools = BuildConfig.DEBUG,
                    appVersion = BuildConfig.VERSION_NAME,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        serviceEnabled.value = isComboServiceEnabled(this)
        usageAccessGranted.value = isUsageAccessGranted(this)
    }

    private fun openUsageAccessSettings() {
        val targetedIntent = createUsageAccessSettingsIntent(this)
        runCatching { startActivity(targetedIntent) }
            .onFailure { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
    }
}

fun isComboServiceEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
        it.resolveInfo.serviceInfo.packageName == context.packageName &&
            it.resolveInfo.serviceInfo.name.endsWith("ComboAccessibilityService")
    }
}

fun isUsageAccessGranted(context: Context): Boolean {
    val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            context.applicationInfo.uid,
            context.packageName,
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            context.applicationInfo.uid,
            context.packageName,
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

fun createUsageAccessSettingsIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_USAGE_ACCESS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: MainUiState,
    serviceEnabled: Boolean,
    usageAccessGranted: Boolean,
    onAcceptDisclosure: (Boolean) -> Unit,
    onOpenAccessibility: () -> Unit,
    onSetFloatingBall: (Boolean) -> Unit,
    onSetButtonsHidden: (Boolean) -> Unit,
    onSetEnhancedForegroundDetection: (Boolean) -> Unit,
    onOpenUsageAccess: () -> Unit,
    onSaveCombo: (Combo) -> Unit,
    onDeleteCombo: (String) -> Unit,
    onOpenTouchTest: () -> Unit,
    showDebugTools: Boolean,
    appVersion: String,
) {
    var editingComboId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingComboId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingCombo = state.combos.firstOrNull { it.id == editingComboId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("连招助手", fontWeight = FontWeight.Bold)
                        Text("本地触控录制与回放", style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = { Icon(Icons.Rounded.Settings, contentDescription = null) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("combo_list"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                PermissionCard(
                    disclosureAccepted = state.settings.disclosureAccepted,
                    serviceEnabled = serviceEnabled,
                    onAcceptDisclosure = onAcceptDisclosure,
                    onOpenAccessibility = onOpenAccessibility,
                )
            }
            item {
                UsageCard()
            }
            if (state.settings.disclosureAccepted) {
                item {
                    GlobalSettingsCard(
                        floatingBallEnabled = state.settings.floatingBallEnabled,
                        buttonsHidden = state.settings.buttonsHidden,
                        enhancedForegroundDetection = state.settings.enhancedForegroundDetection,
                        usageAccessGranted = usageAccessGranted,
                        onSetFloatingBall = onSetFloatingBall,
                        onSetButtonsHidden = onSetButtonsHidden,
                        onSetEnhancedForegroundDetection = onSetEnhancedForegroundDetection,
                        onOpenUsageAccess = onOpenUsageAccess,
                    )
                }
            }
            item {
                Text(
                    text = "已保存连招 (${state.combos.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (state.combos.isEmpty()) {
                item {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Text(
                            "启用触控服务后，打开游戏并点击悬浮球中的“新建录制”。",
                            modifier = Modifier.padding(18.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            items(state.combos, key = { it.id }) { combo ->
                ComboCard(
                    combo = combo,
                    onEdit = { editingComboId = combo.id },
                    onToggleVisible = { onSaveCombo(combo.copy(visible = it)) },
                    onDelete = { deletingComboId = combo.id },
                )
            }
            if (showDebugTools) {
                item {
                    OutlinedButton(onClick = onOpenTouchTest, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.BugReport, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("打开触控测试场（调试版）")
                    }
                }
            }
            item {
                Text(
                    "提示：部分游戏禁止宏或会拦截无障碍注入。请遵守游戏规则；本应用不会绕过反作弊。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            item {
                Text(
                    "版本 $appVersion · 本地运行，无网络权限",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }

    if (editingCombo != null) {
        ComboEditorDialog(
            combo = editingCombo,
            onDismiss = { editingComboId = null },
            onSave = {
                onSaveCombo(it)
                editingComboId = null
            },
        )
    }

    val deletingCombo = state.combos.firstOrNull { it.id == deletingComboId }
    if (deletingCombo != null) {
        AlertDialog(
            onDismissRequest = { deletingComboId = null },
            title = { Text("删除连招？") },
            text = { Text("“${deletingCombo.name}”及其触控轨迹将从本机永久删除。") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteCombo(deletingCombo.id)
                    deletingComboId = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deletingComboId = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun PermissionCard(
    disclosureAccepted: Boolean,
    serviceEnabled: Boolean,
    onAcceptDisclosure: (Boolean) -> Unit,
    onOpenAccessibility: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = if (serviceEnabled) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Text(
                        if (serviceEnabled) "触控服务已开启" else "触控服务未开启",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Text(
                "服务只会在你主动录制或点击连招键时保存坐标与时间，并执行固定触控序列；不会读取屏幕文字、截图、账号或联网传输数据。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = disclosureAccepted,
                    onCheckedChange = onAcceptDisclosure,
                    modifier = Modifier.testTag("disclosure_checkbox"),
                )
                Text("我已了解权限用途、游戏规则与误触风险")
            }
            Button(
                onClick = onOpenAccessibility,
                enabled = disclosureAccepted,
                modifier = Modifier.fillMaxWidth().testTag("open_accessibility"),
            ) {
                Text(if (serviceEnabled) "查看系统触控服务设置" else "前往系统设置开启服务")
            }
        }
    }
}

@Composable
private fun UsageCard() {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("使用方法", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("1. 开启“连招助手触控服务”，然后进入目标游戏。")
            Text("2. 点击紫色“连”悬浮球 → 新建录制；倒计时结束后可连续操作，也可在手势之间等待。")
            Text("3. 录制期间触摸由透明录制层接收，游戏不会同步响应。点击“结束并保存”后统一生成连招，不会自动试播。")
            Text("4. 在“布局按键”中解锁、拖动和调节按键；点击“完成并锁定”后，短按连招键执行。")
        }
    }
}

@Composable
private fun GlobalSettingsCard(
    floatingBallEnabled: Boolean,
    buttonsHidden: Boolean,
    enhancedForegroundDetection: Boolean,
    usageAccessGranted: Boolean,
    onSetFloatingBall: (Boolean) -> Unit,
    onSetButtonsHidden: (Boolean) -> Unit,
    onSetEnhancedForegroundDetection: (Boolean) -> Unit,
    onOpenUsageAccess: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingSwitch("显示设置悬浮球", floatingBallEnabled, onSetFloatingBall)
            HorizontalDivider()
            SettingSwitch("暂时隐藏全部连招键", buttonsHidden, onSetButtonsHidden)
            HorizontalDivider()
            SettingSwitch(
                label = "增强前台识别",
                value = enhancedForegroundDetection,
                onChanged = onSetEnhancedForegroundDetection,
                supportingText = "约每秒只查询最近的前台应用包名；不保存使用历史，也不联网。未授权时自动退回无障碍事件识别。",
                testTag = "enhanced_foreground_detection",
            )
            Surface(
                color = if (usageAccessGranted) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
                contentColor = if (usageAccessGranted) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                },
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = if (usageAccessGranted) {
                        "使用情况访问权限：已授权"
                    } else {
                        "使用情况访问权限：未授权"
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            OutlinedButton(
                onClick = onOpenUsageAccess,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("open_usage_access"),
            ) {
                Text(if (usageAccessGranted) "管理使用情况访问权限" else "前往授权使用情况访问权限")
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    value: Boolean,
    onChanged: (Boolean) -> Unit,
    supportingText: String? = null,
    testTag: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label)
            if (supportingText != null) {
                Text(
                    supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val switchModifier = if (testTag != null) Modifier.testTag(testTag) else Modifier
        Switch(
            checked = value,
            onCheckedChange = onChanged,
            modifier = switchModifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics { contentDescription = label },
        )
    }
}

@Composable
private fun ComboCard(
    combo: Combo,
    onEdit: () -> Unit,
    onToggleVisible: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(combo.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(combo.targetPackage, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = combo.visible, onCheckedChange = onToggleVisible)
            }
            Text(
                "${combo.orientation.name} · ${combo.timeline.segments.size} 段 · ${formatDuration(combo.timeline.durationMs)} · ${combo.speed}× · 重复 ${combo.repeatCount} 次",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

@Composable
private fun ComboEditorDialog(combo: Combo, onDismiss: () -> Unit, onSave: (Combo) -> Unit) {
    var name by remember(combo.id, combo.updatedAt) { mutableStateOf(combo.name) }
    var size by remember(combo.id, combo.updatedAt) { mutableStateOf(combo.buttonSizeDp) }
    var opacity by remember(combo.id, combo.updatedAt) { mutableStateOf(combo.buttonOpacity) }
    var speed by remember(combo.id, combo.updatedAt) { mutableStateOf(combo.speed) }
    var repeat by remember(combo.id, combo.updatedAt) { mutableStateOf(combo.repeatCount.toString()) }
    var interval by remember(combo.id, combo.updatedAt) { mutableStateOf(combo.repeatIntervalMs.toFloat()) }
    var visible by remember(combo.id, combo.updatedAt) { mutableStateOf(combo.visible) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑连招") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("按键大小：${size.roundToInt()}dp")
                Slider(value = size, onValueChange = { size = it }, valueRange = 36f..96f, steps = 59)
                Text("按键透明度：${(opacity * 100).roundToInt()}%")
                Slider(value = opacity, onValueChange = { opacity = it }, valueRange = 0.2f..1f, steps = 15)
                Text("执行倍速：${speed}×")
                Slider(value = speed, onValueChange = { speed = (it * 4).roundToInt() / 4f }, valueRange = 0.25f..4f, steps = 14)
                OutlinedTextField(
                    value = repeat,
                    onValueChange = { repeat = it.filter(Char::isDigit).take(3) },
                    label = { Text("重复次数 1–999") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("重复间隔：${interval.roundToInt()}ms")
                Slider(
                    value = interval,
                    onValueChange = { interval = (it / 50f).roundToInt() * 50f },
                    valueRange = 0f..10_000f,
                    steps = 199,
                )
                SettingSwitch(
                    label = "显示此连招按键",
                    value = visible,
                    onChanged = { visible = it },
                )
                Text("按键位置请在游戏中的“布局按键”模式解锁拖动，完成后会重新锁定。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    combo.copy(
                        name = name,
                        buttonSizeDp = size,
                        buttonOpacity = opacity,
                        speed = speed,
                        repeatCount = repeat.toIntOrNull()?.coerceIn(1, 999) ?: 1,
                        repeatIntervalMs = interval.toLong(),
                        visible = visible,
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun formatDuration(valueMs: Long): String = String.format(Locale.CHINA, "%.2f 秒", valueMs / 1000f)
