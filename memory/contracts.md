# 接口、数据结构与兼容性约束

增量核实：2026-11-12；基于未发布 Java/Rust、Manifest、维护 JSON、构建脚本与正式/测试 APK。这里只记录实现契约；用户选择原因见 [decisions](decisions.md)，环境与操作见 [conventions](conventions.md)。

## C01 · P0 / Active · 发布身份和升级边界

权威文件：[release.json](../releases/android/release.json)、正式 APK 和对应 SHA 文件；完整公开哈希/证书只在该元数据维护，记忆不复制第二套。

- 包名固定 `cn.yilan.news`，minSdk 26 / Android 8.0，targetSdk 36。当前开发 Manifest 与正式包版本分别见 HANDOFF，不能要求两者始终相同。
- 正式目录与本地重建的角色见 D06；新克隆生成自己的密钥，无法覆盖安装原正式签名应用。
- 官方升级须使用原签名且递增版本码；发布前检索 Manifest、MainActivity 关于/界面标识、ApkProvider 文件名、README/下载入口与元数据的版本硬编码。
- 不声称当前构建字节级可重复；源码修改后正式包与源码可能暂时不同，应报告未发版差异。

## C02 · P0 / Active · Android 权限与公开接口

依据 [Manifest](../android/AndroidManifest.xml)、[Provider](../android/src/cn/yilan/news/ApkProvider.java)、`android/res/xml/`：

- 唯一声明权限为 `android.permission.INTERNET`；无存储、定位、通讯录、相机、麦克风、安装其他应用权限。
- `debuggable=false`、`usesCleartextTraffic=false`、关闭系统备份；Java 路径信任系统 CA；linux.do 原生传输使用公共 Mozilla 根证书，仍验证证书链/主机名。不用用户新增 CA/TLS 失败放行作为修复。
- MainActivity 仅导出启动入口，不把传入任意 URL 当抓取请求。
- Provider `exported=false`、`grantUriPermissions=true`；仅 `content://cn.yilan.news.apk/release.apk`，拒绝查询参数、片段或错误 authority。
- 只允许 `r`，底层固定 `ApplicationInfo.sourceDir`；query 只返回本 APK 文件名和大小，insert/update/delete 不支持。
- 用户选择分享接收方时临时授予只读 URI；无任意路径参数、目录枚举或 `file://` 分享。

这是实现范围，不代表完整恶意软件/渗透审计；外部原文在用户选择的浏览器运行。

## C03 · P1 / Active · 来源定义与联网

维护文件 [sources.json](../config/sources.json)；Java `Source` 读取 `id/name/category/provider/kind/home/color/icon/enabled/experimental/interval/endpoints/authority`。AI源authority明确为official/media/aggregator；只有official可计入官方检查时间和模型发布候选，缺字段不推断为官方。interval 最低 60 秒；最多 100 个来源。源 ID 持久化，不能为改显示名随意重命名。

Go `builtins()` 从config/embed.go嵌入的同一JSON解码；DefaultEnabled独立于Experimental，新安装取配置enabled，旧状态保留显式开关。字段、顺序、端点及默认值由 [registry_test.go](../desktop/registry_test.go) 比较；新增源仍须实现适配器，不能只编辑生成的 assets。Go没有Android的ECH传输，注册相同不表示两端网络能力相同。

当前适配：RSS/Atom、JSON、GitHub releases、Anthropic 新闻 HTML、DeepSeek 更新章节、ZCode Release 章节、`rss-ai` 关键词筛选。IT之家/爱范儿 AI RSS 是媒体补充，不是厂商公告。不是所有目录平台都有官方自动源，不能宣称全覆盖。

联网约束依据 `NewsEngine.download/publicURL`：

