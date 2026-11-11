# 一览 · 项目交接入口

更新时间：2026-11-11（Asia/Shanghai）。先读 [AGENTS.md](AGENTS.md)，再读本文件与相关专题；记忆维护规范仅由 [MEMORY_PROTOCOL.md](MEMORY_PROTOCOL.md) 定义。

## 项目概览

主产品为原生 Android 独立 APK，手机自行采集、解析、展示新闻；Windows / Go 版独立保留，不是手机后端。架构与职责见 architecture。

## 当前产品与 Git 状态 · P1 / Active

| 项目 | 当前本地核实 |
| --- | --- |
| Android 源码与测试包 | `3.8.3-dev` / code `30803`，`cn.yilan.news`，Android 8.0+ / target36 |
| 当前测试 APK | [yilan-standalone-3.8.3-dev.apk](dist/android/yilan-standalone-3.8.3-dev.apk)，10,630,457 字节；h2 + 第三方DoH兜底 + ECH缓存；用户已报告手机取帖成功，本轮独立宿主复验取得30条RSS |
| 测试包身份与报告 | [dist 元数据](dist/android/release.json)、[签名与权限报告](dist/android/android-verification.txt)；哈希以元数据为准 |
| 正式 APK（未替换） | `3.0.0` / code `30000`，[正式 APK](releases/android/yilan-standalone-3.0.0.apk)，62,219 字节 |
| 正式发布身份 | [release.json](releases/android/release.json) 与同目录 SHA 文件；README 正式下载仍指向 3.0.0 |
| 来源配置 | 37 源，默认29（11综合+18AI），8个实验源默认关；新增 linuxdo 官方公开 RSS |
| AI 资料 | 本轮未改；16平台 / 31工具，checkedAt=2026-10-28；另有模型结构，日期/置信度见 contracts C06 |
| Windows | 仍 1.1.0；仅同步 linuxdo 默认关闭的 RSS 注册以维持共享配置一致性，未加 ECH、未重建桌面包 |
| Git | main；3.8系列源码已提交并推送为 `002e1e5`；本轮审查文档另作增量提交，最终HEAD以git log为准 |
| origin | `https://github.com/Wenjin-GM/News_getting.git`；用户本轮明确授权提交Git与GitHub；测试包仍只在dist，未发布正式包 |

任务基线的未跟踪 `linux.do/` 及 `AI情报站/` 私有部署文件、REFRESH.md、.scratch/ 原样保留且未提交。002e1e5是先前3.8系列双方增量的完整源码快照，不能把全部改动归给最后一名执行者。本轮随后只独立审查、运行测试/宿主探测和维护文档，未修改业务代码、未重建APK。`.local/`、`.cache/`、dist与签名不提交。

## 当前工作断点与下一步 · P1 / Pending

