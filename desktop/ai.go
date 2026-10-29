package main

import (
	"encoding/json"
	"fmt"
	"net/url"
	"regexp"
	"sort"
	"strings"
	"time"

	catalogdata "newsdesk/internal/catalog"
)

var catalogJSON = catalogdata.AIJSON

type AIProvider struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Region  string `json:"region"`
	Color   string `json:"color"`
	Icon    string `json:"icon"`
	NewsURL string `json:"newsUrl"`
	Note    string `json:"note"`
}
type AITool struct {
	ID          string   `json:"id"`
	Provider    string   `json:"provider"`
	Name        string   `json:"name"`
	Kinds       []string `json:"kinds"`
	Description string   `json:"description"`
	Role        string   `json:"role"`
	Open        string   `json:"open"`
	License     string   `json:"license"`
	URL         string   `json:"url"`
	Repo        string   `json:"repo,omitempty"`
	References  []string `json:"references"`
	Platforms   []string `json:"platforms"`
	SourceID    string   `json:"sourceId,omitempty"`
	Status      string   `json:"status"`
	Note        string   `json:"note,omitempty"`
}
type AICatalog struct {
	CheckedAt string       `json:"checkedAt"`
	Note      string       `json:"note"`
	Providers []AIProvider `json:"providers"`
	Tools     []AITool     `json:"tools"`
}

func aiCatalog() AICatalog {
	var c AICatalog
	if e := json.Unmarshal(catalogJSON, &c); e != nil {
		panic(e)
	}
	return c
}

func aiSources() []Source {
	providers := map[string]AIProvider{}
	for _, p := range aiCatalog().Providers {
		providers[p.ID] = p
	}
	rss := func(id, name, provider, endpoint string) Source {
		p := providers[provider]
		return Source{ID: id, Name: name, Category: "ai", Provider: provider, Description: "官方新闻 · RSS", Kind: "rss", Home: p.NewsURL, Color: p.Color, Icon: p.Icon, Interval: 900, Endpoints: []string{endpoint}}
	}
	release := func(id, name, provider, repo string) Source {
		p := providers[provider]
		return Source{ID: id, Name: name, Category: "ai", Provider: provider, Description: "官方仓库 · 版本发布（含预发布）", Kind: "releases", Home: "https://github.com/" + repo + "/releases", Color: p.Color, Icon: p.Icon, Interval: 1800, Endpoints: []string{"https://github.com/" + repo + "/releases.atom", "https://api.github.com/repos/" + repo + "/releases?per_page=15"}}
	}
	page := func(id, name, provider, kind, endpoint, description string) Source {
		p := providers[provider]
		return Source{ID: id, Name: name, Category: "ai", Provider: provider, Description: description, Kind: kind, Home: endpoint, Color: p.Color, Icon: p.Icon, Interval: 1800, Endpoints: []string{endpoint}}
	}
	return []Source{
		{ID: "ai-media-ithome", Name: "AI 资讯 · IT之家", Category: "ai", Kind: "rss-ai", Description: "媒体补充 · 按 AI 关键词筛选，非厂商公告", Home: "https://www.ithome.com", Color: "#b77964", Icon: "IT", Interval: 900, Endpoints: []string{"https://www.ithome.com/rss/"}},
		{ID: "ai-media-ifanr", Name: "AI 资讯 · 爱范儿", Category: "ai", Kind: "rss-ai", Description: "媒体补充 · 按 AI 关键词筛选，非厂商公告", Home: "https://www.ifanr.com", Color: "#a28d68", Icon: "范", Interval: 900, Endpoints: []string{"https://www.ifanr.com/feed"}},
		rss("ai-openai", "OpenAI · 官方动态", "openai", "https://openai.com/news/rss.xml"),
		release("ai-codex", "Codex · 版本发布", "openai", "openai/codex"),
		page("ai-anthropic", "Anthropic · 官方动态", "anthropic", "anthropic", "https://www.anthropic.com/news", "官方新闻页 · 结构化解析"),
		release("ai-claude-code", "Claude Code · 版本发布", "anthropic", "anthropics/claude-code"),
		rss("ai-google", "Google AI · 官方动态", "google", "https://blog.google/technology/ai/rss/"),
		release("ai-gemini-cli", "Gemini CLI · 版本发布", "google", "google-gemini/gemini-cli"),
		release("ai-qwen-code", "Qwen Code · 版本发布", "alibaba", "QwenLM/qwen-code"),
		release("ai-kimi-code", "Kimi Code · 版本发布", "moonshot", "MoonshotAI/kimi-code"),
		release("ai-mistral-vibe", "Mistral Vibe · 版本发布", "mistral", "mistralai/mistral-vibe"),
		release("ai-minimax-code", "MiniMax Code · 版本发布", "minimax", "MiniMax-AI/minimax-code"),
		rss("ai-copilot-news", "GitHub Copilot · 官方动态", "github", "https://github.blog/changelog/feed/?label=copilot"),
		release("ai-copilot-cli", "Copilot CLI · 版本发布", "github", "github/copilot-cli"),
		rss("ai-huggingface", "Hugging Face · 官方动态", "huggingface", "https://huggingface.co/blog/feed.xml"),
		page("ai-deepseek", "DeepSeek · 更新日志", "deepseek", "deepseek", "https://api-docs.deepseek.com/updates/", "官方 API 更新日志 · 非每日新闻"),
		release("ai-deepseek-harness", "DeepSeek Harness · 版本发布", "deepseek", "deepseek-ai/deepseek-harness"),
		page("ai-zcode", "ZCode · 更新日志", "zai", "zcode", "https://zcode.z.ai/en/changelog", "官方产品更新日志"),
	}
}

