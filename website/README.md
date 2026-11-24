# 课页 Keye 网站与公告

- 主页：<https://keye.kovra.top/>
- 公告接口：<https://keye.kovra.top/announcements.json>
- Android 介绍页：<https://keye.kovra.top/android/>，从主页 Android 卡片的“了解详情”进入。
- `public/`：无需构建的静态 HTML、CSS、JavaScript、图标和公告文件。
- `nginx.conf`：此子域名的独立站点配置，不修改主站或博客配置。

主页分别介绍课页 Desktop（Windows 10／11）和华工课栈 Android（Android 8.0+），提供各自下载、源码与问题反馈入口。功能信息分别依据两个项目的 README。桌面端图标复制自 `keye-desktop/public/app-icon.svg`，Android 图标来自本仓库 `docs/assets/app-icon.png`。

网页默认深色，通过右上角按钮切换浅色；选择以 `keye-theme` 保存到浏览器本地，刷新后保留。`theme.js` 在样式前加载，避免已选择浅色的访问者刷新时先看到深色。禁用本地存储时仍可切换当前页面主题。资料收纳和阅读插图为网页内的示意图，不是应用界面截图；此次视觉改版仅涉及网站文件。

Android 介绍页介绍获取、跟看、阅读、整理与导出，并包含三步上手和常见问题。`public/android/images/` 的三张原始截图来自模拟器：课程页使用现有 `UiGalleryActivity` 和单个受控演示资料；跟看与导出使用现有 `ClassroomDemoActivity` 及其自建测试页面。截图不含真实账号或学校课件，页面标明其演示来源，可点击查看原图。本次只新增网站介绍内容，桌面端介绍页暂缓。

## 当前部署

服务器复用已有 Nginx，配置为 `/etc/nginx/sites-available/keye.conf`，通过 `/etc/nginx/sites-enabled/keye.conf` 启用。

静态文件按发布版本放在 `/var/www/keye/releases/`，入口 `/var/www/keye/current` 是指向当前发布目录的软链接。仅提供静态文件，不运行额外常驻服务，也没有数据库或管理后台。

HTTP 跳转到 HTTPS；JSON 接口直接返回 HTTP 200、`application/json`，缓存一分钟。主页要求重新验证缓存，CSS、JS 和图标缓存一小时。主页通过同源请求读取与 Android 应用相同的公告文件，正文按纯文本显示。

## 证书

使用用户提供的证书，证书链与私钥分别安装到：

```text
/etc/nginx/ssl/keye.kovra.top.pem
/etc/nginx/ssl/keye.kovra.top.key
```

