# HyperOS3FocusRestore 0.12.3

## 更新内容

- 修复短信验证码通知被 HyperOS 3 识别为原生 Focus 后无法正常显示的问题。
- 对 `com.android.mms` 的 `protocol=1` 且 `scene=verifyCode` 通知自动执行 Focus 内容接管。
- 短信验证码无需再手动加入超级岛强制转换白名单。
- 普通短信不会因为本次规则被强制转换。
- 修复 SystemUI 启动早期配置读取失败时回退默认配置的问题，在 `Application.attach()` 后重新读取设置 Provider。
- Debug 版保留两个独立的临时诊断开关：`feature.island.debug=false` 与 `FEATURE_DYNAMIC_ISLAND=false`。
- 固定 Debug/Release 构建使用既有本地测试签名，避免因 Android 用户目录变化生成新签名。
- 保留原生 Focus 优先、超级岛强制转换白名单、完整诊断日志和已有配置兼容性。

## 使用说明

1. 在 LSPosed 中启用模块并勾选 `com.android.systemui` 作用域。
2. 打开模块设置，开启“转换超级岛内容为焦点通知”。
3. 保存设置后重启 SystemUI 或设备。
4. 短信验证码通知会自动识别并转换，不需要将短信加入白名单。
5. 其他超级岛应用仍可通过“自定义”页面的强制转换白名单选择。

## 注意事项

- Release APK 当前使用既有本机 Debug 测试签名，仅适合测试和临时使用，不是正式生产证书。
- 本次版本使用与旧发布 APK 相同的证书，便于覆盖安装升级。
- 本模块不处理 `MIUIStrongToast` / 灵动舞台。
- 本模块不会对日志正文、验证码或通知 key 做运行时脱敏或哈希处理。
- 不同 HyperOS 3 版本的 SystemUI 私有接口可能存在差异。

## 文件

- `HyperOS3FocusRestore-0.12.3-release.apk`：Release 构建，使用既有 Debug 测试签名。
- `HyperOS3FocusRestore-0.12.3-debug.apk`：Debug 构建，包含临时诊断开关。
