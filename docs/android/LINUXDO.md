# LINUX DO 实验来源与本地验证

## 当前状态：3.8.4加固版，三条路径本机取帖通过

- **有效组合**：前轮记录HTTP/1.1请求遭403，而HTTP/2（ALPN `h2,http/1.1`、hyper http2握手）与Chrome Mobile UA组合取得RSS200；前轮还记载仅换UA无效、about.json200而latest.json仍403。本轮仅独立复验最终RSS组合，没有重演完整A/B；不能断言Cloudflare内部判定规则。底层仍为rustls，不等于完整模拟Chrome TLS指纹。
- **ECH 三级获取**（dns.rs `resolve(cache_dir)`）：六家标准公共 DoH → 公共无密钥 JSON DoH（`aaa.ddd.oaifree.com/query-dns`，最多 3 次重试）兜底 → 应用私有目录缓存（cache.rs，`linuxdo-ech-cache.json`，最长 7 天，读回重新校验）。
- **安全不变**：ECH Accepted、证书/主机名校验、5MiB/类型校验全部保留；无 Cookie/登录/WebView/代理，未关闭 TLS。第三方 DoH 仅返回公开 DNS 数据，即使被伪造也会在端到端 TLS 校验失败。
- **当前验收**：3.8.3是用户已报告手机成功的基线；修改前宿主请求4.44秒取得30条。3.8.4的自动、强制第三方、纯缓存三条路径均实测HTTP200/30条、HTTP/2、ECH accepted及TLS验证通过。25项Rust测试、JVM10组回归、三ABI构建/原签名/INTERNET-only通过。新APK10,655,033字节，旧可用APK与正式包哈希未变；新版手机、覆盖安装数据保留未验证，不沿用旧版真机结论。

## 3.8.4加固实现与本轮验证

用户授权加固并要求保留取帖功能；基线main/97368a8。未提交/推送，不继承上一轮Git发布授权。

本轮新增fallback.rs、test_support.rs；修改cache.rs/dns.rs/lib.rs/main.rs、Manifest、ApkProvider、native.py；同步README、本说明、HANDOFF及architecture/contracts/decisions/pitfalls四专题。没有删除文件；任务前未跟踪的linux.do/及AI情报站私有资料未改。git diff --check通过；check-handoff.py退出1，唯一错误仍为测试Manifest/正式包版本不一致，另有既有未发布来源警告，不以替换正式包消除提示。

- 新增fallback.rs，将原第三方接口与8秒/3次重试原样保留；强制HTTPS，最多两次同域HTTPS/443重定向，拒绝降级、换域、凭据或其他端口。声明长度与逐块实际读取双重限制65,535字节，再解析JSON/本站HTTPS记录；校验所属名称、优先级/目标、端口、mandatory和重复字段，避免fake_ech等子串匹配。
- dns.rs统一生产和诊断三级流程，第三方失败不再被丢弃，并报告缓存可用性。公共阶段45秒、第三方25秒，保留各家原请求预算和3×8秒重试；总来源从65改90秒，留约20秒给缓存/站点。正常成功立即返回，最坏失败等待增加。native.py的auto/fallback/cache仅用于开发探测，JNI始终自动模式。
- cache.rs兼容原文件/字段，限制读入65,535字节；临时文件写完sync_all后同目录rename替换，失败不覆盖旧文件。只在站点响应通过既有TLS/ECH/状态/类型/大小校验后写入；缓存命中不续期。最长7天、容忍5分钟小幅时钟偏差，超过未来偏差/过期/损坏/私网/无效ECH拒绝；7天不保证服务器密钥有效7天，原子替换也不等于已做断电验收。
- lib.rs保留原ALPN列表、HTTP/2、UA及全部成功请求头；仅显式要求实际协商h2，避免假称HTTP/1.1回退。RSS输出新增dnsSource/cacheUsed/httpStatus。没有新增依赖、改Cargo.lock、权限、来源/AI资料、状态schema或界面；Manifest/Provider递增为3.8.4-dev/30804。新增test_support.rs只用于cfg(test)，不进入APK。

实际验证命令（已载入scripts/dev-env.ps1；本机使用已有E:/Conda/main/python.exe，未修改系统环境）：

