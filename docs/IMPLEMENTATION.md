# 实现说明

## 代码组织

MainActivity 和 KeyeViewModel 放在根包，分别负责导航、应用状态与任务编排。data 聚合数据模型、SQLite 和媒体处理，network 管理学校请求与登录状态，domain 保存页面选择、跟看和课表时间规则。普通页面直接放在 ui，只有阅读器和通用控件分为 ui/reader、ui/components；主题与通用控件放在一起。export 和 update 按完整功能聚合。

目录服务于查找和协作：经常一起修改、职责相近的文件放在一起，不为尚未增长的功能预建层级。保持单个模块，目录与 Kotlin 包名一致，跨包使用显式导入。

Android applicationId 保持 io.github.goooseby.keye，数据库名称、版本、JSON 字段、SharedPreferences、FileProvider authority 和更新 Intent action 均保持稳定。Manifest 中广播接收器路径与 update 包同步；测试和 Debug 演示入口使用实际生产包导入。源码目录说明见 README。


## 阅读与入口

一级导航为课程、获取、我的。下载管理从获取顶栏及我的进入。课程默认打开最近阅读，支持搜索与全部课程；课件元数据操作与阅读进度分别保存，最近阅读按 readAt 排序。

ReaderScreen 独立于主导航布局，提供连续／逐页、页面总览、页码跳转、页书签、背景、保持亮屏及全屏。常规界面由 Manifest 锁定竖屏，默认零间距纵向连续阅读，由 ContinuousReader 统一管理文档宽度和横向平移，页面按共享缩放因子重新测量高度，LazyColumn 继续承担纵向滚动；单页画布关闭自身手势，避免互相抢占。可在查看设置切换并记住常规逐页偏好。全屏由 ReaderScreen 请求 SENSOR_LANDSCAPE、隐藏系统栏并使用逐页模式；退出请求 PORTRAIT 并恢复常规方式。普通工具栏不参与全屏布局；ReaderControls 的 FullscreenReaderControls 在固定页面视口上叠加渐变、退出、保存当页、书签、还原缩放、翻页与进度滑杆，仅做透明度过渡。翻页按钮调用 Pager 动画定位；进度释放后定位到保留页索引，原始页码仍独立显示。交互计数与拖动状态管理三秒自动隐藏，触摸探索开启时保留控件；系统返回先退出全屏。阅读模式和显示偏好保存在 reading SharedPreferences；页码、页内偏移及比例保存在课件 JSON 中。旋转和重新进入时恢复阅读位置。

ReaderMedia 从原文件解码，PDF 原页在普通显示时按最长边 1800 像素渲染，放大时提升至 3000；图片按采样尺寸解码并应用 EXIF 方向。内存缓存限制为 40 MB。列表与页面总览继续使用小预览，独立缓存限制约 24 MB；这是有尺寸上限的位图阅读，不提供无限分辨率的分块渲染。

普通工具面板提供分组快捷动作：首页／末页、循环定位其他保留页书签、复位缩放、页面管理与导出分享。resetToken 驱动阅读画布复位；当前窗口亮度通过 Window.attributes.screenBrightness 设置，DisposableEffect 离开时恢复，默认沿用系统亮度。全屏期间保持亮屏，退出后沿用常规用户设置。

课件信息提供名称／备注编辑、归类、移入回收站。页面总览支持页面／书签／已移除筛选、长按多选、批量移除与恢复；更多菜单可以移除当前页。excluded 持久记录可恢复的移除状态，原件不删除，阅读与默认输出都跳过这些页。PageSelection 统一保留页索引、移除校验、邻页定位及跨课件导出范围映射，至少保留一页。

ScheduleEntry 持久化可空 startsAt／endsAt，SchoolApi 将课表 start_at／end_at 的 Unix 秒转换为毫秒；AcquireScreen 每十五秒本地判断 start ≤ now < end，仅上课时显示跟看快捷按钮。SchedulePolicy 计算最近已到来的学期边界附近周一，快捷查询仍可手动调整，接口最大范围为 220 天。

普通下载与跟看共用 ClassroomPageSource。一次性同步重新查询目录，用 remoteKey 去重，以唯一文件名逐页保存并在 materialMutex 内提交；失败或取消后已经提交的页仍可读。remotePageCount 为可空的已观察目录数量，兼容旧 JSON。获取页不再把“有页面”等同于“不能继续下载”，所有未删除、未被活动跟看占用的课次均可检查并补全。

