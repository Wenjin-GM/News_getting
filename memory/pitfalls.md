# 高价值失败经验

增量核实：2026-11-09。F01–F13 多为迁移保留的历史经验，未在本轮重现；F14–F16 为当前会话新增核对。区分实际失败、源码证据与待真机验证的修正，新故障先确认版本与环境。

## F01 · P1 / Active · 方案看似可安装，实际仍依赖云

- 条件/尝试：先提供 PWA 安装入口，再做 WebView 2.1 APK；曾公开原先所有者私有的站点。
- 结果：仍有远程站点/云采集前提，不符合用户最终“安装、联网、应用自身完成”的要求。
- 根因与解法：安装形式不等于运行独立；3.0 将 UI、采集和资料改为手机原生，具体决策见 D01。
- 防复发：不能把 PWA、公开站点或网页壳当替代交付。证据：当前三个 Java 类、Manifest、安卓验收报告。

## F02 · P1 / Active · 增量编译混入旧外壳类

- 条件/尝试：从 WebView 壳改原生时沿用 classes/dex/gen 输出目录。
- 结果/根因：历史记录指出旧内部类可能残留 DEX，源码删类不等于输出清理。
- 解法：build.py 在验证生成目录边界后清理再编译，检查 DEX 中禁止的旧依赖。
- 适用/证据：改类结构或架构后构建；`scripts/android/build.py`。不要扩大删除范围至签名/个人状态。

## F03 · P1 / Active · 重建哈希与升级身份不是一回事

- 条件/尝试：将同源重建包与已交付包比较哈希，或在新克隆生成密钥后尝试覆盖升级。
- 结果：同版重建可因 ZIP 时间等不同而哈希变化；不同签名不能覆盖官方安装。
- 根因/解法：文件字节身份与签名身份分离；保持正式 release 不动，以原密钥维护升级、元数据核对公开指纹。不是每次哈希差异都由时间戳造成，须按实际构建核查。
- 证据：build.py、release.json、C01。不得删密钥来“修复”升级，也不得自动替换正式 APK。

## F04 · P1 / Active · 停更 fixture 的星期与日期冲突

- 条件/尝试：旧测试只改 RFC 1123 时间的年份，没有改星期。
- 结果/根因：Java 严格解析失败为 0，测试没有实际覆盖预期的停更条件。
- 解法：修 fixture 中有效日期，保留生产日期校验；不能为测试通过关闭时间验证。
- 证据：`NativeEngineTest.java` 与 `NewsEngine.date/parse`；旧故障来自历史记录，本次未重演。

## F05 · P1 / Active · 内容解析正确性易回归

- 条件/尝试：直接剥标签、选 Atom 首个链接，或将所有科技 RSS 计为 AI。
- 结果：编码标签残留、把 rel=self 当原文、AI 栏混入非 AI；长 JSON ID 若转浮点也会丢精度。
- 根因与解法：先处理 CDATA/实体再清理脚本/样式/标签，Atom 优先 alternate，媒体 AI 关键词筛选，长 ID 保持字符串，稳定 ID 不随刷新时间重建。
- 适用/证据：改解析器时保留有意义的 fixture；`NewsEngine.clean/rss/rows/parse`、`NativeEngineTest.java`。

## F06 · P1 / Active · 失效来源不能伪装实时成功

- 条件：普通 RSS 停更多日；低频官方 AI 更新；离线强制刷新所有来源失败。
- 错误方向：统一七天阈值会误杀 AI；清空失败快照会丢用户已有内容；用采集时间代替发布时间会制造新鲜度。
- 解法：普通 RSS/AI 分开判定，保留 dateOnly 和未知时间，失败保留 items/fetched 并更新 checked/error。
- 证据：C04、安卓验收中断网 29 源失败仍保留新闻/收藏。不能据旧验收声称本次来源全成功。

## F07 · P1 / Active · 中文启动器与 PowerShell 版本

- 条件/尝试：Windows PowerShell 5 读取没有 BOM 的 UTF-8 中文脚本/文件名；在双引号里拼多行代码替换。
- 结果/根因：可能误读中文路径，或反引号、`$变量` 提前展开而改错代码（历史记录）。
- 解法：现有 ps1 保留 UTF-8 BOM/CRLF，用字面 here-string/结构化文件写入并读回；JSON.stringify 不是 shell 转义。
- 适用/证据：兼容 PS5 时不能只以 PS7 结果为准；`.gitattributes`、现有脚本编码。

## F08 · P1 / Active · 整理后源码与便携包路径不同

- 条件/尝试：将 Go/脚本移入 desktop、共享资料移入 internal 后，继续按旧根相对路径运行。
- 结果/根因：import/embed、资源复制、启动器和包内 data 定位可能错位。
- 解法：共享目录唯一维护源、同步各端路径、增加跨端 registry 测试；paths.ps1 分源码与便携包定位，stop 校验进程文件。
- 证据：`docs/STRUCTURE.md`、`desktop/registry_test.go`、`scripts/desktop/paths.ps1`、整理检查与烟测。

## F09 · P1 / Active · 恢复 ZIP 的早于 1980 时间戳

- 条件/尝试：整理前用 Python zipfile 备份具有早期时间戳的源文件。
- 结果/根因：历史备份创建因 ZIP 时间范围限制失败，不能把部分产物当有效恢复点。
- 解法：当时用 `strict_timestamps=False` 创建并做 CRC 校验；以后使用该格式遇到同类日期再采用，不是永久禁止其他备份工具。
- 证据：`.local/maintenance/reorganize.py`、原恢复 ZIP 与整理记录；本次不解包或重新扫描私有历史。