```powershell
python scripts/android/native.py test --offline
python scripts/android/test.py
python scripts/android/native.py probe --feed rss --dns-mode auto --offline
python scripts/android/native.py probe --feed rss --dns-mode fallback --cache-dir .local/linuxdo-hardening-cache --offline
python scripts/android/native.py probe --feed rss --dns-mode cache --cache-dir .local/linuxdo-hardening-cache --offline
python scripts/android/native.py probe --feed dns --dns-mode fallback --offline
python scripts/android/build.py
```

以上均退出0；25项Rust测试含慢公共阶段→第三方、两阶段慢失败→缓存、全失败错误保留、重定向/超大响应/错误记录、旧缓存兼容/原子替换/到期/回拨/不续期。三条真实RSS路径各取得30条，纯缓存前后缓存文件哈希相同。基线报告.local/reports/linuxdo-hardening-before.json；新报告linuxdo-ech-{rss,dns}-{auto,fallback,cache}.json（按实际执行组合）、linuxdo-hardening-package.json。打包核对三ABI库字节一致且确实变更、仅系统libc/libdl依赖、64位16KiB对齐、全部assets未变、生产DEX不含DeviceSmoke，旧可用3.8.3及正式3.0哈希未变。

未验证：新版手机运行/覆盖安装保留、真实慢网/断电；这轮慢失败主要由fixture验证。第三方DNS单点、ECH轮换和站点未来访问策略仍是外部风险。

## 独立审查发现与待办

以下是3.8.3审查时的历史记录（当时源码快照002e1e5、审查未改业务）。已由上节3.8.4修复所列实现缺口；外部依赖风险仍保留，不将下列旧缺陷当作当前实现。

