# 一览 · 项目交接入口

更新时间：2026-11-11（Asia/Shanghai）。先读 [AGENTS.md](AGENTS.md)，再读本文件与相关专题；记忆维护规范仅由 [MEMORY_PROTOCOL.md](MEMORY_PROTOCOL.md) 定义。

## 项目概览

主产品为原生 Android 独立 APK，手机自行采集、解析、展示新闻；Windows / Go 版独立保留，不是手机后端。架构与职责见 architecture。

## 当前产品与 Git 状态 · P1 / Active

| 项目 | 当前本地核实 |
| --- | --- |
| Android 源码与测试包 | `3.8.3-dev` / code `30803`，`cn.yilan.news`，Android 8.0+ / target36 |
| 当前测试 APK | [yilan-standalone-3.8.3-dev.apk](dist/android/yilan-standalone-3.8.3-dev.apk)，10,630,457 字节；**h2 + 第三方 DoH 兜底 + ECH 缓存，本机端到端取到 30 条真实 RSS（200），真机待验** |
| 测试包身份与报告 | [dist 元数据](dist/android/release.json)、[签名与权限报告](dist/android/android-verification.txt)；哈希以元数据为准 |
| 正式 APK（未替换） | `3.0.0` / code `30000`，[正式 APK](releases/android/yilan-standalone-3.0.0.apk)，62,219 字节 |
| 正式发布身份 | [release.json](releases/android/release.json) 与同目录 SHA 文件；README 正式下载仍指向 3.0.0 |
| 来源配置 | 37 源，默认29（11综合+18AI），8个实验源默认关；新增 linuxdo 官方公开 RSS |
| AI 资料 | 本轮未改；16平台 / 31工具，checkedAt=2026-10-28；另有模型结构，日期/置信度见 contracts C06 |
| Windows | 仍 1.1.0；仅同步 linuxdo 默认关闭的 RSS 注册以维持共享配置一致性，未加 ECH、未重建桌面包 |
| Git | main / `8e35353393ae2c0ae3ae16d888e896e4c7fe23a2`；本地 3.8 系列改动尚未提交或推送 |
| origin | `https://github.com/Wenjin-GM/News_getting.git`；前轮已推送 8e35353，本轮没有新增 Git 发布授权 |

任务基线的未跟踪 `linux.do/` 及 `AI情报站/` 私有部署文件、REFRESH.md、.scratch/ 原样保留。前轮新增 `android/native/`、LinuxDoTransport、设备测试、构建/工具脚本、LINUXDO/许可文档；修改共享来源注册、Manifest/分享文件名和记忆。3.8.1追加单站更新按钮，3.8.2增加公共DoH/引导。本轮3.8.3仅修改dns.rs路线/预算/测试、lib.rs总时限、native.py诊断时限、版本和LINUXDO/HANDOFF/contracts/pitfalls；依赖锁与其他既有改动保留。实际范围查 git status/diff，不把用户参考资料作为本轮代码成果。`.local/`、`.cache/`、dist 与签名不提交。

## 当前工作断点与下一步 · P1 / Pending

1. **本机修复已闭环（2026-11-11）**：应用层根因是 Cloudflare 对 HTTP/1.1 + 非浏览器 TLS 指纹下发挑战；改 HTTP/2 + Chrome Mobile UA 后 `/latest.rss` 直接 200。ECH 获取改为公共 DoH → 第三方 JSON DoH 兜底 → 本地缓存三级。全程保留 ECH Accepted、证书/主机名校验，无 Cookie/登录/WebView/代理，未关闭 TLS。决策见 D14/D15，契约见 C03。
2. **本机端到端实测通过**：`linuxdo-probe rss` 退出 0，ECH accepted、TLS 校验通过、application/rss+xml、30 个 `<item>`；Rust 14 项测试、JVM 10 项 PASS、三 ABI release 编译、APK 打包库字节一致均通过。
3. **下一步＝真机覆盖安装验收（唯一关键待办）**：安装 dist 下 3.8.3-dev，设置→信息来源开启 LINUX DO，点「单独更新 LINUX DO」，等待一轮（最多约 65 秒）。手机网络与本机不同，需确认第三方 DoH 在手机网络可达、ECH 与 h2 路径成功；不得把本机成功当作手机已通过。来源开关/收藏/已读保留也待真机确认。
4. **风险**：第三方 DoH 为单一域名公共依赖，可能被封/停服；此时靠 7 天缓存兜底，缓存也过期且无任何可达通道时会失败（如实报错，不降低 TLS、不扩大 WebView/信任根/Cookie）。
5. UI 与模型资料未改：3.5 真机通过的视觉、3.7 底栏/背景等沿用旧验收结论；模型快照 checkedAt=2026-10-28 不变。docs/TODO.md 不自动启动更多功能。

## 已有验证与边界 · P1 / Active

- 本轮 `native.py test` 的 **14 项 Rust 测试**（含 2 项缓存往返）、`test.py` JVM 10 项回归、`build.py` 三 ABI 编译/原签名/INTERNET-only 通过。DNS 测试含缺 ECH 不误报、合法 hint/私网拒绝、并发上限与取消、缓存读写；JVM 不执行按钮/JNI，手机新版待验。
- 已核对三 ABI 库与 APK 内字节一致（arm64 4,464,472 / armv7 2,823,220 / x86_64 5,087,816），全部 assets 未改并与维护源一致，正式 APK 哈希未变。ELF 仅系统 libc/libdl 依赖，64 位 LOAD 段 16KiB 对齐；ABI 仍 arm64/armv7/x86_64，不含 32 位 Intel x86。
- `python scripts/android/build.py` 成功，APK 原证书匹配、仅 INTERNET、SDK/版本和三 ABI 资源检查通过。已核对内置来源/AI JSON 与维护源字节一致，正式 APK 哈希未变；生产 DEX 不含 DeviceSmoke。具体产物身份见 dist 元数据，不在记忆复制哈希。
- `go test ./...` 是 3.8 轮通过的历史证据，含共享来源注册表一致性；3.8.3 未改 Go/来源表，未重跑 Go 或重建桌面产品。
- 本轮真实网络：标准公共 DoH 仅阿里/DNSPod 可达但无 ECH，境外四家超时/RST；`linuxdo-probe rss` 经第三方 DoH 取 ECH、h2 传输后退出 0，拿到 30 条 RSS（application/rss+xml），无代理/无系统 hosts。可达性与 DoT/边缘 IP 死路证据在 .local/reports/，详见 LINUXDO 与 F22。
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
- 本轮交接检查器退出1，唯一错误为 Manifest/正式包版本不一致，另有未发布来源/资料差异警告；五专题索引等其余检查未报错。git diff --check 通过。不能为凑通过替换正式包，见 F16。

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