var aiKeywordRE = regexp.MustCompile(`(?i)(\bAI\b|人工智能|大模型|智能体|OpenAI|GPT|ChatGPT|Codex|Claude|Anthropic|Gemini|Antigravity|DeepSeek|通义|千问|Qwen|灵码|Kimi|月之暗面|MiniMax|智谱|GLM|ZCode|Grok|Mistral|Copilot|CodeBuddy|文心|TRAE|豆包|Kiro|Hugging Face)`)
var anchorRE = regexp.MustCompile(`(?is)<a\b[^>]*\bhref=["']([^"']+)["'][^>]*>(.*?)</a>`)
var headingRE = regexp.MustCompile(`(?is)<h[1-6]\b[^>]*>(.*?)</h[1-6]>`)
var h2RE = regexp.MustCompile(`(?is)<h2\b[^>]*>(.*?)</h2>`)
var paragraphRE = regexp.MustCompile(`(?is)<p\b[^>]*>(.*?)</p>`)
var timeRE = regexp.MustCompile(`(?is)<time\b[^>]*>(.*?)</time>`)
var isoDateRE = regexp.MustCompile(`\d{4}-\d{2}-\d{2}`)
var englishDateRE = regexp.MustCompile(`(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{1,2},\s+\d{4}`)
var idAttrRE = regexp.MustCompile(`\bid=["']([^"']+)["']`)
var versionRE = regexp.MustCompile(`^Release\s+(v[\w.\-]+)$`)
var scriptStyleRE = regexp.MustCompile(`(?is)<(?:script|style)\b[^>]*>.*?</(?:script|style)>`)