- 抓取只允许 HTTPS 且主机在该来源 endpoints/home 派生集合，最多三次重定向；每个备用端点独立尝试。
- `publicURL` 允许标准 http/https 外部阅读链接，拒绝凭证、IP 字面量、本地风格域名、非标准端口、危险协议、可执行文件后缀；它不是 DNS 解析层私网隔离的完整保证。
- 使用标准 HttpsURLConnection 证书/主机名校验，连接与读取各 7 秒；约 18 秒 deadline 在下载读循环检查，不保证整轮耗时固定。
- 响应解压后正文最多 5 MiB，JSON 嵌套最多 64，解析结果最多 40 条/源。
- 不执行脚本或 XML 外部实体请求；来源内容只作为数据。

`linuxdo`默认关闭，只取官方最新一批RSS，不承诺完整历史。APK由LinuxDoTransport/JNI进入固定站点Rust；其余来源仍原HTTPS。ECH三级顺序为标准公共DoH→既有无密钥第三方→私有缓存；站点始终正常验证证书/主机名、ECH Accepted和h2协商。原ALPN列表/Chrome Mobile请求头保留，无CA/登录/Cookie/JS/代理监听或站点重定向。3.8.4总上限90秒（公共45、第三方25），各次原预算/重试不缩短；全部DNS/缓存读入65,535字节、内容5MiB，失败沿用C04旧快照。JNI签名仍nativeGet(endpoint,cacheDir)，目录仍getFilesDir()。完整路线、阶段预算及验证由[LINUXDO](../docs/android/LINUXDO.md)维护，不扩展为通用连接器。

3.8.4已修复第三方例外：HTTPS-only、最多两次同域HTTPS/443重定向、响应上限与本站HTTPS参数校验。缓存旧字段兼容，原子替换，仅站点响应通过校验后写；缓存命中不续期，最长7天且拒绝明显未来时间。诊断共用生产管道并保留每层错误；强制fallback/cache仅宿主探测，JNI始终auto。具体测试/真机未验证范围见LINUXDO，不能把本机三模式成功当新版手机已验收。

3.8.3 的公共 DNS 优先尝试 Cloudflare/Google 的 HTTPS IP URL，正常验证公共证书链/IP SAN；域名引导及系统解析作备用，验证真实域名。只有实测支持IP证书的服务启用IP URL，不扩大Java新闻链接的允许范围或信任根。HTTP200不算完整解析，必须取得受支持 ECH 和公网地址；可用同条有效 HTTPS 记录的 ipv4hint 弥补 A 失败，但仍作站点证书/ECH验证。最多两家并发；首个完整结果取消其他任务，全部失败保留各家原因。不得把“DNS已解析”与后续403/取帖成功混为一谈。完整路线、预算和诊断入口由 LINUXDO 文档持有。

3.8.5 新增 linux.do 全历史 Top 端点与手动更新契约：

- Rust `allowed_path` 精确白名单新增 `https://linux.do/top.json?period=all`（latest.rss/latest.json 不变）；无 query、其他 period 或附加参数的 top.json 一律拒绝。Accept 头加入 application/json；ECH Accepted、h2、证书/主机名与 5MiB 校验不变。
- Java `LinuxDoTransport` 仅放行 latest.rss 与该 top 端点，其余在加载 native 前抛“非登记”。
- `NewsEngine.fetchLinuxDo` 不经通用 endpoint 循环与冷却：top 走 `parseLinuxTop`（取 `topic_list.topics`，按 views 降序，heat=“N 浏览”，url=home+“/t/topic/”+id，excerpt 为摘要，created_at 为发布时间），默认走原 RSS `parse`。
- 链接统一用 `/t/topic/<id>` 短链（与 RSS item link 同形），使同一 topic 在两种模式下 URL 相同、Article id 稳定，已读/收藏可关联。
- 条数：linuxdo 快照上限 50（`LINUXDO_LIMIT`），`Snapshot.from` 对 linuxdo 恢复上限 50、其余源仍 40；`parseLinuxTop` 取满 50 即止。
- 手动入口只选 linuxdo 且要求来源已开启；顶部通用更新与 NewsArchive 均排除 linuxdo，linuxdo 不进通用归档/精选。

3.8.6 重构 linux.do 白名单与三模式契约（取代 3.8.5 的 period=all 用法）：

