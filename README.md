# FocusRestore

用于 HyperOS 3/4 的实验性 LSPosed 模块，尝试恢复 HyperOS 2 的 Focus（焦点通知）状态栏显示路径。

仓库地址：`https://github.com/ImKani/HyperOS3FocusRestore`

## 作者与声明

制作者：ImKani

酷安主页：<https://www.coolapk.com/u/1205658>

GitHub：<https://github.com/ImKani/HyperOS3FocusRestore>

本模块由 AI 辅助反编译分析与编写，代码通过 LSPosed Hook 介入系统界面，存在 ROM 版本差异、系统崩溃、状态栏显示异常、功能失效、数据丢失或其他不可控风险。使用前请自行备份，并自行承担使用风险。模块不保证适用于所有设备、系统版本或第三方通知。

## 许可证

本项目使用 `GNU General Public License v3.0 only`（`GPL-3.0-only`）发布。完整许可证声明见仓库根目录的 `LICENSE` 文件，许可证正文请参阅 GNU 官方文本：<https://www.gnu.org/licenses/gpl-3.0.txt>。

## 实现细节说明

本项目的具体 Hook 方法、字段处理逻辑和内部判断流程不在 README 中公开，以防止他人轻易绕过 GPL 重新实现。如需了解实现细节，请直接查看仓库源码（遵循 GPL-3.0-only）。

## 当前版本

版本：`0.13.13`

本版本新增 HyperOS 4 手动适配，并保留原 HyperOS 3 Hook：

- 由用户手动选择 HyperOS 3 或 HyperOS 4，默认 HyperOS 3；保存后重启 SystemUI 或设备生效
- 设置会同步到 Direct Boot 可读的设备加密存储，确保开机解锁前启动的 SystemUI 能读取手动选择的模式
- SystemUI 在 `Application.attach()` 阶段直接使用可用的 base Context 查询设置，避免 Application Context 尚未建立时误判设置不可用
- 不自动检测系统版本，不在 Hook 缺失时自动切换或回退
- HyperOS 3 继续使用原有 Focus Prompt 路径
- HyperOS 4 监听通知管线，并使用状态栏 Primary Chip 位置显示原生 Focus 或转换后的超级岛文本
- HyperOS 4 无候选时保持 ROM 已停用的 Legacy Primary Chip 为隐藏状态，避免默认通话图标、`00:00:00` 计时器和底色泄漏
- HyperOS 4 接入状态栏 `DarkIconDispatcher`，焦点文字使用与状态栏时间相同的实时明暗 tint，而不是仅按深色模式切换
- HyperOS 4 默认恢复时间与焦点内容之间的分隔竖线，可在设置中关闭；竖线与时间使用相同的实时 tint，正文滚动时保持固定
- 默认在焦点通知显示期间隐藏左侧通知图标容器，焦点消失后恢复 ROM 原 visibility；右侧信号、电池等系统图标不受影响
- 焦点显示期间精确拦截 ROM 对左侧通知图标容器的 visibility 写入，持续保持隐藏并记录 SystemUI 最新期望值；焦点结束后恢复最新 visibility，不覆盖 ROM 的 alpha 动画
- 带 `miui.focus.param` 且没有原生 Bar RemoteViews 的 PARAMS 通知进入超级岛 JSON 文本解析，不再被系统生成的类别 ticker（例如 `Weather`）误判为原生 Focus
- 原生 Focus 优先于手动白名单、短信验证码和普通超级岛转换
- 超级岛内容转焦点通知（可选开关）
- 焦点通知宽度限制与滚动方向控制
- 焦点通知点击控制
- 两条超级岛屏蔽路径在两种模式下均保持启用
- 测试工具已归档

模块标识：

```text
应用名：FocusRestore
Application ID：com.hyperos3.focusrestore
Hook 入口：com.hyperos3.focusrestore.HyperOS3FocusRestoreHook
日志 Tag：HyperOS3FocusRestore
作用域：com.android.systemui
```

## 功能概述

