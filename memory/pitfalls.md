# 高价值失败经验

增量核实：2026-11-12。F01–F13 多为迁移保留的历史经验，未在本轮重现；F14–F21 为后续开发/工具调用新增核对；F26–F28 为来源扩充阶段的路由漏判、无 ECH 死路与 RSS 特殊字符静默失败。区分实际失败、源码证据与待真机验证的修正，新故障先确认版本与环境。

## F01 · P1 / Active · 方案看似可安装，实际仍依赖云

- 条件/尝试：先提供 PWA 安装入口，再做 WebView 2.1 APK；曾公开原先所有者私有的站点。
- 结果：仍有远程站点/云采集前提，不符合用户最终“安装、联网、应用自身完成”的要求。
- 根因与解法：安装形式不等于运行独立；3.0 将 UI、采集和资料改为手机原生，具体决策见 D01。
- 防复发：不能把 PWA、公开站点或网页壳当替代交付。证据：当前三个 Java 类、Manifest、安卓验收报告。

## F02 · P1 / Active · 增量编译混入旧外壳类

- 条件/尝试：从 WebView 壳改原生时沿用 classes/dex/gen 输出目录。
- 结果/根因：历史记录指出旧内部类可能残留 DEX，源码删类不等于输出清理。
- 解法：build.py 在验证生成目录边界后清理再编译，检查 DEX 中禁止的旧依赖。
- 适用/证据：改类结构或架构后构建；`scripts/android/build.py`。不要扩大删除范围至签名/个人状态。

## F03 · P1 / Active · 重建哈希与升级身份不是一回事

- 条件/尝试：将同源重建包与已交付包比较哈希，或在新克隆生成密钥后尝试覆盖升级。
- 结果：同版重建可因 ZIP 时间等不同而哈希变化；不同签名不能覆盖官方安装。
- 根因/解法：文件字节身份与签名身份分离；保持正式 release 不动，以原密钥维护升级、元数据核对公开指纹。不是每次哈希差异都由时间戳造成，须按实际构建核查。
- 证据：build.py、release.json、C01。不得删密钥来“修复”升级，也不得自动替换正式 APK。

## F04 · P1 / Active · 停更 fixture 的星期与日期冲突

- 条件/尝试：旧测试只改 RFC 1123 时间的年份，没有改星期。
- 结果/根因：Java 严格解析失败为 0，测试没有实际覆盖预期的停更条件。
- 解法：修 fixture 中有效日期，保留生产日期校验；不能为测试通过关闭时间验证。
- 证据：`NativeEngineTest.java` 与 `NewsEngine.date/parse`；旧故障来自历史记录，本次未重演。

## F05 · P1 / Active · 内容解析正确性易回归

- 条件/尝试：直接剥标签、选 Atom 首个链接，或将所有科技 RSS 计为 AI。
- 结果：编码标签残留、把 rel=self 当原文、AI 栏混入非 AI；长 JSON ID 若转浮点也会丢精度。
- 根因与解法：先处理 CDATA/实体再清理脚本/样式/标签，Atom 优先 alternate，媒体 AI 关键词筛选，长 ID 保持字符串，稳定 ID 不随刷新时间重建。
- 适用/证据：改解析器时保留有意义的 fixture；`NewsEngine.clean/rss/rows/parse`、`NativeEngineTest.java`。

## F06 · P1 / Active · 失效来源不能伪装实时成功

- 条件：普通 RSS 停更多日；低频官方 AI 更新；离线强制刷新所有来源失败。
- 错误方向：统一七天阈值会误杀 AI；清空失败快照会丢用户已有内容；用采集时间代替发布时间会制造新鲜度。
- 解法：普通 RSS/AI 分开判定，保留 dateOnly 和未知时间，失败保留 items/fetched 并更新 checked/error。
- 证据：C04、安卓验收中断网 29 源失败仍保留新闻/收藏。不能据旧验收声称本次来源全成功。

## F07 · P1 / Active · 中文启动器与 PowerShell 版本

- 条件/尝试：Windows PowerShell 5 读取没有 BOM 的 UTF-8 中文脚本/文件名；在双引号里拼多行代码替换。
- 结果/根因：可能误读中文路径，或反引号、`$变量` 提前展开而改错代码（历史记录）。
- 解法：现有 ps1 保留 UTF-8 BOM/CRLF，用字面 here-string/结构化文件写入并读回；JSON.stringify 不是 shell 转义。
- 适用/证据：兼容 PS5 时不能只以 PS7 结果为准；`.gitattributes`、现有脚本编码。

## F08 · P1 / Active · 整理后源码与便携包路径不同

- 条件/尝试：将 Go/脚本移入 desktop、共享资料移入 internal 后，继续按旧根相对路径运行。
- 结果/根因：import/embed、资源复制、启动器和包内 data 定位可能错位。
- 解法：共享目录唯一维护源、同步各端路径、增加跨端 registry 测试；paths.ps1 分源码与便携包定位，stop 校验进程文件。
- 证据：`docs/STRUCTURE.md`、`desktop/registry_test.go`、`scripts/desktop/paths.ps1`、整理检查与烟测。