- Rust `allowed_path` 返回 String，精确四类：`latest.rss`、`top.json?period=quarterly`、`latest.json?per_page=50`、动态 `/t/<纯数字id>.json`（id 仅 ASCII 数字，拒绝 slug/query/空/非数字）；已移除 period=all 与裸 latest.json。ECH Accepted、ALPN h2、Chrome Mobile UA、证书/主机名、内容类型、5MiB 校验不变。
- Java `LinuxDoTransport` 放行三个固定端点加 TOPIC 正则 `^https://linux.do/t/[0-9]+\.json$`，其余在加载 native 前抛“非登记”。
- 模式三态常量 `LINUXDO_MODE_DEFAULT=0/_VIEWS=1/_LATEST=2`；`fetchLinuxDo(source,previous,mode)` 按模式选端点与解析，失败保留旧快照。
- `parseLinuxViews`：建 user_id→username 映射，用 posters 中 description“原始发帖人”（英文 Original Poster 兜底）识别 OP，跳过 username=neo（不区分大小写）与 created_at 早于 cutoff（now−90 天）的帖子，按 views 取上限 50。
- `parseLinuxLatest`：取 topics 按 created_at 显式倒序取 50，不过滤 Neo/时间。
- `fetchLinuxTopic(topicId)`：取 `post_stream.posts` 中 post_number=1、post_type=1 首帖的 cooked，经 `textFromCooked` 清洗（块级标签转换行、li 加“· ”、解码实体、逐行 trim 保留换行）。
- 链接仍统一 home+“/t/topic/”+id，跨子页同 topic 的 Article id 稳定；`articleId` 抽公共方法。linuxdo 快照上限仍 50。

3.8.6 来源扩充与清理契约（来源 37 → 66，决策 D18）：

本轮独立核对补充：原生ECH已扩展到固定www.v2ex.com，Java登记v2ex/v2ex-latest，Rust精确允许hot.json与index.xml；DNS/第三方接口按目标host查询，linux.do与V2EX缓存按固定文件名隔离。旧“仅linuxdo走native”属于3.8.4时点，当前不适用，安全上限/ECH/证书校验保留。

- 移除停更/无解源 `people`、`ai-huggingface`（BBC 更早移除）；新增公开源：news 类 NPR；tech 类 CNET、Stack Overflow Blog、GitHub Blog、New Scientist、ScienceDaily、HackerNoon、Tom's Hardware；hot 类 `douyin`；ai 类 `ai-aihot`。
- `publicURL` 增强：单参数 URI 严格构造失败时，将竖线编码为 `%7C` 后重试，其余 scheme/host/userInfo/端口/内网黑名单/可执行后缀校验全部不变；仅挽救原本返回 null 的合规链接，对现有源零影响。
- 新增 `douyin` 解析：`rows()` 数据数组取 `word_list`，逐条目取 `word`（标题）、`hot_value`（热度），URL 拼 `https://www.douyin.com/search/<encoded word>`，与现有 json 特判同构。
- 大 feed（Tom's Hardware 约 1.1MB 全文、无轻量端点）标 experimental，失败保留缓存；当前共 8 个 experimental 源。
- 65 源 enabled=true，仅 `linuxdo` 手动源关闭。分类分布 news 6 / tech 25 / finance 2 / hot 5 / community 9 / ai 19。

以上数量为3.8.6历史状态。3.8.7按用户要求移除Tom's Hardware，当前数量以HANDOFF为准；删除来源不删除旧收藏/已读。F29路由、排序、正文和共享表缺口已补反例并修复；手机仍待验。

3.8.7修正：季度候选先过滤Neo（含OP id=1）、无效时间/浏览量，再按long views降序、发布时间和id降序打破同分，最后截取50条；只代表接口本次季度候选，不声称全站绝对浏览榜。HTTP/2请求使用绝对https URI，明确正确的:scheme/:authority；linux.do登记JSON端点带匿名同站Referer与X-Requested-With，RSS/V2EX不加该上下文。无登录/挑战执行，403仍立即停止；实测与因果限制由LINUXDO文档持有。 3.9仅改失败分类：以cf-mitigated=challenge明确挑战，普通403不能直接断言Cloudflare挑战；两者仍停止、保留旧快照，错误含固定请求端点，页面点击查看详情。

