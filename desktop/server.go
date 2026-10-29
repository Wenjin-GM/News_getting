package main

import (
	"crypto/subtle"
	"embed"
	"encoding/json"
	"fmt"
	"html/template"
	"io/fs"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"

	qrcode "github.com/skip2/go-qrcode"
)

//go:embed web/*
var assets embed.FS

func reply(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	json.NewEncoder(w).Encode(v)
}
func failure(w http.ResponseWriter, status int, msg string) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	reply(w, map[string]string{"error": msg})
}
func decode(w http.ResponseWriter, r *http.Request, v any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, 16384)
	d := json.NewDecoder(r.Body)
	d.DisallowUnknownFields()
	if d.Decode(v) != nil {
		failure(w, 400, "请求格式无效")
		return false
	}
	return true
}
func (a *App) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/ai/catalog", func(w http.ResponseWriter, r *http.Request) { reply(w, aiCatalog()) })
	mux.HandleFunc("GET /api/state", func(w http.ResponseWriter, r *http.Request) {
		a.mu.RLock()
		defer a.mu.RUnlock()
		states := []SourceState{}
		for _, id := range a.order {
			states = append(states, *a.states[id])
		}
		reply(w, map[string]any{"version": version, "sources": states, "run": a.run, "serverTime": nowString(), "startedAt": a.started.Format(time.RFC3339)})
	})
	mux.HandleFunc("POST /api/refresh", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Force    bool   `json:"force"`
			Source   string `json:"source"`
			Category string `json:"category"`
		}
		if !decode(w, r, &body) {
			return
		}
		if body.Category != "" && body.Category != "ai" && body.Category != "general" {
			failure(w, 400, "无效的更新范围")
			return
		}
		run, e := a.refreshCategory(body.Force, body.Source, body.Category)
		if e != nil {
			failure(w, 400, e.Error())
			return
		}
		reply(w, run)
	})
	mux.HandleFunc("GET /api/favorites", func(w http.ResponseWriter, r *http.Request) {
		items, e := a.store.favorites()
		if e != nil {
			failure(w, 500, "读取收藏失败")
			return
		}
		reply(w, items)
	})
	mux.HandleFunc("POST /api/favorites", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			ID string `json:"id"`
			On bool   `json:"on"`
		}
		if !decode(w, r, &body) {
			return
		}
		var article Article
		if body.On {
			a.mu.RLock()
			for _, s := range a.states {
				for _, v := range s.Items {
					if v.ID == body.ID {
						article = v
						break
					}
				}
			}
			a.mu.RUnlock()
			if article.ID == "" {
				items, _ := a.store.articles("", "")
				for _, v := range items {
					if v.ID == body.ID {
						article = v
						break
					}
				}
			}
			if article.ID == "" {
				failure(w, 404, "条目已不在当前列表或历史中")
				return
			}
		} else {
			article.ID = body.ID
		}
		if e := a.store.favorite(article, body.On); e != nil {
			failure(w, 500, "保存收藏失败")
			return
		}
		reply(w, map[string]bool{"ok": true})
	})
	mux.HandleFunc("GET /api/history", func(w http.ResponseWriter, r *http.Request) {
		items, e := a.store.articles(r.URL.Query().Get("source"), r.URL.Query().Get("date"))
		if e != nil {
			failure(w, 400, e.Error())
			return
		}
		reply(w, items)
	})
	mux.HandleFunc("GET /api/runs", func(w http.ResponseWriter, r *http.Request) {
		runs, e := a.store.runs()
		if e != nil {
			failure(w, 500, "读取记录失败")
			return
		}
		reply(w, runs)
	})
	mux.HandleFunc("POST /api/source", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			ID      string `json:"id"`
			Enabled bool   `json:"enabled"`
		}
		if !decode(w, r, &body) {
			return
		}
		a.mu.Lock()
		defer a.mu.Unlock()
		if a.run.Running {
			failure(w, 409, "本轮更新完成后可调整来源")
			return
		}
		s, ok := a.states[body.ID]
		if !ok {
			failure(w, 404, "来源不存在")
			return
		}
		previous := s.Enabled
		s.Enabled = body.Enabled
		if e := a.store.saveSource(*s); e != nil {
			s.Enabled = previous
			failure(w, 500, "保存设置失败")
			return
		}
		reply(w, map[string]bool{"ok": true})
	})
	mux.HandleFunc("POST /api/feeds", a.addFeed)
	mux.HandleFunc("DELETE /api/feeds/{id}", func(w http.ResponseWriter, r *http.Request) {
		a.mu.Lock()
		defer a.mu.Unlock()
		if a.run.Running {
			failure(w, 409, "本轮更新完成后可删除来源")
			return
		}
		id := r.PathValue("id")
		s, ok := a.states[id]
		if !ok || !s.Source.Custom {
			failure(w, 400, "只能移除自定义订阅")
			return
		}
		delete(a.states, id)
		for i, k := range a.order {
			if k == id {
				a.order = append(a.order[:i], a.order[i+1:]...)
				break
			}
		}
		a.persistCustom()
		a.store.db.Exec("DELETE FROM sources WHERE id=?", id)
		reply(w, map[string]bool{"ok": true})
	})
	mux.HandleFunc("GET /api/network", func(w http.ResponseWriter, r *http.Request) { reply(w, a.networkInfo()) })
	mux.HandleFunc("GET /api/qr", func(w http.ResponseWriter, r *http.Request) {
		ips := localIPs()
		if len(ips) == 0 {
			failure(w, 400, "未发现局域网 IPv4 地址")
			return
		}
		ip := r.URL.Query().Get("ip")
		if ip == "" {
			ip = ips[0]
		}
		valid := false
		for _, v := range ips {
			if v == ip {
				valid = true
			}
		}
		if !valid {
			failure(w, 400, "无效的本机地址")
			return
		}
		png, e := qrcode.Encode(fmt.Sprintf("http://%s:%d/#token=%s", ip, a.port, a.token), qrcode.Medium, 256)
		if e != nil {
			failure(w, 500, "二维码生成失败")
			return
		}
		w.Header().Set("Content-Type", "image/png")
		w.Write(png)
	})
	mux.HandleFunc("POST /api/export", a.export)
	mux.HandleFunc("POST /api/shutdown", func(w http.ResponseWriter, r *http.Request) {
		reply(w, map[string]bool{"ok": true})
		if a.shutdown != nil {
			go func() { time.Sleep(50 * time.Millisecond); a.shutdown() }()
		}
	})
	web, _ := fs.Sub(assets, "web")
	fileServer := http.FileServer(http.FS(web))
	mux.Handle("/", fileServer)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
		if strings.HasPrefix(r.URL.Path, "/api/") {
			w.Header().Set("Cache-Control", "no-store")
			token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
			if subtle.ConstantTimeCompare([]byte(token), []byte(a.token)) != 1 {
				failure(w, 401, "请从启动器提供的完整链接访问，或输入访问密钥")
				return
			}
		} else {
			w.Header().Set("Cache-Control", "no-cache")
		}
		mux.ServeHTTP(w, r)
	})
}

