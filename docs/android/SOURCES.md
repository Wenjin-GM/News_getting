# 新闻来源维护与公开出口

核对日期：2026-11-12。唯一运行配置是 [sources.json](../../config/sources.json)，不要编辑生成的 android/assets 副本。当前只获取公开列表中的标题、链接、时间及公开摘要，原文交由浏览器；RSS 可用不代表原文免费或手机网络必达。

| 来源 | 本轮实际打开的公开出口 | 本机 HTTPS 结果与解析 |
| --- | --- | --- |
| 纽约时报 | [官方 RSS](https://rss.nytimes.com/services/xml/rss/nyt/HomePage.xml) | 200，解析21条 |
| 华尔街日报 | [Dow Jones 国际 RSS](https://feeds.content.dowjones.io/public/rss/RSSWorldNews) | 200，解析40条（应用上限） |
| 华盛顿邮报 | [官方国际 RSS](https://feeds.washingtonpost.com/rss/world) | 200，解析13条；[官方订阅说明](https://www.washingtonpost.com/information/2026/05/21/washington-post-rss-feeds/) |
| 卫报 | [官方国际 RSS](https://www.theguardian.com/world/rss) | 200，解析40条；[官方 RSS 说明](https://www.theguardian.com/help/feeds) |
| 金融时报 | [官方国际首页 RSS](https://www.ft.com/rss/home/international) | 200，解析11条 |
| 塔斯社 | [官方 RSS](https://tass.com/rss/v2.xml) | 200，解析40条；链接为CDATA，已补解析与危险协议反例 |
| 新华网 | [官方国际栏目 JSON](https://www.news.cn/world/ds_8d5294ed513c4779af6242a3623aa27b.json) | 200，响应约1.49MB，含1000个候选，解析40条；有当日发布时间 |
| 路透社 | [本轮候选 feed](https://www.reutersagency.com/feed/?best-topics=world&post_type=best) | 404；根 feed 也404，未加入配置 |
| 美联社 | [本轮候选 RSS](https://apnews.com/index.rss) | 403，未加入配置 |
| 法新社 | [本轮候选 RSS](https://www.afp.com/en/rss.xml) | 403，未加入配置 |

后三项仅表示这些候选出口本轮不可用，不据此断言厂商绝无公开出口。未引入Google News、第三方RSS转换后端、共享登录、付费墙绕过或TLS例外；当前并未实现这三家的稳定采集。

新华网保留 source ID `xinhuanet`，用当前国际栏目 JSON 替换停在2022年12月的旧RSS，显示名改“新华网 · 国际”。端点来自栏目自己的 [加载器](https://www.news.cn/world/dw.js) 的 `./ds_<datasource>.json` 规则及 [栏目页](https://www.news.cn/world/) 的推荐列表标识。只读JSON，不执行脚本；不抓报道正文。`publishTime` 按上海时区解析，不用采集时间代替。JSON中HTML标题清理为文本，条目链接仍过publicURL校验。继续遵守5MiB、JSON深度、来源主机约束和失败保留旧快照；这不是任意新华网接口或历史分页器。

InfoQ中国按用户新指令整源进入AI专栏，authority=media，旧缓存与归档依据现来源定义展示，source/article ID及收藏、已读不重写。检查其余来源后，量子位、AIHOT、两个媒体AI子流本来就在AI；博客园、IT之家、爱范儿、TechCrunch等仍含大量非AI内容，继续逐篇AI主题分流，不擅自全搬。

本机请求与解析证据保留在被忽略的 `.local/reports/source401-*`。仅证明本次宿主网络和应用解析器；没有用用户手机逐源验证，无法保证国内各运营商直连。公开RSS的发布时间、更新频率及可用性由上游决定，接入不等于“全网最快、全覆盖”。