- **实际改动位置**：lib.rs从HTTP/1.1改hyper HTTP/2、增加TokioExecutor及浏览器UA/Accept/语言头；dns.rs增加第三方JSON接口；cache.rs新增7天ECH/公网地址缓存；JNI/Java新增cacheDir参数，由MainActivity传getFilesDir()。Cargo开启hyper/http2及reqwest/json，锁文件和许可证已同步。原有单站按钮、37源注册与工具准备多数早于此次接手，不全算对方新写。
- **依赖边界**：第三方只返回DNS/ECH/IP数据，不转发新闻正文，也不要求账号/API Key；站点内容仍由手机直接向linux.do取得。它不是本项目自建新闻后端，但增加了外部单一服务依赖；首次安装没有缓存，所有实时解析不可达就会失败。该服务能看到查询名称及连接元数据，不能把端到端内容校验写成“没有任何信息泄露”。
- **HTTPS与资源上限待加固**：private_doh_resolve创建的reqwest客户端使用默认重定向，未配置https_only或禁重定向；直接.json()也没有公共二进制DoH的65,535字节限制。源码未关闭证书验证，但不能保证重定向后始终HTTPS，当前没有已发生降级的证据。[reqwest说明](https://docs.rs/reqwest/0.12.28/reqwest/struct.ClientBuilder.html#method.redirect)及本地同版本源码已核对。
- **回退预算与诊断缺口**：六家公共解析最坏预算约43秒，第三方3×8秒再加站点10秒会超过总65秒；慢失败时可能在读取缓存前就超时。第三方错误被if let Ok丢弃，最终DnsFailure只含公共失败；diagnose_dns也只测公共层，因此DNS诊断失败并不代表生产三级流程必然失败。缺少整条链的边界测试。
- **缓存语义**：保存的是成功解析的ECH/地址，未要求该组数据已成功取帖；7天只是本地淘汰上限，不保证服务器密钥7天有效。读回重验格式/公网IP、最终仍要站点TLS/ECH通过。写文件非原子、损坏/到期/时钟回拨缺测试；当前14项仅增加两个基本缓存测试。
- **协议健壮性**：ALPN同时声明h2/http1.1，但代码无条件HTTP/2握手，未按实际协商结果分流；当前站点支持h2，不影响已确认成功，不能据此宣称具备HTTP/1.1回退。两个不同实现沿用同一3.8.3-dev/30803；以后出新包应递增，本轮不改现有包。

## 当前交付边界

当前开发版 `3.8.4-dev / 30804`，加固已可用的h2/DNS回退。3.8首次增加默认关闭的linuxdo，3.8.1增加单站按钮；开启来源后每次只发起一轮本站刷新，绕过普通缓存/失败冷却，期间禁用并显示结果。顶部更新/开关规则不变，不自动循环重试。

仅使用官方公开 `https://linux.do/latest.rss`，展示标题、原文链接、发布时间和 RSS 提供的文本摘要；沿用每源最多 40 条、摘要 300 字符的归一化规则。不抓私密帖子、完整回复串或任意历史分页，不承诺当天无遗漏。

用户最初 3.8 手机截图只有 Cloudflare 连接重置；最新 3.8.2 复验明确显示 OpenDNS、AdGuard、Cloudflare 超时，阿里与 DNSPod 缺少有效 ECH，单站更新按钮已呈现。3.8.2 没有解决该手机的 DNS 问题。截图没有足够信息区分 TCP、TLS/SNI 阻断或超时预算过短；这些仍是待验证原因，不把电脑的 HTTP403 当成手机已遇到的阶段。

## 早期DNS版本的依据、实现与历史结果

2026-11-11 独立实测（不走 HTTP 代理）：DNSPod、阿里的 DoH 能返回 HTTP200 和二进制 DNS 数据，但当次 linux.do HTTPS 记录均没有 ECH 参数。旧客户端因此无法采用它们，又仅把最后一次 Cloudflare 连接错误显示出来。截图本身不足以确定手机前两家也失败于同一原因；本机复现的是旧链路的依赖与诊断缺陷。

修复集中在 `android/native/linuxdo/src/dns.rs`：

- 公共服务为 Cloudflare、Google、OpenDNS、AdGuard、阿里与 DNSPod。服务地址按运营商官方资料核对，未使用作者私有服务或第三方新闻中转；IP URL 的当前支持另外依赖实际 TLS/接口探测，不将其描述为永久服务保证。
- Cloudflare 依次尝试 `https://1.1.1.1/dns-query`、`https://1.0.0.1/dns-query`；Google 依次尝试 `https://8.8.8.8/dns-query`、`https://8.8.4.4/dns-query`。URL/Host 为真实 IP，rustls 正常验证公开证书链和 IP SAN，不发送域名 SNI，不使用伪造主机名或跳过验证。若 IP 不可连接或证书不匹配即失败并回退，不设 TLS 例外。
- 之后保留域名路线：官方引导 IP 仅替换 TCP 选址，URL/Host/SNI 和证书校验使用服务域名；再备用系统解析。OpenDNS、AdGuard、阿里只用这两类域名路线；DNSPod仅使用域名接入，未采用其已弃用的 IP URL。以上固定的是公共 DNS 服务地址，并非 linux.do IP 或 ECH 密钥。IP URL 仅用于内部 DoH，不改变新闻链接和来源端点的 Java 安全规则。
- 用 RFC8484 GET + 无填充 Base64URL、ID=0；遇 HTTP400/405/415 才尝试一次 POST。同一服务并行查 A 与 HTTPS；有有效 ECH 时可使用该记录中的公网 ipv4hint 弥补 A 查询失败，仍需最终验证 linux.do 的证书与 ECH Accepted。
- 最多同时探测两家服务（四个 A/HTTPS 请求）。连接上限4秒，单次 HTTP 与整条路线的 A+HTTPS/GET→POST 合计上限5秒（原2.3秒）；单家预算为路线数×5秒+1秒：Cloudflare/Google21秒、其余域名引导服务11秒、DNSPod6秒。正常有效结果缺ECH时跳过该服务其余路线。首份完整结果取消其他任务；全部失败保留各家原因。整个来源65秒硬上限（原35秒）；取消后不无限等待 OS DNS 阻塞线程。较长等待是容纳慢连接的代价，不证明手机旧超时根因已确认。
- HTTP200、只有公网 A 地址或只有空/不受支持的 HTTPS 参数都不能算 DNS 阶段成功。拒绝私网提示、非443服务端口、不支持的 mandatory 参数，不关闭 TLS，也不跟随任意目标别名/重定向。
- 如果 DNS 成功但后续连接/访问失败，错误会保留“DNS 已解析（服务名 · 实际 DNS 地址）”上下文，区分 DNS 与站点阶段。开发 DNS 报告另外记录 https-ip / official-bootstrap / system-dns 路线。

实际 Rust 客户端 `native.py probe --feed dns --offline` 结果：

| 服务 | 本机本轮结果 | 使用路径 |
| --- | --- | --- |
| Cloudflare | 2个公网地址、71字节受支持 ECH | HTTPS IP URL 1.1.1.1，公共根/IP证书验证 |
| Google | 2个公网地址、71字节受支持 ECH | HTTPS IP URL 8.8.8.8，公共根/IP证书验证 |
| OpenDNS | 2个公网地址、71字节受支持 ECH | 官方引导 IP + 域名证书校验 |
| AdGuard | 2个公网地址、71字节受支持 ECH | 官方引导 IP + 域名证书校验 |
| 阿里 | 有 A/HTTPS 应答，未提供有效 ECH | 官方引导 IP |
| DNSPod | 有 A/HTTPS 应答，未提供有效 ECH | 系统解析服务域名 |

3.8.3 生产流程对 RSS 和 JSON 均通过 Cloudflare · 1.1.1.1 完成 DNS、ECH 和站点证书验证，随后 HTTP403；报告中的原生探测进程退出2。**（当时）本机新 DNS 路线验证通过，匿名取帖仍未通过；2026-11-11 已由 h2 + 第三方 DoH 突破，见顶部小节。** Python 的正常证书校验探测另外验证了上述四个 IP URL 均 HTTP200，但不能替代 Android/Rust 对备用地址的运行验收。原始诊断在 `.local/reports/linuxdo-ech-dns.json`，公开来源请求报告在同目录 linuxdo-ech-rss/json.json。没有把这些记录/密钥固化为手机可用性保证。

**（历史）已实现 ECH 连接能力，当时尚未能匿名获取帖子；该 403 已在 2026-11-11 由 h2 方案突破。** 本机独立 Rust 探测对 RSS、`latest.json` 都实际完成 ECH 和公开站点证书校验，随后收到 HTTP 403，应用按拒绝/需要访问验证处理。未进一步区分具体 WAF/IP/挑战策略。检测到验证即停止，不把挑战页当新闻，也不运行其脚本。

本机原有 hosts 把 linux.do 指向 `127.211.73.84`；普通 Python 请求曾返回 200，这属于已有桌面加速路径，不能用作手机或无加速器直连证据。本轮未改 hosts、系统证书或该加速器。

## 对用户提供方案的独立核对

原始 `linux.do/` 目录保留，未运行其中 EXE、BAT、代理、证书安装或登录代码，未把其业务数据打进 APK。源码只复制到忽略的 `.cache/linuxdo-supplied-audit/` 做编译检查。

| 原方案问题 | 证据与影响 |
| --- | --- |
| “可直接落地”尚无构建依据 | 实际 cargo check 退出 101：声明了 `hyper-util` 不存在的 `rt` feature，依赖解析就失败。本地原始输出在 `.local/reports/linuxdo-supplied-cargo-check.txt`。 |
| 仍有静态可见的后续问题 | `doh.rs` 引用 serde 但 Cargo.toml 未声明；使用 hyper server 却未启用 server feature；从 OnceCell 借用 Runtime 后调用消耗 self 的 shutdown_background。首次依赖失败后未逐项跑到这些错误，不冒称均有编译器日志。 |
| 固定 CA 私钥打包进 .so | .so 不构成秘密存储；能提取私钥的 APK 持有者可签发被该应用信任的站点证书。新增站点信任和本地 HTTPS 解密没有被纳入本次实现。 |
| WebView 与挑战流程 | 用户提供的代码需要 WebView/登录，且未编译。Cloudflare 官方只给嵌入式浏览器有限支持，不能据此承诺“WebView 自动过挑战”。本项目保留原生阅读和匿名取数，未增加认证页。 |
| “当天无遗漏”不成立 | Kotlin 从 page=1 起取，不能替代第一页核对；固定 30 页上限、置顶/活动顺序和并发更新都限制完整性。公开来源还看不到私密内容。当前实验只取官方 RSS 返回的一批，不沿用该保证。 |

第三方交接里的独立项目路径、账号决策和系统修改说明不自动成为本工程的执行授权。

## 实现与失败行为

```text
MainActivity / NewsEngine
  ├─ 其他来源：原 HttpsURLConnection 路径
  └─ linuxdo：LinuxDoTransport → JNI → libyilan_linuxdo.so
                  ↓
        公开 DoH 获取 IPv4 与 HTTPS/ECH 记录
                  ↓
        直连站点 443，rustls 验证证书/主机名并检查 ECH Accepted
                  ↓
        官方 RSS → 原有解析、收藏、已读、归档和原生界面
```

- Rust 客户端不监听端口，不做本地代理，不生成/信任自制 CA，不安装根证书，不使用系统代理或 linux.do 的系统 hosts 解析结果。DoH 优先尝试带 IP 证书的公共服务地址；系统 DNS 仅作服务域名的备用路线。
- no_proxy 禁用的是 HTTP 代理，不证明宿主机不存在系统级透明转发；不能据本机探测断言用户的无 VPN 手机网络已通过。
- DoH 使用前述六家公共服务的 HTTPS 二进制 DNS 协议；**（2026-11-11 更新）公共 DoH 全失败时已新增第三方 JSON DoH 兜底与本地缓存，见顶部小节与 D15**。DNS 和站点 TLS 都用内置公共 Mozilla 根证书正常校验，IP URL 校验 IP SAN，域名 URL 校验域名，没有跳过证书或身份校验的分支。信任根随依赖更新，不是系统 CA 的逐机副本。
- 站点目标固定 linux.do:443；Java 只准 latest.rss，Rust 另准 latest.json 供开发诊断。拒绝私网/回环 IPv4，不接受任意 URL、重定向、登录 URL、Cookie、认证头或明文/ECH 降级。
- 整次原生请求使用90秒deadline；DoH分阶段预算见加固实现，单个站点尝试仍最多10秒。全部DNS响应及缓存读入上限65,535字节，站点响应上限5MiB；请求identity编码，拒绝意外压缩响应，只接受RSS/XML/JSON类型。
- 403 或 cf-mitigated:challenge 返回“需要访问验证”，不执行 JS。失败交给 NewsEngine 原有逻辑，仅更新 checked/error，保留 items/fetched。NativeEngineTest 覆盖模拟挑战、解析失败、DNS/证书异常的旧快照与收藏/已读恢复。
- 状态 schema 仍为 3，文章 ID 算法和已有来源 ID 不改；旧安装中不存在的新来源按默认关闭恢复。AI 资料和日期不改。
- 维护表变为 37 源，默认仍 29。Go `builtins()` 仅补同一条默认关闭的 RSS 注册以通过共享注册表一致性测试；没有为桌面端引入 Rust/ECH。

实现入口：`android/native/linuxdo/`、`LinuxDoTransport.java`、NewsEngine 可注入的 Transport、MainActivity 初始化。Rust 版本锁定文件为 Cargo.lock；完整依赖和运行时许可在 [许可文本](licenses/linuxdo-dependencies.txt)，构建复制进 APK assets，不打包编译工具。

## 工作区工具链

Rust/Cargo 将 Rust 源码编译为原生代码；NDK 提供 Android 的交叉编译器、链接器和系统库；JDK/Android SDK 构建 Java/资源/APK。模拟器或已授权连接的真机负责运行验证。装上编译器不能证明目标网络可用。

- Rust `1.99.1`、Cargo/rustup 管理器、Android 三目标和下载缓存：`.cache/native/`。只把已核验的 rustup-init 文件命名为 rustup.exe 以管理模式运行，未执行 self install，未写安装注册项或全局 PATH。
- NDK `r30 / 30.0.16248370`：`.cache/native/ndk/`。主站 dl.google.com 和中国域名 dl.google.cn 实测 TLS EOF；Google 官方 redirector.gvt1.com CDN 成功下载，SHA-1 与官方发布页一致。没有关闭 TLS。
- 构建所需便携 CMake `4.4.4`：`.cache/native/python-tools/`；现有 `C:/msys64/ucrt64/bin` 只作为宿主编译器读取，未在该目录安装软件。
- 模拟器/系统镜像/platform-tools：`.cache/android/`，同样通过 Google 官方 CDN 下载并校验原有脚本固定哈希。AVD 在 `.cache/android/avd/`，用户状态/ADB 私钥在忽略的 `.local/android-user/`，日志在 `.local/reports/`。原有用户目录下的 Android 密钥/console token 未修改。
- 只尝试软件 CPU 加速，不安装驱动或启用 Windows 功能；独立 ADB server 端口 5039、模拟器 emulator-5580。进程结果和停机状态由 HANDOFF 记录。

```powershell
. .\scripts\dev-env.ps1
python scripts/android/prepare-native.py --rust
python scripts/android/prepare-native.py --ndk-cdn
python -m pip --isolated install --index-url https://pypi.org/simple --target .cache/native/python-tools --no-cache-dir cmake==4.4.4
python scripts/android/native.py test --offline
python scripts/android/native.py build
python scripts/android/native.py probe --feed dns --offline
python scripts/android/native.py probe --offline
python scripts/android/native.py probe --feed json --offline
python scripts/android/test.py
go test ./desktop -run TestAndroidSourceRegistryMatchesDesktop -count=1
python scripts/android/build.py
```

首次依赖需联网；有Cargo.lock后构建默认--locked，APK构建--offline，缺依赖直接失败。--offline只限制Cargo下载，probe仍实际联网；新报告名linuxdo-ech-{feed}-{dns-mode}.json。dns模式与生产共用三级流程，usable=true才退出0，否则1；RSS/JSON遇访问验证子进程退出2，不算取帖成功。强制cache只省略DNS网络，仍需联网访问站点；APK没有这些模式的设置入口。

可选 Android 运行检查：`prepare-tests.py --emulator --google-cdn` 下载；`. scripts/android/emulator-env.ps1` 设置进程内状态目录；`prepare-avd.py` 建 AVD；`build-smoke.py` 建独立同签名 instrumentation 测试 APK；`smoke.py` 只允许本轮空白 emulator-5580，验证本地已有的 3.7 APK → 3.8 升级、真实 JNI 与 Activity 启动。旧 APK、密钥、报告不在 Git，新克隆不能假定这些资产存在。脚本不负责启动/停止模拟器，必须另行记录进程状态。

## 先前DNS版验证记录（历史，当前结果见顶部）

- 已通过：JVM 全套回归及新增来源/传输失败保留 fixture；Rust 4 项端点/地址/DNS/ECH 编码边界测试；Go 共享注册表测试；三 ABI 交叉编译；3.8 APK 构建、原签名、INTERNET-only、内置资产检查。
- 原生 ABI 为 arm64-v8a、armeabi-v7a、x86_64；不包含 32 位 Intel x86。ELF 仅动态依赖系统 libc/libdl；arm64、x86_64 LOAD 段 16 KiB 对齐，ARMv7 为 4 KiB。不能称全机型认证。
- 先前DNS版3.8.3 APK为10,007,865字节；已由同版本号的当前h2版替代，不能将旧哈希当当前包身份。当前哈希、证书与权限以dist元数据/报告为准，正式releases/android/未替换。
- （历史，3.8.3 之前）实测未通过：本机公开 RSS/JSON 匿名取数均 HTTP 403；已确认 ECH Accepted 和站点证书验证成功。**2026-11-11 已由 h2 方案突破，本机 RSS 取 200，见顶部小节。**
- Android 运行检查未完成：软件模拟器首次一直 offline，smoke.py 等待 240 秒后退出 1，未安装任何 APK；关闭 Vulkan/单核/软件渲染重试后实例结束，未出现可用 ADB。启动失败根因尚未确认，不能断言只是硬件加速问题。独立 instrumentation 测试 APK 已编译，实际运行未执行；无启动截图或升级/JNI 通过证据。
- 本轮自建模拟器、crashpad 辅助进程和独立 ADB 已关闭；5039/5580/5581 均实测不监听。工具和本地虚拟磁盘保留，不改系统功能或驱动。
- 3.8.1 已验证单次刷新恰好调用一次来源传输、绕过近期失败冷却，失败不自动重试/不清旧内容；这部分规则继续保留。
- 3.8.3 本轮实际执行 `native.py test --offline`：12项 Rust 测试通过，含旧9项及 IP 身份/域名回退路线、超时与无效应答区分、A超时不丢弃已完成ECH/地址hint；`test.py` JVM回归、`build.py` 三ABI交叉编译/原签名/INTERNET-only通过。诊断 DNS 命令退出0；RSS/JSON取帖失败、原生子进程退出2。
- 本轮最终包比较通过：三份库均不同于3.8.2、与编译输出字节一致，全部assets未变并与维护源一致；ELF仅系统libc/libdl依赖，64位LOAD段16KiB对齐；正式APK哈希未变。报告为.local/reports/linuxdo-3.8.3-package-check.json。
- 先前DNS版当时未执行真机/模拟器运行、覆盖安装数据验收或手机成功取帖，且3.8.2手机DNS仍失败。后续h2版已有用户取帖成功反馈，来源开关/收藏/已读升级保留未单独确认。本轮未启动模拟器、修改系统网络或重跑未修改的Go项目。

## 本轮文件变更与 Git

| 文件 | 作用 |
| --- | --- |
| 新增 android/native/linuxdo/ 的 Cargo.toml、Cargo.lock、src/lib.rs、src/main.rs | 独立 ECH/JNI 实现、固定依赖与宿主诊断程序 |
| 新增 LinuxDoTransport.java；修改 NewsEngine.java / MainActivity.java | 注入固定来源传输，接入原有刷新、解析与缓存失败保留 |
| config/sources.json / desktop/sources.go | 新增默认关闭的同一 RSS 注册，Go 仅维持共享表一致 |
| AndroidManifest.xml / ApkProvider.java | 当前3.8.4-dev / 30804 与分享文件名 |
| NativeEngineTest.java / 新增 DeviceSmoke.java | JVM 回归及独立设备 instrumentation；后者没有进入产品 DEX |
| build.py / test.py / prepare-tests.py | 原生库/许可打包、增加 JVM 编译入口、官方 CDN 可选下载 |
| 新增 prepare-native.py / native.py / native-licenses.py | 便携工具准备、编译/协议探测和许可证汇集 |
| 新增 emulator-env.ps1 / prepare-avd.py / build-smoke.py / smoke.py | 工作区虚拟设备和升级/JNI 检查入口；设备测试未执行到安装阶段 |
| README.md / THIRD_PARTY_NOTICES.md / 本文 / licenses/linuxdo-dependencies.txt | 修正当前开发构建依赖、说明新库归属和真实限制，保留正式下载链接 |
| HANDOFF.md / memory 五专题 | 当前断点、契约、操作与高价值失败经验 |

没有删除文件。源码快照002e1e5已按用户要求提交/推送，随后审查说明另作增量文档提交；任务前已有的linux.do/和AI情报站私有未跟踪内容保留且未提交。dist/.cache/.local是本地忽略产物。完整暂存检查发现生成许可原文尾空白，原文保留；排除该许可的源码/文档检查通过。交接检查器已知开发/正式版本不一致，不能据此替换正式包。

历史首次3.8.3仅改DNS路线/预算；后续接手新增h2、第三方DoH、缓存及依赖，先提交后审查。当前用户追加授权加固，已构建3.8.4测试包；本轮未提交/推送、清理参考目录或改来源/模型资料，具体差异见顶部加固实现。

## 打开核对过的官方资料

- [Rust 便携安装与环境变量](https://rust-lang.github.io/rustup/devel/environment-variables.html)
- [Android NDK 官方下载与校验值](https://developer.android.com/ndk/downloads)
- [rustls EchConfig API](https://docs.rs/rustls/latest/rustls/client/struct.EchConfig.html)
- [Android 网络安全、证书与 ECH](https://developer.android.com/privacy-and-security/security-config)
- [Cloudflare 支持的浏览器环境](https://developers.cloudflare.com/cloudflare-challenges/reference/supported-browsers/)
- [RFC8484：DoH 的 GET/POST、Base64URL 与 ID=0](https://www.rfc-editor.org/rfc/rfc8484.html)
- [RFC9460：HTTPS记录、地址提示与mandatory参数](https://www.rfc-editor.org/rfc/rfc9460.html)
- [Cisco/OpenDNS 官方 DoH 域名与引导地址](https://umbrella.cisco.com/blog/enhancing-support-dns-encryption-with-dns-over-https)
- [AdGuard 官方公共 DNS 接入](https://adguard-dns.io/en/public-dns.html)
- [DNSPod 域名接入与旧IP URL调整公告](https://docs.dnspod.cn/notices/mian-fei-ban-dot-dohbu-zai-gong-kai-ipjie-ru-de-gong-gao/)
- [Google Public DNS 官方加密协议与域名入口](https://developers.google.com/speed/public-dns/docs/secure-transports)
- [Cloudflare 官方公共服务地址](https://developers.cloudflare.com/1.1.1.1/infrastructure/network-operators/)

这些资料支持协议/工具判断；本机结果以实际源码、命令和私有报告为准，不由第三方文档的成功叙述替代。
