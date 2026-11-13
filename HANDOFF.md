# HANDOFF — 跨会话交接

增量核实：2026-11-13。硬规则见[AGENTS](AGENTS.md)，记忆维护按[MEMORY_PROTOCOL](MEMORY_PROTOCOL.md)。历史证据不能当本轮复验。

## 当前状态

- 主产品原生Android独立APK，cn.yilan.news，4.2.1-dev / 40201，minSdk26 / targetSdk36，仅INTERNET；基础采集无需密钥，可选AI使用用户配置。
- 测试包dist/android/yilan-standalone-4.2.1-dev.apk，12,334,480字节，SHA-256 af4efe40475b6ccb5b80264dab273986a470f684719c8266f5cac45f39ca8729；沿用原签名v2/v3，证书9ec80e3b71cd34a0c642da7d9e3399b59ae8d6c955d36209b43b7a89a5c442f9。
- 正式包仍3.0.0 / 30000，62,219字节，正式目录及下载链接未替换。本轮所有assets与三ABI.so和4.1.1逐字节相同，新增日间drawable图片；目录正文与日期未改。
- 来源共71源，默认70（50综合+20AI，仅linuxdo默认关闭），7 experimental；news11 / tech23 / finance3 / community9 / hot5 / ai20。来源事实由docs/android/SOURCES.md维护。
- AI目录16平台 / 31工具，checkedAt=2026-10-28，modelData.asOf=2026-10-21，未伪装更新。
- Git保持main；本轮提交前基线4e22ea4fb7a60787d17b110d3ad6936c629eff86。用户已明确授权更新记忆、提交并推送4.2/4.2.1前端工程；本交接随修复提交，最终提交号以git log/远端核对为准。已有用户资料保护不覆盖。
- 上轮提交已包含此前4.0.1–4.1工程/共享来源/Go/Rust更新。本轮只改Android界面/外观偏好/对应测试、背景与文档；用户linux.do/、AI情报站三个资料入口留本地不提交，.local/.cache/dist忽略。

## 本轮完成：4.2.1简化设置与聊天键盘修正（D28 / C10 / F39）

- 移除设置标题下的说明小字，仅同排保留启用状态/来源数。配置表单去AI功能大标题与两个页签，必要URL/Key/模型/测试/保存仍在。
- 接口配置和聊天拆成两个独立窗口；新增AiChatDialog非浮动主题，聊天记录独立滚动、输入与相隔20dp的发送/清空固定底部，API30+按IME Insets上移，26–29有adjustResize/可见区域兜底（未实测旧系统）。
- 原生左右气泡不加“你”，等待回复不禁用输入；改scrollTo避免滚动转移输入焦点，清空也清草稿，ticket仍拦迟到回复，模型/调用/临时存储约束不变。
- 更新MainActivity/AiSettingsDialog/AiChatPanel/ReaderIcon/日间styles及ReaderFeatureSmoke，新增ChatKeyboardSmoke和build-smoke.py --chat。来源/日期缓存/分析流程/目录与原生库均未改。

## 保留的4.2前端整理（D27 / C10 / F38）

- 设置按外观/AI功能/缓存管理/消息来源四个同级标题与卡片组织，AI配置与对话双入口、模型状态明显；缓存卡有内容日期摘要及日历入口。来源排序/开关/浏览器跳转和清理语义不变。
- 新增明亮日间三场景atlas，新闻为日光城市、AI为玻璃神经网络、收藏为晶体长廊；夜间原图保留。主题卡有场景预览，背景插画可关；昼夜切换在同一页面也会重载正确资源。
- schema3仅新增backgroundArt/motion两个默认true的可选偏好，保留大字/未读。动效支持320ms可中断的搜索收窄/更新扩展及逆向、160ms阶段文字淡入、180ms导航入场；阶段变化不重复伸缩，进度真实且占位不跳。
- 尊重关闭动效及系统动画开关，后台/重建稳定布局并清焦点；导航清查询避免双重构造。业务采集/AI时序、缓存/ID、来源/目录正文与原生传输未改。
- 新增ReaderUpdateTransition、SurfaceSmoke、scene_day_atlas.png；更新MainActivity/ReaderBackdrop/ReaderTheme/NewsState/ReaderIcon/AiSettingsDialog及UI测试、build-smoke.py --surface；完整契约见C10。

## 保留的4.1.1历史缓存修复（D26 / F37）

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
- 默认精选仅生成当天，前两日仅读已选缓存；逐日刷新/折叠、通用HTTPS兼容API设置与独立锁定模型临时聊天保留，详细契约由docs/android/AI_API.md维护。
- 上轮新增AiHistory/AiResultStore、HistoryStorageTest/HistorySmoke；本轮未改历史存储业务。
- 新闻状态仍native-news-v3.json/schema3、归档news-archive-v1.json/schema1。AI索引沿用ai-results-v1.json，新增dailyFiles/historyChecks，日文件ai-day-<SHA256>.json。
- API密钥仍Keystore AES-GCM私有密文，日文件不含Key。缓存日文件名只由内容哈希产生，删除限固定日文件模式，不放松TLS/权限或引入WebView。
- 归档最多12000条/16MiB、译名1500项/约1MiB、批次96项/384KiB，仍为有界本地存储。正式包、签名文件、用户参考资料、本轮来源/原生传输与目录正文未改。