## 课堂跟看与页批注

ClassroomScreen 是独立的课堂观看状态，复用 ReaderCanvas 与全屏播放器控件。ClassroomSession 表达连接、等待、已连接、暂停、重试、需要登录与结束；跟随开关属于观看状态，通过可保存状态保留，手动翻页和拖动交互暂停跟随，后台同步暂停与回看互不混淆。原始页码通过 retainedPageIndices 映射，移除页不重新出现在跟看中。

KeyeViewModel 的跟看工作通过单个 Mutex 串行执行，代际编号与协程取消共同阻止旧请求在切换后写回；不可取消的旧 HTTP 完成前，新会话也不能并发进入。默认每三秒检查一次；历史补齐每批最多三页，按最新优先下载，批次间检查新目录。补页批次扣除已处理时间再等待，其余正常轮询在请求完成后等待间隔，始终串行。异常指数退避至三十秒，HTTP 429 采用 Retry-After（最长五分钟）。ClassroomPageSource 分离接口调用，正式实现校验账号归属，Debug 演示源仅用于确定性增页和断网检查，不包含在 Release 包。

PageFile.remoteKey 保存不含明文 URL 凭据的 SHA-256 页面标识；忽略已知鉴权查询参数，保留页码等参数。课件列表查询允许有效空列表；普通下载仍报告无课件。去重不依赖数组长度。缺页按目录位置插入，insertClassroomPage 同步迁移所有按页引用，保留已有页相对顺序；新页面文件不覆盖原件。观看以文件来源为键保持位置，批注保存和阅读记录支持来源校验，保存图片与导出均冻结点击时的快照。遗留无标识页通过文件字节摘要匹配，不猜测位置。新文件使用唯一文件名，保存前持有 materialMutex 并重新读取最新课件，因此不会用旧对象覆盖刚保存的批注、书签或移除设置。失败的未提交文件单独清理，已提交页不回滚。

StudyMaterial 的 schoolEntry 和 pageNotes 是兼容的 JSON 可选字段，数据库表版本不变。页批注按一基原始页码保存，长度上限两千字，保存失败保留编辑面板与草稿。PageNotes 在图片原件下绘制独立文字页尾；不修改原件。ExportRequest.snapshots 和明确的页码集合固定本次导出，includeNotes 控制批注页尾；带批注的 PDF 使用渲染输出，不冒充保留文字层的直接复制。

模拟器演示入口、请求轨迹与验收方法见 [课堂跟看检查](CLASSROOM.md)。

## 导出与分享

ExportSheet 处理文件名、全部／当前／指定范围与保存位置。范围支持英文或中文逗号、连续区间和去重，按原页序输出。ExportRequest 保存本次范围，selectExportPages 与课件的持久移除状态取交集；合并范围按传入课件顺序累计原始页码。指定范围只包含已移除页时不能导出。

KeyeViewModel 控制生成、取消和状态。MediaLibrary 完整导出未移除页面的单份 PDF 时直接复制原件，保留文字和矢量；部分页、合并或图片课件输出图片型 PDF。生成时使用原件而非列表缩略图。先生成缓存，再写入目标，写入循环检查取消；失败清理本次创建的输出，默认不覆盖同名文件。

ExportStorage 使用以下位置：

- Android 10 起默认 MediaStore.Downloads 的 Download/Keye/，写入完成前保持 IS_PENDING。
- 用户可通过 OpenDocumentTree 自选文件夹，保存目录 URI 和持久授权；Android 8–9 首次保存需选择文件夹，不索取整盘权限。
- 仅分享的 PDF／当前页 PNG 存放于 cache/shared 的独立目录，用 FileProvider 内容 URI 传递；下次启动清理七天前的临时分享文件，不立即删掉接收方可能仍需读取的文件。

系统分享使用 ACTION_SEND、正确 MIME、ClipData、临时读取授权与系统 chooser，不固定分享目标列表。导出并分享使用已保存的同一份结果；用户取消分享时永久输出仍保留。仅在原发起页面仍显示、应用处于前台且自动分享事件尚未消费时自动打开分享；其他情况由完成提示里的分享按钮继续。不显示“发送成功”。

