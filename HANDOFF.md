# HANDOFF — 跨会话交接

增量核实：2026-11-11。硬规则在[AGENTS](AGENTS.md)，记忆维护按[MEMORY_PROTOCOL](MEMORY_PROTOCOL.md)。当前事实以源码、维护资料及实际验证为准。

## 当前状态

- 主产品：Android原生独立APK，包名`cn.yilan.news`，versionName 3.9.1-dev / versionCode 30901，minSdk 26 / targetSdk 36，唯一权限INTERNET。
- 测试包：`dist/android/yilan-standalone-3.9.1-dev.apk`，10,679,609字节，SHA-256 `a9d46bf962924c926cbfd65dacef8804c144441b11aa5d5ea3b5af58ee396e45`；原证书SHA-256 `9ec80e3b71cd34a0c642da7d9e3399b59ae8d6c955d36209b43b7a89a5c442f9`。元数据和签名报告在dist/android/。
- 正式包仍3.0.0 / 30000，62,219字节。本轮正式APK、旧3.9.0测试包、来源配置、AI目录哈希均未变；本轮全部assets及三ABI库与3.9.0相同；本次只改Java分流。
- 来源：共65源，默认64（45综合+19AI，仅linuxdo默认关闭），7 experimental；news 6 / tech 24 / finance 2 / hot 5 / community 9 / ai 19。未重新扩源。
- AI目录：16平台 / 31工具，checkedAt=2026-10-28；正文与modelData日期均未改，HF静态参考保留。
- Git：main / HEAD `878c27ff815c304b4a5685777d516e7e7776cf86`，本轮用户已授权提交/推送当前3.9.1基线；完成后再开发4.0，未换分支。本轮起点含多轮未提交源码及用户资料，基线在.local/reports/routing391-baseline.json；不能将全部Diff当本轮新增。

## 本轮完成：3.9.1逐篇AI分流（D21）

- 用户明确选择只移AI主题帖子、非AI保留新闻；不整源更改InfoQ分类。普通流和精选按同一标题/摘要AI-only规则分区，旧缓存/归档随展示生效。
- rss-ai媒体子流使用同一规则，支持中文紧邻AI、AIGC、LLM等；普通Java/Rust/SDK不因此全搬到AI。关键词有误分/漏分局限，不称模型语义分析。
- 平台动态来源选项加入当前缓存有AI帖子的混合源；先执行来源/区域/搜索/未读筛选再URL去重，避免未选中副本屏蔽选中来源。
- 保持source ID、Article ID/URL/category、收藏/已读、开关及schema；归档不倒填/删除，媒体不变官方模型证据。V2EX/HN既有科技范围及linux.do独立传输不改。
- 本轮修改NewsEngine/ReadingRules/FeaturedNews/MainActivity、Manifest、NativeEngineTest及UiSmoke；更新本文件、C08/D21/F32和UI_DESIGN。未改Go/Rust/来源JSON/目录正文或正式包，未提交/推送。
- `python scripts/android/test.py`通过14组；`python scripts/android/build.py`成功；`build-smoke.py`及`--ui`独立测试包构建通过。本轮未重跑Rust/Go或真实新闻网络。
- 新API30 AVD yilan-routing391/5586/ADB5048：3.9.0 seed→最终3.9.1 verify的收藏/已读/来源/主题保留通过；UiSmoke逐篇分流、选中来源去重及原UI回归通过。初次媒体fixture暴露判定不一致，统一规则后重建合成升级基线，最终通过。
- 包内全部assets与原生库和3.9.0字节相同，原签名v2/v3、INTERNET-only与生产无测试类检查通过；旧测试/正式APK及资料哈希未变。证据.local/reports/routing391-baseline.json、routing391-runtime.json（首次失败）、routing391-final-runtime.json、routing391-package.json。
- 测试仅用合成数据，新AVD/专用ADB已关闭；只产生工作区源码/文档、缓存、报告和测试包，没有安装工具或改系统网络。用户手机本轮分流及升级尚未验证，手机季度榜403仍未根治。

以下3.9.0的实现/验证为历史证据，不当作3.9.1重新联网取帖。

## 3.9.0历史：界面整理（D20）

- 设置移除阅读分组，外观/消息来源标题在框外；大字与未读开关留在外观，分享安装包为无框文字入口。用户未另行确认这些细分解释，已在进展中说明。
- 来源总框包紧凑小卡；标题点击发送对应home的浏览器Intent，开关独立；不再单列网址，长错误限三行、点击查看全文。
- 来源顺序为linux.do置顶→失败→其余按快照条数降序，同分稳定保留原配置顺序。
- 新闻最新改为平台区域+多选来源主题标签；平台动态改为平台区域+具体source ID多选。AI资料仍按厂商筛选，三处筛选互相独立，V2EX最新主题过滤保留。
- AI顶栏为平台动态/linux.do/AI/分站热榜/精选；模型降为AI子页，与产品/Harness/SDK并列。全部显示工具及模型，精选异步/历史分支使用对应命名常量。
- linux.do选择器纯文字，去掉ECH渠道说明；单站更新共用顶部动态等待条，批量仍按完成来源数计进度。逐源完成不再重建整页，完成后统一绘制，消除顶栏反复自动滚动。
- **403未根治**：现有传输的宿主默认/季度/最新均200，未复现手机故障。Rust仅补失败诊断（challenge标记/请求端点），成功请求、安全规则及端点不变；错误详情点击查看，失败保留旧快照。

## 3.9.0历史文件变更

