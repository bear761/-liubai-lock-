# 留白 · Android 全量上锁 App

连续使用手机超过设定时长（默认 25 分钟）→ **所有 App 自动上锁 5 分钟**。锁定期内打开任何非白名单 App 会被立即退回桌面，并弹出「请稍作休息」。仅 Android，无 iOS 计划。

## 工程结构

```
liubai-lock/
├─ settings.gradle.kts / build.gradle.kts / gradle.properties   # Gradle 8.4 + AGP 8.2.2 + Kotlin 1.9.22
└─ app/src/main/
   ├─ AndroidManifest.xml          # 权限与服务声明
   ├─ java/com/liubai/lock/
   │  ├─ MainActivity.kt           # 首页：权限引导、阈值/时长设置、白名单、测试锁定
   │  ├─ core/LockStateRepo.kt     # 状态仓库：lock_end / 使用累计 / 白名单（SharedPreferences）
   │  ├─ core/OverlayController.kt # 全屏覆盖锁屏 + 「请稍作休息」弹窗
   │  └─ service/
   │     ├─ LockForegroundService.kt        # 统计引擎：秒级累计、触发/解除锁定、常驻通知
   │     ├─ AppWatchAccessibilityService.kt # 前台检测 + 锁定期 HOME 顶回（等效强退）
   │     └─ BootReceiver.kt                 # 开机恢复
   └─ res/                          # 「留白」视觉：#f7f6f2 / #3e6b57
```

## 如何构建（需 Android Studio）

1. **打开工程**：Android Studio（Hedgehog 以上）→ File → Open → 选择 `liubai-lock` 目录。
   - 本仓库未包含 `gradle-wrapper.jar`（二进制不入库）。首次打开若提示 Gradle wrapper 缺失：
     - 方式 A：AS 弹窗中选择使用其自带 Gradle / 自动生成 wrapper；
     - 方式 B：本机装有 Gradle 时，在 `liubai-lock/` 下执行 `gradle wrapper --gradle-version 8.4`。
2. **同步**：等 Gradle Sync 完成（自动下载依赖）。
3. **运行**：手机开启「开发者选项 + USB 调试」，插上电脑，点 Run ▶ 安装。
   - 也可 Build → Build APK 得到 `app-debug.apk` 直接发到手机安装。

## 首次使用（权限引导，一次性）

按 App 首页「开始守护」会依次引导：

1. **悬浮窗权限**（必须）——用于全屏锁屏覆盖
2. **无障碍服务**（必须）——设置 → 无障碍 → 找到「留白」开启，用于检测前台 App 和顶回桌面
3. **使用情况访问**（建议）——降级兜底
4. **通知权限**（Android 13+）——显示使用进度常驻通知
5. 国产 ROM 建议额外把「留白」加入自启动/电池白名单，防杀后台

## 测试流程

1. 打开 App → 点「测试锁定 30 秒」→ 应立即全屏锁定并弹出「请稍作休息」
2. 点「我知道了」关闭弹窗 → 按主屏幕键回到桌面 → 打开任意 App（如设置外的微信）→ 应被立即顶回 + 再次弹窗
3. 30 秒后自动解锁，通知栏回到「已连续使用 xx:xx / 25:00」
4. 正式模式：把阈值临时改成 1 分钟 → 点「开始守护」→ 正常刷手机 1 分钟 → 自动触发 5 分钟锁定（测试完改回）

## 行为规则

- 屏幕亮 + 前台为非白名单 App → 持续累计使用时长
- **灭屏满 2 分钟 → 清零**（防按电源键洗白）；短暂灭屏不清零
- 白名单 App（电话/短信/相机/桌面等，可在 App 内管理）不累计、锁定期可用
- 进程被杀 / 手机重启 → 恢复剩余锁定时间与统计状态

## 当前状态与已知简化（M4 待办）

- [ ] 国产 ROM（小米/华为/OPPO）保活与自启动引导页
- [ ] 覆盖层防下拉通知栏绕过（setHideNavigation 加固）
- [ ] UsageStats 轮询降级模式（无障碍被关时自动接管）
- [ ] 来电/付款等紧急场景白名单细判、勿扰时段、每日限 2 次"推迟 5 分钟"
- [ ] 图标（当前用系统默认图标）