## C04 · P1 / Active · Article / Snapshot 与降级

`NewsEngine.Article` 序列化字段：`id/source/sourceName/category/provider/title/url/summary/heat/published/fetched/dateOnly/rank`。时间戳为毫秒；发布时间未知为 0，不能用采集时间伪装发布时间；dateOnly 保留仅日期的精度。

ID = `SHA-256(sourceId + "|" + URL)` 的前 12 字节十六进制；收藏/已读依赖它。JSON 中长 ID 保持字符串精度，展示还按 URL 去重。标题/摘要清理后作为文本展示。

`Snapshot` 持久化 `source/items/fetched/checked/error/channel`；`cached` 是运行标记，重载/复制设为 true。重载只接受来源一致且 URL/字段校验通过的文章。

`fetch` 的行为：

- 普通刷新按 interval；强制刷新距上次成功不足 60 秒仍可沿用；近期失败有约 60 秒冷却。
- 成功更新 fetched/checked 与渠道；失败只更新 checked/error，保留原 items 与 fetched，不能清空快照或假报新成功。
- 非 AI 的普通 RSS 最新已知发布时间早于 7 天判疑似停更；低频 AI 官方发布豁免；最新时间超过未来一天拒绝。
- GitHub draft 跳过；prerelease 保留并标注，不能宣传为稳定版。

`recheck` 用于精选缺日期再取：跳过 interval/近期缓存与失败冷却，仍执行相同网络/解析安全约束与失败保留策略。LINUX DO 的手动单次更新不再走 recheck，而由 `fetchLinuxDo` 承担（见 C03 3.8.5）。

3.8.5 起 Activity 启动/回前台不再自动发请求（onCreate/onResume 的自动 refresh 已移除）；`shouldAutoCheck` 仅保留给通用刷新逻辑。顶部「更新」按钮手动选择全部已启用源（排除 linuxdo），`fetch` 仍可沿用 60 秒内缓存；linuxdo 仅由其独立子页的「更新选项」手动触发。工具卡片仅凭合法关联来源展示动态与检查时间，不能修改目录正文或声称所有工具今日已更新。

全文预览：先展示可滚动对话框，按Article id查ArticleTextCache，未命中后台fetchLinuxTopic，主线程更新且只更新仍打开的预览；失败保留summary和原因，超长topic id不崩溃。3.8.7缓存为同步LRU，最多24条/2,097,152个UTF-16字符；单篇超预算仍展示但不缓存，Activity销毁清理。清除真实HTML后再解码实体，保留编码的代码尖括号/比较符；不渲染HTML/脚本。收藏/分享/原文入口保留。

## C05 · P1 / Active · 本机状态 schema 与兼容性

MainActivity 用 `AtomicFile(getFilesDir()/native-news-v3.json)`，由 `NewsState` 保存/恢复：`schema=3`、`feeds`、`enabled`、`favorites`、`read`、`compact/large/unread/hotMode`、`themeMode`。读取上限 8 MiB；收藏最多 500，已读 ID 最多 2,000，列表分页 30。缺少 hotMode 等新布尔字段按 false 读；themeMode 只接受 day/night，缺字段或未知值默认为 day；compact 保留兼容但无设置控件。资料不写入状态文件。

3.8.6 新增 `linuxTabs` 持久化字段：`NewsState` 维护 `Map<String,Snapshot>`（键 default/views/latest），序列化为独立顶层 `linuxTabs` JSON 对象；restore 时三个快照均用 `Snapshot.from("linuxdo",...)`（上限 50）。旧文件 feeds 里的 linuxdo 单快照迁移为 default 标签，主 snapshots 不再放 linuxdo；新字段若存在则覆盖迁移值。schema 数字仍为 3，不强制按版本号分支。

