# 测试与验收

自动化测试只能证明代码和测试环境中的行为；触控精度、澎湃OS无障碍限制及具体游戏兼容性必须在 Redmi K80 Pro 上实测。

## 环境

- JDK 17
- Android SDK Platform 36 / Build Tools 36
- Gradle Wrapper 8.11.1
- AGP 8.8.2（为 Dropshots 0.5.0 与 Gradle 8.11.1 兼容；对 compileSdk 36 使用已记录的抑制警告）
- Kotlin 2.1.21

Windows 中文路径需要项目中的 `android.overridePathCheck=true`。如果 Wrapper 首次下载受限，可用已校验 SHA-256 的 Gradle 8.11.1 分发包。Compose 截图 Worker 若在中文路径下报告找不到 `GradleWorkerMain`，请临时映射纯 ASCII 盘符后执行截图任务：

```powershell
subst R: '<包含 ComboAssistant 的工作区路径>'
Push-Location R:\ComboAssistant
.\gradlew.bat :app:validateDebugScreenshotTest
Pop-Location
subst R: /d
```

## 本地测试命令

```powershell
$env:JAVA_HOME = '<JDK 17 路径>'
$env:ANDROID_SDK_ROOT = '<Android SDK 路径>'

# 领域逻辑、序列化、Robolectric/Compose
.\gradlew.bat :app:testDebugUnitTest

# JaCoCo XML 与 HTML 报告
.\gradlew.bat :app:jacocoDebugReport

# 编译主程序、仪器测试和预览截图测试
.\gradlew.bat :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:compileDebugScreenshotTestKotlin

# 更新/验证 Compose 预览截图基线
.\gradlew.bat :app:updateDebugScreenshotTest
.\gradlew.bat :app:validateDebugScreenshotTest

# 连接模拟器或设备后执行 UI Automator、Room 内存库与 Dropshots
.\gradlew.bat :app:connectedDebugAndroidTest

# 记录 Dropshots 基线（设备配置固定后执行）
.\gradlew.bat :app:recordDebugAndroidTestScreenshots

# 生成侧载包
.\gradlew.bat :app:assembleDebug
```

JaCoCo HTML 报告在 `app/build/reports/jacoco/jacocoDebugReport/html/`；Compose 截图报告在 `app/build/reports/screenshotTest/preview/debug/`；仪器测试结果在 `app/build/outputs/androidTest-results/connected/`。

## GitHub CI

- 每次 Pull Request 及 `main` 推送运行 JVM/Robolectric 测试、JaCoCo、Lint、Compose 截图验证、`androidTest` 编译和 Debug APK 构建。
- 每次 Pull Request 及 `main` 推送在固定 API 35、Google APIs、Pixel 7 模拟器运行 `connectedDebugAndroidTest`。
- API 26 兼容作业只在每周定时任务（UTC 周日 18:00，即北京时间周一 02:00）或手动 `workflow_dispatch` 时运行；手动触发同时会运行常规和 API 35 作业。
- CI 文件名为 `ComboAssistant-v0.2.0-beta.1-debug-ci.apk`，使用临时 CI 调试签名，不是侧载发布物。Pre-release 只上传从合并后 `main` 重新构建并用既有调试密钥签名的 `ComboAssistant-v0.2.0-beta.1-debug.apk` 与 `SHA256SUMS.txt`。

## 当前验证结果（v0.2.0-beta.1，2026-08-13）

- `testDebugUnitTest`：59 项通过，0 失败、0 跳过。新增覆盖前台状态机、连续离线录制、真实等待、Usage 事件年龄、方向稳定复测、事务式布局及回放停止竞态。
- `validateDebugScreenshotTest`：九张 Compose 预览基线更新并验证通过。
- `compileDebugAndroidTestKotlin`：Hilt、Room、UI Automator 与 Dropshots 仪器测试源码编译通过。
- `jacocoDebugReport`：XML 与 HTML 覆盖率报告生成成功。
- `lintDebug`：执行成功，0 error、27 个非阻断 warning；主要是依赖版本建议、程序化自定义 View 的 XML 构造器/无障碍提示和悬浮层动态中文文案提示。
- `assembleDebug` 与 `apksigner verify`：通过；包名 `com.yufei.comboassistant`、`versionCode=2`、`versionName=0.2.0-beta.1-debug`。发布 APK 显式使用既有本机调试密钥重新签名，证书 SHA-256 为 `b952979d47d4437b7bf694ab52b9f9165331ead74eaf9a780e7b32f550fe7d9c`。
- `connectedDebugAndroidTest`：当前没有连接 Android 设备，尚未执行；Redmi K80 Pro 真机清单仍待逐项验收。

上述“通过”项必须由最终源码的本地重跑和 Pull Request CI 再次确认；固定 API 35 模拟器结果以对应 GitHub Actions 运行记录为准。自动化全绿仍不等同于澎湃OS或真实游戏验收。

## 覆盖范围

