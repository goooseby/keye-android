# 应用更新与发布

## 来源与版本协议

更新仅使用公开仓库 `goooseby/keye-android` 的 GitHub Releases，不依赖自建服务器、不内置 GitHub Token，不请求任何学校登录凭据。

- 元数据：`https://api.github.com/repos/goooseby/keye-android/releases/latest`。
- 只接收正式版本；不提示草稿、预发布、相同版本或更旧版本。
- 标签采用 `v主版本.次版本.修订号`，例如 `v0.2.0`，与 APK 的 `versionName` 对应。
- 每次正式发布都增加 `versionCode`，不能只修改文件名或重复覆盖旧标签。
- 每个 Release 上传一个固定命名的 `keye-android.apk`，必须是独立可安装的 APK，不是 AAB 或拆分包。
- 必须有 GitHub 为该附件生成的 `sha256:` digest，以及准确的附件大小；校验值不接受正文里的任意链接替代。
- 发布说明来自 Release 正文，以文本显示；下载地址仅接受本仓库的 HTTPS Releases 附件路径。

公开仓库尚未发布正式 Release 时，自动检查安静结束；手动检查说明当前没有可安装的新版本。API 无法访问、限流或响应异常时不能伪装成“已经是最新版”。

## 检查与提醒

应用启动时尝试检查，按设备本地日期每天最多自动请求一次。请求前先保存当天日期，因此网络失败、进程退出和多次重启都不产生当天连续重试。手机日期回拨时不会靠回拨增加自动请求。应用留在前台跨天不会自行定时访问 GitHub，下一次启动才检查。

“我的 → 应用更新”可手动检查，不受每日次数限制；手动请求也记录当天检查日期。请求支持 ETag／304，缓存仅供已知版本展示，不把缓存误当成刚刚联网成功。

提醒在普通应用页面可用时显示，不打断阅读、学校登录或导出表单。操作含义：

| 操作 | 后续行为 |
| --- | --- |
| 下载更新 | 用户明确同意后开始下载，可使用移动数据，漫游时等待网络 |
| 稍后／关闭提示 | 当天不再弹相同自动提示，更新入口仍可查看 |
| 忽略此版本 | 保存忽略的版本号；未来更高版本仍可提醒，手动检查不受忽略限制 |
| 关闭自动提醒 | 同时关闭自动检查和弹窗；可在更新面板重新开启，手动检查一直可用 |

同一天最多展示一次自动提示。已有下载／待安装版本时优先保留用户已选择的版本，不在后台擅自替换下载目标。

## 下载、复用与恢复

使用 Android DownloadManager 执行用户确认的下载。任务不依赖界面协程存活，系统负责排队、网络等待及下载通知；应用持久保存附件信息和任务编号，重启后重新查询。强行停止应用、下载服务被停用、设备存储故障及厂商限制仍可能影响执行，界面提供明确状态和重试。

系统下载位置为应用专属外部目录 `updates/asset-附件编号.apk`。完成后有界复制到应用内部 `files/updates/` 并校验，最终只通过 FileProvider 暴露经过校验的内部文件。不会开放整个文件目录或让安装器直接读取可被外部修改的暂存副本。

已有完整且有效的内部安装包直接显示“立即安装”；取消安装或拒绝安装权限后继续保留，不重新下载。文件缺失、大小／摘要不符时才需要重下。相同损坏文件不会在每次轮询反复计算校验；明确的签名、包名、系统兼容或版本问题阻止安装，重复下载不能修复，不诱导无限重下。

取消下载仅清理应用自身的更新任务与安装包。安装新版本后，下一次启动会清理已经不再需要的旧更新文件与记录，不触碰本地课件和登录信息。

## 安装与权限

安装前检查附件大小、SHA-256、APK 包名、`versionName`、递增的 `versionCode`、最低系统版本和与当前应用一致的签名证书集合。打开安装器前重新校验内部文件。当前协议要求固定签名，不提供签名轮换迁移。

Android 8 起通过系统“允许此来源安装应用”授权，应用只在用户选择更新时解释并打开设置。返回授权页面后继续安装；未授权或取消时保留安装包。最终安装始终由系统确认，不静默安装，也不把打开安装器等同于安装成功。

下载完成且应用处于普通前台页面时继续打开安装确认。后台或进程已退出时发完成通知，点击后前台校验并安装；用户主动点击通知时可以从阅读界面继续，不受自动提醒的避让限制。不从广播接收器强拉安装界面。Android 13 起仅在用户首次下载更新时申请完成提醒的通知权限，拒绝不阻断下载，仍可回到更新面板安装。广播只响应匹配的持久任务编号，并向 DownloadManager 查询真实完成状态，不能凭传入状态安装文件。

## 正式签名与 GitHub Actions