func firstText(re *regexp.Regexp, s string) string {
	m := re.FindStringSubmatch(s)
	if len(m) > 1 {
		return clean(m[1])
	}
	return ""
}
func htmlDate(s string) string {
	d := isoDateRE.FindString(s)
	if d != "" {
		return parseDate(d)
	}
	return parseDate(englishDateRE.FindString(clean(s)))
}
func parseAIHTML(s Source, body []byte) ([]Article, error) {
	text := scriptStyleRE.ReplaceAllString(string(body), "")
	out := []Article{}
	switch s.Kind {
	case "anthropic":
		for _, loc := range anchorRE.FindAllStringSubmatchIndex(text, -1) {
			href := text[loc[2]:loc[3]]
			inside := text[loc[4]:loc[5]]
			if !strings.HasPrefix(href, "/news/") {
				continue
			}
			title := firstText(headingRE, inside)
			if title == "" {
				continue
			}
			summary := firstText(paragraphRE, inside)
			before := text[max(0, loc[0]-700):loc[0]]
			date := htmlDate(inside)
			if date == "" {
				times := timeRE.FindAllString(before, -1)
				if len(times) > 0 {
					date = htmlDate(times[len(times)-1])
				}
			}
			out = append(out, Article{Title: title, URL: "https://www.anthropic.com" + href, Summary: summary, PublishedAt: date, DateOnly: true})
		}
		// The newsroom also has a chronological list with span titles and dates.
		for _, m := range anchorRE.FindAllStringSubmatch(text, -1) {
			if !strings.HasPrefix(m[1], "/news/") || !strings.Contains(m[2], "<time") {
				continue
			}
			title := clean(timeRE.ReplaceAllString(m[2], " "))
			if len([]rune(title)) < 15 {
				continue
			}
			out = append(out, Article{Title: title, URL: "https://www.anthropic.com" + m[1], PublishedAt: htmlDate(m[2]), DateOnly: true})
		}
	case "deepseek", "zcode":
		locations := h2RE.FindAllStringSubmatchIndex(text, -1)
		for i, loc := range locations {
			title := clean(text[loc[2]:loc[3]])
			end := len(text)
			if i+1 < len(locations) {
				end = locations[i+1][0]
			}
			if s.Kind == "zcode" {
				end = len(text)
				for j := i + 1; j < len(locations); j++ {
					next := locations[j]
					if versionRE.MatchString(clean(text[next[2]:next[3]])) {
						end = next[0]
						break
					}
				}
			}
			section := text[loc[1]:end]
			heading := text[loc[0]:loc[1]]
			if s.Kind == "deepseek" {
				date := isoDateRE.FindString(title)
				if date == "" || !strings.Contains(title, "Date:") {
					continue
				}
				id := firstText(idAttrRE, heading)
				aTitle := firstText(headingRE, section)
				if aTitle == "" {
					aTitle = "DeepSeek 官方更新 · " + date
				}
				out = append(out, Article{Title: aTitle, URL: s.Home + "#" + id, Summary: firstText(paragraphRE, section), PublishedAt: parseDate(date), DateOnly: true})
			} else {
				version := versionRE.FindStringSubmatch(title)
				if len(version) < 2 {
					continue
				}
				before := text[max(0, loc[0]-5000):loc[0]]
				dates := englishDateRE.FindAllString(clean(before), -1)
				date := ""
				if len(dates) > 0 {
					date = parseDate(dates[len(dates)-1])
				}
				out = append(out, Article{Title: "ZCode " + version[1], URL: s.Home + "#" + version[1], Summary: trimSummary(section), PublishedAt: date, DateOnly: true})
			}
		}
	default:
		return nil, fmt.Errorf("未知的 AI 动态格式")
	}
	// Prefer dated, recent announcements; preserve source order when dates are unknown.
	sort.SliceStable(out, func(i, j int) bool { return out[i].PublishedAt > out[j].PublishedAt })
	return finish(s, out), nil
}

func parseReleases(s Source, body []byte) ([]Article, error) {
	if strings.HasPrefix(strings.TrimSpace(string(body)), "<") {
		rss := s
		rss.Kind = "rss"
		return parseSource(rss, body)
	}
	var releases []struct {
		Tag        string `json:"tag_name"`
		Name       string `json:"name"`
		URL        string `json:"html_url"`
		Body       string `json:"body"`
		Published  string `json:"published_at"`
		Draft      bool   `json:"draft"`
		Prerelease bool   `json:"prerelease"`
	}
	if e := json.Unmarshal(body, &releases); e != nil {
		return nil, fmt.Errorf("GitHub 未返回有效版本列表，可能达到请求限额")
	}
	out := []Article{}
	for _, r := range releases {
		if r.Draft {
			continue
		}
		name := r.Name
		if name == "" {
			name = r.Tag
		}
		a := Article{Title: name, URL: r.URL, Summary: r.Body, PublishedAt: parseDate(r.Published)}
		if r.Prerelease {
			a.Heat = "预发布"
		}
		out = append(out, a)
	}
	return finish(s, out), nil
}

func validCatalogURL(raw string) bool {
	u, e := url.Parse(raw)
	return e == nil && u.Scheme == "https" && u.Host != "" && u.User == nil
}
func validateCatalog(c AICatalog) error {
	providers := map[string]bool{}
	ids := map[string]bool{}
	for _, p := range c.Providers {
		if providers[p.ID] || p.ID == "" || !validCatalogURL(p.NewsURL) {
			return fmt.Errorf("invalid provider %s", p.ID)
		}
		providers[p.ID] = true
	}
	for _, t := range c.Tools {
		if ids[t.ID] || !providers[t.Provider] || len(t.References) == 0 || len(t.Kinds) == 0 || !validCatalogURL(t.URL) {
			return fmt.Errorf("invalid tool %s", t.ID)
		}
		for _, ref := range t.References {
			if !validCatalogURL(ref) {
				return fmt.Errorf("invalid reference for %s", t.ID)
			}
		}
		ids[t.ID] = true
	}
	if _, e := time.Parse("2006-01-02", c.CheckedAt); e != nil {
		return e
	}
	return nil
}
