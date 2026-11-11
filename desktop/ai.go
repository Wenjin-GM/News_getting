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
