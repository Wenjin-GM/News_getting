# 一览 · 项目交接入口

更新时间：2026-11-11（Asia/Shanghai）。先读 [AGENTS.md](AGENTS.md)，再读本文件与相关专题；记忆维护规范仅由 [MEMORY_PROTOCOL.md](MEMORY_PROTOCOL.md) 定义。

## 项目概览

主产品为原生 Android 独立 APK，手机自行采集、解析、展示新闻；Windows / Go 版独立保留，不是手机后端。架构与职责见 architecture。

## 当前产品与 Git 状态 · P1 / Active

| 项目 | 当前本地核实 |
| --- | --- |
| Android 源码与测试包 | `3.8.4-dev` / code `30804`，`cn.yilan.news`，Android 8.0+ / target36 |
| 当前测试 APK | [yilan-standalone-3.8.4-dev.apk](dist/android/yilan-standalone-3.8.4-dev.apk)，10,655,033字节；加固后三条路径本机均HTTP200/30条，新版手机待验；3.8.3旧可用包保留 |
| 测试包身份与报告 | [dist 元数据](dist/android/release.json)、[签名与权限报告](dist/android/android-verification.txt)；哈希以元数据为准 |
| 正式 APK（未替换） | `3.0.0` / code `30000`，[正式 APK](releases/android/yilan-standalone-3.0.0.apk)，62,219 字节 |
| 正式发布身份 | [release.json](releases/android/release.json) 与同目录 SHA 文件；README 正式下载仍指向 3.0.0 |
| 来源配置 | 37 源，默认29（11综合+18AI），8个实验源默认关；新增 linuxdo 官方公开 RSS |
| AI 资料 | 本轮未改；16平台 / 31工具，checkedAt=2026-10-28；另有模型结构，日期/置信度见 contracts C06 |
| Windows | 仍 1.1.0；仅同步 linuxdo 默认关闭的 RSS 注册以维持共享配置一致性，未加 ECH、未重建桌面包 |
| Git | main / `97368a8ce5f5e869e1c2bfd43c8bc68818eef962`；3.8.4加固源码与交接未提交 |
| origin | `https://github.com/Wenjin-GM/News_getting.git`；上一轮已同步97368a8；本轮仅授权加固，没有新增提交/推送或正式发布授权 |

任务基线仅剩未跟踪linux.do/与AI情报站私有文件，保留且不提交。002e1e5为此前双方3.8增量快照，97368a8为审查后基线。本轮仅原生网络/缓存、宿主诊断、版本及相关文档；新增fallback.rs、cfg(test)的test_support.rs。来源/目录/状态格式/界面不改；.local/.cache/dist与签名不提交。

## 当前工作断点与下一步 · P1 / Pending

1. **验收边界**：3.8.3已获用户手机取帖成功反馈；3.8.4加固版尚未真机覆盖安装，不能沿用旧通过结论。来源开关/收藏/已读升级保留也未单独获确认。
2. **用户当前任务**：授权加固且要求不影响获取。保留已成功的h2/ALPN/请求头、第三方接口与8秒/3次重试、原公共预算；加固HTTPS/响应、缓存和诊断。具体实现由[LINUXDO](docs/android/LINUXDO.md#384加固实现与本轮验证)维护，契约见C03。
3. **本轮实测**：修改前RSS30条；修改后三种模式均HTTP200/30条、HTTP/2、ECH accepted/证书验证通过。纯缓存前后文件哈希一致。25项Rust、JVM10组、三ABI构建/原签名/INTERNET-only与打包差异核对通过。
4. **下一步**：用户覆盖安装3.8.4，开启LINUX DO并单独更新，复验取帖/升级数据。总硬上限从65增至90秒，公共45/第三方25，慢失败后给缓存/站点留约20秒；正常成功立即返回。真实手机网络/断电/慢网尚未独立执行；外部DNS单点、ECH轮换与站点策略仍有风险。
5. UI与模型资料未改：3.5修正版用户已确认通过；3.7新背景/底栏视觉没有新的完整验收结论。资料checkedAt=2026-10-28、模型asOf=2026-10-21不变。docs/TODO.md不自动启动更多功能。

## 已有验证与边界 · P1 / Active

- 本轮native.py test --offline的25项Rust、test.py的JVM10组、build.py均通过。覆盖第三方重定向/响应边界、慢公共阶段→第三方/两层超时→缓存、错误保留、旧缓存兼容/原子替换/到期/回拨/不续期。JVM不执行按钮/JNI，手机升级需另验。
- 本轮三ABI库与APK字节一致且不同于3.8.3；全部assets未改且匹配维护源。ELF仅系统libc/libdl依赖，64位LOAD段16KiB对齐；ABI仍arm64/armv7/x86_64，不含32位Intel x86，生产DEX未含DeviceSmoke。正式3.0与旧可用3.8.3哈希均未变。
- 本轮构建3.8.4原证书、INTERNET-only、SDK/版本检查通过；新依赖/许可证没有变化。打包报告.local/reports/linuxdo-hardening-package.json，产物身份仅由dist元数据持有。
- `go test ./...` 是 3.8 轮通过的历史证据，含共享来源注册表一致性；3.8.3 未改 Go/来源表，未重跑 Go 或重建桌面产品。
- 本轮真实网络：自动经Cloudflare、强制第三方经aaa.ddd.oaifree.com、只用缓存三模式各200/30条；报告.local/reports/linuxdo-ech-rss-{auto,fallback,cache}.json。DNS强制第三方诊断亦退出0，已记录实际来源/状态。修改前基线在linuxdo-hardening-before.json；不把宿主成功当手机通过，no_proxy仍不排除宿主透明转发，见F19/F22。
- 早期build-smoke.py已编译测试APK，但模拟器等待240秒仍offline，后续重试也未出现可用ADB，未安装APK，启动根因未确认。当前h2版已有用户手机取帖成功反馈；独立设备instrumentation、升级数据保留仍未完成，本轮未启动模拟器。
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
- 本轮更新相关既有专题与交接，不创建日期型总结；实现/实测集中LINUXDO，私有报告/缓存不提交。正式、旧可用包和新测试包身份分别保留。
- 旧十专题合并后的知识继续保留；不要恢复旧摘要副本、旧 PWA 运行架构或失效部署细节。界面生成工具默认工作区外缓存的历史边界仍见 F18。
- 文档维护检查 `python scripts/check-handoff.py`、`git diff --check`；保留真实失败，不以重建/改正式包作为记忆维护手段。
