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

## 当前验证结果（2026-08-12）

- `testDebugUnitTest`：11 项通过，0 失败、0 跳过。
- `validateDebugScreenshotTest`：九张 Compose 预览基线验证通过。
- `compileDebugAndroidTestKotlin`：Hilt、Room、UI Automator 与 Dropshots 仪器测试源码编译通过。
- `jacocoDebugReport`：XML 与 HTML 覆盖率报告生成成功。
- `lintDebug`：执行成功，0 error、26 个非阻断 warning；主要是依赖版本建议、程序化自定义 View 的 XML 构造器/无障碍提示和悬浮层动态中文文案提示。
- `assembleDebug` 与 `apksigner verify`：通过；APK 使用 v2 调试签名，包名为 `com.yufei.comboassistant`。
- `connectedDebugAndroidTest`：当前没有连接 Android 设备，尚未执行；Redmi K80 Pro 真机清单仍待逐项验收。

## 覆盖范围

- JUnit4：时间轴间隔不含镜像耗时、60 秒/200 段边界、倍速延时、最小 16ms 手势时长、包名和方向门禁、并发拒绝、1/999 次精确重复、固定重复间隔、JSON 多指往返及参数归一化。
- Robolectric/Compose：首次权限说明、未确认时禁止跳转无障碍设置、连招列表与编辑弹窗状态恢复。
- Compose 预览截图：320×640、393×873、873×393 三种尺寸，每种覆盖浅色、深色和 1.5 倍字体，共九个快照。
- Android 仪器测试：Hilt 测试 Runner、Room 内存数据库 CRUD、UI Automator 启动/权限说明流程、Dropshots 触控测试场基线。
- Debug 触控测试场：点击计数、完整手势计数、最大同时触点和轨迹可视化。

## Redmi K80 Pro 实机清单

建议测试前关闭自动旋转以外的手势增强、保持屏幕常亮，并使用不涉及账号资产的测试游戏/测试页面。

- [ ] `adb install -r app/build/outputs/apk/debug/app-debug.apk` 成功，应用版本显示为 Debug 包。
- [ ] 首次未勾选用途说明时，前往无障碍设置按钮不可用；勾选后可进入系统设置。
- [ ] 澎湃OS允许开启服务，紫色设置球出现；应用未申请普通悬浮窗权限。
- [ ] 横屏目标中完成 3 秒倒计时，依次录制点击、长按、滑动和双指；每次抬手后动作被即时镜像。
- [ ] 停止并命名后，独立连招键只在相同包名和横屏显示；拖动后重启服务仍保持位置。
- [ ] 36dp/96dp、20%/100% 透明度、单独隐藏和全部隐藏均生效。
- [ ] 0.25× 与 4× 的动作时长和动作间延时同步缩放，重复间隔不随倍速缩放。
- [ ] 1 次重复完整结束；999 次只启动若干轮，然后点击红色停止按钮验证安全停止。
- [ ] 回放时启动第二条连招被拒绝；目标应用拒绝注入时明确提示不兼容/执行失败。
- [ ] 回放中切换应用、旋转、熄屏、关闭服务或用户手势取消时自动终止。
- [ ] 录制达到 60 秒或 200 段自动结束；取消或镜像失败不保存残缺连招。
- [ ] 重启应用和无障碍服务后，Room 连招与 DataStore 悬浮状态恢复。

记录设备型号、澎湃OS版本、Android 版本、游戏包名/版本、方向、分辨率、每项结果与失败录像。游戏是否真正响应不能从 `dispatchGesture()` 成功回调单独推断。
