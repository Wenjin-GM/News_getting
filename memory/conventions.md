# 开发规范、环境与操作约定

增量核实：2026-11-09，依据当前脚本/目录；不表示本次重新构建或联网测源。硬规则由 [AGENTS](../AGENTS.md) 持有，技术契约见 [contracts](contracts.md)。记忆管理方法唯一依据根 [MEMORY_PROTOCOL](../MEMORY_PROTOCOL.md)，本文件不另设维护协议。

## P2 / Active · 日常协作与开发环境

- 默认中文，简明说明结果、验证与限制；自主完成已授权工作，只有实质缺失信息才澄清。用户偏好及一次性安排边界见 D09。
- 原工作空间 `F:\SELF\Try\News`，Windows/PowerShell；所有命令默认在根目录。换机器必须按当次环境定位工具，不硬编码 Codex 自带运行时或用户名。
- Python 3.10+ 标准库；Go 1.24+；Node 20.11+ 仅电脑版语法检查/.mjs 烟测，安卓构建无需 Node。
- 便携 JDK 21.0.12.1、Platform 36 r02、Build Tools 36 在 `.cache/android/`；固定官方 URL 与 SHA 值由 prepare.py 持有。
- 先 `. .\scripts\dev-env.ps1`，只影响当前进程：Go 缓存、TEMP/TMP、Python 字节码开关等均指向工作空间。不要覆盖 `$HOME` 或 `$CODEX_HOME`，不改全局 PATH/注册表/执行策略。
- `.gitattributes`：普通文本 LF，ps1/cmd CRLF，二进制不转码；ps1 保留 UTF-8 BOM 兼容 PS5。第三方许可保持原字节，不为消除空白告警改正文。

## P1 / Active · 安卓构建与相关测试

```powershell
. .\scripts\dev-env.ps1
python scripts/android/prepare.py
python scripts/android/test.py
python scripts/android/build.py
```

prepare.py 从官方来源下载校验工具，只写工作空间；有匹配 ready 标记则复用。仅验证过 Windows 工具链，不宣称 Linux/macOS 已可构建。

build.py 在已验证生成边界内清理 classes/dex/gen，复制维护 JSON 和 PNG，执行 AAPT2 → javac --release 8 → D8 → zipalign → apksigner。输出到 `dist/android/`，公开包身份真值与升级约束见 C01。

3.7 背景原图维护在 `android/res/drawable-nodpi/`，生成提示词在 `assets/android-background-prompts.json`，不是只保留工具缓存。输出路径限制的实际陷阱见 pitfalls F18。Git 提交源码/资源不自动发布 dist APK；新克隆没有本机测试包和原签名材料，不能按交接中的本机产物链接推断可直接下载。

签名密码以子进程环境变量传递；当前报告在 `dist/android/android-verification.txt`，同目录 release.json/sha256 为本次测试产物身份。脚本从 Manifest 取版本，日常不写 releases。`-dev` 构建必须已有原签名材料并匹配正式证书，缺失会失败，不自动生成身份。构建检查 INTERNET、SDK、内置 JSON 和旧 WebView/站点/部分 SDK 标识，不是全面安全扫描；非 dev 首次构建生成的自有签名不能覆盖原正式安装。

默认 `test.py` 只准备固定 SHA-256 的 `org.json:json:20250517` JVM JAR，运行 fixture；JAR 不进 APK，手机用系统 org.json。

测试入口编译 NewsEngine/NewsState/NewsArchive/FeaturedNews/ReadingRules/ReaderTheme，不编译或运行 MainActivity。分类集合与 checked[] 回归通过仍不能代替原生对话框实测；必须在交付中区分 JVM、构建/签名与手机验收。相同版本号修正版可重建到同名 dist 文件，不能仅凭“3.5.0-dev”区分首包/修正版，应查当前元数据哈希；历史同版包可能已被替换。

```powershell
python scripts/android/test.py --live
```

这是可选实际联网直连，写 `.local/reports/native-direct-check.json`。上游可变，历史通过不等于新测试；文档迁移无需运行。

### P2 / Active · 仅 GUI 验证时准备模拟器

`python scripts/android/prepare-tests.py --emulator` 会下载大型官方工具和 API30/Android11 x86_64 镜像；不加参数只准备 JVM 依赖。此前大文件已清理，不能假定设备仍在。

可复用的历史隔离配方（参数不是当下设备状态）：

- `.cache/android/` 下工具；ANDROID_USER_HOME、ANDROID_AVD_HOME、ANDROID_EMULATOR_HOME、临时目录全部限定工作空间。
- `YilanNativeTest`，432×936、density180、约1.5GiB RAM；曾用 `-no-snapshot -read-only -no-audio -no-boot-anim -gpu swiftshader -port 5560 -no-metrics`。
- 当时已有 WHPX，不曾装驱动/开 Windows 功能；新环境不可用时报告限制，不静默改宿主机设置。
- 独立 ADB 5038：`ADB_SERVER_SOCKET=tcp:127.0.0.1:5038`；曾需 connect127.0.0.1:5561 后使用 emulator-5560。
- SDK CLI 仅安装/诊断，UI 按当次允许的技能与工具；只控制自己创建的设备。
- 离线测试仅在该虚拟机开飞行模式，dumpsys connectivity 确认 default network none，不断主机网络。
- 结束关闭自建模拟器和独立 ADB，核对端口；不停止用户现有调试进程。