注意：当前 load 读取已有字段，但没有根据 schema 值执行严格拒绝/迁移分支；不要把 schema 标记误记为已交付通用迁移机制。更改字段/ID 时须显式设计兼容性。

清除数据/卸载会删除缓存与收藏；旧 2.1 WebView/localStorage 与 3.0 原生状态不自动迁移。安卓没有文件导入导出、云备份/同步或可恢复账号，不能从电脑版功能推导存在。

分类/厂商选择、日期展开、缺口检查次数当前只在 Activity 内存，不承诺重启后保留。分类空集合表示“全部”，不把 all 与具体分类共同存储；对话框需同步适配器数组与控件，见 pitfalls F14。

## C06 · P1 / Active · AI 目录语义

[ai_catalog.json](../internal/catalog/ai_catalog.json) 提供 providers/tools、checkedAt、references；平台含官方 newsUrl，工具含 `id/provider/kinds/open/license/url/references/platforms/status/note` 等以及可选 sourceId。

- `open` 枚举 `open/mixed/not-verified`：未验证不等于已确认闭源，部分开源不等于所有运行时/服务开放免费。
- `status` 的 active/preview/transition 是目录快照语义，不是本记忆的 Active/Pending 状态。
- 产品、Harness、SDK、framework、模型/API/套餐的分类原因见 D04。
- 目录 checkedAt 仍为 2026-10-28；Gemini CLI transition 与 DeepSeek Harness preview 只是该快照，不表示本次重新确认厂商现状。
- 更新出品方、许可、预览/迁移等时间敏感资料时读每项官方 references，再更新事实与 checkedAt；禁止仅改日期造成虚假复核。

新增 `modelData`：`asOf=2026-10-21`、datasetVersion、origin，以及 profiles/models/sources。数据来自 `AI情报站/local-site/data/dataset.js` 的部分结构，ID 映射 `kimi→moonshot`、`zhipu→zai`；不是直接替换 providers/tools，也未导入完整六类视图。模型/分析的维护真值在该 JSON，数量直接查文件，不复制另一份清单。

- 每个模型 releasedAt 独立于整份快照 asOf；列表按有效日期降序，未知日期最后。预览名称/厂商/发布日期/特点，详情与最多三项对比保留分析、厂商优劣势、出处及快照置信度。
- `ReadingRules.confidence` 将空 sourceIds 的 verified 降为 unverified；非空出处不代表本阶段逐条重新核实，更不能把厂商分析当模型实测结论。
- 用户后续要求去掉模型列表、详情的资料日期文案；仅改显示，JSON asOf 未改。当前模型对比标题仍展示 asOf，不自动扩展成删除全部日期。
- `latestModelCheck` 取官方非工具来源快照 fetched 最大值，表示其中最近一次成功获取；不是所有来源检查成功，不按屏幕厂商筛选重算，更不是人工事实复核。
- `newModelNotices` 只用快照之后的官方非工具流及有限名称/发布词规则给出处链接提示，不写回 modelData，不保证全厂商/全新模型覆盖。局限与误判风险不能省略。
- 平台动态排除 tools[].sourceId 对应的所有来源，不依赖产品名硬编码；这些版本流仍可在工具页显示。排除整条工具来源不保证厂商综合新闻中绝无工具话题。

## C07 · P1 / Active · 电脑版单独边界

默认 `127.0.0.1:8765`，用户主动 `-LAN` 才开放局域网。API 需要访问密钥；完整含密钥 URL 不进入记忆。该服务不是 APK 前提。

源码启动数据在 `.local/runtime/desktop/data/`，便携包在包旁 `data/`，由 `scripts/desktop/paths.ps1` 判别；stop 脚本先核对进程路径，不能按端口盲停其他进程。打包不夹带本机数据。

## C08 · P1 / Active · 分流、分站热榜、精选与本地历史

实现依据 `FeaturedNews`、`ReadingRules`、`NewsArchive` 和 MainActivity，回归入口为 `NativeEngineTest`。以下词法精选规则适用于未启用AI的基础模式；启用AI后的契约及4.0.1 InfoQ特例见C09。