导出不强制跳转下载管理，进度与结果浮在当前页面底部。失败可重试 PDF 请求；导出参数在 ViewModel 生命周期内保留，进程结束后不会恢复未完成生成。输出和下载目前仍随进程运行，不具备持久后台执行能力。

## 数据

SQLite 为应用私有目录 databases/keye-library.sqlite，原件与预览为 files/materials/<编号>/。表内保存 JSON，数据库结构版本为 1。readAt、readOffset、readFraction、bookmarks，以及任务的 createdAt、batchOrder 为带默认值的 JSON 可选字段，兼容已有记录，不重建表、不删除课件。修改 SQL 表结构时仍必须实现数据库迁移。

阅读位置更新不增加课件内容修订号；页书签与名称修改不改变 readAt。公共导出文件与应用私有原件、缓存分别管理。卸载应用会删除私有课件，需保留的内容应先导出。

## 学校与任务

学校 Cookie 解析为内存凭据，系统 WebView 可能保存 Cookie；只有用户主动退出才清除 Cookie；自动检测失效、网络失败和打开登录页均保留 Cookie，成功登录调用 flush 保存学校提供的有效期。凭据只附加到学校主域名请求，图片地址和跳转仅接受 HTTPS。SchoolConnection 表达五种连接状态；MainActivity 在 RESUMED 生命周期内检查并每 60 秒复核，进入获取／我的也触发带 60 秒节流的检查。使用当天课表接口验证（没有课次也有效），验证超时最多 15 秒。401／403、明确的登录重定向和 JSON 认证错误转换为 SchoolSessionExpired；CDN 图片失败、超时、服务器错误及无效响应只报告连接问题，不删除凭据。已被平台拒绝的同一 Cookie 不反复标为已连接，需新的 Cookie 或登录页面再次验证成功。学校登录页面轮询新 Cookie 并验证，手动完成可重试。

认证失败同步清除内存中的有效状态和旧查询结果，本机课件保持可读。异步验证和课表查询分别使用序号与协程取消，忽略已被新请求或退出登录替代的结果。AcquireScreen 的展开集合属于当前查询结果，初始为空，支持单门和全组开合；选择集合独立保存，折叠不清除选择。

下载最多同时两项，同一课次不重复排队；本地导入最多一项。整批等待任务通过 SQLite 事务保存并一次发布后启动工作协程；mergeTaskBatch 按 ID 去重。队列按不可变 createdAt、batchOrder 排序，updatedAt 只表达状态更新时间。AnimatedTaskQueue 保留正在退出的展示行，单个 AnimatedVisibility 负责水平位移、淡出和高度收拢，不叠加 LazyColumn 的 placement 动画。下载重试需要来源学校账号。退出登录取消下载和查询，保留已下载内容；离线阅读和导出无需登录。

导入保留来源 URI 授权，支持取消及重试。原件被移动或授权失效时提示重新选择。任务在进程中运行，中断后下次启动标记失败。

## 应用更新

AppUpdateManager 独立管理公开 GitHub Releases 元数据、ETag、每日检查记录、忽略偏好、DownloadManager 任务和安装包。UpdatePolicy 使用纯逻辑验证稳定版本、每日门限、仓库地址和签名集合。AppUpdateViewModel 用互斥锁串行恢复、下载、校验与安装准备，不将系统下载任务绑定到界面生命周期；AppUpdateHost 仅在前台查询本机下载状态，不借轮询请求 GitHub。

下载完成后的私有副本通过大小、SHA-256、包名、版本、最低系统和签名校验，安装前再次复核。UpdateDownloadReceiver 只查询匹配任务并发送完成通知，真正安装由前台 MainActivity 处理。拒绝权限和取消安装不会删除有效安装包。应用更新的任务与课件下载独立，不能清理课程文件。签名配置仅从环境变量读取；正式发布流程及检查边界见 [UPDATES.md](UPDATES.md)。

## 能力边界

不提供 PPTX 原生解析、幻灯片动画、OCR、全文搜索、笔迹批注、自动播放或云同步。原 PDF 完整导出可以保留原有文字；图片课件和重渲染输出不凭空产生文字层。分享面板可用不代表特定应用已安装或对方已收到。

产品交互依据见 [PRODUCT_DESIGN.md](PRODUCT_DESIGN.md)，视觉约定与检查入口见 [UI.md](UI.md)。
