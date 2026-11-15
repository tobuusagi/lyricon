# Lyricon DP11.6 / vc57 实施结果

日期：2026-11-16

## 本轮修复

dp11.5 / vc56 在 t1452u 真机上的失败证据表明，播放时 `notification_icon_area` 与 `status_bar_start_side_content` 从 `VISIBLE (0)` 被隐藏为 `GONE (8)`，但暂停回调虽然进入了恢复流程，记录的基线仍是 `-1`，所以恢复跳过了 `visibility` 写回。原因是 `ViewVisibilityTracker.isComposeView` 沿父链检查 Compose 祖先，导致这些遵循传统 View 可见性的 ViewGroup 没有保存基线。随后已处于 GONE 的目标又被播放规则略过，使后续暂停没有待恢复对象。该结论来自设备日志，不是 GONE 回声覆盖基线。

本轮修复：

- `isComposeView` 现在只判定目标 View 自身的类名；传统容器即使位于 Compose 祖先下也会保存和读取基线。
- `ViewVisibilityController` 在每次首次隐藏时保存本地可见性基线；tracker 返回未知时用本地值恢复。只有恢复确实写回目标状态后才清除 fallback。
- 如果此前本模块隐藏的 View 仍是 GONE，播放规则会继续将它保留在待恢复集合中，不会因当前 GONE 永久丢弃跟踪。
- 隐藏与恢复诊断日志保留基线、`visBefore` / `visAfter`，并记录 `isComposeView` 以及是否使用本地基线；不包含歌曲元数据。
- 播放回调早于歌词首次可见时，标记待同步；后续布局在歌词 View 变为可见时补应用规则。歌词明确报告隐藏后会清除此待同步标记，避免布局回调覆盖明确隐藏状态。
- 版本更新为 `1.0.40.rc-dp11.6` / `versionCode 57`。

## 保留行为与边界

播放时继续显示歌词、隐藏配置的父容器，并保留 Precise 状态栏颜色跟随。原本记录为系统 `INVISIBLE` 或 `GONE` 的基线仍按记录状态恢复，不强制设为 `VISIBLE`。布局插入方式和高度逻辑没有改动；dp11.5 记录的 sibling 插入、`WRAP_CONTENT` 和布局数值未在本轮真机复测，本轮不判定位置通过或失败。

## 测试与构建

- `:xposed:testDebugUnitTest --tests io.github.proify.lyricon.xposed.DP11CompatUnitTest`：26 tests passed。覆盖 Compose 祖先与普通容器的隐藏/恢复、本地 fallback、重复播放/暂停、延迟歌词可见后的布局补应用，以及尊重系统 `INVISIBLE`。
- `:lyricon:assembleDebug`：成功，构建参数使用 `-Xmx2g`、最多 2 workers 且关闭并行。
- APK 元数据由 `aapt2` 核对为 package `io.github.proify.lyricon`、versionName `1.0.40.rc-dp11.6`、versionCode `57`。`apksigner verify` 报缺少 `META-INF/MANIFEST.MF`，确认产物未签名。
- JVM 测试和构建不能证明真机行为。**not device-fixed until the user confirms.** 本轮未操作手机、adb、LSPosed 或 SystemUI；下一次真机运行需确认暂停时钟/通知容器恢复、重播时重新隐藏、重复播放/暂停循环、系统 `INVISIBLE/GONE` 保真，并检查新诊断字段。位置需另行观察，不沿用旧记录作为本轮设备结论。

## 产物

- APK：`out/lyricon-1.0.40.rc-dp11.6-debug-unsigned.apk`
- SHA-256：`c5c643f38c074ae70ccf1795b8e41d6ee3aeccc12927abcde458d9c6fe4eb3c9`（作业文档曾误写为 `4b34734cbe8cd7794d7d4fe4f1380467ba6d287e92fb0f484d60f493e4bd430e`，以独立 sha256sum 核对为准）
- Sidecar：`out/lyricon-1.0.40.rc-dp11.6-debug-unsigned.apk.sha256`
