# 一览 · 项目交接入口

更新时间：2026-11-09（Asia/Shanghai）。先读 [AGENTS.md](AGENTS.md)，再读本文件与相关专题；记忆维护规范仅由 [MEMORY_PROTOCOL.md](MEMORY_PROTOCOL.md) 定义。

## 项目概览

主产品为原生 Android 独立 APK，手机自行采集、解析、展示新闻；Windows / Go 版独立保留，不是手机后端。架构与职责见 architecture。

## 当前产品与 Git 状态 · P1 / Active

| 项目 | 当前本地核实 |
| --- | --- |
| Android 源码与测试包 | `3.7.0-dev` / code `30700`，`cn.yilan.news`，Android 8.0+ / target36 |
| 当前测试 APK | [yilan-standalone-3.7.0-dev.apk](dist/android/yilan-standalone-3.7.0-dev.apk)，4,666,368 字节；图片背景/玻璃面板版，待真机验收 |
| 测试包身份与报告 | [dist 元数据](dist/android/release.json)、[签名与权限报告](dist/android/android-verification.txt)；哈希以元数据为准 |
| 正式 APK（未替换） | `3.0.0` / code `30000`，[正式 APK](releases/android/yilan-standalone-3.0.0.apk)，62,219 字节 |
| 正式发布身份 | [release.json](releases/android/release.json) 与同目录 SHA 文件；正式下载仍指向 3.0.0 |
| 来源配置 | 36 源，默认29（11综合+18AI），7个实验源默认关；维护源 `config/sources.json` 未扩充 |
| AI 资料 | 16平台 / 31工具，checkedAt=2026-10-28；另有模型结构，字段与日期语义见 contracts C06 |
| Windows | 保留 1.1.0；本阶段未修改或重新验收 |
| Git | `main`；3.5 验收基线为 `476788a`，3.6/3.7 UI 与本交接随本轮提交保存；当前提交号查 `git log -1`，不在自身提交内硬编码 HEAD |
| origin | `https://github.com/Wenjin-GM/News_getting.git`；用户已明确授权本轮提交/推送，实际远端身份以 `git ls-remote origin refs/heads/main` 核对 |

本轮提交范围为 3.6/3.7 源码、原生主题/背景资源、测试及记忆文档。`AI情报站/` 的私有部署文件、REFRESH.md 与 `.scratch/` 保持本地未跟踪；`.local/`、`.cache/`、`dist/` 不提交。以实际 Git 状态为准，不清理未跟踪内容。

## 当前工作断点与下一步 · P1 / Pending

1. 用户已确认 3.5 修正版真机复验通过；随后明确授权先提交 Git/GitHub 再升级 UI。已推送 `476788a`，包含公开工程与交接资料；签名/个人状态、临时检索 `.scratch/`、私有部署说明保持本地，未替换正式包。
2. 当前交付为 3.7：依据 3.6 真机截图移除分页大标题、修正底栏文字/图标中线、筛选改为靠左纯文字；新闻/AI/收藏分别内置生成背景，日夜渐变遮罩与半透明泛光面板，设置不铺图。细节及生成资源见 [界面设计与验收](docs/android/UI_DESIGN.md)。这些 UI/资源与本交接均纳入本轮 Git/GitHub 保存范围；提交/推送不等于真机验收或正式发版。
3. 下一步是 3.7 真机验收：底栏中线、纯文字筛选功能、三页背景/设置无图、玻璃效果/文字可读性，及主题重开、大字、系统栏与覆盖安装数据。当前截图是修正前证据，不能写成 3.7 通过。
4. 用户最新指令优先于 [TODO](docs/TODO.md)：其中要求列表展示模型资料日期的条款已被“去掉这句话”取代；JSON 日期保留，模型对比标题仍带快照日期。TODO 原文未在本轮改写。
5. 本轮用户授权为提交 GitHub 与整理记忆。完成后等待 3.7 手机反馈或新指令；新增来源、完整情报视图、端侧模型实验和正式发版不自动开展。

## 已有验证与边界 · P1 / Active

