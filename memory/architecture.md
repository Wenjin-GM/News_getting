# 架构与模块关系

增量核实：2026-11-13；依据现行原生源码与独立验证。共享来源已改为同一JSON嵌入，避免两端手工清单漂移。验收与断点见[HANDOFF](../HANDOFF.md)，审查修复经验由pitfalls维护。本文件拥有架构职责，字段/决策分别见contracts/decisions。

## P1 / Active · 两个独立产品

一览 / News Getting / NewsDesk 的主产品为 Android 原生独立 APK；Windows / Go 版单独保留。两端共享维护资料，运行时互不依赖。

```text
config/sources.json → APK 内置来源 → 手机 HTTPS 直连
                                      ↓
                                  NewsEngine
                                      ↓
AI 目录内置资料 ────────────────→ MainActivity 原生文本界面
                                      ↓
                              应用私有 AtomicFile 状态

desktop/ → Go 采集 / SQLite / 本地 HTTP API → 内嵌网页
```

Android 没有本地服务或WebView；4.0新增可选的用户自备API模型调用，基础新闻不依赖它。开发机的 Python/JDK/SDK/Go/Node 不属于手机运行依赖。

## P1 / Active · Android 模块地图

| 路径 | 职责 |
| --- | --- |
| [MainActivity.java](../android/src/cn/yilan/news/MainActivity.java) | 全部原生 UI、导航、搜索/筛选、刷新调度、私有状态与系统分享 |
| [NewsEngine.java](../android/src/cn/yilan/news/NewsEngine.java) | 纯 Java 来源/文章/快照模型、HTTPS、RSS/Atom/JSON/HTML 适配、安全 URL 与降级 |
| [LinuxDoTransport.java](../android/src/cn/yilan/news/LinuxDoTransport.java)、[Rust核心](../android/native/linuxdo/src/lib.rs) | linuxdo、v2ex、v2ex-latest的固定端点ECH/JNI；Rust仅允许linux.do及www.v2ex.com；其他源仍标准HTTPS，不做通用代理或认证页 |
| [dns.rs](../android/native/linuxdo/src/dns.rs) | ECH 三级获取：公共 DoH failover → 第三方 JSON DoH 兜底 → 缓存；A/HTTPS 完整性校验、有限并发与阶段诊断 |
| [fallback.rs](../android/native/linuxdo/src/fallback.rs) | 原第三方JSON DoH的有界HTTPS传输、同域重定向/记录校验；不承载新闻正文 |
| [cache.rs](../android/native/linuxdo/src/cache.rs) | linuxdo/v2ex按固定文件名隔离缓存，保留原linuxdo旧文件；有界读写、原子替换、最长7天，成功响应后更新，命中不续期 |
| [AiClient.java](../android/src/cn/yilan/news/AiClient.java)、[AiSettingsDialog.java](../android/src/cn/yilan/news/AiSettingsDialog.java) | HTTPS兼容接口、测试/用户选择并锁定模型、原生配置弹窗 |
| [AiEngine.java](../android/src/cn/yilan/news/AiEngine.java)、[AiCoordinator.java](../android/src/cn/yilan/news/AiCoordinator.java) | 标题分批汇报/精选、手动翻译、阶段调度（阶段串行/内部最多两路）及进度、配置世代隔离 |
| [AiBatchCache.java](../android/src/cn/yilan/news/AiBatchCache.java) | 有界成功批次缓存，避免相同标题/模型/提示重复调用；保存于既有AI结果文件 |
| [AiTitles.java](../android/src/cn/yilan/news/AiTitles.java) | 独立显示译名与有界私有缓存，原文/ID不变，逐批补译 |
| [AiHistory.java](../android/src/cn/yilan/news/AiHistory.java) | 本地归档/快照/精选原文合并，分日复用/选择/补取计划，非分页边界及幂等补迁移 |
| [AiResultStore.java](../android/src/cn/yilan/news/AiResultStore.java) | 不可变按日文件及原子索引提交，旧格式兼容、容量/损坏保护，文件操作由AiVault注入 |
| [ContentDates.java](../android/src/cn/yilan/news/ContentDates.java)、[CacheCalendarDialog.java](../android/src/cn/yilan/news/CacheCalendarDialog.java) | 上海内容日期、三日窗口、快照清理及原生日历确认 |
| [AiChatPanel.java](../android/src/cn/yilan/news/AiChatPanel.java)、[SearchInput.java](../android/src/cn/yilan/news/SearchInput.java) | 锁定配置的临时原生聊天、键盘关闭搜索焦点处理 |
| [AiVault.java](../android/src/cn/yilan/news/AiVault.java) | AndroidKeyStore AES-GCM配置及独立结果缓存，不扩展新闻状态文件 |
| [NewsState.java](../android/src/cn/yilan/news/NewsState.java) | 不依赖 UI 的旧状态恢复、默认设置与序列化 |
| [NewsArchive.java](../android/src/cn/yilan/news/NewsArchive.java) | 容量受限的本机历史归档，与状态文件分开 |
| [ArticleTextCache.java](../android/src/cn/yilan/news/ArticleTextCache.java) | 全文进程内有界同步LRU，限制条数及字符总量，不写个人状态 |
| [FeaturedNews.java](../android/src/cn/yilan/news/FeaturedNews.java) | 无模型标题归并、机构去重与上海日期分组 |
| [SourceFilters.java](../android/src/cn/yilan/news/SourceFilters.java) | 来源区域/主题展示分组、平台动态精确来源筛选及设置排序，不承载端点/采集定义 |
| [ReadingRules.java](../android/src/cn/yilan/news/ReadingRules.java) | 筛选/勾选同步、来源分流、资料排序和新模型提示规则 |
| [ReaderTheme.java](../android/src/cn/yilan/news/ReaderTheme.java)、[ReaderIcon.java](../android/src/cn/yilan/news/ReaderIcon.java) | 日夜配色 token、偏好归一化与无外部依赖的原生图标 |
| [ReaderBackdrop.java](../android/src/cn/yilan/news/ReaderBackdrop.java)、[GlassPanelDrawable.java](../android/src/cn/yilan/news/GlassPanelDrawable.java) | 内置背景的有界解码、渐变遮罩与静态半透明泛光面板；设置页不铺图 |
| [styles.xml](../android/res/values/styles.xml) | 与配色对应的原生主题、输入/勾选/弹窗默认属性 |
| [ApkProvider.java](../android/src/cn/yilan/news/ApkProvider.java) | 仅分享本应用安装文件的只读 Provider |
| [AndroidManifest.xml](../android/AndroidManifest.xml) | 身份、SDK、权限、组件导出和备份配置 |
| [network_security_config.xml](../android/res/xml/network_security_config.xml) | 系统证书信任与禁止明文 |
| [data_extraction_rules.xml](../android/res/xml/data_extraction_rules.xml) | 数据提取边界 |
| [NativeEngineTest.java](../android/tests/NativeEngineTest.java) | JVM 解析/安全/快照 fixture 和可选直连验证 |

