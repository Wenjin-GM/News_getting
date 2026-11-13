# HANDOFF — 跨会话交接

增量核实：2026-11-13。硬规则见[AGENTS](AGENTS.md)，记忆维护按[MEMORY_PROTOCOL](MEMORY_PROTOCOL.md)。历史证据不能当本轮复验。

## 当前状态

- 主产品原生Android独立APK，cn.yilan.news，4.1.1-dev / 40101，minSdk26 / targetSdk36，仅INTERNET；基础采集无需密钥，可选AI使用用户配置。
- 测试包dist/android/yilan-standalone-4.1.1-dev.apk，10,732,857字节，SHA-256 5be6a9a014bea333ceabea85c2de0f09033b1331b749f5b8329b5e5edb381fd1；沿用原签名v2/v3，证书9ec80e3b71cd34a0c642da7d9e3399b59ae8d6c955d36209b43b7a89a5c442f9。
- 正式包仍3.0.0 / 30000，62,219字节，正式目录及下载链接未替换。本轮所有assets与三ABI.so和4.1.0逐字节相同，目录正文与日期未改。
- 来源共71源，默认70（50综合+20AI，仅linuxdo默认关闭），7 experimental；news11 / tech23 / finance3 / community9 / hot5 / ai20。来源事实由docs/android/SOURCES.md维护。
- AI目录16平台 / 31工具，checkedAt=2026-10-28，modelData.asOf=2026-10-21，未伪装更新。
- Git保持main；本轮起点c76764151567f3bba03b003a7ce5686353f3e20a，用户已授权修复、维护记忆、提交并推送GitHub。交接随修复提交，最终提交号以git log/远端核对为准。
- 提交包含前几轮未提交的4.0.1/4.0.2/4.1应用工程、共享来源及Go适配、已有Rust变更与本轮修复；不把全部Diff归因本轮。用户linux.do/、AI情报站三个资料入口继续留本地不提交；.local/.cache/dist忽略。

## 本轮完成：4.1.1历史缓存修复（D26 / F37）

- 日历继续表示任意内容缓存；归档、快照、译名、已选报道及空groups已选日期都能标记，不等于“新闻精选已生成”。无内容的检查元数据不冒充内容缓存。
- AiHistory合并现存归档/快照/已选原文，仍按启用源、板块及published上海日期过滤；不会把收藏倒填历史，也不写造归档获取历史。
- 每次更早扩展三日，分别判断：已有精选直接显示；本地可选日期先整理；仍缺日期逐日补查，不因一个空日重选或阻塞其余日期。
- 非分页当前列表早于目标日无可取内容时不无效重抓；无内容检查持久化，主动单日刷新可重查；后来有本地可用报道时优先整理。来源失败不记成确定无内容，旧结果保留。
- 旧聚合逐日幂等补缺，保留已有日期；迁移落盘。已选空groups同样保存并显示；缺失日展示无内容/未整理/失败状态及刷新入口。
- AiResultStore按日不可变文件+原子索引提交，取代全部精选挤在共享6MiB文件。先写日文件，索引成功后清理无引用文件，失败读旧索引；不扫描孤儿复活删除数据。
- 旧单文件格式可读，旧记录逐条验证。索引/单日各6MiB、有效AI总64MiB，不自动驱逐已选历史；容量/IO失败显示未保存和重试，收到提交回执才标已保存。
- 归档结构及记录验证后再恢复；损坏读取保留内存和原文件并禁覆盖，提供重试。AI损坏读暂停自动分析/写入，重试成功恢复；不把读取异常当空数据写回。
- 清理时保留收藏/已读/来源开关/主题/API配置；读失败待保护时不虚报清理，generation/revision防止旧写入复活；原子索引更新后GC日文件，包括受限识别的.new/.bak残留。

## 已有能力与文件边界

- 4.1外文列表中文译名/原文详情及手动翻译取消、满栏更新按钮、触摸激活搜索与关闭/后台释放焦点保留。
- 默认精选仅生成当天，前两日仅读已选缓存；逐日刷新/折叠、通用HTTPS兼容API设置与锁定模型临时聊天保留，详细契约由docs/android/AI_API.md维护。
- 本轮新增AiHistory/AiResultStore、HistoryStorageTest/HistorySmoke，修改Coordinator/Vault/MainActivity/NewsArchive、Manifest和已有ReaderFeatureSmoke；test.py/build-smoke.py增加相应入口。
- 新闻状态仍native-news-v3.json/schema3、归档news-archive-v1.json/schema1。AI索引沿用ai-results-v1.json，新增dailyFiles/historyChecks，日文件ai-day-<SHA256>.json。
- API密钥仍Keystore AES-GCM私有密文，日文件不含Key。所有新文件名只由内容哈希产生，删除限固定日文件模式，不放松TLS/权限或引入WebView。
- 归档最多12000条/16MiB、译名1500项/约1MiB、批次96项/384KiB，仍为有界本地存储。正式包、签名文件、用户参考资料、本轮来源/原生传输与目录正文未改。