## F09 · P1 / Active · 恢复 ZIP 的早于 1980 时间戳

- 条件/尝试：整理前用 Python zipfile 备份具有早期时间戳的源文件。
- 结果/根因：历史备份创建因 ZIP 时间范围限制失败，不能把部分产物当有效恢复点。
- 解法：当时用 `strict_timestamps=False` 创建并做 CRC 校验；以后使用该格式遇到同类日期再采用，不是永久禁止其他备份工具。
- 证据：`.local/maintenance/reorganize.py`、原恢复 ZIP 与整理记录；本次不解包或重新扫描私有历史。

## F10 · P1 / Active · GitHub API 限流不代表上传失败

- 条件/尝试：推送后用匿名 API 查询，遇到 403 rate limit。
- 结果/根因：API 观察渠道限流，Git push 与 raw 可用；不等于仓库权限错误。
- 解法：当时以远端 ref 与匿名 raw README/APK 哈希核验；不为此新建 PAT、扩大权限或改可见性。
- 证据：HEAD `f51c8e7` 与历史上传记录；本次没有联网重新核对远端。

## F11 · P2 / Active · 模拟器现场和工具状态不可复用

- 条件/尝试：独立 ADB 5038 看不到设备；初始化截图/动画滞后一帧；试图复用旧 REPL/窗口坐标。
- 结果：可误判 APK 失败，或重复点击；工具检测用户输入时继续抢控制也不合适。
- 解法：历史需连接 `127.0.0.1:5561` 再选 emulator-5560；重新观察当前 UI，尊重用户控制权，按当次工具规则操作。隔离方法见 conventions。
- 适用/证据：仅本次创建的虚拟设备；历史模拟器已停止并清理，旧会话 ID 不是项目状态。

## F12 · P2 / Active · 避免无谓依赖与误判告警

- 条件/尝试：早期图标生成依赖 Pillow；JDK --release 8 告警、终端中文乱码被当构建失败。
- 解法：当前复制现有 PNG，Python 仅标准库；根据退出码、实际签名/包内容判断，不全局装图像库或仅凭乱码判 APK 损坏。
- 证据：build.py 的图标复制、javac 参数和验证步骤。编译告警是否无害仍须按具体输出判断。

## F13 · P1 / Active · 旧记忆检查器会固化重复体系

- 条件/尝试：旧 checker 固定十个编号专题与来源/工具/平台重复表，维护 JSON 后必须再维护记忆快照。
- 结果/根因：旧框架把可派生事实复制成第二套权威，迁移后继续运行会报错或诱导恢复旧文件。
- 解法：此次迁移改为从 HANDOFF/可选 INDEX 发现主题、检查优先级/状态/链接与实际资料；不要求复制目录全表。新规范唯一在根协议文件。
- 证据：`scripts/check-handoff.py`；旧一次性表生成脚本在迁移验收后删除。

## F14 · P1 / Active · 多选集合正确仍可能显示互斥项同时勾选

