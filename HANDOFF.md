# 一览 · 项目交接入口

更新时间：2026-11-09（Asia/Shanghai）。先读 [AGENTS.md](AGENTS.md)，再读本文件与相关专题；记忆维护规范仅由 [MEMORY_PROTOCOL.md](MEMORY_PROTOCOL.md) 定义。

## 项目概览

主产品为原生 Android 独立 APK，手机自行采集、解析、展示新闻；Windows / Go 版独立保留，不是手机后端。架构与职责见 architecture。

## 当前产品与 Git 状态 · P1 / Active

| 项目 | 当前本地核实 |
| --- | --- |
| Android 源码与测试包 | `3.5.0-dev` / code `30500`，`cn.yilan.news`，Android 8.0+ / target36 |
| 当前测试 APK | [yilan-standalone-3.5.0-dev.apk](dist/android/yilan-standalone-3.5.0-dev.apk)，111,371 字节；同版本修正版，用户已确认真机复验通过 |
| 测试包身份与报告 | [dist 元数据](dist/android/release.json)、[签名与权限报告](dist/android/android-verification.txt)；哈希以元数据为准 |
| 正式 APK（未替换） | `3.0.0` / code `30000`，[正式 APK](releases/android/yilan-standalone-3.0.0.apk)，62,219 字节 |
| 正式发布身份 | [release.json](releases/android/release.json) 与同目录 SHA 文件；正式下载仍指向 3.0.0 |
| 来源配置 | 36 源，默认29（11综合+18AI），7个实验源默认关；维护源 `config/sources.json` 未扩充 |
| AI 资料 | 16平台 / 31工具，checkedAt=2026-10-28；另有模型结构，字段与日期语义见 contracts C06 |
| Windows | 保留 1.1.0；本阶段未修改或重新验收 |
| Git | `main` / HEAD `f51c8e70b8e62aed9b4c869b17e32aca7e6f7635`；本轮获授权先提交并推送当前已验收工程 |
| origin | `https://github.com/Wenjin-GM/News_getting.git`；本轮未联网核实远端 |

工作区包含多轮未提交 Android、脚本、目录资料，以及用户已有 README/文档、`AI情报站/`、记忆文件等；以 `git status --short` 和实际差异为准。不要把整份差异当作下一任务的成果，不清理未跟踪内容。

## 当前工作断点与下一步 · P1 / Pending

1. 最新修正版已交付：分类对话框同时同步选择集合、`checked[]` 与列表；移除模型列表和详情的“模型资料日期”文案。用户已确认修正版真机复验通过。
2. 当前授权顺序：先将已验收工程提交 Git/GitHub，再参考 aihot.news 与 glowsunset.cn 的视觉设计升级原生 Android 界面，加入设置内可切换的日间/夜间主题。正式包不随开发构建替换。
3. 3.5 首版的日期折叠/末尾续看、缺口再取、工具版本流排除、模型预览排序已实现；用户只明确反馈分类未修好。其余项不能据未投诉写成已全部通过。
4. 用户最新指令优先于 [TODO](docs/TODO.md)：其中要求列表展示模型资料日期的条款已被后续“去掉这句话”取代；JSON 日期保留，模型对比标题仍带快照日期。TODO 原文未在本轮改写。
5. 本轮提交/推送授权针对升级前当前工程；新 UI 完成后交付测试包。候选来源、完整情报站、模型实验不在范围内。

## 已有验证与边界 · P1 / Active

- 上一开发轮实际执行 `python scripts/android/test.py`、`python scripts/android/build.py`、`git diff --check` 通过。新增测试覆盖对话框勾选数组同步、分类互斥、多选和重开状态；构建通过原签名及 INTERNET-only 检查。
- 交付时检查 APK 已包含 `syncChoices`，不再包含“模型资料日期”标签，内置目录与维护 JSON 字节一致。本次记忆维护只读核对包哈希与元数据，不重跑构建或业务测试。
- 用户已确认早期热度最小版覆盖安装后收藏、已读、来源开关仍在；TODO 记载无模型精选已通过手机验收。这些证据不自动延续到当前修正版。
- 2026-10-29 的 3.0 默认29源成功、模拟器断网保留缓存、Go/Windows 烟测等是历史证据，入口仍为 [安卓验收](docs/android/VALIDATION.md)、[直连记录](docs/android/direct-network-test.json)、[整理检查](docs/REORGANIZATION_CHECKS.md)。本阶段未重新证明全源可达、全机型兼容或安全认证。
- 正式 APK 和当前测试 APK 的哈希本次均与各自元数据匹配。维护目录含未发布模型资料，与正式包资产不同属预期差异。
- 本轮 `python scripts/check-handoff.py` 退出 1，唯一错误为 Manifest/正式包版本不一致，另有未发布资产差异警告；5 个专题索引、20 份文档链接/敏感模式等其余检查未报错。`git diff --check` 通过。检查器仍强制开发/正式同版，不得为通过检查改回版本或替换正式包，见 pitfalls F16。

## 风险与待确认 · P1 / Pending

- 用户已确认 3.5 修正版真机复验通过；下一轮视觉升级需重新验收，不能沿用旧包结论。手机型号、Android 版本和多网络表现未记录。
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
- 上轮记忆增量已完成：更新入口与五个专题，无日期型总结。当前开发遵守既有数据/安全边界。
- 旧十专题已合并为五专题：架构→architecture，字段/安全/持久化→contracts，产品与候选决策→decisions，操作与出处→conventions，失败→pitfalls，当前状态→本文件。保留合并后的知识，不恢复旧重复表格。
- 文档维护执行 `python scripts/check-handoff.py` 和 `git diff --check`，准确报告检查器的版本假设错误；无需为记忆维护联网采集或重建 APK。
