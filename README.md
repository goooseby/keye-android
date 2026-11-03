# Keye Android · 课页

原生 Android 课程课件阅读应用，使用 Kotlin 和 Jetpack Compose。围绕获取课件、离线阅读、继续阅读和导出分享设计，最低支持 Android 8.0（API 26）。

当前公开版本为 **0.2.0**。[下载安装包](https://github.com/goooseby/keye-android/releases/latest/download/keye-android.apk) · [发布记录](https://github.com/goooseby/keye-android/releases)。课程内容以 PDF 或图片阅读，不直接解析 PPT／PPTX 文件，也不支持幻灯片动画。

## 功能

- **课程与续读**：最近阅读、全部课程、课件搜索和本地导入，保存原始页码及页内阅读位置。
- **阅读**：默认竖屏连续滚动，可切换横向逐页；连续模式统一缩放整份课件，支持双击及双指缩放，提供页面总览、跳页和页书签。
- **课堂跟看**：从课次进入，约三秒检查新增页，默认跟随最新；手动回看不跳页，支持按页文字批注、保存当页及已有页面快照导出。后台暂停，页面自动保留到课程。
- **全屏观看**：自动横屏，悬浮翻页按钮、进度滑杆、保存当页、书签和缩放复位；控件闲置自动隐藏，退出恢复常规阅读方式。
- **阅读工具**：首页／末页、下一书签、阅读背景、保持亮屏及阅读器独立亮度。
- **页面管理**：单页／批量移除、撤销及恢复；原件保留，阅读与默认导出跳过已移除页。
- **导出与分享**：全部保留页、当前页或指定范围导出 PDF，通过 Android 系统分享 PDF 或当前页图片。
- **学校课件**：当前适配华南理工大学课堂视频平台，需使用本人可访问的学校账号登录；支持今天、近两周、本学期及自选日期查询和批量下载，课程默认折叠，可逐门或全部展开；自动检测登录失效，网络错误保留登录信息。
- **应用更新**：GitHub Releases 检查，自动每天最多一次；系统后台下载，已下载安装包可直接继续安装，支持忽略版本和关闭自动提醒。
- **本机管理**：课程归类、名称与备注、下载设置、保存目录和回收站。本地阅读与导出无需学校登录。

Android 10 起默认导出到“下载 / Keye”，也可选择其他文件夹；同名文件自动编号。Android 8–9 首次导出需选择保存目录。学校接口依赖平台实际可用性，本地 PDF 与图片导入可独立使用。

## 开发与构建

需要 JDK 21、Android SDK Platform 36 和 Android Studio。项目使用 Gradle Wrapper 8.13、Android Gradle Plugin 8.13.2、Kotlin 2.2.21，字节码目标为 Java 17。无需另外安装系统 Gradle。

1. 克隆仓库，用 Android Studio 打开包含 `settings.gradle.kts` 的项目根目录。
2. 在 SDK Manager 安装 Android SDK Platform 36 与对应构建工具，Gradle JDK 选择 JDK 21。
3. 让 Android Studio 生成本机 `local.properties`，或设置 `ANDROID_HOME` 指向自己的 Android SDK。
4. 在项目根目录执行：

```powershell
# Windows PowerShell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

```sh
# macOS / Linux
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。检查报告位于 `app/build/reports/`。

开发者自备 SDK 和设备；仓库不包含本机路径、学校登录凭据、课件内容、签名密钥或构建产物。

## 目录

```text
app/src/main/    应用代码、界面与资源
app/src/debug/   仅用于 Debug 的界面观察入口
app/src/test/    队列顺序、页面选择与导出范围的逻辑检查
gradle/wrapper/  固定版本的 Gradle Wrapper
docs/           产品、交互与开发说明
```

## 文档与能力边界

- [产品与交互设计](docs/PRODUCT_DESIGN.md)
- [实现与能力边界](docs/IMPLEMENTATION.md)
- [界面规范](docs/UI.md)
- [开发环境、安装与调试](docs/ENVIRONMENT.md)
- [应用更新、正式签名与发布](docs/UPDATES.md)

下载和导出目前随应用进程运行，不提供持久后台恢复。部分页／合并导出使用图片型 PDF；完整导出未修改的单份 PDF 时优先保留原件。当前没有 OCR、全文搜索、笔迹批注或云同步。