正式分发包的签名证书 SHA-256：`daf463749076f74d28aab43d1b331cd0645abaf7e9ff83361e460039fede03e5`。此值是公开的证书标识，不是私钥或口令。

正式安装包必须始终使用同一套签名密钥。密钥保存在仓库外并做受控备份；丢失后无法用另一把密钥正常覆盖已有安装。不要把密钥、口令、本机签名路径提交 Git，也不要依赖每台开发机各自生成的调试密钥发布正式版。

当前开发用 Debug 包与正式签名包属于不同签名身份，不能直接互相覆盖。保持现有开发设备数据，不为测试更新擅自卸载应用。第一次正式分发应先确定长期签名，后续再用相同身份升级。

仓库提供 `.github/workflows/release.yml`，仅由 `main` 分支手动运行：

1. 在仓库 Settings → Secrets and variables → Actions 配置 `KEYE_KEYSTORE_BASE64`、`KEYE_KEYSTORE_PASSWORD`、`KEYE_KEY_ALIAS`、`KEYE_KEY_PASSWORD`。前者为长期私钥库文件的 Base64，其他值对应私钥库和别名。
2. 修改 `app/build.gradle.kts` 中的默认 `versionName` 与 `versionCode`，提交并推送。构建也支持 `-PkeyeVersionName=... -PkeyeVersionCode=...`，正式发布流程以提交中的默认版本为准。
3. Actions → Build signed release draft → Run workflow，填写面向用户的发布说明。
4. 工作流运行单元检查、Release 静态检查和签名构建，再验证签名、包名、版本格式和非调试标志，生成 SHA256SUMS，并创建带 APK 的 Release 草稿。
5. 检查草稿的标签、版本、说明和附件，再正式发布。客户端不识别草稿；不以未检查的部分产物替换既有正式版本。

工作流中的第三方执行入口限于固定提交的官方 checkout 和 setup-java。应用本身不读取 Actions Secrets。签名只在受控的手动发布任务中使用，普通拉取请求不触发发布。

本机签名构建时通过环境变量设置 `KEYE_KEYSTORE_PATH`、`KEYE_KEYSTORE_PASSWORD`、`KEYE_KEY_ALIAS`、`KEYE_KEY_PASSWORD` 后执行 `:app:assembleRelease`。没有签名配置时产生的 unsigned APK 不能发布；工作流不会退回 Debug 签名。

## Release 优化与问题定位

app/build.gradle.kts 的 release 构建启用 isMinifyEnabled 与 isShrinkResources，采用 proguard-android-optimize.txt 和 app/proguard-rules.pro。本机和 Actions 的 assembleRelease 都自动执行 R8，无需在工作流另写混淆命令。Debug 不压缩，便于日常调试。

自定义保留规则仅针对 AndroidViewModelFactory 反射创建的两个 Application 构造函数，不整包保留类；页面数据使用显式 JSON 字段，不依赖反射序列化。Manifest 组件、Compose 和 AndroidX 的规则由 Android 构建工具及依赖提供。新增反射、JNI 或反射式序列化时，需同步检查精确的保留规则并验证优化后的包。

Actions 归档 app/build/outputs/mapping/release，文件名包含构建提交 SHA，保存 90 天，缺少产物时构建失败；发布前也要求 mapping.txt 非空。每次正式发布后下载并长期保存对应映射，GitHub 构建附件到期后不再可取。排查混淆栈时用同一 APK 对应的 mapping.txt 配合 Android SDK Retrace，不能拿另一次构建的映射替代。映射不包含签名私钥，也不作为客户端更新附件。

混淆缩短类与成员名并配合裁剪减少包体，不能代替加密或凭据保护；公开源码仍按公开仓库管理。

参考：[Android 官方 R8 配置](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)、[精确保留规则](https://developer.android.com/topic/performance/app-optimization/keep-rules-overview)。

## 检查场景

- 同日重复启动、失败后重启、跨日启动、日期回拨，以及手动检查绕过每日限制。
- 无正式 Release、草稿、预发布、错误标签、限流、断网、ETag 和缺少安装包摘要。
- 后台下载、网络暂停、取消、进程重启、下载已完成但未安装、文件缺失及损坏。
- 非本仓库地址、错误包名、签名不一致、低版本、不兼容 Android 与完整包替换。
- 安装权限拒绝、授权后返回、系统安装取消、通知拒绝与通知点击恢复。
- 覆盖安装成功后的课件、登录信息与阅读进度保留，以及旧安装包清理。

参考：[GitHub Releases API](https://docs.github.com/en/rest/releases/releases)、[DownloadManager](https://developer.android.com/reference/android/app/DownloadManager)、[后台启动限制](https://developer.android.com/guide/components/activities/background-starts)、[安装来源授权](https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_UNKNOWN_APP_SOURCES)。