- 通过 LSPosed 模块恢复 HyperOS 2 的焦点通知显示路径，使部分通知可以显示在状态栏焦点区域。
- HyperOS 3 模式保留原有 `FocusedNotifPromptView` Hook；HyperOS 4 模式通过通知集合事件维护显示状态，并复用系统 `ongoing_activity_chip_primary` 位置。两种模式只安装用户选择的对应 Hook。
- 提供“转换超级岛内容为焦点通知”开关，开启后会尝试从带有超级岛协议的通知中提取文本内容，补入焦点通知显示。仅处理文本，不支持图片、按钮或动态计时器；对于没有超级岛参数的普通通知不会生成额外内容。HyperOS 4 转换不会强制修改 `mIsFocusNotification`。
- 提供焦点通知宽度限制开关（默认开启，上限 160dp）。HyperOS 4 会先测量完整内容，再将显示 Host 截断到该上限并在超宽时滚动。
- HyperOS 4 焦点内容跟随状态栏时钟实时反色，适配浅色/深色应用界面和状态栏外观变化。
- 提供两个 HyperOS 4 专用开关：“焦点通知隐藏其他图标”和“显示焦点通知分隔竖线”，均默认开启；选择 HyperOS 3 时保留其设置值但在界面中浅色禁用。
- 隐藏图标只影响左侧通知图标容器；锁屏/解锁时若 ROM 请求恢复可见，模块会记录该最新请求并继续隐藏，焦点消失后恢复 SystemUI 最新期望的 visibility。分隔竖线固定在内容左侧并跟随状态栏时间反色。
- 提供滚动方向开关：开启“往返滚动”时内容左右往返移动，关闭时单向滚动循环。可配合“兼容重试模式”使用，解决某些 ROM 滚动停止的问题。
- 默认禁用所有焦点通知点击，避免点击后通知消失或异常；可在设置中手动开启，风险自负。
- 模块始终尝试关闭 HyperOS 超级岛显示路径，避免其占用状态栏区域。
- 本模块不适配或隐藏 MIUIStrongToast（灵动舞台），需要隐藏请使用其他专用工具。
- 模块仅作用于 `com.android.systemui`，不要求 KernelSU 模块。

## 设置项说明

模块设置页从 LSPosed 模块详情进入，不显示桌面图标。修改设置后需保存，并手动重启 SystemUI 或设备才能完整生效。

主要设置项：

- **系统界面版本**：手动选择 HyperOS 3 或 HyperOS 4，默认 HyperOS 3。模块不会自动检测或回退；选错版本时只会记录缺失能力或 Hook 失败日志。
- **超级岛内容转焦点通知**：默认关闭。开启后尝试从超级岛协议中提取文本内容并显示为焦点通知；关闭时不做转换。
- **焦点通知宽度限制**：默认开启，上限 160dp；关闭后使用 ROM 原生宽度。HyperOS 4 日志会记录 `contentWidth`、`hostWidth` 和 `maxWidthPx`。
- **焦点通知隐藏其他图标（HyperOS 4）**：默认开启。只在焦点内容可见期间隐藏 `notificationIcons`；锁屏/解锁时会拦截并记录 ROM 最新的 visibility 请求，焦点消失后恢复该最新值，不修改 ROM 的 alpha。选择 HyperOS 3 时该项浅色禁用，但保存值不变。
- **显示焦点通知分隔竖线（HyperOS 4）**：默认开启。在时间与焦点内容之间显示固定竖线，颜色跟随状态栏时间实时反色。选择 HyperOS 3 时该项浅色禁用，但保存值不变。
- **往返滚动**：默认开启。开启后内容左右往返滚动，关闭则单向循环。
- **兼容重试模式**：默认关闭。开启后滚动任务最多启动两次，适用于某些 ROM 布局刷新后重置跑马灯的情况。
- **调试：允许焦点通知点击**：默认关闭，仅用于调试，不保证可用。开启后可能导致焦点通知消失、不可见、误触发，或使系统通知逻辑处理异常。

## 系统灵动舞台

本模块不适配或隐藏 MIUIStrongToast（灵动舞台）。需要隐藏时，可选择其他专用工具；设置页也会显示这一提示。超级岛屏蔽与灵动舞台隐藏属于不同的系统路径。

## 测试工具

测试发送器已经归档到 `legacy/testsender`，不参与主模块构建和 LSPosed 作用域。它独立安装后提供焦点通知、超级岛模板和清理测试通知，便于点击后立即返回桌面观察 SystemUI 显示。

设置修改后必须点击保存，并由用户手动重启 SystemUI 或设备。

## 设置页风险提示

设置页会明确提示以下内容：

- “调试：允许焦点通知点击”仅用于调试且功能不可靠；HyperOS 3 上基本所有焦点通知都不支持点击，HyperOS 4 模式关闭点击时会消费状态栏 Focus 区域的触摸事件。
- 开启调试点击后，HyperOS 4 优先使用原生 RemoteViews 点击事件，转换文本使用通知 `contentIntent`；这些事件可能无效，并可能导致焦点通知消失、不可见、误触发或系统处理异常。
- 点击后的系统通知逻辑可能无法正常处理。
- 模块通过 LSPosed Hook 介入 SystemUI，存在 ROM 版本差异、系统崩溃、显示异常、功能失效和数据丢失风险。
- 超级岛转换只处理通知实际提供的协议内容，不负责隐藏系统灵动舞台；需要隐藏时应使用其他工具。
- 修改设置后需要保存，并重启 SystemUI 或设备才能完整生效。

## 日志判读

测试时先确认日志中的 `configuredMode=OS3/OS4` 和 `installedMode=OS3/OS4` 与手动选择一致。HyperOS 3 模式会记录 `showOnStatusBar`、`before setData`、`after setData`、`updateRemoteViews begin` 等日志；HyperOS 4 模式会记录 `notifPipelineListener`、`statusBarPrimarySlot`、`OS4 candidate` 和 `OS4 focus shown`。所有 Hook 独立安装和记录错误，某一项缺失不会触发自动模式回退。