1. **用户验收新增事实**：用户报告另一个模型接手升级后，APK已能访问并获取linux.do内容；这属于用户真机反馈，不是本轮执行者亲手操作手机。来源开关/收藏/已读升级保留未单独获确认。
2. **独立源码核对**：在原ECH直连上新增HTTP/2及Chrome Mobile请求头、无密钥第三方JSON DoH回退、私有目录ECH缓存。当前RSS成功；不能据此断言Cloudflare内部的具体判定规则或完整模拟了Chrome TLS指纹。决策见D14/D15，契约见C03。
3. **本轮独立实测通过**：Rust14项、JVM10组PASS；重新离线编译宿主CLI，不用ECH缓存请求官方RSS，4.58秒取得30条、ECH accepted且TLS验证通过。现有APK三ABI库与编译输出、内置来源/资料、元数据哈希一致；本轮没有重建APK，三ABI构建属于前轮证据。
4. **审查断点**：新增第三方DoH的HTTPS/重定向与响应上限、回退耗时可能挤掉缓存、诊断遗漏新增层等需后续加固，详见[LINUXDO独立审查](docs/android/LINUXDO.md#独立审查发现与待办)。当前用户只要求提交并查看，没有自动修改已可用的实现。
5. UI与模型资料未改：3.5修正版用户已确认通过；3.7新背景/底栏视觉没有新的完整验收结论。资料checkedAt=2026-10-28、模型asOf=2026-10-21不变。docs/TODO.md不自动启动更多功能。

## 已有验证与边界 · P1 / Active

- 本轮 `native.py test` 的 **14 项 Rust 测试**（含 2 项缓存往返）、`test.py` JVM 10 项回归、`build.py` 三 ABI 编译/原签名/INTERNET-only 通过。DNS 测试含缺 ECH 不误报、合法 hint/私网拒绝、并发上限与取消、缓存读写；JVM 不执行按钮/JNI，手机新版待验。
- 已核对三 ABI 库与 APK 内字节一致（arm64 4,464,472 / armv7 2,823,220 / x86_64 5,087,816），全部 assets 未改并与维护源一致，正式 APK 哈希未变。ELF 仅系统 libc/libdl 依赖，64 位 LOAD 段 16KiB 对齐；ABI 仍 arm64/armv7/x86_64，不含 32 位 Intel x86。
- `python scripts/android/build.py` 成功，APK 原证书匹配、仅 INTERNET、SDK/版本和三 ABI 资源检查通过。已核对内置来源/AI JSON 与维护源字节一致，正式 APK 哈希未变；生产 DEX 不含 DeviceSmoke。具体产物身份见 dist 元数据，不在记忆复制哈希。
- `go test ./...` 是 3.8 轮通过的历史证据，含共享来源注册表一致性；3.8.3 未改 Go/来源表，未重跑 Go 或重建桌面产品。
- 本轮真实网络：当前源码RSS探测退出0，约69KiB/30条；报告.local/reports/linuxdo-review-rss.json，CLI不使用ECH缓存。成功输出未标记DNS服务，不能断言本轮走了第三方回退。前轮记录标准DoH超时、第三方成功仅属于当时网络，不当作永久封锁结论。no_proxy和绕过linux.do hosts仍不证明宿主没有透明转发，见F19/F22。
- build-smoke.py 已编译独立同签名 instrumentation 测试 APK；smoke.py 在首次软件模拟器等待240秒后退出1，未安装任何 APK。关闭 Vulkan/单核重试仍未出现可用 ADB，实例已结束；该轮模拟器内的实际升级、JNI、Activity 启动与截图均未验证，启动失败根因未确认；随后用户已给出3.8手机运行/原生DNS失败截图，但升级数据保留、新按钮和成功抓帖仍未验收。
- 正式 3.0、既有3.5真机/早期无模型精选和网络成功记录仅为历史证据，入口仍为 [安卓验收](docs/android/VALIDATION.md)。不延伸为当前版本或全源/全机型认证。
- dist/测试包/报告仅在本工作区；Git 不含这些产物。新克隆必须准备 Rust/NDK/依赖和有效签名，不能把源码提交理解为正式下载更新。

## 本机状态与风险 · P1 / Active

- 已安装便携 Rust 1.99.1（含Android三目标）、NDK r30、CMake4.4.4到 .cache/native*；模拟器/API30镜像/ADB 到 .cache/android/，均只配置工作区缓存/数据。原有宿主 GCC 仅调用，未在外部安装；不需要先装 Android Studio。
- Google 主站/中国域名 TLS EOF 后，官方 gvt1 CDN 下载及固定哈希校验成功；未关闭 TLS。准备/重试入口与操作细节由 conventions/ F20 / LINUXDO 持有。
- 前轮自建模拟器/ADB已关闭并核过端口，本轮未重启；未更改系统DNS/hosts/证书/代理、全局PATH或驱动。新生成仅工作区构建/诊断报告，诊断脚本在.local/maintenance，运行报告在.local/reports。既有工具与AVD保留。
- 首次普通请求的成功会受现有桌面加速配置影响；no_proxy 只排除 HTTP 代理，不证明宿主没有透明转发。协议/电脑构建与真实手机网络验收严格区分，见 F19。
- 精选只有本机实际抓到的有界历史；模型快照未逐条重新事实核验；新增来源不等于获取旧日记录或更新模型资料。完整规则由 contracts 维护。
- 微博、X、旧人民网 RSS 等未新增接入。完整情报站、模型/密钥、备份和正式发布仍须新指令；不继承历史发布请求。
- 交接检查器已知问题为Manifest/正式包版本不一致及未发布来源警告，不能为凑通过替换正式包，见F16。此次完整暂存检查发现第三方许可原文尾空白，保留原文；排除该生成许可后的源码/文档检查通过。

## 专题索引（单一知识所有权）

| 专题 | 何时读取 / 权威内容 |
| --- | --- |
| [architecture](memory/architecture.md) | 模块、数据流、共享维护源、文件职责 |
| [decisions](memory/decisions.md) | 用户确认需求、执行者取舍与未授权的方案 |
| [contracts](memory/contracts.md) | 数据/时间/ID、网络/TLS、归并归档、兼容与发布 |
| [pitfalls](memory/pitfalls.md) | 高价值失败、根因证据与适用限制 |
| [conventions](memory/conventions.md) | 环境、构建/测试/发布操作、私有路径 |

五专题由本文件直接索引，无需 INDEX；P0 规则只由 AGENTS 完整维护。普通开发按任务读专题，仅记忆维护读取 MEMORY_PROTOCOL。

## 接续与记忆沿革 · P2 / Active

- 新任务核对实际 Git/源码/用户最新授权；README 是正式安装/当前开发入口，TODO 不是自动执行授权。
- 3.8阶段维护既有五专题，本轮增量仅contracts/pitfalls与本交接，不创建日期型总结；技术实测集中LINUXDO，私有运行报告不提交。正式/开发身份分开维护。
- 旧十专题合并后的知识继续保留；不要恢复旧摘要副本、旧 PWA 运行架构或失效部署细节。界面生成工具默认工作区外缓存的历史边界仍见 F18。
- 文档维护检查 `python scripts/check-handoff.py`、`git diff --check`；保留真实失败，不以重建/改正式包作为记忆维护手段。
