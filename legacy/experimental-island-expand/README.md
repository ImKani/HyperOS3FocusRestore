# 点击展开超级岛实验归档

该实验从 0.13.26 主线移除。Provider 索引 18 的 `expand_island_on_click` 仅作为废弃兼容列保留，并固定返回关闭。

## 归档原因

0.13.23 至 0.13.25 真机日志证明：

- Focus View 能收到点击。
- `MiuiShadeTouchHandlerImpl.handleExternalTouch()` 接受 DOWN/UP 并返回 `true`。
- OS4 修正后使用中央岛坐标，并以 80ms 间隔发送 DOWN/UP，仍无可见展开。
- FocusRestore 已关闭 ROM 原生超级岛显示路径，因此插件没有可展开的小岛状态；继续实现需要恢复岛生命周期或调用内部 `smallToExpanded` 状态机，风险和维护成本过高。

## 历史实现

- `014c534`：初始超级岛点击实验。
- `e32c666`：接入 shade touch handler 并修正坐标。
- `031c10c`：排除边缘 cutout，延迟发送 UP。
- `IslandTouchCoordinates.java` 和对应测试保留于本目录，仅供未来恢复研究。

恢复实验时应从上述提交提取 `HyperOS3FocusRestoreHook.dispatchIslandTap()`、`hookDynamicIslandTouchHandler()` 及 `HyperOS4FocusController.FocusHostView` 的实验分支，不应直接把归档源码加入 app sourceSet。