- 3.9.1按用户澄清逐篇分流（4.0.1的InfoQ整源特例见下）：原新闻最新候选中的AI主题帖进AI平台动态/AI精选，非AI留新闻最新/精选；InfoQ不是整源改类。NewsEngine.isAI用标题/摘要AI-only关键词，rss-ai同口径；与isAIOrTech及V2EX/HN原规则不同。匹配有局限，不声称语义分类。
- 平台动态候选含原AI专用源、V2EX最新和混合新闻源；混合源只展示AI帖子，来源选项在当前缓存有AI帖子时加入。来源/区域/搜索/未读过滤后再URL去重，不能让未选中源副本屏蔽选中源。
- 展示分流不改source/article ID、category、URL、开关、收藏/已读及schema。已存归档按现规则重分展示，不倒填或删除；媒体AI内容不改变officialAI身份、模型检查时间或目录正文。
- 3.9展示筛选由SourceFilters持有：新闻最新按来源区域（全部/国内/国外）与多选主题标签取交集；多标签取并集。平台动态第二级改为具体source ID多选，不再匹配文中厂商；两页区域与第二级互相独立，更换区域清空对应第二级。AI资料仍用厂商筛选，模型与产品/Harness/SDK同为AI子页，全部包含工具及模型。
- 区域是发布者展示分组而非服务器地址/网络可达性；中文国际新闻仍属其国内来源，国内厂商GitHub版本流按厂商区域。主题为来源级分组，不是模型分析文章。新增来源需核对分组，JVM对所有新闻最新/平台动态来源检查覆盖。
- 设置由总框包小卡，顺序为linux.do→错误快照→条数降序，同分保留配置顺序；标题开home浏览器，开关独立，移除裸网址。阅读分组移除但开关保留在外观，分享为文字入口。上述均不写新状态字段、改源ID或文章ID。
- 新闻最新候选取已启用news/tech/finance及community类RSS（少数派、机核、Lobsters、Slashdot），排除linuxdo/v2ex/v2ex-latest/hackernews，最终排除AI主题帖后按发布时间排序；区域与多选标签语义见上文。新闻分站热榜含头条/百度/微博/知乎/抖音/B站热词和热门视频/贴吧，共8来源ID、7平台，排除AI/科技项及AI榜同URL。
- AI分站热榜从HOT_SOURCES的10个来源中取启用、最近获取成功、无错误且有AI/科技项的榜，只展示匹配条目，避免科技项两边都被丢弃；HN整体视为科技。V2EX最新另进AI平台动态，并逐条按同一AI/科技规则过滤，不混入新闻最新。3.9起平台动态按具体消息来源筛选，资料页厂商过滤不变。
- 每榜默认展开，可按来源收起；榜内按 rank 排序。rank 是解析列表序号，不称官方名次；heat 是原样展示字符串，空值/旧数据缺字段显示“未提供”，不能转为 0 或跨站相加。
- 精选只用有发布时间的归档：NFKC/小写/去符号标题双字组 Dice 相似度至少 0.76，至少六个共同组、标题长度至少八、数字集合相同；事件内所有不同标题互相通过，防止链式桥接。时间跨度不超 72 小时。
- 至少两家独立机构才成事件：provider 优先，否则按 home 主机（去 www）识别机构；同来源同 URL 去重。机构数与报道数不同，同机构不同链接不增加独立来源数。事件详情保留来源报道，不生成综述或跨站评分。
- 新闻精选范围与新闻最新相同；AI精选取AI来源、混合新闻源的AI主题帖、过滤后的V2EX最新及本页实际成功展示的AI榜，归档使用相同AI/科技过滤。平台动态排除工具流的规则不自动排除AI精选中的工具事件。
- 事件按最新报道的上海日期分组，初始近七个日历日，最近日期展开，各日期可折叠；末尾扩展更早本地日期。无更早记录时，对最早实际事件的前一天再请求相关源（空时间线先查当天），每个范围/日期每次 Activity 生命周期最多一次，可等当前刷新结束后再发起。
- 再取仍无该日条目显示“来源没有返回这一天”；有该日条目但不满足多来源归并则如实提示；全失败保留旧记录。接口没有任意历史分页，不编造缺日，遇到未补齐缺口停止继续向前。
- `news-archive-v1.json` 独立 AtomicFile，schema=1，records 含 article/firstSeen 与 incomplete 标记；最多 12,000 条、16 MiB。只接受成功且非 cached 的新快照，不从旧状态或收藏倒填历史。
- 保留策略按 max(firstSeen, fetched) 保护最近七天已存记录，超限优先淘汰更旧记录；近期记录已占满时拒收新增并标 incomplete。不能承诺满额时仍保存全部新条目，也不能把七日保留误解为安装即拥有过去七天新闻。

