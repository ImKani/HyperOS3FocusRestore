# HyperOS3FocusRestore 项目环境配置

> 这份文件供后续 AI、开发工具和人工构建时读取。修改本机环境后，请同步修改本文件。

## 项目路径

- 项目根目录：`C:\Users\Kani\Desktop\FocusRestoreLSPosed`
- Android 模块：`app`
- Application ID：`com.hyperos3.focusrestore`
- Java 包名：`com.hyperos3.focusrestore`
- 当前版本：`0.12.2`
- 当前 Git 分支：`master`
- 远程仓库：`https://github.com/ImKani/HyperOS3FocusRestore.git`
- 默认不执行 `git push`，除非用户明确要求。

## 构建工具

- Gradle 目录：`C:\Users\Kani\Desktop\gradle-9.7.1`
- Gradle 可执行文件：`C:\Users\Kani\Desktop\gradle-9.7.1\bin\gradle.bat`
- 项目没有 `gradlew.bat`，不要使用 `./gradlew.bat`。
- Android SDK：`C:\Users\Kani\Desktop\Android\Sdk`
- Android Build Tools：`C:\Users\Kani\Desktop\Android\Sdk\build-tools\36.0.0`
- 当前 JDK：`C:\Users\Kani\scoop\apps\temurin25-jdk\current`
- 当前 Java 版本：Temurin 25.0.4
- Android compileSdk：`35`
- Android targetSdk：`35`
- Android minSdk：`27`
- Android Gradle Plugin：`8.7.3`
- Xposed API：`de.robv.android.xposed:api:82`，仅 `compileOnly`

## 推荐构建命令

PowerShell 中从项目根目录执行：

```powershell
Set-Location "C:\Users\Kani\Desktop\FocusRestoreLSPosed"
$env:ANDROID_HOME = "C:\Users\Kani\Desktop\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:JAVA_HOME = "C:\Users\Kani\scoop\apps\temurin25-jdk\current"
$env:GRADLE_USER_HOME = "C:\Users\Kani\Desktop\FocusRestoreLSPosed\.gradle-temp"
& "C:\Users\Kani\Desktop\gradle-9.7.1\bin\gradle.bat" testDebugUnitTest assembleDebug assembleRelease --no-daemon -x lintVitalAnalyzeRelease
```

说明：

- `.gradle-temp` 是临时构建缓存，构建完成后可以删除，不要提交。
- 如果缓存目录导致 Gradle native service 初始化失败，可以删除 `.gradle-temp` 后重试。
- 如果签名阶段出现权限问题，需要允许 Gradle 访问用户目录或项目 `.android` 目录；不要生成新的随机签名。
- Java 25 会产生 Java 8 source/target 弃用警告，当前不影响构建。
- Android metrics 写入 `C:\Users\Kani\.android` 失败的警告不影响构建。
- Release 的 `lintVital` 在某些 JDK 25 环境下可能失败；代码编译、Dex、签名和 APK 打包可能已经完成。需要完整 Release 任务时，先确认当前环境是否仍复现，再处理 Lint，不要误判为源码编译失败。

## 输出文件

- Debug APK：`app\build\outputs\apk\debug\HyperOS3FocusRestore-0.12.2-debug.apk`
- Release APK：`app\build\outputs\apk\release\HyperOS3FocusRestore-0.12.2-release.apk`
- APK 文件被 `.gitignore` 忽略，不提交到 Git；发布时作为 GitHub Release 资产上传。
- 当前 Release APK 是本地测试构建，不代表使用正式生产签名。

## 签名配置，必须保持

旧发布 APK 使用的 Keystore 仍然存在：

```text
C:\Users\Kani\Desktop\FocusRestoreLSPosed\.android\debug.keystore
```

- Alias：`androiddebugkey`
- Keystore 密码：`android`
- Alias 密码：`android`
- 旧发布证书 SHA-256：`ab58b5e208e21aaa9a8628c3ceb661b2bb89cdcfb1d941fbf892ae4936e16809`
- 旧发布证书 SHA-1：`7d22679908faaa73c7bfb247edb166aa61930849`