func (a *App) networkInfo() map[string]any {
	urls := []string{}
	for _, ip := range localIPs() {
		urls = append(urls, fmt.Sprintf("http://%s:%d/#token=%s", ip, a.port, a.token))
	}
	return map[string]any{"localUrl": fmt.Sprintf("http://127.0.0.1:%d/#token=%s", a.port, a.token), "lanUrls": urls, "ips": localIPs(), "lanEnabled": a.bind == "0.0.0.0", "port": a.port, "dataDir": a.dataDir}
}
func writeRuntime(dir string, info map[string]any) error {
	b, e := json.MarshalIndent(info, "", "  ")
	if e != nil {
		return e
	}
	return os.WriteFile(filepath.Join(dir, "runtime.json"), b, 0600)
}
func (a *App) persistCustom() error {
	custom := []Source{}
	for _, id := range a.order {
		if a.states[id].Source.Custom {
			custom = append(custom, a.states[id].Source)
		}
	}
	return a.store.set("customSources", custom)
}
func (a *App) addFeed(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Name     string `json:"name"`
		URL      string `json:"url"`
		Category string `json:"category"`
	}
	if !decode(w, r, &body) {
		return
	}
	if len([]rune(strings.TrimSpace(body.Name))) < 2 || len([]rune(body.Name)) > 30 {
		failure(w, 400, "来源名称需要 2～30 个字符")
		return
	}
	u, e := url.Parse(body.URL)
	if e != nil || u.Scheme != "https" || u.Hostname() == "" || u.User != nil || u.Fragment != "" {
		failure(w, 400, "请输入不含账号密码的 HTTPS 订阅地址")
		return
	}
	ips, e := net.LookupIP(u.Hostname())
	if e != nil || len(ips) == 0 {
		failure(w, 400, "订阅域名无法解析")
		return
	}
	for _, ip := range ips {
		if !ip.IsGlobalUnicast() || ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
			failure(w, 400, "自定义订阅仅支持公网 HTTPS 地址")
			return
		}
	}
	valid := map[string]bool{"news": true, "hot": true, "tech": true, "finance": true, "community": true, "ai": true}
	if !valid[body.Category] {
		body.Category = "news"
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.run.Running {
		failure(w, 409, "本轮更新完成后可添加来源")
		return
	}
	customCount := 0
	for _, s := range a.states {
		if s.Source.Custom {
			customCount++
		}
	}
	if customCount >= 20 {
		failure(w, 400, "最多支持 20 个自定义来源")
		return
	}
	for _, s := range a.states {
		if s.Source.FeedURL == body.URL {
			failure(w, 400, "该订阅已存在")
			return
		}
	}
	src := Source{ID: "feed-" + randomID()[:10], Name: strings.TrimSpace(body.Name), FeedURL: body.URL, Home: u.Scheme + "://" + u.Host, Category: body.Category, Description: "自定义 RSS / Atom 订阅", Kind: "rss", Color: "#528b74", Icon: "R", Interval: 600, Custom: true}
	state := &SourceState{Source: src, Enabled: true, Status: "idle", Items: []Article{}}
	a.order = append(a.order, src.ID)
	a.states[src.ID] = state
	if e = a.persistCustom(); e != nil {
		delete(a.states, src.ID)
		a.order = a.order[:len(a.order)-1]
		failure(w, 500, "保存订阅失败")
		return
	}
	a.store.saveSource(*state)
	reply(w, state)
}

