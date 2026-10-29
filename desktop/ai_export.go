package main

import (
	"encoding/json"
	"fmt"
	"html/template"
	"net/http"
	"strings"
)

func (a *App) exportAICatalog(w http.ResponseWriter, format string) {
	c := aiCatalog()
	providers := map[string]string{}
	for _, p := range c.Providers {
		providers[p.ID] = p.Name
	}
	var content []byte
	ext := "md"
	switch format {
	case "json":
		ext = "json"
		content, _ = json.MarshalIndent(c, "", "  ")
	case "html":
		ext = "html"
		var b strings.Builder
		t := template.Must(template.New("catalog").Funcs(template.FuncMap{"vendor": func(id string) string { return providers[id] }, "join": strings.Join}).Parse(`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>一览 · AI 编程产品与 Harness</title><style>body{max-width:1100px;margin:40px auto;padding:0 22px;background:#f7f8f4;color:#264535;font:14px/1.8 system-ui}a{color:#2e7755}article{background:white;border:1px solid #dfe6d6;padding:20px;margin:15px 0;border-radius:12px}small{color:#78906a}h2{margin:0 0 5px}p{margin:6px 0}</style><h1>AI 编程产品与 Harness 目录</h1><p>核对日期：{{.CheckedAt}}</p><p>{{.Note}}</p>{{range .Tools}}<article><small>{{vendor .Provider}} · {{join .Kinds " / "}}</small><h2><a href="{{.URL}}">{{.Name}}</a></h2><p>{{.Description}}</p><p>定位：{{.Role}}</p><p>许可：{{.License}}</p><p>{{.Note}}</p><small>官方依据：{{range .References}}<a href="{{.}}">{{.}}</a> {{end}}</small></article>{{end}}</html>`))
		if e := t.Execute(&b, c); e != nil {
			failure(w, 500, "生成 AI 目录失败")
			return
		}
		content = []byte(b.String())
	default:
		var b strings.Builder
		fmt.Fprintf(&b, "# AI 编程产品与 Harness 目录\n\n核对日期：%s\n\n%s\n\n", c.CheckedAt, c.Note)
		for _, t := range c.Tools {
			fmt.Fprintf(&b, "## %s · %s\n\n- 官网：%s\n- 类型：%s\n- 简介：%s\n- 定位：%s\n- 许可：%s\n", providers[t.Provider], t.Name, t.URL, strings.Join(t.Kinds, " / "), t.Description, t.Role, t.License)
			if t.Note != "" {
				fmt.Fprintf(&b, "- 提示：%s\n", t.Note)
			}
			for _, ref := range t.References {
				fmt.Fprintf(&b, "- 官方依据：%s\n", ref)
			}
			b.WriteString("\n")
		}
		content = []byte(b.String())
	}
	a.writeExport(w, content, ext, len(c.Tools))
}
