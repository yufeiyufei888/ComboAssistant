# 连招助手

连招助手是一个供个人侧载的 Android 本地触控录制与回放工具。它使用无障碍服务的 `dispatchGesture()` 与可信的 `TYPE_ACCESSIBILITY_OVERLAY`，不需要 Root、Shizuku 或普通悬浮窗权限。

> [!WARNING]
> 当前版本为 **v0.2.0-beta.1**。自动化测试通过并不代表澎湃OS或真实游戏兼容；Redmi K80 Pro 实机验收尚未完成。请勿用于排位赛、账号资产操作或无人值守自动化，并先确认游戏规则。

## v0.2 重点

- 连续离线录制：3 秒倒计时后透明层持续采集点击、长按、滑动与多指轨迹，直到点击“结束并保存”。两次手势之间的真实等待会写入时间轴，保存后不会自动试播。
- 稳定前台会话：同一游戏内登录页、加载页、Surface 与比赛场景切换会刷新而不是清空悬浮键；输入法、SystemUI 和权限窗口只视为临时遮挡。
- 可选增强识别：用户可单独授权“使用情况访问”，应用只低频查询最近前台 Activity 包名，不保存使用历史，也不联网。关闭或拒绝后继续使用无障碍事件识别。
- 集中布局模式：正常状态下设置球和连招键均锁定。进入“布局按键”后才可选择、拖动和调整大小/透明度；“完成并锁定”一次保存，“取消”完整回滚。
- 安全回放：开始前及每个手势段前重新验证目标包名、稳定方向和屏幕状态。回放串行执行，红色停止键可立即取消当前注入。

普通无 Root 应用不能在同一透明层上既完整读取原始 `MotionEvent`，又把同一串触摸实时原样透传给游戏。因此录制期间底层游戏不会同步响应；需要根据录制前的画面盲录，保存后再手动回放。这是 Android 公开 API 的能力边界，不等同于小米系统组件的输入层实现。

## 功能

- 坐标按录制屏幕宽高归一化保存；同方向、不同分辨率按比例映射，方向变化时拒绝执行。
- 每条连招可设置 36–96dp 视觉大小、20%–100% 透明度、单独显示/隐藏、0.25×–4× 倍速、1–999 次重复、0–10 秒固定重复间隔。
- 默认值：56dp、75%、1×、重复 1 次、间隔 100ms。
- 单次录制最多 60 秒或 200 个已完成手势。达到上限自动保存已有有效内容；旋转、熄屏、切换到真实其他应用或服务中断会取消录制。
- Room 保存 v1 版本化 JSON 时间轴，DataStore 保留现有全局悬浮设置；覆盖安装 v0.1 时不做破坏性迁移。
- Debug 版包含触控测试场和仅驻留内存的最近 200 条前台识别日志，不记录窗口文字。

## 使用

1. 安装 Debug APK，阅读用途、误触和游戏规则风险说明后确认。
2. 在系统无障碍设置中开启“连招助手触控服务”。如需增强识别，再在主界面单独开启并授权“使用情况访问”。
3. 打开目标游戏，点击紫色设置球与“新建录制”。倒计时后按预想节奏连续录制；此时游戏不会响应触摸。
4. 点击录制 HUD 的“结束并保存”，命名和编辑参数，然后在自动进入的布局模式中摆放按键，点击“完成并锁定”。
5. 正常模式下短按连招键执行、长按编辑。需要移动时从设置面板重新进入“布局按键”。回放中可随时点击红色停止按钮。

## 权限与隐私

应用不声明 `INTERNET`、`QUERY_ALL_PACKAGES` 或 `SYSTEM_ALERT_WINDOW`，不读取窗口内容、截图、账号或游戏数据。无障碍服务保持 `canPerformGestures=true`、`canRetrieveWindowContent=false`、`isAccessibilityTool=false`。

唯一新增权限是可选的 `PACKAGE_USAGE_STATS`；它必须由用户在系统设置中单独授权。应用只查询最近的前台事件用于恢复/校验包名，不持久化结果。

项目不尝试绕过反作弊，也不提供无限循环、定时任务、音量键触发、Root/Shizuku、跨方向执行、云同步或导入导出。

## 构建与验证

环境：JDK 17、Android SDK 36、Gradle 8.11.1；`minSdk 26`、`compileSdk/targetSdk 36`。

```powershell
$env:JAVA_HOME = '<JDK 17 路径>'
$env:ANDROID_SDK_ROOT = '<Android SDK 路径>'
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

本地构建 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。CI 上传的 `ComboAssistant-v0.2.0-beta.1-debug-ci.apk` 使用临时 CI 调试签名，仅用于自动化验证；GitHub Pre-release 的可侧载文件固定为 `ComboAssistant-v0.2.0-beta.1-debug.apk`，并附带 `SHA256SUMS.txt`。发布文件必须从合并后的 `main` 重新构建并核验包名、版本、权限、文件哈希和既有签名证书。

完整测试、CI 与 Redmi K80 Pro 验收清单见 [docs/testing.md](docs/testing.md)，Beta 状态见 [docs/known-issues.md](docs/known-issues.md)。

## 许可证

源码按 [MIT License](LICENSE) 开源。使用者需自行确认所在地区法律、游戏服务条款与账号风险。