## P1 / Active · 电脑版构建与隔离验证

```powershell
.\scripts\desktop\test.ps1
.\scripts\desktop\build.ps1
.\scripts\desktop\package.ps1
node scripts/desktop/smoke.mjs
```

- test：Go 测试（含跨端来源），检查 app.js/ai.js 语法。
- build：编译 `./desktop` → `dist/windows/NewsDesk.exe`。
- package：在 `.cache/windows-package/` 隔离组包，输出无个人数据的 ZIP。
- smoke：实际启动包内程序，用 `.cache/smoke-*` 隔离数据，验证鉴权/来源/RSS/导出，最后关闭测试进程；会联网，按改动需要运行。
- start/stop/paths 脚本负责源码和便携包路径，不需系统服务/开机启动/代理或防火墙改动。数据与 API 边界见 C07。

## P0 / Active · 私有状态处置

规则由 AGENTS 约束；以下只记录位置与用途，不读取秘密值：

| 本地忽略路径 | 用途 / 禁止误清理 |
| --- | --- |
| `.local/signing/android/yilan-release.p12` | 原正式发布身份，不能重建/提交/转发 |
| `.local/signing/android/keystore-password.txt` | 对应密码，不打印、不放命令参数/日志/记忆 |
| `.local/runtime/desktop/data/newsdesk.db` | 原个人数据库；同目录可能有偏好、访问密钥/运行状态 |
| `.local/runtime/desktop/logs/`、`exports/` | 整理前日志与导出，不能当无用构建缓存 |
| `.local/archive/workspace-before-reorganization.zip` | 整理前源码与旧 Sites Git 恢复包；历史记录不含原 DB/私钥/大型工具缓存，仅留本地 |
| `.local/reports/`、`.local/maintenance/` | 本地验证、整理报告/辅助脚本，用途不明则保留 |
| `.cache/` | 可重建工具/测试数据；清理前仍须核对目标与是否混入个人文件 |

本次仅核对关键路径存在性，不将密码、数据库或旧历史内容读入记忆。其他克隆不具备这些私有文件，使用时重新检查，不假定身份可恢复。

## P1 / Active · 发布操作约定

提交/推送须当次有效授权，不能继承历史发布请求。有明确发布任务时：检查 Git 状态/范围 → 实现和版本一致 → 相关测试 → 原证书与版本码 → 核验正式包/元数据 → 更新说明 → 审查暂存文件与秘密排除 → 按有效授权发布。

`.gitignore` 排除 `.local/.cache/dist`、生成 assets/mipmap、签名、环境文件、数据库/日志；不强行添加这些路径。公开证书指纹可发布，密钥/令牌/含授权 URL 不可发布。不要将恢复 ZIP 或嵌套旧 Git 历史当公开分发物。

现有 Git Credential Manager 曾成功认证，不记录凭据或全局身份内容；API 限流处理见 F10。文档不能预知包含自身的提交哈希，不为此反复 amend。

## P2 / Active · 来源、证据与核实入口

事实读取顺序按根协议：需求查用户确认/decisions，实现查源码配置，行为查具体测试，目录动态事实查官方 references。维护来源/目录的操作边界由 C03/C06 规定。

| 问题 | 依据 |
| --- | --- |
| 正式 APK / 分发 | `releases/android/release.json`、实际 APK；[用户指定 GitHub](https://github.com/Wenjin-GM/News_getting) |
| 媒体与厂商端点 | `config/sources.json`；AI 平台官方 newsUrl、工具 URL/references 在共享目录 JSON |
| Android 构建 | [命令行](https://developer.android.com/build/building-cmdline)、[AAPT2](https://developer.android.com/tools/aapt2)、[工具](https://developer.android.com/tools) |
| 官方工具包 | [Microsoft JDK](https://learn.microsoft.com/java/openjdk/download)、[SDK 元数据](https://dl.google.com/android/repository/repository2-1.xml)、[镜像元数据](https://dl.google.com/android/repository/sys-img/android/sys-img2-1.xml) |
| JVM JSON 测试库 | [Maven 官方产物](https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar)，固定校验在 prepare-tests.py |
| 依赖/归属 | go.mod/go.sum；[SQLite](https://gitlab.com/cznic/sqlite)、[go-qrcode](https://github.com/skip2/go-qrcode)、THIRD_PARTY_NOTICES |

新闻来自来源渠道，不由模型生成；HN 使用 Algolia 查询是该来源的数据入口，不是作者自建聚合后端。NewsNow 的公开适配字段是早期参考，归属见第三方声明，不携带其 Cookie/私有数据。

历史 PWA 的 MDN manifest/CSP、Chrome Local Network Access、Android WebView 文件安全资料只服务旧架构；不作为当前 APK 配置依据。保留架构替换原因即可，不复制全部旧参考清单。

## P2 / Active · 记忆与文档检查入口

```powershell
python scripts/check-handoff.py
git diff --check
```

检查器只读文档、维护 JSON、正式 APK/元数据与 Manifest，不下载、不启动应用、不读私钥/个人库。专题少，HANDOFF 直接索引五份文件，无需 INDEX。记录新知识前按根协议判断价值，无新增知识无需改专题；不要新建日期流水总结或另立维护协议。

当前检查器假设开发 Manifest 与正式版本一致，分离开发后会报错；具体条件及处理边界见 pitfalls F16。报告原始结果，不能称全部通过，也不为了记忆检查重建或替换正式包。
