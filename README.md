# HyperOS3FocusRestore

用于 HyperOS 3 的实验性 LSPosed 模块，尝试恢复 HyperOS 2 的 Focus（焦点通知）状态栏显示路径。

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

版本：`0.12.8`

本版本修复短信验证码通知被 HyperOS 3 识别为原生 Focus 后无法正常显示的问题，并保留以下用户可见功能：

- 恢复焦点通知状态栏显示
- 超级岛内容转焦点通知（可选开关）
- 焦点通知宽度限制与滚动方向控制
- 焦点通知点击控制
- 超级岛屏蔽
- 测试工具已归档

模块标识：

```text
应用名：HyperOS3FocusRestore
Application ID：com.hyperos3.focusrestore
Hook 入口：com.hyperos3.focusrestore.HyperOS3FocusRestoreHook
日志 Tag：HyperOS3FocusRestore
作用域：com.android.systemui
```

## 功能概述

- 通过 LSPosed 模块恢复 HyperOS 2 的焦点通知显示路径，使部分通知可以显示在状态栏焦点区域。
- 提供“转换超级岛内容为焦点通知”开关，开启后会尝试从带有超级岛协议的通知中提取文本内容，补入焦点通知显示。仅处理文本，不支持图片、按钮或动态计时器；对于没有超级岛参数的普通通知不会生成额外内容。
- 提供焦点通知宽度限制开关（默认开启，上限 160dp），防止长内容覆盖状态栏右侧图标。
- 提供滚动方向开关：开启“往返滚动”时内容左右往返移动，关闭时单向滚动循环。可配合“兼容重试模式”使用，解决某些 ROM 滚动停止的问题。
- 默认禁用所有焦点通知点击，避免点击后通知消失或异常；可在设置中手动开启，风险自负。
- 模块始终尝试关闭 HyperOS 超级岛显示路径，避免其占用状态栏区域。
- 本模块不适配或隐藏 MIUIStrongToast（灵动舞台），需要隐藏请使用其他专用工具。
- 模块仅作用于 `com.android.systemui`，不要求 KernelSU 模块。

## 设置项说明

模块设置页从 LSPosed 模块详情进入，不显示桌面图标。修改设置后需保存，并手动重启 SystemUI 或设备才能完整生效。

主要设置项：

- **超级岛内容转焦点通知**：默认关闭。开启后尝试从超级岛协议中提取文本内容并显示为焦点通知；关闭时不做转换。
- **焦点通知宽度限制**：默认开启，上限 160dp；关闭后使用 ROM 原生宽度。
- **往返滚动**：默认关闭。开启后内容左右往返滚动，关闭则单向循环。
- **兼容重试模式**：默认关闭。开启后滚动任务最多启动两次，适用于某些 ROM 布局刷新后重置跑马灯的情况。
- **允许焦点通知点击**：默认关闭。开启后点击事件交由系统处理，可能再次出现通知不可见或消失。

## 系统灵动舞台

本模块不适配或隐藏 MIUIStrongToast（灵动舞台）。需要隐藏时，可选择其他专用工具；设置页也会显示这一提示。超级岛屏蔽与灵动舞台隐藏属于不同的系统路径。

## 测试工具

测试发送器已经归档到 `legacy/testsender`，不参与主模块构建和 LSPosed 作用域。它独立安装后提供焦点通知、超级岛模板和清理测试通知，便于点击后立即返回桌面观察 SystemUI 显示。

设置修改后必须点击保存，并由用户手动重启 SystemUI 或设备。

## 设置页风险提示

设置页会明确提示以下内容：

- HyperOS 3 上基本所有焦点通知都不支持点击。
- 点击可能导致焦点通知消失或暂时不可见。
- 点击后的系统通知逻辑可能无法正常处理。
- 模块通过 LSPosed Hook 介入 SystemUI，存在 ROM 版本差异、系统崩溃、显示异常、功能失效和数据丢失风险。
- 超级岛转换只处理通知实际提供的协议内容，不负责隐藏系统灵动舞台；需要隐藏时应使用其他工具。
- 修改设置后需要保存，并重启 SystemUI 或设备才能完整生效。

## 日志判读

测试时可通过日志判断通知走向。若日志中主要出现 `DynamicIslandService` / `DynamicIslandController`，说明通知走了动态岛路径；若同时出现 `HyperOS3FocusRestore` 相关日志（如 `showOnStatusBar`、`before setData`、`after setData`、`updateRemoteViews begin` 等），则说明进入了本模块恢复的焦点通知路径。

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
app/build/outputs/apk/debug/HyperOS3FocusRestore-0.12.8-debug.apk
app/build/outputs/apk/release/HyperOS3FocusRestore-0.12.8-release.apk
```

模块不声明网络、存储、后台服务等额外权限。关于项目按钮通过系统浏览器打开外部链接，网络访问由浏览器处理。

## 安装和作用域

1. 安装 `HyperOS3FocusRestore-0.12.8-release.apk` 或 `HyperOS3FocusRestore-0.12.8-debug.apk`。
2. 在 LSPosed 中启用本模块。
3. 作用域应只有：

```text
系统界面
com.android.systemui
```

4. 第一轮测试关闭 KernelSU 的动态岛属性模块。
5. 重启设备，确保 SystemUI 的静态功能字段在 Hook 后初始化。
6. 触发以前会显示超级岛或焦点通知的通知。

这是全新 Application ID，旧版模块不会自动升级。测试时请禁用旧模块，避免两个模块同时 Hook SystemUI。

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
HyperOS3FocusRestore: forced feature.island.debug=false
HyperOS3FocusRestore: showOnStatusBar=...
HyperOS3FocusRestore: before setData ...
HyperOS3FocusRestore: after setData ...
HyperOS3FocusRestore: updateRemoteViews begin ...
HyperOS3FocusRestore: scheduled native focus marquee delayMs=...
HyperOS3FocusRestore: started native focus marquee ...
```

如果只有 `showOnStatusBar` 而没有 `setData`，说明判断已放行但通知没有进入焦点通知 View。如果只有 `DynamicIslandService`，说明它只进入了动态岛路径。若 `updateRemoteViews` 报错，说明 RemoteViews 与当前 SystemUI 的布局、资源或类不兼容。
