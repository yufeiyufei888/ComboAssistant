# 连招助手

连招助手是一款仅供个人侧载的本地 Android 触控录制与回放工具。它使用无障碍服务的 `dispatchGesture()` 和 `TYPE_ACCESSIBILITY_OVERLAY`，不需要 Root、Shizuku 或普通悬浮窗权限。

> [!WARNING]
> **当前是实验性原型（Prototype），不是稳定成品。** 实机测试表明它已经能完成部分录制与回放，但在同一游戏的登录页/开局场景切换、悬浮键位置锁定以及连续录制体验上仍有明显缺陷。请先阅读 [已知问题与路线图](docs/known-issues.md)，不要用于排位赛、账号资产操作或无人值守自动化。

## 当前能力

- 透明录制层保存点击、长按、滑动和多指轨迹。每次抬手后，录制层暂时不可触摸并把刚完成的手势镜像到下层应用；镜像耗时不会写入连招时间轴。
- 单条连招最多 60 秒或 200 个手势段，达到限制自动保存；镜像失败、用户取消、切换应用、旋转、熄屏或服务中断会立即停止。
- 保存目标包名、录制方向、录制尺寸和归一化坐标。同方向不同分辨率按比例映射；目标应用或方向不一致时拒绝执行。
- 设置悬浮球可新建录制、显示/隐藏全部连招键、打开主界面或停止执行；每条已启用连招有独立、可拖动的悬浮按键。
- 连招键支持 36–96dp、20%–100% 透明度、单独隐藏、0.25×–4× 倍速、1–999 次重复和 0–10 秒重复间隔。默认值是 56dp、75%、1×、1 次、100ms。
- Room 保存版本化 JSON 时间轴，DataStore 保存全局悬浮状态；无网络、云同步、导入导出、截图或屏幕文字采集。
- 调试版包含“触控测试场”，用于观察点击、轨迹和最大同时触点，不依赖真实游戏结果。

## 使用

1. 安装 Debug APK 并打开“连招助手”。阅读权限用途和误触/游戏规则风险后勾选确认。
2. 点击“前往系统设置开启服务”，在系统无障碍设置中开启“连招助手触控服务”。
3. 打开目标游戏，点击紫色“连”悬浮球，再点“新建录制”。3 秒倒计时后开始操作。
4. 点击右上角红色“停止录制”，保存后编辑名称和参数。短按独立连招键执行，拖动调整位置，长按编辑。
5. 回放期间点击右上角红色“停止”按钮。它与录制停止按钮使用同一安全区域。

普通应用无法获得小米系统组件的旁路触控权限，因此这里是“抬手后镜像”，不是完全同步的系统级录制。某些游戏会拒绝无障碍手势或禁止宏；应用不会尝试绕过反作弊，使用前请确认游戏规则。

## 构建

环境：JDK 17、Android SDK 36、Gradle 8.11.1。项目使用 Kotlin、Jetpack Compose、Material 3、Hilt、Room 与 DataStore，`minSdk 26`，`compileSdk/targetSdk 36`。

```powershell
$env:JAVA_HOME = '<JDK 17 路径>'
$env:ANDROID_SDK_ROOT = '<Android SDK 路径>'
.\gradlew.bat :app:assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`；公开的实验版 APK 可从 GitHub Releases 下载。完整测试命令、截图基线和 Redmi K80 Pro 验收步骤见 [docs/testing.md](docs/testing.md)。

## 权限与隐私

Manifest 不声明 `INTERNET`、`QUERY_ALL_PACKAGES` 或 `SYSTEM_ALERT_WINDOW`。无障碍服务配置为 `canPerformGestures=true`、`canRetrieveWindowContent=false`、`isAccessibilityTool=false`；服务只订阅窗口状态变化，用于目标包名和前台切换门禁。

首版不支持无限循环、定时任务、音量键触发、Root/Shizuku、跨方向执行或云同步。本项目不面向应用商店发布。

## 项目状态与贡献

这个仓库保留当前可运行原型和真实验收边界，欢迎围绕 [已知问题](docs/known-issues.md) 提交 Issue 或 Pull Request。修复时请保持无网络、无 Root、不读取窗口内容、不绕过反作弊的边界，并区分自动化测试结果与真实游戏兼容性。

## 许可证

源码按 [MIT License](LICENSE) 开源。使用者需要自行确认所在地区法律、游戏服务条款和账号风险。