- 3.7 开发轮实际执行 JVM 测试、APK 构建和差异检查通过，构建通过原签名/INTERNET-only 校验。本轮提交前再跑 `python scripts/android/test.py`，核对既有 APK/元数据/内置资源，不重建。测试含主题兼容、阅读数据保留、场景映射、透明度及叠层对比度；Go 检查是 3.5 基线提交前的历史证据。
- 3.5 修正版交付时已核对 `syncChoices` 与资料标签移除；该轮随后获用户真机通过。3.6/3.7 沿用采集/归并规则，内置维护资料未改；不能把先前手机通过延伸为新 UI 通过。
- 用户已确认早期热度最小版覆盖安装后收藏、已读、来源开关仍在；TODO 记载无模型精选已通过手机验收。这些证据不自动延续到当前修正版。
- 2026-10-29 的 3.0 默认29源成功、模拟器断网保留缓存、Go/Windows 烟测等是历史证据，入口仍为 [安卓验收](docs/android/VALIDATION.md)、[直连记录](docs/android/direct-network-test.json)、[整理检查](docs/REORGANIZATION_CHECKS.md)。本阶段未重新证明全源可达、全机型兼容或安全认证。
- 正式 APK 和当前测试 APK 的哈希均与各自元数据匹配。维护目录含未发布模型资料，与正式包资产不同属预期差异。测试 APK/报告链接只在原工作区有效，Git 不含 dist；新克隆需按 conventions 构建，不能认为 GitHub 正式下载已更新到 3.7。
- 本轮 `python scripts/check-handoff.py` 退出 1，唯一错误为 Manifest/正式包版本不一致，另有未发布资产差异警告；5 个专题索引及文档链接/敏感模式等其余检查未报错。`git diff --check` 通过。检查器仍强制开发/正式同版，不得为通过检查改回版本或替换正式包，见 pitfalls F16。

## 风险与待确认 · P1 / Pending

- 3.6 用户已提供真机截图并反馈对齐/视觉调整，3.7 尚未复验。上一轮官方 Google 镜像下载 TLS 握手失败，本轮未重试、未启动模拟器或 ADB；具体限制见界面设计文档。手机型号、Android 版本和多网络表现未记录。
- 精选只整理本机实际抓到的记录；公开源通常只返回最新一批，缺口再取不意味着有任意历史分页。归并、归档容量和停止条件见 contracts C08。
- 模型资料没有逐条重新事实核验。新模型提示是有限标题规则，不能宣称发现所有新发布，也不能据动态更新时间声称旧优势、劣势已复核。
- linux.do、微博、X、旧人民网时政 RSS 维持未启用/未接入；不将先前探测结果描述成永久可用性判断。新增来源、完整情报站和端侧实验仍属待办，须新指令。
- 项目 LICENSE、备份/云同步等长期候选见 decisions D10；旧云站点未执行下线，本地旧网页删除不能证明远端消失。

## 专题索引（单一知识所有权）

| 专题 | 何时读取 / 权威内容 |
| --- | --- |
| [architecture](memory/architecture.md) | 模块、数据流、共享维护源、文件职责 |
| [decisions](memory/decisions.md) | 用户确认需求、技术取舍、被替代的方案 |
| [contracts](memory/contracts.md) | 数据/时间/ID、归并/归档、网络/权限、兼容与发布 |
| [pitfalls](memory/pitfalls.md) | 高价值失败、根因证据、已尝试修正与适用限制 |
| [conventions](memory/conventions.md) | 环境、构建/测试/发布操作、私有路径 |

五专题由本文件直接索引，无需 INDEX；P0 执行规则只由 AGENTS 完整维护。

## 接续与记忆沿革 · P2 / Active

- 新任务先核对 Git、相关源码与用户最新指令。README 是正式安装/开发入口，可能未描述未发布功能；TODO 是需求记录，不是自动执行授权。
- 现有五专题保留单一知识归属；本轮整理更新交接与高价值避坑，不创建日期型总结或扩张核心硬规则。生成工具默认工作区外缓存的实际行为及防复发边界见 pitfalls F18。
- 旧十专题已合并为五专题：架构→architecture，字段/安全/持久化→contracts，产品与候选决策→decisions，操作与出处→conventions，失败→pitfalls，当前状态→本文件。保留合并后的知识，不恢复旧重复表格。
- 文档维护执行 `python scripts/check-handoff.py` 和 `git diff --check`，准确报告检查器的版本假设错误；无需为记忆维护联网采集或重建 APK。