`app/build.gradle` 已明确使用：

```gradle
storeFile file('../.android/debug.keystore')
storePassword 'android'
keyAlias 'androiddebugkey'
keyPassword 'android'
```

重要规则：

1. 不要删除或覆盖项目中的 `.android\debug.keystore`。
2. 不要让 Gradle 自动使用 `C:\Users\Kani\.android\debug.keystore`。
3. 不要运行 `keytool -genkey` 重新生成 Debug Keystore。
4. Debug 和 Release 当前都应使用旧证书。
5. 构建后使用以下命令检查证书：

```powershell
& "C:\Users\Kani\Desktop\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify --print-certs "app\build\outputs\apk\debug\HyperOS3FocusRestore-0.12.2-debug.apk"
```

预期 SHA-256 必须是：

```text
ab58b5e208e21aaa9a8628c3ceb661b2bb89cdcfb1d941fbf892ae4936e16809
```

`.android` 已被 `.gitignore` 忽略，因此 Keystore 不在 Git 中。需要备份 Keystore 时，应单独安全备份，不要把私钥提交到公共仓库。

## 测试命令

```powershell
Set-Location "C:\Users\Kani\Desktop\FocusRestoreLSPosed"
$env:ANDROID_HOME = "C:\Users\Kani\Desktop\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:JAVA_HOME = "C:\Users\Kani\scoop\apps\temurin25-jdk\current"
$env:GRADLE_USER_HOME = "C:\Users\Kani\Desktop\FocusRestoreLSPosed\.gradle-temp"
& "C:\Users\Kani\Desktop\gradle-9.7.1\bin\gradle.bat" testDebugUnitTest --no-daemon
```

当前单元测试主要覆盖：

- `IslandPayloadParserTest`
- `FocusPriorityPolicyTest`

## 当前功能相关构建注意事项

- Hook 只在 `com.android.systemui` 进程安装。
- `feature.island.debug=false` 和 `FEATURE_DYNAMIC_ISLAND=false` 是两个独立的 Debug 临时开关。
- 两个 Debug 开关只在 Debug APK 设置页显示。
- Release 构建保持原有 Dynamic Island 禁用行为。
- SystemUI 配置通过 `SettingsProvider` 读取；Provider 字段只能在末尾追加，不能改变旧字段顺序。
- 短信验证码自动接管条件为：
  - 包名为 `com.android.mms`
  - `protocol=1`
  - `scene=verifyCode`
- 普通短信不会因为该规则被强制转换。
- 原生 Focus 默认优先；超级岛白名单仍可强制接管其他应用。
- 必须保留两种原有禁用机制：
  - `feature.island.debug=false`
  - `DynamicFeatureConfig.FEATURE_DYNAMIC_ISLAND=false`
- 不要添加 `MIUIStrongToast` 或 HyperCeiler `HideStrongToast` Hook。
- 不要添加通知 key 哈希、通知正文脱敏或运行时日志扫描。
- 保留完整诊断日志。

## Git 注意事项

本地环境/构建产生的文件通常不应提交：

- `.gradle/`
- `.gradle-temp/`
- `.android/`
- `.idea/`
- `local.properties`
- `app/build/`
- `*.apk`

用户明确要求保留且不要修改/提交的文件：

- `AI_HANDOFF.md`
- `优化完善计划.md`

提交前检查：

```powershell
git diff --check
git status --short
git diff --stat
```

除非用户明确要求，不要自动执行远程推送。

## 签名变更处理

如果新 APK 的证书指纹不是上面记录的旧指纹：

1. 先停止发布，不要覆盖旧 Release 资产。
2. 检查 `app/build.gradle` 是否仍指向 `../.android/debug.keystore`。
3. 检查构建环境是否错误使用了 `C:\Users\Kani\.android\debug.keystore`。
4. 检查项目 Keystore 文件是否仍存在。
5. 只有用户明确确认要迁移签名时，才考虑新 Keystore 和升级策略。
