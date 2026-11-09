# 接口、数据结构与兼容性约束

增量核实：2026-11-09；基于未发布 Java、Manifest、维护 JSON、构建脚本与正式/测试 APK。这里只记录实现契约；用户选择原因见 [decisions](decisions.md)，环境与操作见 [conventions](conventions.md)。

## C01 · P0 / Active · 发布身份和升级边界

权威文件：[release.json](../releases/android/release.json)、正式 APK 和对应 SHA 文件；完整公开哈希/证书只在该元数据维护，记忆不复制第二套。

- 包名固定 `cn.yilan.news`，minSdk 26 / Android 8.0，targetSdk 36。当前开发 Manifest 与正式包版本分别见 HANDOFF，不能要求两者始终相同。
- 正式目录与本地重建的角色见 D06；新克隆生成自己的密钥，无法覆盖安装原正式签名应用。
- 官方升级须使用原签名且递增版本码；发布前检索 Manifest、MainActivity 关于/界面标识、ApkProvider 文件名、README/下载入口与元数据的版本硬编码。
- 不声称当前构建字节级可重复；源码修改后正式包与源码可能暂时不同，应报告未发版差异。

## C02 · P0 / Active · Android 权限与公开接口

依据 [Manifest](../android/AndroidManifest.xml)、[Provider](../android/src/cn/yilan/news/ApkProvider.java)、`android/res/xml/`：

- 唯一声明权限为 `android.permission.INTERNET`；无存储、定位、通讯录、相机、麦克风、安装其他应用权限。
- `debuggable=false`、`usesCleartextTraffic=false`、关闭系统备份；信任系统 CA，不用用户新增 CA/TLS 失败放行作为修复。
- MainActivity 仅导出启动入口，不把传入任意 URL 当抓取请求。
- Provider `exported=false`、`grantUriPermissions=true`；仅 `content://cn.yilan.news.apk/release.apk`，拒绝查询参数、片段或错误 authority。
- 只允许 `r`，底层固定 `ApplicationInfo.sourceDir`；query 只返回本 APK 文件名和大小，insert/update/delete 不支持。
- 用户选择分享接收方时临时授予只读 URI；无任意路径参数、目录枚举或 `file://` 分享。

这是实现范围，不代表完整恶意软件/渗透审计；外部原文在用户选择的浏览器运行。

## C03 · P1 / Active · 来源定义与联网

维护文件 [sources.json](../config/sources.json)；Java `Source` 读取 `id/name/category/provider/kind/home/color/icon/enabled/experimental/interval/endpoints`。interval 最低 60 秒；最多 100 个来源。源 ID 持久化，不能为改显示名随意重命名。

Go `builtins()` 与安卓配置的字段、顺序、端点及 enabled/experimental 对应由 [registry_test.go](../desktop/registry_test.go) 比较。改来源须同步维护源、相关适配器并运行一致性测试，不能只编辑生成的 assets。

当前适配：RSS/Atom、JSON、GitHub releases、Anthropic 新闻 HTML、DeepSeek 更新章节、ZCode Release 章节、`rss-ai` 关键词筛选。IT之家/爱范儿 AI RSS 是媒体补充，不是厂商公告。不是所有目录平台都有官方自动源，不能宣称全覆盖。

联网约束依据 `NewsEngine.download/publicURL`：

- 抓取只允许 HTTPS 且主机在该来源 endpoints/home 派生集合，最多三次重定向；每个备用端点独立尝试。
- `publicURL` 允许标准 http/https 外部阅读链接，拒绝凭证、IP 字面量、本地风格域名、非标准端口、危险协议、可执行文件后缀；它不是 DNS 解析层私网隔离的完整保证。
- 使用标准 HttpsURLConnection 证书/主机名校验，连接与读取各 7 秒；约 18 秒 deadline 在下载读循环检查，不保证整轮耗时固定。
- 响应解压后正文最多 5 MiB，JSON 嵌套最多 64，解析结果最多 40 条/源。
- 不执行脚本或 XML 外部实体请求；来源内容只作为数据。

## C04 · P1 / Active · Article / Snapshot 与降级

`NewsEngine.Article` 序列化字段：`id/source/sourceName/category/provider/title/url/summary/heat/published/fetched/dateOnly/rank`。时间戳为毫秒；发布时间未知为 0，不能用采集时间伪装发布时间；dateOnly 保留仅日期的精度。

ID = `SHA-256(sourceId + "|" + URL)` 的前 12 字节十六进制；收藏/已读依赖它。JSON 中长 ID 保持字符串精度，展示还按 URL 去重。标题/摘要清理后作为文本展示。