- JUnit4：同包场景、输入法/SystemUI 遮挡、跨应用、Usage 冷启动与冲突；真实中间等待、60 秒/200 段、多指与取消；倍速、包名/方向门禁、并发拒绝、1/999 次重复、固定重复间隔、旧 JSON 往返和坏 JSON 隔离。
- Robolectric/Compose：首次权限说明、未确认时禁止跳转无障碍设置、连招列表与编辑弹窗状态恢复。
- Compose 预览截图：320×640、393×873、873×393 三种尺寸，每种覆盖浅色、深色和 1.5 倍字体，共九个快照。
- Android 仪器测试：Hilt 测试 Runner、Room 内存数据库 CRUD、UI Automator 启动/权限说明流程、Dropshots 触控测试场基线。
- Debug 触控测试场：点击计数、完整手势计数、最大同时触点和轨迹可视化。

## Beta 发布核验

从合并后的 `main` 重新构建后，先将最终侧载 APK 命名为 `ComboAssistant-v0.2.0-beta.1-debug.apk`，再执行以下核验；不要发布 CI 临时签名产物。

```powershell
# 版本与包名（将 <SDK> 替换为 Android SDK 路径）
& '<SDK>\build-tools\36.0.0\aapt2.exe' dump badging .\ComboAssistant-v0.2.0-beta.1-debug.apk

# 合并后的 APK 权限清单
& '<SDK>\build-tools\36.0.0\aapt2.exe' dump permissions .\ComboAssistant-v0.2.0-beta.1-debug.apk

# 签名证书
& '<SDK>\build-tools\36.0.0\apksigner.bat' verify --verbose --print-certs .\ComboAssistant-v0.2.0-beta.1-debug.apk

# 发布哈希
Get-FileHash .\ComboAssistant-v0.2.0-beta.1-debug.apk -Algorithm SHA256
```

核验结果必须为：包名 `com.yufei.comboassistant`、`versionCode=2`、`versionName=0.2.0-beta.1-debug`、签名证书 SHA-256 `b952979d47d4437b7bf694ab52b9f9165331ead74eaf9a780e7b32f550fe7d9c`。合并清单只允许可选 `PACKAGE_USAGE_STATS` 及构建工具自动生成的应用内部权限，不得包含 `INTERNET`、`QUERY_ALL_PACKAGES` 或 `SYSTEM_ALERT_WINDOW`。将 APK 的 SHA-256 写入同目录 `SHA256SUMS.txt` 后一并上传 Pre-release。

## Redmi K80 Pro 实机清单

建议测试前关闭自动旋转以外的手势增强、保持屏幕常亮，并使用不涉及账号资产的测试游戏/测试页面。

- [ ] `adb install -r ComboAssistant-v0.2.0-beta.1-debug.apk` 覆盖安装成功，应用版本显示为 `0.2.0-beta.1-debug`。
- [ ] 首次未勾选用途说明时，前往无障碍设置按钮不可用；勾选后可进入系统设置。
- [ ] 澎湃OS允许开启服务，紫色设置球出现；应用未申请普通悬浮窗权限。
- [ ] 实况足球登录页→加载页→开局→启动页往返时，同包连招键自动恢复；输入法、通知栏只暂时隐藏。
- [ ] 设置面板显示当前识别包名和隐藏原因；候选状态可手动确认为本次会话游戏，熄屏、服务重启或真实跨应用后该临时确认失效。
- [ ] 拒绝或关闭“使用情况访问”后无障碍事件识别仍可工作；授权后冷启动恢复与约每秒低频校验生效，应用不保存使用历史。
- [ ] 横屏目标中完成 3 秒倒计时，连续录制点击、长按、滑动、双指及中间等待；录制期间游戏明确不响应，结束后不自动试播。
- [ ] 保存命名后自动进入布局模式；拖动多个连招键与设置球，调整大小/透明度，“完成并锁定”持久化，“取消”恢复工作副本。
- [ ] 正常模式中的设置球与连招键均固定；滑动不移动、不执行，短按执行、长按编辑。
- [ ] 36dp/96dp 视觉大小、至少 48dp 实际触控区、20%/100% 透明度、单独隐藏和全部隐藏均生效。
- [ ] 0.25× 与 4× 的动作时长和动作间延时同步缩放，重复间隔不随倍速缩放。
- [ ] 1 次重复完整结束；999 次只启动若干轮，然后点击红色停止按钮验证安全停止。
- [ ] 回放时启动第二条连招被拒绝；目标应用拒绝注入时明确提示不兼容/执行失败。
- [ ] 回放中切换应用、旋转、熄屏、关闭服务或用户手势取消时自动终止。
- [ ] 录制达到 60 秒或 200 段自动保存已有已完成手势；取消、ACTION_CANCEL、旋转或真实跨应用不保存。
- [ ] 点击“结束并保存”时尚未抬起的手势被丢弃；最后一次抬手到点击结束的尾部等待不写入时间轴。
- [ ] 覆盖安装 v0.1 后旧连招、坐标和 DataStore 设置仍存在；签名证书指纹保持一致。
- [ ] 重启应用和无障碍服务后，Room 连招与 DataStore 悬浮状态恢复。

记录设备型号、澎湃OS版本、Android 版本、游戏包名/版本、方向、分辨率、每项结果与失败录像。游戏是否真正响应不能从 `dispatchGesture()` 成功回调单独推断。