## C09 · P0 / Active · 可选AI接口、固定模型与本机结果

- 4.0已实现AiSettingsDialog→AiClient→AiVault/Coordinator/Engine。URL仅公共HTTPS、拒绝凭证/查询/私网及重定向，默认TLS验证；GLM保留/api/paas/v4路径，DeepSeek兼容/models与/chat/completions。模型列表仅候选，必须测试用户选择的模型后才能保存；4.1移除品牌候选兜底，列表失败统一手填ID测试；公开HTTPS自定义路径/端口可用，不按厂商白名单限制。绝不自动选择/替换模型，响应模型不符拒绝。
- URL/Key/model/profile/enabled通过AndroidKeyStore AES-GCM写私有ai-settings-v1.enc，不进native-news-v3.json、源码或分享APK；结果另存ai-results-v1.json。换配置隔离汇报/译名/批次，保留带原模型标注的按日历史；配置世代及Vault revision拦截迟到回调/清除后排队写入，停用不影响基本新闻；不把密文保存称全设备攻击防护。
- 4.1一键更新依次获取来源→当前列表外文译名→三分区汇报→两分区当天精选。全部标题分批分析；相同配置/提示/输入可复用成功批次，未命中才发送，只含本批引用ID和标题，无正文/链接/热度；批间仅合并摘要。失败保留上次成功结果及错误，401/402/403/404/429停止后续请求，不换模型。未配置/停用AI时使用旧本地规则精选；AI失败不自动退回规则精选。
- 4.0.1精选使用本批r0等明确引用标识；模型重复引用由首个事件认领，空重复事件丢弃；不存在的引用/格式错误最多同模型重做一次，仍错保留旧结果。最终原文均由本地映射取得，允许单篇推荐。4.1默认只生成当天，前两天仅读已存精选；更早点击一次扩展三天，缓存命中不重选，缺失则复查公开列表并限目标日选择，不能取得时明确为空。
- 外文预览翻译只在手动点击后发送该预览文字，保留原文；不自动抓更多正文，不向聊天索取用户密钥。结果为未核验AI文本，不执行HTML/指令。
- 4.1工作时隐藏搜索框、按钮占满栏内宽度，AI阶段分别显示正在生成汇报/正在生成精选并旋转图标。获取条按完成来源数；AI条按阶段内分区及已完成批次推进（含明确标识的本地复用），发现额外合并/纠错请求时调整计划，不模拟token流或按秒假进度。阶段回调不重建内容顶栏，汇报结果/错误即时重绑对应卡片内部；主题重建后恢复运行状态，全部完成/失败后恢复搜索框与更新按钮。
- 4.0.1 InfoQ中国配置category=ai/authority=media；全部帖子包含旧缓存进入AI平台/精选，不重写旧文章身份，也不成为官方模型证据。其他混合源保留C08逐篇规则。新增来源与新华网JSON详情见[SOURCES](../docs/android/SOURCES.md)。