`Snapshot` 持久化 `source/items/fetched/checked/error/channel`；`cached` 是运行标记，重载/复制设为 true。重载只接受来源一致且 URL/字段校验通过的文章。

`fetch` 的行为：

- 普通刷新按 interval；强制刷新距上次成功不足 60 秒仍可沿用；近期失败有约 60 秒冷却。
- 成功更新 fetched/checked 与渠道；失败只更新 checked/error，保留原 items 与 fetched，不能清空快照或假报新成功。
- 非 AI 的普通 RSS 最新已知发布时间早于 7 天判疑似停更；低频 AI 官方发布豁免；最新时间超过未来一天拒绝。
- GitHub draft 跳过；prerelease 保留并标注，不能宣传为稳定版。

`recheck` 是精选缺日期时的例外：跳过 interval/近期缓存与失败冷却，仍执行相同网络/解析安全约束与失败保留策略。它只重新请求现有端点，不提供历史分页。

Activity 启动/回前台用 `shouldAutoCheck` 决定请求：已启用官方 AI 来源距最近成功满 24 小时检查，失败 60 秒内不重试；其他源仍用自身 interval。手动更新选择全部已启用源，但 `fetch` 仍可沿用 60 秒内缓存。工具卡片仅凭合法关联来源展示动态与检查时间，不能修改目录正文或声称所有工具今日已更新。

## C05 · P1 / Active · 本机状态 schema 与兼容性

MainActivity 用 `AtomicFile(getFilesDir()/native-news-v3.json)`，由 `NewsState` 保存/恢复：`schema=3`、`feeds`、`enabled`、`favorites`、`read`、`compact/large/unread/hotMode`、`themeMode`。读取上限 8 MiB；收藏最多 500，已读 ID 最多 2,000，列表分页 30。缺少 hotMode 等新布尔字段按 false 读；themeMode 只接受 day/night，缺字段或未知值默认为 day；compact 保留兼容但无设置控件。资料不写入状态文件。

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

实现依据 `FeaturedNews`、`ReadingRules`、`NewsArchive` 和 MainActivity，回归入口为 `NativeEngineTest`。

- 新闻最新只取已启用 news/tech/finance，排除 v2ex、v2ex-latest、hackernews，按发布时间排序；分类多选取并集。新闻分站热榜只含头条/贴吧，排除命中 AI/科技规则或与 AI 热榜相同 URL 的项。
- AI 分站热榜只含启用且最近获取成功、无错误、有 AI/科技项的 V2EX/Hacker News；HN 整体视为科技，V2EX 非 AI/科技不展示。头条/贴吧不进入 AI 榜，其 AI/科技项当前也不转入另一张榜。不能把“分类不重复”误解成所有热帖都必有展示。
- 每榜默认展开，可按来源收起；榜内按 rank 排序。rank 是解析列表序号，不称官方名次；heat 是原样展示字符串，空值/旧数据缺字段显示“未提供”，不能转为 0 或跨站相加。
- 精选只用有发布时间的归档：NFKC/小写/去符号标题双字组 Dice 相似度至少 0.76，至少六个共同组、标题长度至少八、数字集合相同；事件内所有不同标题互相通过，防止链式桥接。时间跨度不超 72 小时。
- 至少两家独立机构才成事件：provider 优先，否则按 home 主机（去 www）识别机构；同来源同 URL 去重。机构数与报道数不同，同机构不同链接不增加独立来源数。事件详情保留来源报道，不生成综述或跨站评分。
- 新闻精选范围与新闻最新相同；AI 精选取 AI 来源和本页实际成功展示的 AI 榜。平台动态排除工具流的规则不自动排除 AI 精选中的工具事件。
- 事件按最新报道的上海日期分组，初始近七个日历日，最近日期展开，各日期可折叠；末尾扩展更早本地日期。无更早记录时，对最早实际事件的前一天再请求相关源（空时间线先查当天），每个范围/日期每次 Activity 生命周期最多一次，可等当前刷新结束后再发起。
- 再取仍无该日条目显示“来源没有返回这一天”；有该日条目但不满足多来源归并则如实提示；全失败保留旧记录。接口没有任意历史分页，不编造缺日，遇到未补齐缺口停止继续向前。
- `news-archive-v1.json` 独立 AtomicFile，schema=1，records 含 article/firstSeen 与 incomplete 标记；最多 12,000 条、16 MiB。只接受成功且非 cached 的新快照，不从旧状态或收藏倒填历史。
- 保留策略按 max(firstSeen, fetched) 保护最近七天已存记录，超限优先淘汰更旧记录；近期记录已占满时拒收新增并标 incomplete。不能承诺满额时仍保存全部新条目，也不能把七日保留误解为安装即拥有过去七天新闻。