## F10 · P1 / Active · GitHub API 限流不代表上传失败

- 条件/尝试：推送后用匿名 API 查询，遇到 403 rate limit。
- 结果/根因：API 观察渠道限流，Git push 与 raw 可用；不等于仓库权限错误。
- 解法：当时以远端 ref 与匿名 raw README/APK 哈希核验；不为此新建 PAT、扩大权限或改可见性。
- 证据：HEAD `f51c8e7` 与历史上传记录；本次没有联网重新核对远端。

## F11 · P2 / Active · 模拟器现场和工具状态不可复用

- 条件/尝试：独立 ADB 5038 看不到设备；初始化截图/动画滞后一帧；试图复用旧 REPL/窗口坐标。
- 结果：可误判 APK 失败，或重复点击；工具检测用户输入时继续抢控制也不合适。
- 解法：历史需连接 `127.0.0.1:5561` 再选 emulator-5560；重新观察当前 UI，尊重用户控制权，按当次工具规则操作。隔离方法见 conventions。
- 适用/证据：仅本次创建的虚拟设备；历史模拟器已停止并清理，旧会话 ID 不是项目状态。

## F12 · P2 / Active · 避免无谓依赖与误判告警

- 条件/尝试：早期图标生成依赖 Pillow；JDK --release 8 告警、终端中文乱码被当构建失败。
- 解法：当前复制现有 PNG，Python 仅标准库；根据退出码、实际签名/包内容判断，不全局装图像库或仅凭乱码判 APK 损坏。
- 证据：build.py 的图标复制、javac 参数和验证步骤。编译告警是否无害仍须按具体输出判断。

## F13 · P1 / Active · 旧记忆检查器会固化重复体系

- 条件/尝试：旧 checker 固定十个编号专题与来源/工具/平台重复表，维护 JSON 后必须再维护记忆快照。
- 结果/根因：旧框架把可派生事实复制成第二套权威，迁移后继续运行会报错或诱导恢复旧文件。
- 解法：此次迁移改为从 HANDOFF/可选 INDEX 发现主题、检查优先级/状态/链接与实际资料；不要求复制目录全表。新规范唯一在根协议文件。
- 证据：`scripts/check-handoff.py`；旧一次性表生成脚本在迁移验收后删除。

## F14 · P1 / Active · 多选集合正确仍可能显示互斥项同时勾选

- 条件：原生 AlertDialog 的“全部”与具体类别互斥，其余类别多选；用户在 3.5 首包真机仍报告未修好。
- 已尝试：ReadingRules.choose 不再因程序取消 All 而清集合，Activity 加 syncing 回调保护；JVM 只测集合，仍遗漏 UI 适配器状态，不能称为控件问题已解决。
- 代码证据：pickMany 传给 setMultiChoiceItems 的 checked[] 只在初始化赋值；程序更新 ListView 未同步数组。AOSP AlertController 点击只更新被点数组槽位，getView 重绘又用数组的 true 恢复勾选，因此残留 All 或具体项可再次亮起。此遗漏已确认，但未在用户手机完整复现。
- 当前修正：ReadingRules.syncChoices 先同步整个同一数组，再设置全部 ListView 项；初始化也用此方法，仍保留同步保护。JVM dialogChoiceState 覆盖两向互斥、连续多选、空选/最后一项、重开初始状态；实际 UI 重绘与重开仍待用户复验。
- 防复发：集合、适配器数组与控件须一致；不要只测纯集合就宣称真机通过。证据为 MainActivity.pickMany、ReadingRules.syncChoices、NativeEngineTest.dialogChoiceState，以及 [AOSP 源码](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/com/android/internal/app/AlertController.java#1220)（会话中已打开核对）。

## F15 · P1 / Active · 动态检查时间不能刷新整份资料的事实日期

- 条件/误解：模型页沿用导入快照日期，用户询问为何仍是旧日期，容易将联网成功与资料事实更新混同。
- 核实：动态拉取没有重新评估模型特点/厂商优劣势。仅改 asOf 或 checkedAt 会制造虚假复核；多个日期各有语义，见 contracts C06。
- 当前方案：最近成功获取与内置资料分开；新发布只提示来源链接，不改旧资料。用户随后要求移除列表/详情日期文案，已仅改显示，底层快照不动；不是授权删掉事实日期或把资料改成今天。
- 证据：ReadingRules.latestModelCheck/newModelNotices、NativeEngineTest.deviceCorrections、维护 JSON；这些测试证明不改写结构，不能证明每项事实仍然有效。

## F16 · P1 / Active · 正式包与开发源码版本不同会触发旧交接检查器

- 条件：保留正式 3.0.0，继续交付 dist 下 3.5.0-dev；scripts/check-handoff.py 仍把 Manifest 版本等同 releases 元数据。
- 根因/影响：静态检查器的发布同版假设已过窄，版本不一致不等于交接失真。正式资产与维护模型资料不同也会有预期警告。
- 处理边界：核对正式包和测试包各自哈希、身份、权限及记忆索引，原样报告失败；不改回开发版本、不替换正式 APK 来凑通过。后续可经授权让检查器区分两种身份，本轮不修改脚本。

## P3 / Obsolete · 已退役层的临时排障

旧 Vinext 的 Node 要求、manifest 登录凭据、Service Worker、CSP nonce、隐藏 stdin CR/LF 等均属于已删除 PWA。只保留该适用性边界，不迁移临时会话 ID、命令流水或失效部署步骤，不列为原生待办。
