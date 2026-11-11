# 高价值失败经验

增量核实：2026-11-11。F01–F13 多为迁移保留的历史经验，未在本轮重现；F14–F21 为后续开发/工具调用新增核对。区分实际失败、源码证据与待真机验证的修正，新故障先确认版本与环境。

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
- 引导表只能是公共DNS服务自己的官方地址；不把过时linux.do目标IP/ECH密钥固化进APK，不用私有DoH后端或TLS放行补成功率。详细来源、实测与测试在 [LINUXDO](../docs/android/LINUXDO.md)，当前验收断点见 HANDOFF。

## F22 · P1 / Active · 标准加密 DNS 与替代 HTTP 客户端的死路（2026-11-11）

- 条件：当前网络对知名 DoH IP 做 IP 封锁（1.1.1.1/8.8.8.8/9.9.9.9:443 超时），国内 DoH 剥离 ECH。系统排查“TCP 可连”不等于“能完成 TLS”：
  - **DoT 853**：1.1.1.1:853 TCP 可连，但 TLS 握手被 RST（10054），DPI 按 SNI=cloudflare-dns.com 阻断。
  - **CF 边缘 IP 绕路**：188.114.96.1/97.1、104.17.1.1 TCP 可连，但用其访问 cloudflare-dns.com 超时（同样 SNI 阻断），不能替代 1.1.1.1。
  - **NextDNS/Yandex**：45.90.28.0、77.88.8.8 TCP 可连，TLS 握手 RST；**360**（101.198.198.198）返回 403。
- 供应链事实：**rquest 所有版本（0.20/0.31/1.x/2.x）已从 crates.io yanked**，不能作为新依赖；Cloudflare 官方 **boring/boring-sys（5.3/5.4 非 yanked）可用且含完整 ECH API**，但 boring-sys 构建需 git（PQC 补丁）与 nasm，本机无 git/nasm（perl 在 msys64）。最终未引入，h2 已用现有 rustls 达成目标。
- 有效解：保持 ECH + TLS 校验，传输改 h2 + Chrome Mobile UA（见 D14），ECH 用公共→第三方 JSON DoH→缓存三级获取（见 D15）。
- 防复发：判断加密 DNS 可用性必须验证到 TLS 握手与实际 ECH，不能只测 TCP；不要重复 rquest/boring 或 DoT/边缘 IP 路线。证据在 .local/reports/{net-reach,dot-reach,cf-doh-reach,doh-ip-reach}.txt 与 [LINUXDO](../docs/android/LINUXDO.md)。

## P3 / Obsolete · 已退役层的临时排障

旧 Vinext 的 Node 要求、manifest 登录凭据、Service Worker、CSP nonce、隐藏 stdin CR/LF 等均属于已删除 PWA。只保留该适用性边界，不迁移临时会话 ID、命令流水或失效部署步骤，不列为原生待办。