func (a *App) export(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Format string `json:"format"`
		Scope  string `json:"scope"`
	}
	if !decode(w, r, &body) {
		return
	}
	if body.Scope == "ai-catalog" {
		a.exportAICatalog(w, body.Format)
		return
	}
	items := []Article{}
	var err error
	if body.Scope == "favorites" {
		items, err = a.store.favorites()
	} else if body.Scope == "history" {
		items, err = a.store.articles("", "")
	} else {
		a.mu.RLock()
		for _, id := range a.order {
			s := a.states[id]
			if body.Scope == "ai" && s.Source.Category != "ai" {
				continue
			}
			if s.Enabled {
				items = append(items, s.Items...)
			}
		}
		a.mu.RUnlock()
	}
	if err != nil {
		failure(w, 500, "读取导出数据失败")
		return
	}
	var content []byte
	ext := "md"
	switch body.Format {
	case "json":
		ext = "json"
		content, _ = json.MarshalIndent(map[string]any{"exportedAt": nowString(), "note": "fetchedAt 为采集时间；历史缓存不代表本轮更新成功。", "items": items}, "", "  ")
	case "html":
		ext = "html"
		var b strings.Builder
		t := template.Must(template.New("report").Parse(`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>一览 · 新闻快照</title><style>body{max-width:850px;margin:50px auto;padding:0 24px;background:#f7f8f5;color:#213d33;font:16px/1.8 system-ui}article{padding:18px 0;border-bottom:1px solid #d9e0da}a{color:#236447}small{color:#6c7970}h1{font-size:32px}p{margin:5px 0}</style><h1>一览 · 新闻快照</h1><p>导出于 {{.Time}} · {{len .Items}} 条</p><small>这是一份静态快照。采集时间不等于发布时间，缓存内容不代表本轮更新成功。</small>{{range .Items}}<article><a href="{{.URL}}" rel="noreferrer">{{.Title}}</a><p>{{.Summary}}</p><small>{{.SourceName}} · 采集 {{.FetchedAt}}{{if .PublishedAt}} · 发布 {{.PublishedAt}}{{end}}</small></article>{{end}}</html>`))
		err = t.Execute(&b, map[string]any{"Time": nowString(), "Items": items})
		if err != nil {
			failure(w, 500, "生成报告失败")
			return
		}
		content = []byte(b.String())
	default:
		var b strings.Builder
		fmt.Fprintf(&b, "# 一览 · 新闻快照\n\n导出时间：%s\n\n采集时间不等于发布时间；缓存内容不代表本轮更新成功。\n\n", nowString())
		for _, v := range items {
			title := strings.NewReplacer("[", "［", "]", "］", "\n", " ").Replace(v.Title)
			fmt.Fprintf(&b, "- [%s](%s)\n  - 来源：%s；采集：%s；发布：%s\n", title, v.URL, v.SourceName, v.FetchedAt, v.PublishedAt)
		}
		content = []byte(b.String())
	}
	a.writeExport(w, content, ext, len(items))
}

func (a *App) writeExport(w http.ResponseWriter, content []byte, ext string, count int) {
	dir := filepath.Join(a.dataDir, "exports")
	if e := os.MkdirAll(dir, 0700); e != nil {
		failure(w, 500, "创建导出目录失败")
		return
	}
	filename := "newsdesk-" + time.Now().Format("20060102-150405") + "-" + randomID()[:4] + "." + ext
	path := filepath.Join(dir, filename)
	if e := os.WriteFile(path, content, 0600); e != nil {
		failure(w, 500, "保存导出失败")
		return
	}
	reply(w, map[string]any{"filename": filename, "path": path, "content": string(content), "count": count})
}
