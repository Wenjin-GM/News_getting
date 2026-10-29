# 一览 · Windows / Go 版 1.1.0

这是可单独运行的电脑版。Android 独立 APK 位于仓库 `releases/android/`，手机运行时不需要本电脑程序。

## 从源码工作空间启动

先在仓库根目录执行：

```powershell
.\scripts\desktop\build.ps1
.\scripts\desktop\start.ps1
```

也可双击本目录的「启动一览.cmd」。普通启动仅监听 `127.0.0.1:8765`，打开带访问密钥的本机页面；「启动手机访问.cmd」会主动启用局域网访问，手机需与电脑在同一网络。此局域网方式是电脑版的附加入口，不是 Android 独立版的前提。

停止时运行 `scripts/desktop/stop.ps1` 或双击「停止一览.cmd」。关闭网页不会停止后台进程。

## 便携 Windows 发布包

`dist/windows/NewsDesk-1.1.0-windows-x64.zip` 是本地打包输出，不提交到源码仓库。解压后双击启动器，无需安装 Go、Node.js、Python、Docker 或数据库服务。

源码工作空间通过启动器运行时，个人数据在 `.local/runtime/desktop/data/`；便携包的数据在解压目录的 `data/`。直接运行 EXE 时，默认数据在 EXE 旁的 `data/`，也可用 `-data` 指定。

数据库、密钥和日志请保留在本机，不要上传 GitHub。迁移数据前先停止对应进程，再复制完整数据目录；不要单独删除运行中的 SQLite WAL / SHM 文件。

## 功能

- 36 个来源，默认启用 29 个；新闻、热搜、科技、财经、社区和独立 AI 专栏。
- 一键并发采集、超时与备用渠道、失败保留快照、来源诊断和历史归档。
- 收藏、搜索、JSON / CSV / Markdown 导出及受限自定义公网 HTTPS RSS。
- 16 家 AI 平台、31 项编程工具与 Harness / SDK 资料、官方依据与对比。
- 访问密钥、跨站写请求检查、本地文件边界、敏感信息不进入发布包。

更多 AI 使用说明见 `docs/desktop/AI_GUIDE.md`；便携包中为 `AI_GUIDE.md`。

## 开发与验证

以下命令在仓库根目录运行：

```powershell
.\scripts\desktop\test.ps1
.\scripts\desktop\build.ps1
.\scripts\desktop\package.ps1
node scripts/desktop/smoke.mjs
```

需要 Go 1.24+，Node.js 20.11+ 仅用于语法检查和端到端测试。所有 Go 依赖、临时和构建缓存都定向到工作空间 `.cache/`。共享 AI 目录位于 `internal/catalog/ai_catalog.json`，来源一致性测试检查安卓配置与电脑版定义同步。

源码主包：`desktop/`；网页资源：`desktop/web/`；CLI 参数：`-port 8765 -bind 127.0.0.1 -data <目录> -refresh=true`。`-probe` 探测内置来源，会在指定数据目录写诊断日志。

程序不安装系统服务、不开机自启，不修改注册表、代理、防火墙或休眠配置。启动器只增加对应的后台进程和监听端口；停止器校验进程路径后停止本目录程序。PowerShell 执行策略参数仅作用于启动的子进程。第三方许可见仓库 `THIRD_PARTY_NOTICES.md` 或便携包中的同名文件与 `licenses/`。