Activity底栏为新闻、AI专栏、收藏、设置。新闻有最新/分站热榜/精选；AI有平台动态/linux.do/AI/分站热榜/精选，模型降为AI内与产品/Harness/SDK并列。linux.do以当前子页名称的纯文字下拉选择默认/浏览量/最新，再单独更新，非记忆旧说的横向三标签；它有独立linuxTabs，隔离通用流/归档，预览取首帖纯文本。3.8.7补齐扩源后的路由、候选排序及正文实体语义；具体分流由C08持有。工具/模型/主题维持原规范，见[UI_DESIGN](../docs/android/UI_DESIGN.md)。

网络线程池固定四线程；UI 由主线程刷新，磁盘使用独立单线程与去抖保存。3.8.5 起取消启动/回前台自动检查，更新均由顶部按钮或 linux.do 子页手动触发，没有常驻后台推送。3.9批量逐源仅更新进度、完成统一重绘；单站复用全局动态等待条。状态持久化的字段与兼容边界由 contracts 维护。

## P1 / Active · 共享资料的唯一维护源

| 维护源 | 两端使用方式 |
| --- | --- |
| [config/sources.json](../config/sources.json)、[config/embed.go](../config/embed.go) | 唯一来源清单：安卓构建复制，Go builtins直接解码嵌入副本；不再手工维护第二套表 |
| [ai_catalog.json](../internal/catalog/ai_catalog.json) | APK 平台/工具及 modelData 维护源；来源快照与日期语义见 contracts C06 |
| [embed.go](../internal/catalog/embed.go) | Go 嵌入同一目录资料，由 `desktop/ai.go` 使用 |
| [registry_test.go](../desktop/registry_test.go) | 比较来源定义、顺序、端点及默认启用；enabled与Experimental为独立属性 |