- 修改`android/src/cn/yilan/news/MainActivity.java`及Manifest：布局、筛选、层级、刷新绘制/进度与30900版本。
- 新增`android/src/cn/yilan/news/SourceFilters.java`：UI来源区域/标签和设置排序；不复制来源端点或默认开关。
- 修改Rust `lib.rs/main.rs`仅区分challenge与普通403、输出固定请求端点；不加重试、Cookie或新入口。
- 修改`DeviceSmoke.java`增加真实JNI季度探测模式；修改`NativeEngineTest.java`、`scripts/android/test.py`；新增`android/tests/UiSmoke.java`；扩展`scripts/android/build-smoke.py --ui`：JVM及独立UI回归，测试类不进产品APK。
- 更新`docs/android/UI_DESIGN.md`、LINUXDO文档当前范围提示、HANDOFF及既有记忆专题。未改Go、config/sources.json、ai_catalog.json、业务状态schema或正式下载。
- 原有`linux.do/`、`AI情报站/`及其未跟踪资料均保留。前轮3.8.7修复（D19/F29/F30）仍在未提交基线，不当成本轮重新实现。

## 3.9.0历史验证

- `python scripts/android/test.py`：通过13组；`python scripts/android/native.py test --offline`：通过28项；`python scripts/android/build.py`：成功（3ABI/AAPT2/Java/D8/原签名）。实际使用E:/Conda/main/python.exe并载入dev-env.ps1。本轮Go未改、未重跑。
- `python scripts/android/build-smoke.py`和`--ui`：构建独立签名测试APK，不夹进正式产品。
- 全新API30隔离AVD yilan-ui390/5584，独立ADB5047：先3.8.7+DeviceSmoke seed，再覆盖3.9.0+verify，收藏/已读/关闭来源/夜间主题保留通过；合成测试数据不涉及个人数据库。
- UiSmoke：设置嵌套与排序、标题浏览器Intent、两级筛选器布局、模型子级、逐源回调时顶栏View保持、linux.do共享等待条/更新中切主题/完成隐藏，均通过；补测403旧快照保留、进度停止与错误详情入口也通过。模拟采集用于UI，不能算该AVD真实取帖成功。
- 已打开检查日夜设置、新闻筛选、平台动态、AI模型和linux.do进度六张截图。早期测试截图路径失败及绘制未完成已修正测试采集方式，未扩大产品存储权限；完整边界在UI_DESIGN。
- Android真实网络：最终APK的DeviceSmoke linuxviews模式通过生产JNI取得37条过滤后季度帖子；同轮宿主auto季度JSON 200/50。只证明本机模拟器链路，手机403仍未解决。证据ui390-android-quarterly.json、ui390-final-runtime.json与ui390-quarterly-host-auto.json。
- 实际网络：RSS auto由Cloudflare公共DoH取得200/30；季度与最新fallback由原第三方DoH取得200/50/50，均ECH accepted/HTTP2/证书验证。只代表宿主本次，不代表手机403已解决。
- 包检查：全部assets与3.8.7相同，三ABI原生库与最终构建字节相同、仅libc/libdl依赖、64位16KiB对齐，原签名v2/v3有效、仅INTERNET，产品DEX含SourceFilters且不含UiSmoke/DeviceSmoke。报告ui390-package.json。
- 私有证据：`.local/reports/ui390-runtime.json`（升级通过及早期测试故障）、ui390-ui-runtime.json（最终UI通过）、ui390-screens/、ui390-linux-*.json；基线在ui390-baseline.json与.local/ui390-baseline/。
- `git diff --check`通过；记忆检查脚本仍会将开发Manifest与正式release版本差异判错，不能为此替换正式包。本轮最终检查仅报该一项，其余索引/链接/数量无报错。
- 状态变化限工作区源码/文档、缓存、AVD、报告和APK。未安装工具、改系统DNS/hosts/证书/代理；本轮隔离模拟器与ADB已全部退出，缓存保留在工作区。

## 工作断点与风险

- 用户手机/家庭网络、偶发403、不同机型/大字体/长列表压力尚未验证；API30模拟器通过不是全机型认证。
- 用户补充确认403发生在浏览量，Wi-Fi和移动网络均有；本轮Android与宿主均未复现。403已到HTTP阶段，需结合新版错误详情的challenge标记复核，不能继续当DNS故障处理；外部DoH/站点策略可变，宿主成功不能排除透明转发影响。
- 模型资料是原快照，未因为界面重排改日期。来源主题标签不是逐条分析，也不表示国外分组可在所有网络访问。
- 更新继续手动、linux.do三模式与通用流/归档隔离；单站没有可诚实计算的百分比，使用不定进度动画。
- HF静态产品是否移除仍待用户决定；当前不是自动源，不作为采集报错处理。

## 下一步

1. 用户真机复验InfoQ等混合源的AI帖子进入AI平台动态、非AI仍在新闻，并查看来源多选、历史精选和旧收藏/已读。
2. 手机浏览量若仍403，点击错误详情记录challenge标记及完整提示再排查；不靠WebView/账号/共享Cookie/关闭TLS，不能声称匿名访问可以无条件通过站点验证。
3. 本轮已获Git提交/推送授权，先固化3.9.1基线，再开发4.0的可选用户API、固定模型、标题汇报/AI精选/手动翻译（D22）。正式发布未授权；不得覆盖正式包。

## 记忆索引

| 专题 | 内容 |
| --- | --- |
| [memory/architecture.md](memory/architecture.md) | 双产品、模块地图、共享JSON及SourceFilters |
| [memory/decisions.md](memory/decisions.md) | D01–D22，D22为4.0用户API开发授权 |
| [memory/contracts.md](memory/contracts.md) | C01–C08，来源、状态及3.9筛选/分流契约 |
| [memory/pitfalls.md](memory/pitfalls.md) | F01–F32，F32为筛选去重顺序及AI口径一致性 |
| [memory/conventions.md](memory/conventions.md) | 工具、构建、隔离UI/升级测试及私有状态 |