如果只有动态岛日志而没有模块日志，可能模块未生效或通知未满足焦点条件。如果模块日志中出现 `updateRemoteViews` 报错，说明 RemoteViews 与当前 SystemUI 不兼容。

模块不再提供整体功能旁路开关；需要停用模块时，应在 LSPosed 中关闭作用域或禁用模块。

## KernelSU 关系

KernelSU 不是本模块的必需依赖。若使用 KernelSU 修改动态岛属性，应确保它不会重新开启原生超级岛；不一致时以更早生效的系统属性和 SystemUI 初始化结果为准。

## 构建

建议环境：

```text
JDK 17
Android SDK Platform 35
Android Gradle Plugin 8.7.3
```

构建 debug 或 release 变体，APK 输出路径：

```text
app/build/outputs/apk/debug/HyperOS3FocusRestore-0.13.10-debug.apk
app/build/outputs/apk/release/HyperOS3FocusRestore-0.13.10-release.apk
```

模块不声明网络、存储、后台服务等额外权限。关于项目按钮通过系统浏览器打开外部链接，网络访问由浏览器处理。

## 安装和作用域

1. 安装 `HyperOS3FocusRestore-0.13.10-release.apk` 或 `HyperOS3FocusRestore-0.13.10-debug.apk`。
2. 在 LSPosed 中启用本模块。
3. 作用域应只有：

```text
系统界面
com.android.systemui
```

4. 从 LSPosed 模块详情进入设置页，选择 HyperOS 3 或 HyperOS 4 并保存；默认 HyperOS 3。
5. 第一轮测试关闭 KernelSU 的动态岛属性模块。
6. 重启设备，确保 SystemUI 的静态功能字段和手动选择的 Hook 在启动阶段初始化。
7. 触发以前会显示超级岛或焦点通知的通知。

这是现有 Application ID 的显示品牌更新，旧版可通过相同包名、签名和更高版本号覆盖升级。测试时请禁用旧模块，避免两个模块同时 Hook SystemUI。

## 抓取日志

测试时不要让模块主动重启 SystemUI。先清空日志，再由用户手动重启 SystemUI，等待状态栏恢复后触发测试歌词。

### MT 管理器 Root 终端

在 MT 管理器终端中先执行：

```sh
/system/bin/logcat -c
```

然后执行下面这一行开始抓取。只读取默认 buffer，不使用 `-b all`，避免日志快速增长到几十 MB：

```sh
/system/bin/logcat -v threadtime HyperOS3FocusRestore:I FocusedNotifPromptView:I PromptViewAnimState:D AndroidRuntime:E '*:S' > /sdcard/hyperos3-focus-restore.log
```

如果已经进入 Root shell（提示符为 `#`），不要再次输入 `su -c`。开始抓取后不会返回命令提示符，这是正常现象。测试完成后按 `Ctrl+C` 停止。

日志文件位置：

```text
/sdcard/hyperos3-focus-restore.log
```

如果 MT 管理器对标签过滤命令处理异常，可以抓取默认 buffer 的完整日志：

```sh
/system/bin/logcat -v threadtime > /sdcard/hyperos3-focus-restore.log
```

### ADB 电脑抓取

```sh
adb logcat -c
```

用户手动重启 SystemUI 后，执行：

```sh
adb logcat -v threadtime HyperOS3FocusRestore:I FocusedNotifPromptView:I PromptViewAnimState:D AndroidRuntime:E '*:S' > hyperos3-focus-restore.log
```

不要使用 `adb shell pkill -f com.android.systemui`，除非用户明确要求由电脑重启 SystemUI。

### SystemUI 崩溃日志

如需单独检查崩溃，在测试完成后执行：

```sh
/system/bin/logcat -b crash -d > /sdcard/hyperos3-focus-restore-crash.log
```

## 重点日志

```text
HyperOS3FocusRestore: Dynamic Island property override: feature.island.debug=false
HyperOS3FocusRestore: capabilities configuredMode=OS3 installedMode=OS3 ...
HyperOS3FocusRestore: showOnStatusBar=...
HyperOS3FocusRestore: before setData ...
HyperOS3FocusRestore: scheduled native focus marquee delayMs=...
HyperOS3FocusRestore: capabilities configuredMode=OS4 installedMode=OS4 ...
HyperOS3FocusRestore: OS4 notifPipelineListener=registered
HyperOS3FocusRestore: OS4 statusBarPrimarySlot=attached ...
HyperOS3FocusRestore: OS4 focus shown ...
```

如果只有 `showOnStatusBar` 而没有 `setData`，说明判断已放行但通知没有进入焦点通知 View。如果只有 `DynamicIslandService`，说明它只进入了动态岛路径。若 `updateRemoteViews` 报错，说明 RemoteViews 与当前 SystemUI 的布局、资源或类不兼容。