- 条件：原生 AlertDialog 的“全部”与具体类别互斥，其余类别多选；用户在 3.5 首包真机仍报告未修好。
- 已尝试：ReadingRules.choose 不再因程序取消 All 而清集合，Activity 加 syncing 回调保护；JVM 只测集合，仍遗漏 UI 适配器状态，不能称为控件问题已解决。
- 代码证据：pickMany 传给 setMultiChoiceItems 的 checked[] 只在初始化赋值；程序更新 ListView 未同步数组。AOSP AlertController 点击只更新被点数组槽位，getView 重绘又用数组的 true 恢复勾选，因此残留 All 或具体项可再次亮起。此遗漏已确认，但未在用户手机完整复现。
- 当前修正：ReadingRules.syncChoices 先同步整个同一数组，再设置全部 ListView 项；初始化也用此方法，仍保留同步保护。JVM dialogChoiceState 覆盖两向互斥、连续多选、空选/最后一项、重开初始状态；该修正版后续已获用户真机复验通过反馈；当前新 UI 版仍须重新验收，状态由 HANDOFF 维护。
- 防复发：集合、适配器数组与控件须一致；不要只测纯集合就宣称真机通过。证据为 MainActivity.pickMany、ReadingRules.syncChoices、NativeEngineTest.dialogChoiceState，以及 [AOSP 源码](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/com/android/internal/app/AlertController.java#1220)（会话中已打开核对）。

## F15 · P1 / Active · 动态检查时间不能刷新整份资料的事实日期

- 条件/误解：模型页沿用导入快照日期，用户询问为何仍是旧日期，容易将联网成功与资料事实更新混同。
- 核实：动态拉取没有重新评估模型特点/厂商优劣势。仅改 asOf 或 checkedAt 会制造虚假复核；多个日期各有语义，见 contracts C06。
- 当前方案：最近成功获取与内置资料分开；新发布只提示来源链接，不改旧资料。用户随后要求移除列表/详情日期文案，已仅改显示，底层快照不动；不是授权删掉事实日期或把资料改成今天。
- 证据：ReadingRules.latestModelCheck/newModelNotices、NativeEngineTest.deviceCorrections、维护 JSON；这些测试证明不改写结构，不能证明每项事实仍然有效。

## F16 · P1 / Active · 正式包与开发源码版本不同会触发旧交接检查器

- 条件：保留正式 3.0.0，继续交付 dist 下开发版（例如 3.5/3.6）；scripts/check-handoff.py 仍把 Manifest 版本等同 releases 元数据。
- 根因/影响：静态检查器的发布同版假设已过窄，版本不一致不等于交接失真。正式资产与维护模型资料不同也会有预期警告。
- 处理边界：核对正式包和测试包各自哈希、身份、权限及记忆索引，原样报告失败；不改回开发版本、不替换正式 APK 来凑通过。后续可经授权让检查器区分两种身份，本轮不修改脚本。

## F17 · P1 / Active · 纵向布局的文字仍可能与居中图标错位

- 用户真机截图显示 3.6 底栏图标居中、文字偏左；不是新闻标题或数据归并问题。
- 源码确认：item.setGravity(CENTER) 只居中子 View；纵向 LinearLayout 对未指定布局参数的 TextView 默认给整行宽度，TextView 自身文字仍靠左。仅设置父容器 gravity 不足以对齐。
- 3.7 改为文字显式 wrap-content、Gravity.CENTER、单行，与固定尺寸图标共用父容器中线；横栏关闭 baseline 对齐。源码和构建检查已完成，真机复验待用户反馈，不称为实机已通过。
- 适用：原生图标+标签、主题选择或自绘导航；检查子项宽度和内部 gravity 两层。证据 MainActivity.drawNav 与本会话用户截图；视觉规范由 UI_DESIGN 维护。

## F18 · P1 / Active · 成图提示词不能保证工具的本地保存位置

- 条件：3.7 背景生成时，提示词指定只保存到工作区，但内置工具实际将原图写到 `$CODEX_HOME/generated_images/` 默认缓存；提示词不是文件输出参数。这与 AGENTS 的工作区写入边界存在冲突，不能略过不报。
- 已做处理：三张选定图复制到 `android/res/drawable-nodpi/`，提示词及资源路径见 `assets/android-background-prompts.json`；APK 只依赖项目资源。工具原缓存保留，已向用户披露，没有擅自跨目录清理。
- 防复发：使用成图工具前确认其真实落盘机制，不能凭提示词宣称写入已受限；不能将这次实际缓存视为永久的工作区外写入授权。边界仍有效时优先采用已授权且能限定工作区落盘的公开图片获取方案，或取得明确的缓存例外授权。
- 证据：本会话 image_gen 返回的默认保存路径、项目资源与提示词记录；生成资产归属与呈现规范由 UI_DESIGN 维护，不重复保存图片/提示词副本。

## F19 · P1 / Active · 桌面加速成功不能证明匿名 ECH 抓取或移动端可嵌入

- 本轮只读核对发现系统 hosts 早已将 linux.do 指向 127.211.73.84；普通 HTTPS 请求返回 200 是受已有加速路径影响的证据，不能写成无加速器直连/手机通过。未还原或修改用户原系统配置。
- 独立客户端不用系统 HTTP 代理或 linux.do hosts，并用公共根验证站点，ECH Accepted 后 RSS/JSON 实测均 HTTP 403。ECH 只解决握手隐私/可连接性，不执行站点访问挑战；no_proxy 也不能证明宿主机没有系统级透明转发，手机目标网络仍须单独验收。
- 用户后续 3.8 手机截图为 encrypted DNS unavailable / Connection reset by peer（最后报告 Cloudflare DoH）。表明手机已运行到原生 DNS 连接阶段，但未到帖子接口，不能将电脑的 HTTP403 当作手机已遇到的相同故障。新增独立更新入口只方便单站重试，不能声称已修复 DNS 或挑战。
- 用户提供的 Android 稿实际 cargo check 首先失败于 hyper-util 不存在的 rt feature；仅有说明和源码不等于已编译。另有本地 CA/私钥与 WebView 认证范围变化，不能由第三方文档替用户授权。
- 本轮改用独立无 CA/登录/监听的 Rust/JNI 公开 RSS 实验源，挑战即停并保留旧快照；构建和工具链不等于已取到内容。证据与原稿审查集中在 [LINUXDO](../docs/android/LINUXDO.md)，报告只留 .local/reports。

## F20 · P1 / Active · Google 主下载域名 TLS 失败可尝试官方 CDN，仍须验哈希

- 本轮 NDK 主站/中国域名均 SSL EOF，已有 JVM/Java 工具并不等于具备 NDK。未关闭 TLS 或安装第三方 CA。
- 经用户授权，Google redirector.gvt1.com/edgedl/android/repository/ 官方 CDN 下载成功；NDK、模拟器和镜像均核对固定官方校验值后才使用。脚本分别为 prepare-native.py --ndk-cdn、prepare-tests.py --emulator --google-cdn，不使用不明镜像包。
- 工具下载成功不代表模拟器启动成功；没有可用硬件加速时不自动安装驱动/启用 Windows 功能。运行结果和自建进程关闭情况看 HANDOFF，不把本轮工具准备误记成运行验收。

## F21 · P1 / Active · DoH 连得上也可能没有 ECH，只保留末次错误会误导诊断

- 3.8 手机截图只有最后一个 Cloudflare DoH reset；不能由它推断所有服务都是连接失败。2026-11-11 本机实测：DNSPod/阿里返回HTTP200与有效二进制DNS，但linux.do的HTTPS记录没有ECH；旧客户端只剩Cloudflare这一条可能可用的回退，且其域名引导仍依赖系统DNS。
- 3.8.2新增OpenDNS/AdGuard、官方IP引导且保留域名SNI/证书校验、GET及有限POST回退、有限并发与全部原因诊断。本机实际Rust客户端取得三家有效ECH并完成站点验证，但RSS/JSON仍403。证明本机DNS阶段可用，不证明手机或取帖通过。
- 后续手机3.8.2复验：OpenDNS/AdGuard/Cloudflare均超时，阿里/DNSPod缺ECH，证明上一轮仍未解决手机问题。指定TCP引导IP仍发送域名SNI；截图无法区分链路阻断与2.3秒预算过短，不把其中一种推测写成已确认根因。
- 3.8.3新增Cloudflare/Google HTTPS IP URL并正常验证公共证书/IP SAN（不发域名SNI），保留域名回退、将路线预算放宽到5秒。本机实际Rust两家主IP均取得ECH，后续站点仍403；备用IP仅有Python校验证书的探测记录，新版手机仍待验。IP URL支持按实际握手验证，不从运营商公布IP推断其必然支持IP证书，也不保证未来永远可用。
- 引导表使用运营商自己的地址，不把过时linux.do目标IP/ECH密钥固化进APK；早期仅标准公共DoH的选择已由D15新增无密钥第三方兜底替代，TLS放行仍禁。详细来源、实测与测试在[LINUXDO](../docs/android/LINUXDO.md)，当前验收断点见HANDOFF。

## F22 · P1 / Active · 既有加密DNS与替代客户端失败记录的适用边界

- 前轮接手记录：1.1.1.1/8.8.8.8/9.9.9.9:443超时、国内DoH缺ECH；这是当时网络的观测，不能单靠超时确定封锁、单靠缺字段断言主动剥离，或延伸为所有网络永久不可达。“TCP可连”不等于“能完成TLS”：
  - **DoT 853**：记录1.1.1.1:853 TCP可连、TLS RST（10054）；SNI/DPI阻断是当时解释，非本轮独立确认根因。
  - **CF边缘IP**：记录188.114.96.1/97.1、104.17.1.1 TCP可连，但访问cloudflare-dns.com超时；当时未成功，不保证以后仍失败。
  - **NextDNS/Yandex**：45.90.28.0、77.88.8.8 TCP 可连，TLS 握手 RST；**360**（101.198.198.198）返回 403。
- 前轮报告rquest依赖撤回、boring构建工具受限，均未引入；本轮未独立重查撤回状态或重编译这些库，不当作永久事实。原记录“本机无git”不准确：本轮Git已实际提交/推送，Get-Command可找到git，PATH内未找到nasm。现有rustls/h2已工作，无需为历史推测自动安装替代工具。
- 有效解：保持 ECH + TLS 校验，传输改 h2 + Chrome Mobile UA（见 D14），ECH 用公共→第三方 JSON DoH→缓存三级获取（见 D15）。
- 防复发：可用性验证到TLS/ECH和实际RSS；重试历史失败路线需有网络/工具变化依据，不永久禁止。新增回退/缓存的审查待办由[LINUXDO](../docs/android/LINUXDO.md#独立审查发现与待办)维护；历史报告在.local/reports/{net-reach,dot-reach,cf-doh-reach,doh-ip-reach}.txt，本轮成功在linuxdo-review-rss.json。

## F23 · P1 / Active · 总截止不能代替分阶段预算，缓存命中不能续期

- 3.8.3标准DNS最坏约43秒加第三方3×8秒，已经超过65秒总限，缓存读取和站点连接可能根本没机会执行；只增加“最后读缓存”分支不保证回退可达。3.8.4保留原请求/重试预算，公共45/第三方25/总90秒，预留约20秒连接；慢阶段fixture确认能到缓存。
- 旧缓存基于解析成功即写，可能覆盖旧可用记录；若每次命中又写回会无限续期。3.8.4只在站点响应通过验证后写、命中不更新saved_at，旧格式可读、坏更新不覆盖；纯缓存真实取帖前后文件哈希一致。7天仅本地淘汰上限，不能保证服务器ECH有效期。
- 证据/完整实现由[LINUXDO](../docs/android/LINUXDO.md#384加固实现与本轮验证)、cache/dns测试维护；新版手机仍待验，不将本机三个成功模式称全网保证。

## F24 · P1 / Active · top.json 偶发 403 与测试 fixture 触发停更检查

- **top 端点首次偶发 403**：实测 `top.json?period=all` 第一次返回 Cloudflare 403（托管挑战），间隔数秒重试即 200；同轮 daily/monthly 及无 query 均 200。判定为边缘缓存未命中/抖动而非稳定封锁。端上失败保留旧快照、不自动重试，用户可再点；不要把一次 403 当成端点被封。
- **RSS 测试 fixture 触发停更检查**：为跨模式 id 对比构造的 RSS fixture 用了 2025-01 的 pubDate，`parse` 对非 AI 的普通 RSS 有“最新发布早于 7 天判疑似停更”，使 fetchLinuxDo 返回空快照、`get(0)` 越界。改用 `ZonedDateTime.now` 生成 RFC1123 时间后通过。构造 RSS fixture 必须用近期日期，停更规则不能为测试关闭（参见 F04/F06）。
- **取数组用 arr() 而非 obj()**：`topic_list.topics` 是 JSONArray，误用 obj() 直接编译失败，属一次性类型错误。
- 证据：.cache/tmp 下 probe 脚本与 rss/top body、`NativeEngineTest.linuxdoTopMode`；端点字段与 views 范围见 C03。

## F25 · P1 / Active · 3.8.6 端点死路与排序陷阱

- **period=all 不可用**：`top.json?period=all` 50 候选按「Neo + 90 天」过滤后仅剩 4 条（27 Neo、44 超期）；all 是全历史高浏览经典老帖，无法满足 50，已弃用。有效替代是 `period=quarterly`（过滤后约 37 条）。
- **top 分页遭拦截**：`top.json?period=quarterly&page=1` 返回 Cloudflare 403，无法翻页补足；浏览量子页合格多少显示多少，禁止伪造或用其他端点凑数。
- **裸 latest.json 条数不足**：不带参数仅 30 条，须 `latest.json?per_page=50` 才返回 50。
- **latest 列表非严格时间序**：`latest.json` 会混入置顶帖与被顶起的旧帖（实测最旧为 2025-03 的 Neo 置顶帖），不能直接按列表顺序当“最新”，必须在解析时按 created_at 显式倒序。
- 证据：`.cache/tmp/p386/{quarterly,latest50,topic}.json`、probe386/probe386b 脚本；`NativeEngineTest.linuxdoModes` 覆盖过滤/排序/全文。本机成功不等于手机可达，移动网络验收仍待办。

## F26 · P1 / Active · ECH 路由白名单漏判，V2EX最新回退直连被 RST

- 条件/现象：用户报告「V2EX最新」证书或加密连接验证失败，而「V2EX」正常；两源经 Rust ECH 客户端实测均可达（hot.json 67KB、index.xml 96KB，内容交集为 0，一个热门 10 条、一个时间序 50 条，均保留）。
- 根因：`LinuxDoTransport.nativeSource(id)` 只放行 `linuxdo/v2ex`，**漏了 `v2ex-latest`**；该源因此回退 `NewsEngine.download` 普通 HTTPS，真实 IP+真实 SNI 被 RST。`allowedEndpoint` 本就含 index.xml，无需改。
- 解法：`nativeSource` 增加 `v2ex-latest` 分支，使其走 native ECH。
- 防复发：新增走 ECH 的源 id，必须同时在 `nativeSource`（路由）与 `allowedEndpoint`（端点白名单）两处登记；白名单类故障先核对“源 id 是否在路由判断”，不能只看端点白名单。
- 证据：`LinuxDoTransport.java`、native probe `v2ex-latest` ECH accepted/HTTP200。

## F27 · P1 / Active · Hugging Face 无 ECH 死路，与 BBC 同构

- 条件：HF 来源响应超时。实测：
  - 系统 DNS 被投毒到 Twitter 段假 IP `199.59.149.231`；
  - 可信 DoH 给出真实 IP 在 AWS CloudFront（alidns `128.242.240.125`；dnspod `18.65.14.85/125/100/87`）；
  - 真实 IP+真实 SNI 握手全部 RST(10054)/超时，存在 SNI 阻断；
  - 无 HTTPS/ECH 记录；
  - 域名前置：SNI 用 `aws.amazon.com/www.amazon.com` 返回 **421 Misdirected Request**（AWS 已封 domain fronting），`d1.awsstatic.com` 直接 RST。
- 结论：硬约束下无合规端上解，已按用户要求移除源；第三方镜像 hf-mirror 属中转不可用。
- 防复发：CloudFront 系站点先查 ECH；无 ECH 且真实 SNI 被 RST 即判死路，勿重复域名前置。
- 证据：`.cache/tmp` 下 net5、hf_front、probe6。

## F28 · P1 / Active · RSS link 未编码特殊字符静默 0 条，大 feed 国际出口波动

- 条件/根因：New Scientist feed 可达（44KB、识别 10 item），但 `NewsEngine` 解析返回 0 条“未返回有效条目”。其 item link 含未编码竖线（`utm_campaign=RSS|NSNS`），Java 单参数 `new URI` 抛 URISyntaxException，`publicURL` 返回 null，条目全被丢弃——属静默失败，非网络问题。
- 解法：`publicURL` 在严格构造失败时，把竖线编码为 `%7C` 后重试，其余安全校验（scheme/host/userInfo/端口/内网黑名单/可执行后缀）全部不变；只影响原本就会被丢弃的条目，对现有源零影响。
- 大 feed 波动：Tom's Hardware 全文 feed 约 1.1MB，无轻量端点（`/feeds/news` 等同样约 1MB，`count` 参数无效），gzip 下载耗时 4–19s 波动、常超 18s deadline，标 experimental、失败靠缓存兜底。
- 候选源死路汇总（勿重复）：
  - 国内 36氪/第一财经/财新 `/feed` 返回 HTML 非 RSS；虎嗅超时；果壳 404；观察者/21 经济 404；
  - 国外卫报/路透/半岛/德国之声超时（被墙无 ECH）；美联社 403；CNN RST；科学美国人 404；Engadget 202 空反爬。
- 证据：`.cache/tmp` 下 source_probe、source_probe2、toms_test、newscientist.xml。

## F29 · P1 / Active · 采集成功不等于展示与语义正确（3.8.7已修复）

- **历史反例**：3.8.6 Rust26项/JVM11组通过，但6个启用源无列表入口；浏览量输入10/9000仍按10/9000显示；`&lt;stdio.h&gt;`被正文清洗吞掉；Go共享表Android66对Go37失败。原排序fixture本就有序，不能证明排序实现正确。
- **有效修复**：路由按C08分工而非放开所有community；先过滤/排序所有季度候选再截取50；先剥真实HTML再解码实体；Go嵌入同一维护JSON，enabled独立于experimental。删除Tom's Hardware不清除旧收藏/已读。
- **同批修复**：分享名取实际包版本；设置从最近检查的linuxTabs读状态；AI authority明确区分厂商/媒体/聚合，避免污染官方检查时间和发布候选；全文缓存改有界同步LRU。无界HashMap的OOM/竞态只是审查风险，未曾实测发生。
- **防复发**：每个启用源必须有内容路由；候选乱序、超过50条且尾部最高、Neo缺users映射、超long整型阈值、编码标签/比较符、删除源后状态恢复都要有反例。不能通过删除一致性测试或沿用假设“enabled=!experimental”解决共享漂移。
- **证据**：NativeEngineTest.repairs387、ArticleTextCache、desktop/registry_test.go；旧反例及网络抽检在.local/reports/review-386-*.json，修复前20份相关文件在.local/repair387-baseline/。最终命令/包/手机未验证范围见HANDOFF。

## F30 · P1 / Active · TLS之上的HTTP/2仍要正确声明HTTPS来源

- **问题/根因**：原Rust请求只给path，h2 0.4.20在请求没有URI scheme且默认HTTP/1版本时，会把伪头:scheme填http；TLS套接字本身不会替它补https。本地依赖src/client.rs与请求构造测试确认该实现行为。
- **修复**：lib.rs用绝对https URI，让scheme/authority和已验证TLS主机一致；linux.do登记JSON再补匿名Accept/同站Referer/X-Requested-With。RSS/V2EX不加JSON上下文；证书、ECH Accepted、h2、固定URL白名单及403停止行为不变。
- **验证边界**：仅改scheme后季度榜仍403；补列表上下文后季度榜两次200，最新JSON403后200；公开主题2910500的JSON同样先403、补上下文后200。不同时间请求并非严格同边缘A/B，不能断言某个头就是Cloudflare内部规则，更不保证今后无403。
- **死路/波动**：两个季度地址变体（附per_page=50及top/quarterly.json）当时均403，未保留额外白名单；V2EX Atom曾连接超时，后续Atom/hot两者200。遇403不执行挑战/登录，失败仍保留旧快照。
- **证据**：Rust h2_pseudo_headers_use_the_verified_https_origin测试；真实探测路径、命令与报告由docs/android/LINUXDO.md持有。传输能取内容与手机验收仍须分开。

## F31 · P1 / Active · 重建整页造成顶栏闪动，单请求不能伪造百分比

- 批量每源完成就120ms去抖render，会removeAllViews重建横向筛选栏；chip的post又滚到当前项，造成重复抖动。3.9逐源仅更新计数/全局进度，完成后一次重绘。UiSmoke用可控制的两源合成传输，等待第一源完成，断言同一顶栏View仍保留，再放行第二源，避免只测试最终恢复正常。
- linux.do旧页内进度max=1/start=0，阻塞期间无进展且与全局busy混用，重新render还可能显示满格。3.9复用全局条，单站请求用indeterminate表示等待，不伪造阶段百分比；批量仍是done/total。运行测试覆盖处理中换主题/重建chrome及结束隐藏。
- 模型下沉后AI顶栏用命名常量对应五页，精选后台归并/更早日期/滚到底的判定一并对齐；旧代码有把热榜序号用于精选的判断，不能只改显示标签而漏改这些分支。
- 403是完成DNS/TLS后的HTTP阶段，不凭“DNS已解析”继续放宽DNS/TLS。用户补充浏览量在Wi-Fi/移动网络均403；3.9三模式宿主均200，最终Android JNI季度也取得37条，手机故障未复现。新增失败诊断区分cf-mitigated=challenge与未标记403，附请求端点，不改成功请求或停止/保留旧快照策略。不能把UI优化或本机成功说成手机403已修复，历史证据见F30。
- 证据：NativeEngineTest.ui390、UiSmoke、docs/android/UI_DESIGN.md和私有ui390报告。本轮API30模拟器升级/界面通过，但不是全机型或手机网络认证。

## F32 · P1 / Active · 先来源筛选再去重，混合源与AI媒体子流同口径

- 混合新闻源加入AI后，与已有ai-media子流会有同URL、不同source/Article ID。旧Activity先seen.add(url)再筛选，未选中且先遍历的副本吃掉选中源条目；不能靠改ID、合并收藏或删除子流修复。3.9.1先做可见性筛选再URL去重，UiSmoke用IT之家/AI资讯同URL反例实测。
- 3.9.1仅按来源category分流不符合当时逐篇需求（4.0.1用户已明确InfoQ整源特例，其他混合源仍逐篇）。普通流和FeaturedNews.group使用逐篇AI-only规则；通用归档保留原记录并按现规则展示，不只改最新而漏掉历史精选。
- 首次Android回归用“AI图像模型发布”构造媒体子流失败：旧rss-ai只匹配标题的ASCII词边界AI，中文紧邻AI未命中，而混合源规则可命中。统一为相同标题/有界摘要口径，JVM和最终UI通过；不简单改成带空格fixture掩盖差异。
- AI-only与AI-or-tech不可混用：普通Java Agent/Rust SDK/英文Daily mail/Airbnb保持非AI；V2EX/HN既有科技范围维持原规则。关键词可能误分/漏分，不称语义判断。
- 证据：NativeEngineTest.aiRouting391、UiSmoke.routeAI、routing391-runtime（首次失败）、routing391-final-runtime及package报告；3.9.0→最终3.9.1隔离升级保留通过，用户真机尚未验。

## F33 · P1 / Active · 精选不能因重复引用整批报废，阶段进度须跨AI任务持续

4.0用户真机两个精选报“引用超界或重复”。源码确认数字索引引用校验遇任一重复就抛错，整批不更新；没有用户原始响应，不能断言实际同时出现了越界。4.0.1改为本批明确r前缀引用、重复首次归属去重；未知引用不猜偏移或拼链接，同模型限一次纠错，仍失败保留旧结果。AiEngineTest覆盖组内/跨组重复、无效ID纠错和固定模型；AiSmoke用重复引用验证五分区可完成。

旧进度仅绑定MainActivity.busy，而AiCoordinator.running属于后续独立任务，导致取帖后条消失。现统一控制器读两者，AI回调带阶段/请求进展，三阶段完成恢复按钮宽度；有色背景上的运行阶段文字保留onAccent，不因disabled变成低对比灰字。测试必须观察过程中各阶段、结束/失败/主题重建，不只检查最终结果。

## F34 · P1 / Active · HTTP200不代表新闻够新或能被现解析器使用

新华网旧RSS返回200但条目实际停于2022年，时间为脱离标签的文本且有乱码；不能当正常新消息源。已在官方栏目/加载器核对出当前JSON，保留源ID、真实上海时间。塔斯社RSS链接CDATA在旧Java解析器直接送publicURL导致所有条目无效；只解开CDATA再走原安全校验，保留危险协议反例。七个实际响应均经生产解析器验证有日期条目；宿主可达不能推断手机网络全可达。候选接口404/403不通过放松TLS、第三方抓取后端或付费墙绕过补齐。端点、结果及未接入来源由SOURCES文档单一维护。

## F35 · P1 / Active · 阶段条更新不代表结果已呈现，性能测试不能代替线上排障

4.0.1中Coordinator虽每个分区成功都写results/通知，Activity回调只在!running时render；结果要等两个精选全部结束才显示。4.0.2在保持顶栏View的前提下即时重绑汇报卡片；按status区分本分区等待/运行/完成，不用全局running把已完成汇报继续标成分析中。AiSmoke必须阻塞精选请求并观察新汇报已经出现，不能仅看最终界面。

原五分区串行、每50标题、近7日精选重复发送、摘要递归合并都可能拉长耗时；本轮96批次/短输出/阶段内两路/有界成功缓存降低重复请求。121标题汇报4次变3次、未变输入0新请求只属合成请求数证据，不是线上时间或费用基准。无效引用从缓存删除，缓存命中也要本地映射校验；换profile不能跨账号复用，清除后迟到任务不可复活缓存。

用户只提供泛化“网络、超时或响应格式异常”，不足以定位真实失败；已拆分超时/DNS/TLS/连接断开/JSON格式，异常原文/服务体不得写UI或日志以免泄密。不靠盲目增加超时或自动多次重试宣称根治；失败分区可单独重试。用户模型deepseek-flash不等于已确认官方地址；官方路径原已禁思考，不能错误声称本轮“关闭深思考解决”。详见AI_API官方依据。

## F36 · P1 / Active · 内容日与采集日不能混用，显示翻译/删除须有同一数据关联

- 11月12日抓到的11月10日新闻必须按published上海日归类；不能以fetched补日历或把最新响应装成历史。ContentDates统一日期，日期精选/归档/快照/译名同时清理；未知日期不猜。对保留收藏的原文副本不得顺手删除。
- 列表译名不能覆盖Article.title：否则会改变标题分析/筛选/分享语义，旧ID与新显示混杂；AiTitles以ID+原标题持有标签，详情原文独立，中文译名也参与搜索。换配置不把历史旧模型结果当新模型生成，每日标注原模型。
- 隐藏键盘只改IME不够，EditText仍可保留焦点导致切回自动弹键盘。SearchInput/IME Insets与onPause同步清焦点、光标和可聚焦状态，触摸再激活；标题补译在输入中不抢焦点。
- 清理前须防止在途来源/AI结果重写刚删除内容；日历操作检查busy/loading/saving/running，清AI结果递增revision/generation，后续新状态原子保存。清不存在日期不应误清其他缓存；源/旧精选/译名的孤立日期也须能从日历发现。
- 运行测试需隔离上一用例的合成归档，不能仅凭标题中的Day0判断真实日期；截图/主题切换屏障用控制闩而非短时间假设；持续动画时不依赖waitForIdleSync全局空闲，改以阶段条件等待，测试超时不算生产网络故障。ReaderFeatureSmoke覆盖实际确认、多选/截止日、重启与换profile保留，不能以模拟器代替全部手机输入法/厂商API认证。

## F37 · P1 / Active · 日历有缓存不等于已选精选，历史读取与保存修复

- 用户确认日历应表示任意内容缓存，不能改成仅标已选新闻；新闻/AI每日精选按scope@day分开。AI-only/关闭源/仅译名也会点亮日历但不一定有新闻输入；正常首次整理不等于丢失记忆，未读取手机私有文件不能断言其实际损坏/超限。
- 4.1的输入仅archive.articles、三日任一缺输入即整批recheck；部分迁移一见任一日期键就跳过；空groups无日历标记；归档异常静默；共享AI6MiB拒写后仅内存可见。6项合成审计曾确认条件，旧ReaderFeatureSmoke没有这些边界覆盖。
- 4.1.1以AiHistory合并现存归档/快照/精选原文，仍逐篇过滤日期/启用来源/新闻或AI；按日先复用/本地选择后补查，不从收藏倒填或制造归档历史。非分页列表不能提供更早日就不无效重抓；确定无内容以historyChecks保留，失败不记成无内容，手动图标可重查。
- 旧聚合逐日幂等补缺且不覆盖已存日，迁移落盘；成功空groups是已选内容，也标日历。AiResultStore不可变日文件先写、原子索引最后提交、成功后GC，清理只从索引恢复，不扫描孤儿复活；AtomicFile的.new/.bak孤儿也须受限清除。
- 索引/单日各6MiB，总有效AI64MiB，不自动驱逐已选历史；限额/IO失败保留旧文件并显示未保存及重试。归档先完整验证，损坏读保留内存/磁盘并禁覆盖；AI读失败暂停自动写/分析，重试成功恢复。内存可见不能称已保存，需提交回执。
- 证据：HistoryStorageTest的纯JVM故障与大文件回归；HistorySmoke的旧APK种入部分日期/空结果、覆盖升级、真实Android>6MiB重载、容量拒写/重试、损坏读保护及清理重载；ReaderFeatureSmoke的先本地后缺日、快照独有零获取、空groups日历与损坏归档保护。具体当次结果见HANDOFF，不等于手机/真实API验收。

## P3 / Obsolete · 已退役层的临时排障

旧 Vinext 的 Node 要求、manifest 登录凭据、Service Worker、CSP nonce、隐藏 stdin CR/LF 等均属于已删除 PWA。只保留该适用性边界，不迁移临时会话 ID、命令流水或失效部署步骤，不列为原生待办。
