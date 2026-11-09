# AI 平台情报台 · 本地版

与线上一致的完整静态快照（无后端、无外部依赖），改数据或改样式后刷新浏览器即可生效。

## 启动

双击 `启动本地站点.bat`，或手动执行其一：

- `node serve.js`（推荐，需安装 Node.js）
- `python -m http.server 8765 --bind 127.0.0.1`

然后浏览器打开 http://127.0.0.1:8765 。端口被占用时：`set PORT=8888 && node serve.js`，或把 python 命令里的 8765 换成其它端口。

注意：必须通过 HTTP 访问（上面的方式），直接双击 index.html 用 file:// 打开时部分浏览器会拦截 script。

## 文件说明

| 文件 | 作用 |
| --- | --- |
| `index.html` | 入口页面 |
| `styles.css` | 全部样式（深色仪表盘） |
| `app.js` | 渲染层，纯前端、不联网 |
| `data/dataset.js` | **唯一数据源**（`window.AI_DATASET`），改数据只动这个文件 |
| `serve.js` / `启动本地站点.bat` | 本地静态服务器 |

## 改数据

`data/dataset.js` 里的每个条目都带 `sourceIds`（指向 `meta.sources[].id`）和 `confidence`
（`verified` = 命中官方来源且一致；`partial` = 数字未完全对应或仅二手；`unverified` = 仅供线索，不可作为决策依据）。

- 概览顶部显示 `meta.datasetVersion` 与 `meta.asOf`；改完数据记得更新这两个字段。
- `meta.status` 改为 `"draft"` 时页面顶部会出现草稿横幅，便于内部预览。
- `meta.gaps[]` 是"已知缺口"，始终展示，用于记录没核到的信息。
- 厂商条目（`vendors[]`）分 `cn`（国产主力）/ `tier2`（第二梯队）/ `intl`（国际对照）三组，页签与配色自动跟随。
- 时间线（`events[]`）按 `date` 倒序渲染，日期必须是 `YYYY-MM-DD`。

## 与线上站点的关系

本目录 = 线上站点（ai-platform-intel-f03vnqw5jz0.qoder.zone）发布时刻的完整快照，
其中 `index.html` 与线上发布物逐字节一致。开发目录（含进行中的数据刷新）在项目根的 `web/`，
两边互不影响；本地想用新数据时，把 `web/data/dataset.js` 覆盖到 `local-site/data/` 即可。