## 实际验证与证据

- python scripts/android/test.py通过19组，原日期/保存/身份与外观偏好/对比度回归通过。
- python scripts/android/build.py成功，原签名40201、仅INTERNET；build-smoke.py --chat/--surface/--ui/--ai/--reader独立包构建成功；测试类不进产品。
- API30隔离AVD yilan-chat421 / emulator-5590 / ADB5059：4.2.0种入收藏/已读/开关/主题，覆盖4.2.1后保留通过。仅该AVD启用LatinIME软键盘与硬键盘同显，不改个人手机。
- ChatKeyboardSmoke真实触摸通过：IME展开不收起直接发送和清空、多行草稿、按钮完全位于键盘上方且间隔至少12dp、气泡无前缀、固定模型、输入焦点保留、清空后迟到回复不复活、日间/夜间。
- SurfaceSmoke四分组/独立配置与聊天及日历入口、日夜/插画、宽度过渡/逆向/打断/阶段计数/关动效回归通过。
- UiSmoke/AiSmoke/ReaderFeatureSmoke通过，含原稳定顶栏/主题中刷新、固定模型/阶段屏障/即时汇报、翻译、历史复用/清理/重启等回归。Rust29项/Go/HistorySmoke仅是4.1.1历史证据，本轮未改这些业务、未重跑。
- 当次证据.local/reports/chat421-runtime.json、chat421-package.json、chat421-screens/；初次窗焦点前程序拉键盘失败、实际发送后fullScroll抢焦点的失败报告保留。最后另以宿主/ui同步日夜主题复验ChatKeyboardSmoke通过，最终日夜图已替换，工具结果可查；F39记录夹具/焦点陷阱。
- 开发包全部assets/三ABI.so与4.2.0相同、正式哈希未变、无测试Key/runner；配置无标题/页签、设置无说明、日夜键盘/气泡截图已打开核对。无真实Key/付费调用/个人手机操作。
- git diff --check通过；记忆索引/链接有效。check-handoff.py仍仅因开发Manifest与正式版本不同报错，并提示pitfalls篇幅/来源异于正式包；不为通过检查替换正式包。
- 提交维护轮只更新授权/断点并审计工程与既有测试证据，不重建APK、不把历史模拟器结果称本轮新实机验收；日间图/两独立聊天及动效测试类入库，.local/.cache/dist和用户参考资料不入库。
- 工具/报告/新AVD在工作空间，专用模拟器和ADB交付前关闭；未改宿主网络/证书或安装系统工具。内置成图工具自动另存原图于其默认生成目录，已复制工程资源；应用不引用外部副本，完整prompt在assets/android-background-prompts.json。

## 工作断点与风险

- 用户需真机复验聊天窗布局、中文/厂商输入法键盘内发送/清空和连续输入；API26–29兜底未实测，大字/所有屏幕/帧率不作保证。历史缓存/真实API真机复验仍待反馈。
- 未取得用户手机私有缓存，不能断言其实际曾发生损坏/容量满/丢失；修复覆盖源码与合成复现的条件。
- 历史适配器仍无通用按日期分页，未抓到的旧日期不能补造；无内容检查可主动刷新。原始数据存在不代表已选过，第一次整理仍需模型调用。
- 有界存储仍可能空间不足，此时保留旧磁盘结果、新内存结果明确未保存；清理并重试保存。严重损坏只能保护和提示，不能凭空恢复已毁字节。
- 仅汉字外文等语言判断仍有启发式局限；标准兼容协议不等于所有特殊鉴权/模型别名可用，真实API耗时与译文质量待反馈。
- linux.do手机浏览量403无本轮根治证据，原生传输未改；路透/AP/AFP仍未接入，静态模型资料日期未更新，HF参考移除仍待用户决定。

## 下一步

1. 用户安装4.2.1-dev复验简化设置/独立配置/键盘内聊天；若失败提供主题/输入法/系统/字体大小与操作，不含Key。
2. 根据实际反馈继续；不绕访问限制、不放松TLS，不把候选来源/待办当自动授权。
3. 本次授权提交工程与记忆到GitHub main，正式发布另需指令；测试APK在dist/android/，原正式包不替换。继续开发前核对git status与用户参考资料。

## 记忆索引

| 专题 | 内容 |
| --- | --- |
| [memory/architecture.md](memory/architecture.md) | 原生模块、AiHistory与原子按日存储地图 |
| [memory/decisions.md](memory/decisions.md) | D01–D28，阅读/缓存及独立聊天/设置范围 |
| [memory/contracts.md](memory/contracts.md) | C01–C10，日期/清理及日夜/动效偏好 |
| [memory/pitfalls.md](memory/pitfalls.md) | F01–F39，缓存、动效与IME/聊天焦点陷阱 |
| [memory/conventions.md](memory/conventions.md) | 工作区工具、独立runner、旧包种入与证据边界 |
