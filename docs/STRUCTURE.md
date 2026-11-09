# 工作空间结构与迁移

根目录以 Android 独立版为主入口，保留 Windows 版作为单独可运行的程序。没有 PWA、Sites、Node 前端依赖或嵌套 Git 仓库。

AI 接手项目的核心入口为 [HANDOFF.md](../HANDOFF.md)，阅读规则见 [AGENTS.md](../AGENTS.md)。模块与文档身份见 [架构记忆](../memory/architecture.md)；仅维护记忆时读取根 [MEMORY_PROTOCOL.md](../MEMORY_PROTOCOL.md)。

| 原位置 | 现位置 / 处理方式 |
| --- | --- |
| `mobile/android/` | `android/` 原生源码；生成的资源进入 Git 忽略目录 |
| `mobile/scripts/*android*.py` | `scripts/android/` |
| 根目录 Go 源码、`web/` | `desktop/` |
| 根目录 `ai_catalog.json` 及其副本 | `internal/catalog/ai_catalog.json` 为唯一维护源；Go 嵌入，APK 构建时复制 |
| `mobile/lib/sources.json` | `config/sources.json`；测试检查它与 Go 来源定义一致 |
| 安卓正式 APK、SHA-256、版本信息 | `releases/android/`，可随仓库分享 |
| 构建生成的 EXE、ZIP、APK | `dist/`，不提交 Git |
| 原 `data/`、`logs/`、`exports/` | `.local/runtime/desktop/`，完整保留，不提交 Git |
| 原安卓签名密钥与密码 | `.local/signing/android/`，完整保留，不提交 Git |
| 安卓 JDK / SDK、Go 依赖与编译缓存 | `.cache/`，不提交 Git |
| 旧版 PWA、模拟器镜像、Node 依赖及过期包 | 已移出源码或删除可再生成的冗余；整理前源码备份保留于本地 `.local/archive/` |

`.local/` 仅存在于原开发工作空间或由程序在本机创建，不会出现在克隆的仓库中。整理前源码恢复备份包含旧布局和旧 Sites 的本地 Git 历史，不包含个人数据库、发布私钥或大型构建缓存。

## 唯一入口

- 安卓使用者：仓库根 README → `releases/android/`。
- 安卓开发者：`scripts/android/prepare.py`、`test.py`、`build.py`。
- Windows 使用者：`desktop/` 的启动器；数据自动定位到工作空间 `.local/runtime/desktop/data/`。
- Windows 开发者：`scripts/desktop/test.ps1`、`build.ps1`、`package.ps1`。
- 官方 APK 以 `releases/android/` 为准，本机构建不会自动覆盖该目录。

请勿提交 `.local/`、签名密钥、密码、数据库、访问链接或任何含令牌的运行日志。不要把整个开发目录打包发送给应用使用者；分享 APK 即可。
