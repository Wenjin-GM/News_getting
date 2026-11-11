# 架构与模块关系

增量核实：2026-11-11；Android 依据当前未发布源码、配置和测试包；Windows 保留3.8阶段同步默认关闭来源注册的改动，本轮未改或重测。验收范围见 [HANDOFF](../HANDOFF.md)。本文件拥有架构与文件职责；字段约束见 [contracts](contracts.md)，决策原因见 [decisions](decisions.md)。

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

Android 没有本地服务、WebView、模型调用或云聚合 API。开发机的 Python/JDK/SDK/Go/Node 不属于手机运行依赖。

## P1 / Active · Android 模块地图

| 路径 | 职责 |
| --- | --- |
| [MainActivity.java](../android/src/cn/yilan/news/MainActivity.java) | 全部原生 UI、导航、搜索/筛选、刷新调度、私有状态与系统分享 |
| [NewsEngine.java](../android/src/cn/yilan/news/NewsEngine.java) | 纯 Java 来源/文章/快照模型、HTTPS、RSS/Atom/JSON/HTML 适配、安全 URL 与降级 |
| [LinuxDoTransport.java](../android/src/cn/yilan/news/LinuxDoTransport.java)、[Rust 核心](../android/native/linuxdo/src/lib.rs) | 可选固定站点 ECH/JNI；仅 linuxdo 使用，其余来源保留标准 HTTPS；站点走 HTTP/2，不监听本地端口或引入认证页 |
| [dns.rs](../android/native/linuxdo/src/dns.rs) | ECH 三级获取：公共 DoH failover → 第三方 JSON DoH 兜底 → 缓存；A/HTTPS 完整性校验、有限并发与阶段诊断 |
| [cache.rs](../android/native/linuxdo/src/cache.rs) | 应用私有目录 ECH 缓存（`linuxdo-ech-cache.json`，最长 7 天，读回重新校验），仅实时通道全失败时使用 |
| [NewsState.java](../android/src/cn/yilan/news/NewsState.java) | 不依赖 UI 的旧状态恢复、默认设置与序列化 |
| [NewsArchive.java](../android/src/cn/yilan/news/NewsArchive.java) | 容量受限的本机历史归档，与状态文件分开 |
| [FeaturedNews.java](../android/src/cn/yilan/news/FeaturedNews.java) | 无模型标题归并、机构去重与上海日期分组 |
| [ReadingRules.java](../android/src/cn/yilan/news/ReadingRules.java) | 筛选/勾选同步、来源分流、资料排序和新模型提示规则 |
| [ReaderTheme.java](../android/src/cn/yilan/news/ReaderTheme.java)、[ReaderIcon.java](../android/src/cn/yilan/news/ReaderIcon.java) | 日夜配色 token、偏好归一化与无外部依赖的原生图标 |
| [ReaderBackdrop.java](../android/src/cn/yilan/news/ReaderBackdrop.java)、[GlassPanelDrawable.java](../android/src/cn/yilan/news/GlassPanelDrawable.java) | 内置背景的有界解码、渐变遮罩与静态半透明泛光面板；设置页不铺图 |
| [styles.xml](../android/res/values/styles.xml) | 与配色对应的原生主题、输入/勾选/弹窗默认属性 |
| [ApkProvider.java](../android/src/cn/yilan/news/ApkProvider.java) | 仅分享本应用安装文件的只读 Provider |
| [AndroidManifest.xml](../android/AndroidManifest.xml) | 身份、SDK、权限、组件导出和备份配置 |
| [network_security_config.xml](../android/res/xml/network_security_config.xml) | 系统证书信任与禁止明文 |
| [data_extraction_rules.xml](../android/res/xml/data_extraction_rules.xml) | 数据提取边界 |
| [NativeEngineTest.java](../android/tests/NativeEngineTest.java) | JVM 解析/安全/快照 fixture 和可选直连验证 |

Activity 使用原生控件，底栏为新闻、AI 专栏、收藏、设置。新闻有最新、分站热榜、精选；AI 有平台动态、工具、模型、分站热榜、精选。工具合并产品/Harness/SDK 类型筛选；最多三项对比针对模型。列表为标题及小字来源/发布时间，详情保留摘要、收藏、分享、原文。紧凑模式控件已移除，旧字段仍兼容。各流规则见 contracts C08；视觉规范与待验收范围见 [UI_DESIGN](../docs/android/UI_DESIGN.md)。

网络线程池固定四线程；UI 由主线程刷新，磁盘使用独立单线程与去抖保存。首次启动与回前台按条件检查来源，没有常驻后台推送。状态持久化的字段与兼容边界由 contracts 维护。

## P1 / Active · 共享资料的唯一维护源

| 维护源 | 两端使用方式 |
| --- | --- |
| [config/sources.json](../config/sources.json) | 安卓来源注册表；Go `builtins()` 与之通过测试保持一致 |
| [ai_catalog.json](../internal/catalog/ai_catalog.json) | APK 平台/工具及 modelData 维护源；来源快照与日期语义见 contracts C06 |
| [embed.go](../internal/catalog/embed.go) | Go 嵌入同一目录资料，由 `desktop/ai.go` 使用 |
| [registry_test.go](../desktop/registry_test.go) | 比较来源定义、顺序、端点及默认启用与 Experimental 的对应关系 |

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