## 实际验证与证据

- python scripts/android/test.py通过19组：14引擎、3AI、2历史存储；覆盖快照独有复用、分日计划、部分迁移、原子提交故障、超过6MiB重载、损坏读与残留文件保护。
- python scripts/android/build.py成功，原签名40101、仅INTERNET；build-smoke.py及--ui/--ai/--reader/--history五个独立测试包构建成功，测试类不进产品。
- python scripts/android/native.py test --offline通过29项；go test ./...通过。本轮未修改传输或Go业务，测试为纳入已有工程变更的提交检查。
- API30隔离AVD yilan-history411 / emulator-5586 / ADB5057：4.1.0种入收藏/已读/开关/主题及部分迁移旧AI文件，覆盖4.1.1后保留通过。
- HistorySmoke通过真实Android AtomicFile/Keystore的缺日补迁移/空结果、>6MiB重载、容量拒写保留旧索引/未保存提示及重试、损坏读禁覆盖/恢复、清理后重载。
- UiSmoke与AiSmoke原回归通过；ReaderFeatureSmoke通过先本地后缺日、已选零重选、快照独有零获取、无历史不造数据、归档损坏保护/重读、空结果日历及收藏保留；原翻译/焦点/聊天回归通过。
- 当次证据.local/reports/history411-runtime.json、history411-package.json、history411-screens/；初次模拟器缺目录锁错误和恢复夹具用了过时索引的失败证据保留，后者改用最后成功提交的索引，不改生产超时。重复启动错误日志压缩保留首尾。
- 开发包全部assets/三ABI.so与4.1.0相同、正式哈希未变、无测试Key/runner；截图已打开核对日历和保存标记。无真实Key/付费调用/个人手机操作。
- git diff --check通过；记忆索引/链接有效。check-handoff.py仍仅因开发Manifest与正式版本不同报错，并提示pitfalls篇幅/来源异于正式包；不为通过检查替换正式包。
- 本轮工具/缓存/报告/新AVD均在工作空间，专用模拟器和ADB交付前关闭；未改宿主DNS/hosts/代理/证书，未安装系统工具。

## 工作断点与风险

- 用户需真机复验历史前翻是否直接复用、单日刷新、日历空结果/精确清理、重启保留及真实API；隔离回归不能替代全厂商/机型认证。
- 未取得用户手机私有缓存，不能断言其实际曾发生损坏/容量满/丢失；修复覆盖源码与合成复现的条件。
- 历史适配器仍无通用按日期分页，未抓到的旧日期不能补造；无内容检查可主动刷新。原始数据存在不代表已选过，第一次整理仍需模型调用。
- 有界存储仍可能空间不足，此时保留旧磁盘结果、新内存结果明确未保存；清理并重试保存。严重损坏只能保护和提示，不能凭空恢复已毁字节。
- 仅汉字外文等语言判断仍有启发式局限；标准兼容协议不等于所有特殊鉴权/模型别名可用，真实API耗时与译文质量待反馈。
- linux.do手机浏览量403无本轮根治证据，原生传输未改；路透/AP/AFP仍未接入，静态模型资料日期未更新，HF参考移除仍待用户决定。

## 下一步

1. 用户安装4.1.1-dev按本轮缓存问题真机复验，若失败提供无Key的日期/操作/错误和“已保存/未保存”状态。
2. 根据实际反馈继续；不绕访问限制、不放松TLS，不把候选来源/待办当自动授权。
3. 正式发布另需用户指令，本轮Git推送只发布工程代码与技术文档；本地测试APK仍在dist/android/。

## 记忆索引

| 专题 | 内容 |
| --- | --- |
| [memory/architecture.md](memory/architecture.md) | 原生模块、AiHistory与原子按日存储地图 |
| [memory/decisions.md](memory/decisions.md) | D01–D26，七项阅读能力与缓存修复/提交授权 |
| [memory/contracts.md](memory/contracts.md) | C01–C09，固定模型/日期/清理及兼容约束 |
| [memory/pitfalls.md](memory/pitfalls.md) | F01–F37，失败条件、缓存审计与修复边界 |
| [memory/conventions.md](memory/conventions.md) | 工作区工具、独立runner、旧包种入与证据边界 |
