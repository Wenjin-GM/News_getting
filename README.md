# 一览 · News Getting

手机和电脑上的轻量新闻、热点与 AI 动态阅读器。主交付是 **Android 原生独立版 3.0**：安装并联网即可使用，手机自行采集、解析和展示新闻，不依赖 ChatGPT 平台、电脑或自建后端。

维护或让 AI 接续项目时，先读 **[HANDOFF.md 核心交接入口](HANDOFF.md)**，再按其中索引查阅 `memory/`。项目规则入口为 [AGENTS.md](AGENTS.md)，无需翻阅历史聊天。

## 安装安卓独立版

**[下载 APK 3.0.0](https://github.com/Wenjin-GM/News_getting/raw/refs/heads/main/releases/android/yilan-standalone-3.0.0.apk)** · [校验与版本信息](releases/android/release.json) · [安装与使用说明](android/README.md)

约 61 KB，Android 8.0+，无需账号或 API 密钥。可以直接转发 APK，或在应用「设置 → 分享本应用安装包」中分享。Android 的安装确认由系统处理，请保留系统安全扫描。

- 36 个内置来源，默认启用 29 个，提供新闻、热搜、科技、财经与社区内容。
- AI 专栏：平台动态、16 家平台、31 项编程工具 / Harness / SDK，可筛选与对比。
- 标题和摘要在应用内阅读，收藏、已读、搜索、离线快照、大字及紧凑模式。
- 仅申请联网权限，无 WebView、网页脚本、广告 SDK 或遥测；私人数据保存在应用沙箱。

渠道可用性取决于手机网络和源站。来源失败时保留历史内容；完整媒体原文是可选的外部浏览器入口。工具目录是随应用版本维护的资料快照。详见 [安全边界](docs/SECURITY.md) 与 [安卓验收记录](docs/android/VALIDATION.md)。

## 项目目录

| 路径 | 内容 |
| --- | --- |
| `android/` | 原生安卓源码、资源和引擎测试 |
| `desktop/` | 可独立运行的 Windows / Go 版及其网页界面 |
| `config/sources.json` | 安卓内置新闻来源定义 |
| `internal/catalog/` | 两个版本共享的 AI 工具目录与 Go 嵌入包 |
| `assets/` | 共用品牌图标 |
| `scripts/android/` | 安卓工具准备、构建、测试脚本 |
| `scripts/desktop/` | 电脑版构建、启动、测试、打包脚本 |
| `docs/` | 安全说明、目录说明、验收与截图 |
| `HANDOFF.md`、`memory/` | 项目全貌、决策、偏好、知识、状态与 AI 接续规则 |
| `releases/android/` | 已签名的正式 APK、SHA-256 与版本信息 |
| `dist/` | 本机构建结果，Git 忽略 |
| `.cache/` | 可重新生成的工具与构建缓存，Git 忽略 |
| `.local/` | 签名密钥、个人数据和本地恢复备份，Git 忽略 |

旧版 PWA / Sites 实现和模拟器镜像已从源码目录移除。[整理与路径迁移说明](docs/STRUCTURE.md) 列出了新的入口和本地数据位置。

## 开发与构建

以下命令在仓库根目录执行。当前 3.8 开发源码需要 Python 3.11+、便携 JDK/SDK 和 Rust/Android NDK；Go 版另需 Go 1.24+，网页语法检查使用 Node.js。手机运行 APK 不需要这些开发工具。正式下载仍为上面的 3.0.0。

```powershell
. .\scripts\dev-env.ps1
python scripts/android/prepare.py
python scripts/android/prepare-native.py --rust
python scripts/android/prepare-native.py --ndk-cdn
python -m pip --isolated install --index-url https://pypi.org/simple --target .cache/native/python-tools --no-cache-dir cmake==4.4.4
python scripts/android/native.py test
python scripts/android/native.py build
python scripts/android/test.py
python scripts/android/build.py
```

官方工具位于 `.cache/android/` 与 `.cache/native/`，生成物和缓存仍在工作区；APK 输出到 `dist/android/`。当前 `-dev` 升级构建要求 `.local/signing/android/` 中已有原签名材料，缺失即失败，不自动创建替代身份。新克隆不包含私钥，自行签名不能覆盖安装仓库提供的正式 APK。

3.8额外注册默认关闭的LINUX DO实验来源，开发配置37源、默认仍29。3.8.3已有用户手机取帖成功反馈；3.8.4加固版的自动解析、第三方回退、纯ECH缓存三模式本机均取得官方RSS，新版手机待复验。实现、工具和验证边界见[LINUX DO实验说明](docs/android/LINUXDO.md)，正式下载仍为3.0.0。

电脑版：

```powershell
.\scripts\desktop\test.ps1
.\scripts\desktop\build.ps1
.\scripts\desktop\start.ps1
```

也可在 `desktop/` 双击启动器。电脑版数据保存在 `.local/runtime/desktop/data/`，安卓独立版运行时不会连接它。其他功能见 [电脑版说明](desktop/README.md)。第三方许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
