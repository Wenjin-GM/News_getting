/* AI 平台情报台 · 前端渲染层
 * 数据全部来自 data/dataset.js 的 window.AI_DATASET（Agent 策展快照）。
 * 本文件只做展示与交互，不发起任何网络请求。
 */
(function () {
  "use strict";

  var D = window.AI_DATASET || {};
  var META = D.meta || {};
  var VENDORS = D.vendors || [];
  var MODELS = D.models || [];
  var TOOLS = D.tools || [];
  var EVENTS = D.events || [];
  var GAPS = D.gaps || [];

  /* ---------- 安全与格式工具 ---------- */

  var ESC_MAP = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };
  function esc(s) {
    if (s === null || s === undefined) return "";
    return String(s).replace(/[&<>"']/g, function (c) { return ESC_MAP[c]; });
  }
  // 只放行 http/https，避免 javascript: 之类的伪链接
  function safeUrl(u) {
    var s = String(u || "");
    return /^https?:\/\/[^\s"'<>]+$/i.test(s) ? s : null;
  }
  function srcById(id) {
    var list = META.sources || [];
    for (var i = 0; i < list.length; i++) if (list[i].id === id) return list[i];
    return null;
  }
  function fmtDate(d) {
    if (!d) return "—";
    var s = String(d);
    var m = s.match(/^(\d{4})-(\d{2})-(\d{2})/);
    if (m) return m[1] + "-" + m[2] + "-" + m[3];
    var ym = s.match(/^(\d{4})-(\d{2})/);
    if (ym) return ym[1] + "-" + ym[2];
    return esc(s);
  }
  function fmtNum(v) {
    if (v === null || v === undefined || v === "") return "—";
    var n = Number(String(v).replace(/[,,]/g, ""));
    if (!isFinite(n)) return esc(v);
    if (n >= 1e6) return (n / 1e6).toFixed(n % 1e6 ? 2 : 0) + "M";
    if (n >= 1e3) return (n / 1e3).toFixed(n % 1e3 ? 1 : 0) + "K";
    return String(n);
  }
  function money(p) {
    if (!p || (p.inputPerMTok === undefined && p.inputPerMTok === null)) {
      if (!p || !p.notes) return "—";
      return esc(p.notes);
    }
    var c = p.currency === "CNY" ? "¥" : "$";
    var i = p.inputPerMTok, o = p.outputPerMTok;
    return esc(c + (i === undefined || i === null ? "?" : i) + " / " +
      c + (o === undefined || o === null ? "?" : o) + " <span style=\"color:var(--text-3)\">每百万 token</span>");
  }

  var GROUP_LABEL = { cn: "国产主力", intl: "国际对照", tier2: "国产第二梯队" };
  var GROUP_VAR = { cn: "var(--cn)", intl: "var(--intl)", tier2: "var(--tier2)" };
  var ROLE_LABEL = {
    flagship: "旗舰", production: "主力", "open-weights": "开源权重",
    reasoning: "推理", multimodal: "多模态", legacy: "上一代", other: "其他"
  };
  var TOOL_STATUS_CLASS = {
    "GA": "status-ga", "已发布": "status-ga", "公测": "status-beta", "beta": "status-beta",
    "内测": "status-beta", "预览": "status-beta", "更名": "status-dead", "停止维护": "status-dead",
    "未知": "status-unknown"
  };

  function confPill(c) {
    var v = c === "verified" || c === "partial" || c === "unverified" ? c : "unverified";
    var label = v === "verified" ? "已核实" : v === "partial" ? "部分核实" : "未核实";
    return "<span class=\"pill " + v + "\">" + label + "</span>";
  }
  function osPill(m) {
    if (m.openSource === true) return "<span class=\"pill os\">开源</span>";
    if (m.openSource === "partial") return "<span class=\"pill os\" style=\"color:var(--warn);border-color:rgba(240,169,59,.34)\">部分开源</span>";
    if (m.openSource === "unverified") return "<span class=\"pill unverified\">开源状态未证实</span>";
    return "<span class=\"pill closed\">闭源</span>";
  }
  function srcChips(ids, directUrl) {
    var out = "";
    (ids || []).forEach(function (id) {
      var s = srcById(id);
      if (!s) return;
      var u = safeUrl(s.url);
      if (!u) return;
      out += "<a class=\"src\" href=\"" + esc(u) + "\" target=\"_blank\" rel=\"noopener noreferrer nofollow\">" + esc(s.name) + "</a>";
    });
    if (directUrl && !out) {
      var du = safeUrl(directUrl);
      if (du) out += "<a class=\"src\" href=\"" + esc(du) + "\" target=\"_blank\" rel=\"noopener noreferrer nofollow\">来源</a>";
    }
    return out ? "<div class=\"srcs\">" + out + "</div>" : "";
  }
  function vendorById(id) {
    for (var i = 0; i < VENDORS.length; i++) if (VENDORS[i].id === id) return VENDORS[i];
    return null;
  }
  function modelById(id) {
    for (var i = 0; i < MODELS.length; i++) if (MODELS[i].id === id) return MODELS[i];
    return null;
  }
  function modelsOf(vid) {
    return MODELS.filter(function (m) { return m.vendorId === vid; });
  }
  function toolsOf(vid) {
    return TOOLS.filter(function (t) { return t.vendorId === vid; });
  }

  /* ---------- 导航与路由 ---------- */

  var VIEWS = [
    { id: "overview", label: "厂商总览", count: function () { return VENDORS.length; } },
    { id: "models", label: "模型矩阵", count: function () { return MODELS.length; } },
    { id: "tools", label: "配套工具", count: function () { return TOOLS.length; } },
    { id: "compare", label: "横向对比", count: function () { return MODELS.length; } },
    { id: "events", label: "动态时间线", count: function () { return EVENTS.length; } },
    { id: "method", label: "方法与来源", count: function () { return (META.sources || []).length; } }
  ];

  var state = {
    view: "overview",
    modelFilter: { vendor: "all", group: "all", os: "all", q: "" },
    modelSort: { key: "releasedAt", dir: "desc" },
    eventVendor: "all",
    compare: [],
    drawer: null
  };

  function currentView() {
    var h = (location.hash || "").replace(/^#\/?/, "");
    for (var i = 0; i < VIEWS.length; i++) if (VIEWS[i].id === h) return h;
    return "overview";
  }

  function renderRail() {
    var html = "<div class=\"rail-label\">视图</div>";
    VIEWS.forEach(function (v) {
      var n = v.count();
      html += "<button class=\"nav-btn\" data-view=\"" + v.id + "\"" +
        (state.view === v.id ? " aria-current=\"true\"" : "") + ">" +
        esc(v.label) + (n ? "<span class=\"nav-count\">" + n + "</span>" : "") + "</button>";
    });
    document.getElementById("rail").innerHTML = html;
  }

  function renderTop() {
    var html =
      "<span class=\"hide-sm\">数据集 <b>v" + esc(META.datasetVersion || "0") + "</b></span>" +
      "<span>更新 <b>" + fmtDate(META.asOf) + "</b></span>" +
      "<span class=\"hide-sm\">厂商 <b>" + VENDORS.length + "</b> · 模型 <b>" + MODELS.length + "</b></span>";
    document.getElementById("topmeta").innerHTML = html;
  }

  function draftBanner() {
    if (META.status !== "draft") return "";
    return "<div class=\"banner\"><span class=\"dot\"></span>" +
      "当前为 <b style=\"margin:0 4px\">草稿数据集</b> — 内容尚在检索整理中，数字与来源未经完整核验，请勿据此选型。</div>";
  }

  /* ---------- 视图 1：厂商总览 ---------- */

  function viewOverview() {
    var byGroup = { cn: [], intl: [], tier2: [] };
    VENDORS.forEach(function (v) { (byGroup[v.group] || byGroup.cn).push(v); });

    var html = head("厂商总览",
      "按集团队梳理定位、旗舰模型与配套工具生态。点击进入厂商档案查看优劣势分析与全部模型。");

    if (!VENDORS.length) return html + emptyState();

    var openCount = MODELS.filter(function (m) { return m.openSource === true || m.openSource === "partial"; }).length;
    html += "<div class=\"stats\">" +
      stat(VENDORS.length, "收录厂商") +
      stat(MODELS.length, "收录模型") +
      stat(TOOLS.length, "配套工具") +
      stat(openCount, "开源权重模型") +
      stat(EVENTS.length, "已记录动态") +
      "</div>";

    ["cn", "tier2", "intl"].forEach(function (g) {
      var list = byGroup[g];
      if (!list.length) return;
      html += "<h3 class=\"sec\">" + GROUP_LABEL[g] + " · " + list.length + " 家</h3><div class=\"grid\">";
      list.forEach(function (v) { html += vendorCard(v); });
      html += "</div>";
    });

    html += gapsBlock() + footer();
    return html;
  }

  function stat(k, l) {
    return "<div class=\"stat\"><div class=\"stat-k\">" + esc(k) + "</div><div class=\"stat-l\">" + esc(l) + "</div></div>";
  }

  function vendorCard(v) {
    var ms = modelsOf(v.id);
    var ts = toolsOf(v.id);
    var flag = ms.filter(function (m) { return m.role === "flagship"; })[0];
    var chips = ms.slice(0, 4).map(function (m) {
      var hot = m.role === "flagship";
      return "<span class=\"mchip" + (hot ? " hot" : "") + "\">" + esc(m.name) + "</span>";
    }).join("");
    if (ms.length > 4) chips += "<span class=\"mchip\">+" + (ms.length - 4) + "</span>";

    return "<div class=\"vcard\" data-vendor=\"" + esc(v.id) + "\" style=\"--vc:" + (GROUP_VAR[v.group] || "var(--cn)") + "\">" +
      "<div class=\"vcard-top\">" +
        "<span class=\"vcard-name\">" + esc(v.name) + "</span>" +
        "<span class=\"vcard-en\">" + esc(v.nameEn || "") + "</span>" +
        "<span class=\"vcard-flag flag-" + esc(v.group) + "\">" + GROUP_LABEL[v.group] + "</span>" +
      "</div>" +
      "<div class=\"vcard-sum\">" + esc(v.overview || "") + "</div>" +
      "<div class=\"vcard-rel\">" + (chips || "<span class=\"mchip\">模型待补</span>") + "</div>" +
      "<div class=\"vcard-foot\">" +
        "<span>旗舰 <b>" + esc(flag ? flag.name : "—") + "</b></span>" +
        "<span>工具 <b>" + ts.length + "</b></span>" +
        "<span>最近 <b>" + fmtDate(v.lastEventDate || (ms[0] && ms[0].releasedAt)) + "</b></span>" +
      "</div>" +
      "</div>";
  }

  /* ---------- 视图 2：模型矩阵 ---------- */

  function viewModels() {
    var html = head("模型矩阵",
      "各家旗舰与主力模型的参数、上下文、模态、开源许可与定价。点击行头排序，点击行查看详情与 benchmark 来源。");

    if (!MODELS.length) return html + emptyState();

    html += "<div class=\"controls\">";
    html += "<input class=\"search\" id=\"mq\" placeholder=\"搜索模型 / 厂商\" value=\"" + esc(state.modelFilter.q) + "\">";
    html += " " + groupChips();
    html += "<button class=\"chip\" data-os=\"all\" aria-pressed=\"" + (state.modelFilter.os === "all") + "\">全部许可</button>";
    html += "<button class=\"chip\" data-os=\"open\" aria-pressed=\"" + (state.modelFilter.os === "open") + "\">开源权重</button>";
    html += "<button class=\"chip\" data-os=\"closed\" aria-pressed=\"" + (state.modelFilter.os === "closed") + "\">闭源</button>";
    html += "</div>";

    var rows = filteredModels();
    if (!rows.length) {
      return html + "<div class=\"empty\">当前筛选条件下没有模型。</div>" + footer();
    }

    html += "<div class=\"tablewrap\"><table><thead><tr>" +
      th("模型", "name") + th("厂商", "vendor") + th("角色", "role") +
      th("发布", "releasedAt") + th("上下文", "contextLength") +
      "<th>模态</th>" + th("许可", "openSource") + th("定价", "price") +
      "<th>置信度</th></tr></thead><tbody>";

    rows.forEach(function (m) {
      var v = vendorById(m.vendorId) || {};
      html += "<tr class=\"mrow\" data-model=\"" + esc(m.id) + "\">" +
        "<td><b>" + esc(m.name) + "</b>" + (m.version ? " <span class=\"mono\" style=\"color:var(--text-3);font-size:11px\">" + esc(m.version) + "</span>" : "") + "</td>" +
        "<td><span class=\"vtag\">" + esc(v.name || m.vendorId) + "</span></td>" +
        "<td>" + esc(ROLE_LABEL[m.role] || m.role || "—") + "</td>" +
        "<td class=\"n\">" + fmtDate(m.releasedAt) + "</td>" +
        "<td class=\"n\">" + esc(fmtNum(m.contextLength)) + "</td>" +
        "<td>" + esc((m.modalities || []).join("·") || "—") + "</td>" +
        "<td>" + osPill(m) + "</td>" +
        "<td class=\"n\">" + money(m.pricing) + "</td>" +
        "<td>" + confPill(m.confidence) + "</td>" +
        "</tr>";
    });
    html += "</tbody></table></div>";
    html += gapsBlock() + footer();
    return html;
  }

  function groupChips() {
    var cur = state.modelFilter.group;
    return "<button class=\"chip\" data-group=\"all\" aria-pressed=\"" + (cur === "all") + "\">全部</button>" +
      ["cn", "tier2", "intl"].map(function (g) {
        return "<button class=\"chip\" data-group=\"" + g + "\" aria-pressed=\"" + (cur === g) + "\">" + GROUP_LABEL[g] + "</button>";
      }).join(" ");
  }

  function th(key, sortKey) {
    var s = state.modelSort;
    var arrow = s.key === sortKey ? "<span class=\"arrow\">" + (s.dir === "asc" ? "▲" : "▼") + "</span>" : "";
    return "<th data-sort=\"" + sortKey + "\">" + key + arrow + "</th>";
  }

  function filteredModels() {
    var f = state.modelFilter;
    var q = f.q.trim().toLowerCase();
    var rows = MODELS.filter(function (m) {
      var v = vendorById(m.vendorId) || {};
      if (f.group !== "all" && v.group !== f.group) return false;
      if (f.vendor !== "all" && m.vendorId !== f.vendor) return false;
      if (f.os === "open" && !(m.openSource === true || m.openSource === "partial")) return false;
      if (f.os === "closed" && m.openSource !== false) return false;
      if (q) {
        var hay = (m.name + " " + (m.version || "") + " " + v.name + " " + (m.summary || "")).toLowerCase();
        if (hay.indexOf(q) < 0) return false;
      }
      return true;
    });
    var s = state.modelSort, dir = s.dir === "asc" ? 1 : -1;
    rows.sort(function (a, b) {
      var x, y;
      if (s.key === "vendor") { x = (vendorById(a.vendorId) || {}).name || ""; y = (vendorById(b.vendorId) || {}).name || ""; }
      else if (s.key === "price") { x = a.pricing && a.pricing.inputPerMTok; y = b.pricing && b.pricing.inputPerMTok; }
      else if (s.key === "contextLength") { x = Number(a.contextLength) || 0; y = Number(b.contextLength) || 0; }
      else if (s.key === "openSource") { x = a.openSource === true ? 2 : a.openSource === "partial" ? 1 : 0; y = b.openSource === true ? 2 : b.openSource === "partial" ? 1 : 0; }
      else if (s.key === "role") { x = a.role || ""; y = b.role || ""; }
      else { x = a[s.key] || ""; y = b[s.key] || ""; }
      if (typeof x === "string") return x.localeCompare(y, "zh") * dir;
      return ((x || 0) - (y || 0)) * dir;
    });
    return rows;
  }

  /* ---------- 视图 3：配套工具生态 ---------- */

  function viewTools() {
    var html = head("配套工具生态",
      "各家面向开发者的代码编辑器、IDE 插件、CLI、云端 Agent 与 API 平台。这一层往往比模型本身更能体现厂商的工程投入。");

    if (!TOOLS.length) return html + emptyState();

    var byVendor = {};
    TOOLS.forEach(function (t) { (byVendor[t.vendorId] = byVendor[t.vendorId] || []).push(t); });

    var order = VENDORS.filter(function (v) { return byVendor[v.id]; });
    order.forEach(function (v) {
      html += "<div class=\"tgroup\"><div class=\"tgroup-h\"><h4>" + esc(v.name) + "</h4>" +
        "<span class=\"vtag\">" + GROUP_LABEL[v.group] + "</span><span class=\"rule\"></span></div>";
      if (v.toolNote) html += "<p class=\"prose\" style=\"margin:-2px 0 10px\">" + esc(v.toolNote) + "</p>";
      html += "<div class=\"tlist\">";
      byVendor[v.id].forEach(function (t) {
        var cls = TOOL_STATUS_CLASS[t.status] || "status-unknown";
        html += "<div class=\"titem\"><div class=\"titem-h\"><span class=\"nm\">" + esc(t.name) + "</span>" +
          "<span class=\"pill " + cls + "\">" + esc(t.status || "未知") + "</span></div>" +
          "<div class=\"titem-f\">" + esc(t.form || "—") + (t.pricing ? " · " + esc(t.pricing) : "") + "</div>" +
          "<div class=\"titem-d\">" + esc(t.positioning || t.note || "") + "</div>" +
          srcChips(t.sourceIds, t.url) + "</div>";
      });
      html += "</div></div>";
    });

    html += gapsBlock() + footer();
    return html;
  }

  /* ---------- 视图 4：横向对比 ---------- */

  function viewCompare() {
    var html = head("横向对比",
      "选择至多 4 个模型做逐项对照。对比维度包括发布时间、上下文、模态、开源许可、定价与已记录的 benchmark。");

    if (!MODELS.length) return html + emptyState();

    html += "<div class=\"cmp-pick\">";
    VENDORS.forEach(function (v) {
      modelsOf(v.id).forEach(function (m) {
        var on = state.compare.indexOf(m.id) >= 0;
        html += "<button class=\"cmp-chip\" data-cmp=\"" + esc(m.id) + "\" aria-pressed=\"" + on + "\">" +
          esc(v.name + " · " + m.name) + "</button>";
      });
    });
    html += "</div>";

    var picked = state.compare.map(modelById).filter(Boolean);
    if (!picked.length) {
      html += "<div class=\"empty\">尚未选择模型。点击上方标签开始对比。</div>";
      return html + footer();
    }

    html += "<div class=\"cmp-grid\">";
    picked.forEach(function (m) {
      var v = vendorById(m.vendorId) || {};
      html += "<div class=\"cmp-col\" style=\"border-top:2px solid " + (GROUP_VAR[v.group] || "var(--cn)") + "\">" +
        "<h4>" + esc(m.name) + "</h4>" +
        "<div class=\"mono\" style=\"font-size:11px;color:var(--text-3)\">" + esc(v.name || "") + "</div>" +
        "<div class=\"cmp-attr\">" +
          attr("角色", ROLE_LABEL[m.role] || m.role) +
          attr("发布", fmtDate(m.releasedAt)) +
          attr("上下文", fmtNum(m.contextLength)) +
          attr("参数", m.params || "未公开") +
          attr("模态", (m.modalities || []).join("·")) +
          attr("许可", (m.openSource === true ? "开源" : m.openSource === "partial" ? "部分开源" : m.openSource === "unverified" ? "未证实" : "闭源") + (m.license ? " · " + m.license : "")) +
          attr("定价", plainMoney(m.pricing)) +
        "</div>";
      if (m.benchmarks && m.benchmarks.length) {
        html += "<div style=\"margin-top:12px\"><div class=\"cmp-attr\"><div class=\"k\" style=\"grid-column:1/-1\">Benchmark</div></div>";
        m.benchmarks.forEach(function (b) {
          html += "<div class=\"mono\" style=\"display:flex;justify-content:space-between;font-size:12px;padding:3px 0;border-bottom:1px solid rgba(255,255,255,.04)\">" +
            "<span style=\"color:var(--text-3)\">" + esc(b.name) + "</span><b>" + esc(b.score) + "</b></div>";
        });
        html += "</div>";
      }
      html += "<div style=\"margin-top:10px\">" + confPill(m.confidence) + "</div>" +
        srcChips(m.sourceIds) + "</div>";
    });
    html += "</div>";
    return html + footer();
  }
  function attr(k, val) {
    return "<div class=\"k\">" + esc(k) + "</div><div>" + esc(val || "—") + "</div>";
  }
  function plainMoney(p) {
    if (!p) return "—";
    if (p.inputPerMTok === undefined || p.inputPerMTok === null) return p.notes || "—";
    var c = p.currency === "CNY" ? "¥" : "$";
    return c + p.inputPerMTok + " / " + c + (p.outputPerMTok || "?") + " 每百万 token";
  }

  /* ---------- 视图 5：动态时间线 ---------- */

  function viewEvents() {
    var html = head("动态时间线",
      "2026 年以来各家的发布、开源、定价与产品变动。每条附来源链接，便于回溯核对。");

    if (!EVENTS.length) return html + emptyState();

    html += "<div class=\"controls\"><button class=\"chip\" data-ev=\"all\" aria-pressed=\"" + (state.eventVendor === "all") + "\">全部厂商</button>";
    VENDORS.forEach(function (v) {
      html += "<button class=\"chip\" data-ev=\"" + esc(v.id) + "\" aria-pressed=\"" + (state.eventVendor === v.id) + "\">" + esc(v.name) + "</button>";
    });
    html += "</div>";

    var list = EVENTS.slice().filter(function (e) {
      return state.eventVendor === "all" || e.vendorId === state.eventVendor;
    }).sort(function (a, b) { return String(b.date || "").localeCompare(String(a.date || "")); });

    if (!list.length) return html + "<div class=\"empty\">该厂商暂无记录动态。</div>" + footer();

    html += "<div class=\"tl\">";
    list.forEach(function (e) {
      var v = vendorById(e.vendorId) || {};
      html += "<div class=\"tl-item\" style=\"--tlc:" + (GROUP_VAR[v.group] || "var(--cn)") + "\">" +
        "<div class=\"tl-date\">" + fmtDate(e.date) + " · " + esc(v.name || "") + "</div>" +
        "<div class=\"tl-title\">" + esc(e.title) + "</div>" +
        "<div class=\"tl-sig\">" + esc(e.significance || "") + "</div>" +
        srcChips(e.sourceIds, e.url) + "</div>";
    });
    html += "</div>";
    return html + footer();
  }

  /* ---------- 视图 6：方法与来源 ---------- */

  function viewMethod() {
    var html = head("方法与来源",
      "这份数据集如何产生、多久更新一次，以及如何判断每个字段可信到什么程度。选型前请先读这一页。");

    html += "<div class=\"doc\">" +
      "<h3 class=\"sec\">数据如何产生</h3>" +
      "<p>本站不是实时爬虫。每个字段来自 Qoder Agent 对厂商官网、发布博客、changelog、GitHub 仓库、HuggingFace 模型卡、arXiv 技术报告与开发者论坛的<b style=\"color:var(--text)\">人工检索</b>，再经过整理与交叉核对后写入版本化数据集。</p>" +
      "<div class=\"callout\">" +
        "<b style=\"color:var(--text)\">为什么不做实时抓取？</b> 厂商官网与论坛多为前端渲染且设有反爬，Edge 侧现拉现解会大面积超时或抓空；更重要的是，「优劣势分析」属于判断而非事实，无法由抓取产生。因此数据以带时间戳的快照入库，由定时任务周期性重新检索更新。" +
      "</div>" +
      "<p>更新节奏：" + esc(META.refreshCadence || "待定") + "。每次更新递增数据集版本并刷新「更新日期」。</p>" +

      "<h3 class=\"sec\">置信度标记</h3>" +
      "<div class=\"legend\">" + confPill("verified") + confPill("partial") + confPill("unverified") + "</div>" +
      "<ul>" +
        "<li><b>已核实</b>：本次检索直接命中厂商官方来源，且与至少一处来源一致。</li>" +
        "<li><b>部分核实</b>：来源支持主要结论但某个数字（如参数量、定价）未完全对应，或仅二手来源。</li>" +
        "<li><b>未核实</b>：未能定位可靠来源，保留仅作线索，<b style=\"color:var(--bad)\">不可作为决策依据</b>。</li>" +
      "</ul>" +
      "<p>定价与 benchmark 数字对时间敏感。若某项置信度不是「已核实」，请前往该厂商官方定价页复核后再使用。</p>" +

      "<h3 class=\"sec\">已知缺口</h3>";

    if (!GAPS.length) html += "<p>暂无记录在案的缺口。</p>";
    else {
      html += "<div class=\"sw\">";
      GAPS.forEach(function (g) {
        html += "<div class=\"sw-item down\"><span class=\"mk\">!</span><div><b style=\"color:var(--text)\">" +
          esc(g.area) + "</b><br><span style=\"font-size:12.5px\">" + esc(g.detail) + "</span></div></div>";
      });
      html += "</div>";
    }

    html += "<h3 class=\"sec\">来源登记 · " + ((META.sources || []).length) + " 条</h3>";
    var srcs = META.sources || [];
    if (!srcs.length) html += "<p>来源清单尚未登记。</p>";
    else {
      html += "<div class=\"tablewrap\"><table><thead><tr><th>名称</th><th>类型</th><th>归属</th><th>地址</th></tr></thead><tbody>";
      srcs.forEach(function (s) {
        var u = safeUrl(s.url);
        var v = vendorById(s.vendor) || {};
        html += "<tr><td>" + esc(s.name) + "</td><td class=\"n\">" + esc(TYPE_LABEL[s.type] || s.type || "—") + "</td>" +
          "<td><span class=\"vtag\">" + esc(v.name || s.vendor || "通用") + "</span></td>" +
          "<td>" + (u ? "<a class=\"src\" href=\"" + esc(u) + "\" target=\"_blank\" rel=\"noopener noreferrer nofollow\">" + esc(host(u)) + "</a>" : "<span style=\"color:var(--text-3)\">—</span>") + "</td></tr>";
      });
      html += "</tbody></table></div>";
    }

    html += "<h3 class=\"sec\">免责声明</h3>" +
      "<p>模型能力评价与「优劣势」条目包含主观判断，反映撰写时点的检索结果与使用场景假设，不构成采购建议。基准分数受评测设置影响，跨厂商直接比较需谨慎。本站在公开访问下对所有人展示同一份快照，不追踪访问者。</p>" +
      "</div>";
    return html + footer();
  }
  var TYPE_LABEL = { official: "官网", changelog: "发布记录", github: "GitHub", hf: "HuggingFace", forum: "社区/论坛", paper: "技术报告", news: "媒体" };
  function host(u) {
    try { return decodeURIComponent(String(u).replace(/^https?:\/\//, "").split("/")[0]); }
    catch (e) { return String(u).slice(0, 40); }
  }

  /* ---------- 详情抽屉 ---------- */

  function openDrawer(kind, id) {
    state.drawer = { kind: kind, id: id };
    paint();
  }
  function closeDrawer() { state.drawer = null; paint(); }

  function drawerHtml() {
    if (!state.drawer) return "";
    if (state.drawer.kind === "vendor") {
      var v = vendorById(state.drawer.id);
      if (!v) return "";
      var ms = modelsOf(v.id), ts = toolsOf(v.id);
      var out = "<div class=\"overlay\" data-close=\"1\"><div class=\"drawer\" role=\"dialog\" aria-modal=\"true\" aria-label=\"" + esc(v.name) + "厂商档案\">" +
        "<div class=\"drawer-head\"><h2>" + esc(v.name) + "</h2>" +
        "<span class=\"vtag\">" + GROUP_LABEL[v.group] + "</span>" +
        "<span class=\"vcard-en\">" + esc(v.nameEn || "") + "</span>" +
        "<button class=\"x\" data-close=\"1\" aria-label=\"关闭\">×</button></div><div class=\"drawer-body\">";

      out += "<dl class=\"kv\">" +
        kv("定位", v.overview) + kv("开源策略", v.strategy) +
        kv("模型数", ms.length + " 个") + kv("工具数", ts.length + " 项") + "</dl>";

      out += secLabel("旗舰与主力模型");
      if (!ms.length) out += "<p class=\"prose\">模型数据待补。</p>";
      ms.forEach(function (m) {
        out += "<div class=\"sw-item\" style=\"cursor:pointer\" data-model=\"" + esc(m.id) + "\">" +
          "<span class=\"mk\" style=\"color:var(--cn)\">›</span><div><b style=\"color:var(--text)\">" + esc(m.name) + "</b> " +
          "<span class=\"mono\" style=\"font-size:11px;color:var(--text-3)\">" + esc(ROLE_LABEL[m.role] || "") + " · " + fmtDate(m.releasedAt) + "</span>" +
          "<div style=\"font-size:12.5px;margin-top:2px\">" + esc(m.summary || "") + "</div>" +
          "<div style=\"margin-top:5px;display:flex;gap:6px;align-items:center;flex-wrap:wrap\">" + osPill(m) +
          "<span class=\"mono\" style=\"font-size:11px;color:var(--text-3)\">上下文 " + esc(fmtNum(m.contextLength)) + "</span>" +
          confPill(m.confidence) + "</div></div></div>";
      });

      out += secLabel("优势");
      out += swList(v.strengths, "up", "+");
      out += secLabel("劣势与风险");
      out += swList(v.weaknesses, "down", "−");

      if (v.scenarios && v.scenarios.length) {
        out += secLabel("适用场景");
        out += "<ul class=\"prose\">" + v.scenarios.map(function (s) {
          return typeof s === "string" ? "<li>" + esc(s) + "</li>" : "<li>" + esc(s.text || s.name) + (s.note ? " — " + esc(s.note) : "") + "</li>";
        }).join("") + "</ul>";
      }

      if (ts.length) {
        out += secLabel("配套工具");
        out += "<div class=\"srcs\">" + ts.map(function (t) {
          return "<span class=\"mchip\">" + esc(t.name) + " · " + esc(t.form || "") + "</span>";
        }).join("") + "</div>";
      }

      var evs = EVENTS.filter(function (e) { return e.vendorId === v.id; })
        .sort(function (a, b) { return String(b.date || "").localeCompare(String(a.date || "")); }).slice(0, 8);
      if (evs.length) {
        out += secLabel("近期动态");
        out += "<div class=\"tl\">";
        evs.forEach(function (e) {
          out += "<div class=\"tl-item\"><div class=\"tl-date\">" + fmtDate(e.date) + "</div>" +
            "<div class=\"tl-title\" style=\"font-size:13px\">" + esc(e.title) + "</div>" +
            "<div class=\"tl-sig\">" + esc(e.significance || "") + "</div>" + srcChips(e.sourceIds, e.url) + "</div>";
        });
        out += "</div>";
      }
      out += "</div></div></div>";
      return out;
    }

    if (state.drawer.kind === "model") {
      var m = modelById(state.drawer.id);
      if (!m) return "";
      var mv = vendorById(m.vendorId) || {};
      var o = "<div class=\"overlay\" data-close=\"1\"><div class=\"drawer\" role=\"dialog\" aria-modal=\"true\" aria-label=\"" + esc(m.name) + "模型详情\">" +
        "<div class=\"drawer-head\"><h2>" + esc(m.name) + "</h2>" +
        "<span class=\"vtag\">" + esc(mv.name || "") + "</span>" +
        "<button class=\"x\" data-close=\"1\" aria-label=\"关闭\">×</button></div><div class=\"drawer-body\">" +
        "<p class=\"prose\">" + esc(m.summary || "") + "</p>" +
        "<dl class=\"kv\">" +
        kv("角色", ROLE_LABEL[m.role] || m.role) + kv("版本", m.version) + kv("发布时间", fmtDate(m.releasedAt)) +
        kv("参数规模", m.params || "未公开") + kv("上下文长度", fmtNum(m.contextLength)) +
        kv("模态", (m.modalities || []).join(" · ")) +
        kv("开源状态", (m.openSource === true ? "开放权重" : m.openSource === "partial" ? "部分开放" : m.openSource === "unverified" ? "开放承诺未证实" : "闭源") + (m.license ? " · " + m.license : "")) +
        kv("定价", plainMoney(m.pricing) + (m.pricing && m.pricing.cachedInput ? "（含缓存价）" : "")) +
        kv("推理成本备注", m.pricing && m.pricing.notes) +
        "</dl>";
      if (m.benchmarks && m.benchmarks.length) {
        o += secLabel("已记录 Benchmark");
        o += "<div class=\"tablewrap\"><table><thead><tr><th>项目</th><th>分数</th><th>时点</th><th>置信度</th></tr></thead><tbody>";
        m.benchmarks.forEach(function (b) {
          o += "<tr><td>" + esc(b.name) + "</td><td class=\"n\"><b>" + esc(b.score) + "</b></td>" +
            "<td class=\"n\">" + fmtDate(b.asOf || m.releasedAt) + "</td><td>" + confPill(b.confidence || m.confidence) + "</td></tr>";
        });
        o += "</tbody></table></div><p class=\"prose\" style=\"font-size:12px;color:var(--text-3);margin-top:8px\">基准分数受评测配置（pass@1、是否给工具、思考预算）影响，跨厂商直接比较需谨慎。</p>";
      }
      if (m.analysis) {
        o += secLabel("分析");
        o += "<div class=\"prose\">" + String(m.analysis).split("\n").map(function (p) { return "<p>" + esc(p) + "</p>"; }).join("") + "</div>";
      }
      o += secLabel("置信度与来源");
      o += "<p>" + confPill(m.confidence) + "</p>" + srcChips(m.sourceIds);
      o += "</div></div></div>";
      return o;
    }
    return "";
  }
  function kv(k, val) {
    if (val === undefined || val === null || val === "") return "";
    return "<dt>" + esc(k) + "</dt><dd>" + esc(val) + "</dd>";
  }
  function secLabel(t) {
    return "<h3 class=\"sec\">" + esc(t) + "</h3>";
  }
  function swList(items, cls, mark) {
    if (!items || !items.length) return "<p class=\"prose\">暂无记录。</p>";
    var out = "<div class=\"sw\">";
    items.forEach(function (it) {
      var text = typeof it === "string" ? it : it.text;
      var ids = typeof it === "string" ? [] : it.sourceIds;
      var c = typeof it === "string" ? null : it.confidence;
      out += "<div class=\"sw-item " + cls + "\"><span class=\"mk\">" + mark + "</span><div>" + esc(text) +
        "<div style=\"margin-top:4px\">" + confPill(c) + srcChipsInline(ids) + "</div></div></div>";
    });
    return out + "</div>";
  }
  function srcChipsInline(ids) {
    var out = "";
    (ids || []).forEach(function (id) {
      var s = srcById(id);
      if (!s) return;
      var u = safeUrl(s.url);
      if (!u) return;
      out += " <a class=\"src\" style=\"margin-left:4px\" href=\"" + esc(u) + "\" target=\"_blank\" rel=\"noopener noreferrer nofollow\">" + esc(s.name) + "</a>";
    });
    return out;
  }

  /* ---------- 通用块 ---------- */

  function head(t, p) {
    return "<div class=\"view-head\"><h1>" + esc(t) + "</h1><p>" + esc(p) + "</p></div>";
  }
  function emptyState() {
    return "<div class=\"empty\">该视图在当前数据集版本下没有条目。</div>";
  }
  function gapsBlock() {
    if (!GAPS.length) return "";
    return "<h3 class=\"sec\">已知缺口</h3><div class=\"sw\">" + GAPS.map(function (g) {
      return "<div class=\"sw-item down\"><span class=\"mk\">!</span><div><b>" + esc(g.area) + "</b> — " + esc(g.detail) + "</div></div>";
    }).join("") + "</div>";
  }
  function footer() {
    return "<div class=\"foot\">" +
      "<span>数据集 v" + esc(META.datasetVersion || "0") + "</span>" +
      "<span>更新 " + fmtDate(META.asOf) + "</span>" +
      "<span>" + esc(META.generatedBy || "Qoder Agent 策展") + "</span>" +
      "<span>本站为静态快照，不做实时抓取</span></div>";
  }

  /* ---------- 主渲染 ---------- */

  var RENDER = {
    overview: viewOverview, models: viewModels, tools: viewTools,
    compare: viewCompare, events: viewEvents, method: viewMethod
  };

  function paint() {
    state.view = currentView();
    renderRail();
    renderTop();
    var main = document.getElementById("main");
    main.innerHTML = draftBanner() + (RENDER[state.view] || viewOverview)();
    document.getElementById("overlay-host").innerHTML = drawerHtml();
    main.scrollIntoView({ block: "start" });
  }

  /* ---------- 事件委托 ---------- */

  document.addEventListener("click", function (ev) {
    var t = ev.target;

    if (t.closest("[data-close]")) { closeDrawer(); return; }

    var nav = t.closest("[data-view]");
    if (nav) {
      var id = nav.getAttribute("data-view");
      if (state.view === id) paint();
      else location.hash = "#/" + id;
      return;
    }

    var vc = t.closest("[data-vendor]");
    if (vc) { openDrawer("vendor", vc.getAttribute("data-vendor")); return; }

    var mr = t.closest("[data-model]");
    if (mr) { openDrawer("model", mr.getAttribute("data-model")); return; }

    var sort = t.closest("[data-sort]");
    if (sort) {
      var k = sort.getAttribute("data-sort");
      if (state.modelSort.key === k) state.modelSort.dir = state.modelSort.dir === "asc" ? "desc" : "asc";
      else { state.modelSort.key = k; state.modelSort.dir = k === "name" || k === "vendor" ? "asc" : "desc"; }
      paint();
      return;
    }

    var grp = t.closest("[data-group]");
    if (grp) { state.modelFilter.group = grp.getAttribute("data-group"); paint(); return; }

    var osp = t.closest("[data-os]");
    if (osp) { state.modelFilter.os = osp.getAttribute("data-os"); paint(); return; }

    var evv = t.closest("[data-ev]");
    if (evv) { state.eventVendor = evv.getAttribute("data-ev"); paint(); return; }

    var cmp = t.closest("[data-cmp]");
    if (cmp) {
      var mid = cmp.getAttribute("data-cmp");
      var i = state.compare.indexOf(mid);
      if (i >= 0) state.compare.splice(i, 1);
      else if (state.compare.length < 4) state.compare.push(mid);
      paint();
      return;
    }
  });

  document.addEventListener("input", function (ev) {
    if (ev.target.id === "mq") {
      var q = ev.target.value;
      state.modelFilter.q = q;
      paint();
      var el = document.getElementById("mq");
      if (el) { el.focus(); el.setSelectionRange(q.length, q.length); }
    }
  });

  document.addEventListener("keydown", function (ev) {
    if (ev.key === "Escape" && state.drawer) closeDrawer();
  });

  window.addEventListener("hashchange", paint);

  paint();
})();