私钥只允许 root 读写（0600）。本机原始证书解压在 `D:\WebDev\28512837_keye.kovra.top_nginx\`，不放入 Git，也不放入网站公开目录。

证书有效期截至 **2027-02-12 07:59:59（Asia/Shanghai）**。当前使用手动提供的证书，未配置证书签发或续期自动化；请到期前取得新证书并替换以上文件，确认密钥匹配，执行 `nginx -t` 后 reload Nginx。不要把普通静态文件发布当成证书更新。

## 发布公告

修改 `public/announcements.json`，字段与阅读状态规则见 [应用公告说明](../docs/ANNOUNCEMENTS.md)。发布时先验证 JSON，再上传到站点目录中的临时文件，最后在同一目录用 `mv` 替换正式文件，避免客户端下载到写入一半的内容。发布公告无需重启 Nginx，也无需重新发布 APK。

第一次将公告接口地址加入旧版应用时，用户仍需要安装包含公告功能的新版本。网站下载按钮继续指向 GitHub 的最新正式安装包，不会自动发布本机测试包。

## 发布主页修改

将完整 `public/` 文件上传到 `/var/www/keye/releases/` 下的新目录；文件权限 0644、目录权限 0755。用临时软链接指向新目录后，通过 `mv -Tf` 原子替换 `/var/www/keye/current`。保留上一份发布目录，可通过切回软链接回滚。

仅改主页时，保留服务器当前的 `announcements.json`，避免覆盖另外发布的公告。HTML 中 CSS／JS 的版本参数在修改相应资源后更新，避免已有访问者使用旧缓存。

修改 Nginx 配置前保留原配置；只在 `nginx -t` 成功后执行 `systemctl reload nginx`。验证主页、JSON 接口、HTTP 跳转、HTTPS 证书，以及原主站／博客仍然正常。

2026-11-15 素材更新：主页和 Android 页导航改用 KOVRA 主站标志。以当前 MainActivity 的周课表和当日课表替换旧 UiGallery 课程预览，使用分散在不同日期、时段并保留空档的演示课程，课表为首页核心功能单独介绍；模拟器原资料库截图后恢复。

### 安卓介绍页：连续图文介绍（2026-11-15）

`public/android/` 按课表、课堂跟看、离线阅读、整理导出四个章节展开。每段使用应用实拍和功能说明，桌面图文交替，手机上下排列；`story.js` 提供一次性渐入、轻微滚动位移与当前章节导航，不接管滚轮。尊重 `prefers-reduced-motion`，禁用 JavaScript 时内容仍可读。新增课堂与阅读截图沿用当前应用真实组件的模拟器演示数据，不是学校课件或真实账号数据。网页未使用外部动画依赖。

### 阅读视口与侧边目录（2026-11-15）

安卓介绍页取消顶部双层吸附导航：站点头部正常滚走，1280px 及以上仅在功能章节显示左侧目录；窄屏目录随文档滚走。章节手机截图交替轻微倾斜，保留原始截图，采用开放式排版。验证使用实际视口截图（含 1365×768、1706×900 和手机），另检查目录不与正文重叠、锚点标题可见、横屏短视口与深浅主题。

### 玻璃导航与紧凑首屏（2026-11-15）

主页和安卓介绍页共用 `navigation.css` / `navigation.js`。顶部保持展开导航，滚动前 150px 内连续向内收拢成低高度玻璃胶囊，使用背景模糊、透明色和边缘高光；减少动画模式直接切换状态。安卓首屏改为桌面左右图文布局，手机收紧标题和间距，保证进入页面即可看到产品界面。导航仅单层吸附，章节仍使用侧边目录，并为锚点预留顶部空间。

### 华工课栈 Windows 介绍页（2026-11-15）

新增 `public/desktop/`，主页 Windows 卡片增加“了解详情”入口，并同步使用当前名称与资料库截图。功能内容以桌面项目 README 为依据，图片原样复制自 `D:/pythonDev/PythonProject/keye-desktop/docs/screenshots/` 的 library、acquire、editor、crop，均为项目示例模式截图。使用当前桌面项目图标，不启动、修改桌面应用或接触用户资料。

展示采用紧凑图文首屏、横向工作台全景、交替图文、滚动渐入和轻微窗口倾斜，复用站点玻璃导航。PDF 图形为 HTML/CSS 流程示意，页面有明确标注。验证覆盖深浅主题、笔记本与手机视口、原图入口、目录锚点、FAQ、减少动画和禁用脚本回退；发布时保留服务器公告文件。

### 安装包服务器托管（2026-11-15）

主页和两个介绍页直接提供两种下载方式：主按钮为本站直连并标记“推荐”，旁边保留 GitHub 同版安装包下载，源码入口独立。没有新建中转页面。`downloads.js` 从 `/downloads/releases.json` 同步版本、大小及两种地址；请求失败或禁用脚本时，仍使用 HTML 内已校验的版本链接。

安装包存放在 `/var/www/keye/downloads/<windows|android>/<版本>/`，独立于 `/var/www/keye/current`，切换网页版本不会丢失安装包。Nginx 在现有 HTTPS 站点引入 `/etc/nginx/keye-downloads.conf`，保留桌面公告单独 alias、原有安全头、断点续传、版本文件路径和短期缓存，禁止目录列表及隐藏文件访问。元数据为 JSON，安装包通过 SHA-256 与 GitHub Release asset digest 比较，文件大小及格式校验通过后才更新清单。

本次镜像 Windows v0.5.1、Android v0.3.3（当时已发布正式版，非本地开发构建）。两种渠道是同一版本、相同 SHA-256 的原始包。

服务器直接访问 GitHub 超时，因此**没有启用服务器自动同步定时任务**。在能访问 GitHub、配置了现有 SSH 登录的开发电脑上，发版后执行：

```pwsh
python website/sync-downloads.py
```

脚本从 GitHub 最新正式 Release 获取两个原包并校验，上传后再由服务器复核；安装包发布后最后原子替换 `releases.json`。失败保留旧下载入口，拒绝同版本文件内容被替换；旧版本不自动删除。同步不修改应用源码、GitHub Release 或应用内更新地址。开发缓存与二进制包在忽略的 `app/build/diagnostics/website/download-mirror/`，不进入静态网页源码；`public/downloads/releases.json` 只是本地预览用的初始清单，线上由独立 alias 提供。

2026-11-17 Android 更新至 v0.3.4：同步 GitHub 正式签名 APK，并更新下载清单、主页和 Android 页的备用下载链接。两处 APK 的 SHA-256 均为 `98e8d1efbfbb7b571918de96b3de98583a6f798c9bda4417ee9d6c5e50e9f35f`，大小 2,881,805 字节，断点续传验证通过。网站功能介绍、公告原文和 Windows v0.5.1 保留；前一静态目录和旧安装包保留用于回滚。

2026-11-24 Android 更新至 v0.3.5。通过 GitHub CLI 核验最新正式附件后同步 APK 和 Android 下载清单，并更新主页及 Android 页的备用下载链接、发布对应公告。公网下载和断点续传验证通过。

### 主页视觉打磨（2026-11-15）

新增仅主页加载的 `home.css` / `home.js`，避免影响两端介绍页。首屏用 Windows 工作台与 Android 课表原图组成产品展示，缩小标题、调整图文重心；两端卡片统一字号层级、渐变底色、展示区域与留白。底部改为工具使用节奏说明和双栏公告区，添加轻微进入动效，减少动画或禁用脚本时内容直接可见。

视觉检查使用修改前后的真实浏览器视口及整页截图，覆盖深浅主题、笔记本、宽屏、平板与手机。首轮发现首屏图注与倾斜窗口重叠，二次调整后补充检查；保留两种下载渠道、服务器推荐、详情及源码入口。安装包与公告仍保存在既有位置。
