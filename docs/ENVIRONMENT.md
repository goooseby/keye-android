# 开发环境与调试

## 工具要求

| 项目 | 要求 |
| --- | --- |
| JDK | 21，应用字节码目标 Java 17 |
| Android SDK | Platform 36；最低安装版本 API 26，目标 API 35 |
| Gradle | 使用仓库自带 Wrapper 8.13 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.2.21 |
| 设备 | Android 8.0 以上真机，或兼容的 Android 虚拟设备 |

Android Studio 打开仓库根目录，也就是包含 `settings.gradle.kts` 和 `gradlew` 的这一层。上层目录只作为项目容器，不需要建立另一套 Gradle 工程。

在 Android Studio 的 SDK Manager 中安装 SDK，在 Gradle 设置中选择 JDK 21。SDK 路径由 IDE 写入本机 `local.properties`，也可通过 `ANDROID_HOME` 设置。不要提交绝对 SDK 路径、IDE 工作区、签名密钥或账号信息。

## 构建与检查

在项目根目录执行，Windows 使用 `gradlew.bat`，macOS／Linux 使用 `./gradlew`：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

- 安装包：`app/build/outputs/apk/debug/app-debug.apk`。
- 单元检查报告：`app/build/reports/tests/testDebugUnitTest/index.html`。
- 静态检查报告：`app/build/reports/lint-results-debug.html`。

首次构建需要网络下载依赖。`app/build/`、`.gradle/` 和 `.kotlin/` 均为本机构建产物，不提交仓库。

## 真机安装

手机开启开发者选项与 USB 调试，连接后在手机上允许当前电脑。以下命令要求 Android SDK 的 `platform-tools` 已加入 PATH，`DEVICE_SERIAL` 替换成 `adb devices` 显示的实际编号。

```powershell
adb devices
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE_SERIAL shell am start -n io.github.goooseby.keye/.MainActivity
```

更新安装使用 `-r` 保留现有应用资料；不要为了更新而卸载或清除应用数据。多设备连接时始终指定 `-s`。

## 模拟器

在 Android Studio 的 **Tools → Device Manager** 创建手机虚拟设备，选择本机可用的系统镜像后启动，再从运行设备列表选择它。Windows 使用模拟器需要相应虚拟化支持；采用 ADB 真机开发时无需安装模拟器。

## 功能与视觉检查

优先使用自建图片与 PDF。学校功能由账号持有人使用有权访问的账号登录检查；真实账号、Cookie、课件和设备截图不作为代码样例提交。

1. 本地导入、课程归类、长按多选、回收站及返回行为。
2. 默认连续阅读、逐页切换、续读、缩放平移及书签定位。
3. 常规界面竖屏锁定；全屏自动横屏，悬浮控件不改变课件尺寸，退出恢复页码和原阅读方式。
4. 单页／批量移除、撤销与恢复，至少保留一页；阅读和默认输出一致跳过已移除页。
5. 导出指定范围、同名编号、目录授权、取消与系统分享；不向真实联系人发送测试文件。
6. 批量任务入队、进度更新和完成时保持顺序，失败可解释并可重试。
7. 深色、窄屏、大字号、键盘和触摸探索下的可读性与操作空间。

Debug 专用的 `UiGalleryActivity` 可辅助观察部分界面状态，不包含在 Release 包内。它会读取本机已有课件作为预览来源，不能替代主流程验证。
