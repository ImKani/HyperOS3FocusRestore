# HyperOS3FocusRestore 0.12.2

## 更新内容

- 修复 HyperIsland Toolkit 通知同时携带 Focus RemoteViews 时被误判为原生 Focus、导致超级岛协议内容未转换的问题。
- 新增超级岛应用强制转换白名单，可在“自定义”页面手动选择应用。
- 白名单仅在“转换超级岛内容为焦点通知”开启时生效，默认关闭并以浅色禁用显示。
- 白名单应用即使同时携带 Focus RemoteViews，也会优先转换超级岛协议内容。
- 应用列表支持搜索应用名称和包名。
- 已选应用自动置顶。
- 默认隐藏未选择的系统应用，已选系统应用仍保持可见。
- 应用列表只在点击“刷新”时读取，刷新后缓存应用名称、包名和系统应用状态，下次打开优先使用缓存。
- 应用列表改为应用名称和包名两行显示，优化选中状态视觉反馈。
- 拆分独立的超级岛白名单设置面板。
- 统一设置页、白名单对话框、按钮和列表行的圆角样式。
- 保留超级岛屏蔽双保险：`feature.island.debug=false` 和 `FEATURE_DYNAMIC_ISLAND=false`。
- 保留原生 Focus 优先、完整诊断日志和现有配置兼容性。

## 使用说明

1. 在 LSPosed 中启用模块并勾选 `com.android.systemui` 作用域。
2. 打开模块设置，开启“转换超级岛内容为焦点通知”。
3. 进入“自定义”页面，打开“强制转换白名单”。
4. 点击“刷新”加载已安装应用列表。
5. 选择需要强制转换的应用，点击“完成”。
6. 点击顶部“保存”，然后重启 SystemUI 或设备。

## 注意事项

- Release APK 当前使用本机 Debug 签名，仅适合测试和临时使用，不适合作为正式长期发布证书。
- 本模块不处理 `MIUIStrongToast` / 灵动舞台。
- 本模块不会对日志正文、验证码或通知 key 做运行时脱敏或哈希处理。
- 不同 HyperOS 3 版本的 SystemUI 私有接口可能存在差异。

## 文件

- `HyperOS3FocusRestore-0.12.2-release.apk`：Release 构建，使用 Debug 测试签名。
- `HyperOS3FocusRestore-0.12.2-debug.apk`：Debug 构建。