4.0.2补充：阶段内最多两路调用，汇报阶段全部结束后才开始精选；支持单分区重试。批次成功缓存写既有ai-results-v1.json可选字段，最多96项/384KiB/7天，按profile/URL/模型/提示版本/完整输入与输出预算匹配，清配置同步清缓存，未知引用响应不留缓存。标题不截断、失败不换模型、401等停止后续请求的规则不变；旧文件无缓存/调用统计字段按未知兼容，不显示负次数。完整预算、调用流程与官方DeepSeek参数依据由[AI_API](../docs/android/AI_API.md)维护，不能以合成测试承诺真实耗时。

4.1补充：日期键/译名/清理及对话契约由[AI_API](../docs/android/AI_API.md)详细维护。所有日期按published的上海日历日，未知不使用fetched替代；日历按日多选或严格早于截止日，明确保留收藏、已读、开关和密文配置。清理同时去除快照/归档/日期精选/关联译名与派生缓存，revision阻止旧写入复活。历史按日保存、单日刷新/折叠，换profile也不冒充新模型结果；聊天仅本窗口最近12轮，不自动附新闻或持久化，会话只用已保存锁定配置。搜索输入仅触摸激活，关闭键盘/后台清焦点；中文译名可搜索，但不改Article.title/ID。

4.1.1补充：日历仍标任意内容缓存，含空groups的已选日期；不等于对应板块已选。历史读取先已选，再符合条件的本地归档/快照/已选原文，最后逐日补查；无历史分页不反复抓最新冒充旧日。historyChecks与成功空精选区分、可主动刷新；旧聚合逐日补缺不覆盖已有结果。AI按日文件及原子索引提交/容量/损坏保护/明确保存状态由AI_API拥有完整契约，不能把内存可见称已落盘。

## C10 · P1 / Active · 原生设置层级、日夜素材与界面动效

- 4.2设置按外观/AI功能/缓存管理/消息来源四个同级标题与卡片组织，标题在框外。AI卡显示配置/模型状态，有接口与模型及直达对话入口；无启用模型时禁用聊天入口，不增加API自动请求。缓存卡展示可按内容日期管理的数据摘要并进入原日历，清理契约仍C09。
- 外观保留日间/夜间、大字、未读，新增backgroundArt/motion两个schema3可选Boolean，旧状态缺失都默认true；切换不改ID、收藏/已读/API配置。日间冷白/天空蓝，使用scene_day_atlas.png三等分的新闻/AI/收藏场景；夜间保留原三个场景。ReaderBackdrop按资源和主题重载、单图解码<=200万像素，关闭插画后释放当前图，仅纯色；设置主背景无图。
- 日夜卡片有真实场景缩略预览。日间素材由内置imagegen生成，完整prompt与资源路径在assets/android-background-prompts.json；只把工作区资源打包，运行时无生成或下载。工具另保留其默认生成目录副本，不由应用读取。
- ReaderUpdateTransition仅变宽度/透明度/搜索间距，320ms可中断；工作最终搜索GONE/更新满栏，结束逆向恢复；相同工作状态切阶段不重启动画。阶段文字160ms淡入、旋转刷新图标保留，实际进度仍由采集/AI计数决定。进度线空闲INVISIBLE占位避免列表跳动。
- 导航/子页切换轻微淡入上移180ms，数据刷新与搜索不重复入场；导航清查询只触发一次列表构造。motion=false或系统关闭动画时立即稳定布局并停止装饰旋转；后台/重建取消并稳定现有动画，不让搜索重获焦点。关闭动效不是改变刷新/模型时序。

4.2.1补充：去掉设置各节标题下的说明小字，仅保留同排已启用/来源数等实际状态。API配置窗口只有字段/测试/必要状态/保存等操作，无“AI功能”大标题和页签；聊天由独立AiChatDialog承载，无共享配置/对话切换。聊天记录独立加权滚动，输入及间隔20dp的清空/发送固定底部，API30+按IME/system-bar Insets上移，26–29用adjustResize与可见区域兜底（待真机）。左右气泡只显示原消息，不加“你”；等待时仅禁发送，输入焦点与草稿保留，清空包含草稿并使迟到回复失效。聊天仍固定配置/临时内存/手动请求，非UI契约C09不变。