构建复制两个 JSON 到被忽略的 `android/assets/`，复制 `assets/icon-192.png` 到生成的 mipmap 目录；不得维护生成副本。图标与 `assets/brand.svg` 是共享品牌资源。

3.8 构建另用 `scripts/android/native.py` 编译三 ABI .so，依赖锁在 Cargo.lock，许可由 native-licenses.py 生成/打包。Rust/NDK 仅开发依赖；手机只运行内置库。传输、安全和失败语义由 contracts C03 维护，实测限制与操作入口见 [LINUXDO](../docs/android/LINUXDO.md)。

当前来源数和目录规模见 HANDOFF；完整来源、工具和平台清单直接查 JSON，不在记忆复制第二份表格。

`AI情报站/local-site/` 与 `AI情报站/web/` 是用户保留的两份不同静态资料，不是手机运行依赖。当前仅从 local-site 导入模型/厂商分析到维护 JSON；原快照保留，未在 APK 嵌入网页，也未完成六类情报视图或网页反向派生。后续字段映射由 contracts C06 约束。

## P1 / Active · Windows 模块地图

根 Go 模块是 `newsdesk`；`desktop/` 为 `package main`。

| 文件/目录 | 职责 |
| --- | --- |
| `desktop/main.go` | 生命周期、命令行、采集任务与来源状态 |
| `desktop/server.go` | 本地 HTTP API、鉴权、LAN 分享；嵌入 `web/*` |
| `desktop/sources.go` | 来源定义、模型、RSS/JSON 适配及网络边界 |
| `desktop/ai.go`、`ai_export.go` | AI 采集、HTML/发布解析、目录与导出 |
| `desktop/store.go` | SQLite 持久化 |
| `desktop/web/` | HTML/CSS/JS，无 Node 前端构建步骤 |

依赖以 `go.mod` / `go.sum` 为准，主要有 modernc SQLite、go-qrcode。电脑版自定义 RSS、导出与 LAN 能力不代表安卓版也有这些功能。

## P2 / Active · 工作空间与文档地图

| 路径 | 用途 |
| --- | --- |
| `android/`、`desktop/`、`config/`、`internal/`、`assets/` | 源码与维护资料 |
| `scripts/android/`、`scripts/desktop/` | 准备、构建、测试、打包、启动/停止 |
| `scripts/diagnostics/check-ai-links.mjs` | 官方资料入口诊断，非应用运行依赖 |
| `scripts/check-handoff.py` | 新记忆结构、链接、元数据与正式包一致性的只读检查 |
| `releases/android/` | 已发布 APK、公开元数据/哈希，Git 跟踪 |
| `dist/` | 本机重建产物，Git 忽略 |
| `.cache/` | 便携工具、构建、隔离测试缓存，Git 忽略 |
| `.local/` | 私有签名、个人数据、报告、恢复资料；处置见 conventions |

文档分工：

- [根 README](../README.md)、[安卓说明](../android/README.md)、[电脑版说明](../desktop/README.md)：现行安装/开发入口。
- [SECURITY](../docs/SECURITY.md)：安全边界说明，不是第三方安全认证。
- [STRUCTURE](../docs/STRUCTURE.md)：已完成的旧路径到新路径迁移地图。
- [REORGANIZATION_CHECKS](../docs/REORGANIZATION_CHECKS.md)：目录整理时点验证记录。
- [安卓验收](../docs/android/VALIDATION.md)、[直连记录](../docs/android/direct-network-test.json)、`docs/android/*.png`：3.0 交付时点证据，不是实时状态。
- [电脑 1.0 报告](../docs/desktop/TEST_REPORT.md)、[1.1 报告](../docs/desktop/TEST_REPORT_AI_1.1.0.md)：保留历史版本/路径/测试数；其中 12 项测试与整理后的 13 项有时间差。
- [AI_GUIDE](../docs/desktop/AI_GUIDE.md)：电脑版 AI 指南；不可据此宣称安卓已有导出等功能。
- [第三方声明](../THIRD_PARTY_NOTICES.md)、`docs/desktop/licenses/`：依赖归属与许可原文，不能当 AI 冗余记忆删除。

## P3 / Superseded · 旧架构的识别边界

旧 `mobile/`、Vinext/React PWA、Sites 嵌套 Git、2.1 WebView 壳不在现行源码树；不恢复为默认架构。原生替换原因由 decisions 保存。旧模拟器/镜像/AVD 已在此前整理中清理，历史设备和临时工具会话不能作为当前环境。
