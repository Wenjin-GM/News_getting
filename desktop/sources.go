package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"encoding/xml"
	"fmt"
	"html"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"

	sourceconfig "newsdesk/config"
)

type Article struct {
	ID          string `json:"id"`
	Source      string `json:"source"`
	SourceName  string `json:"sourceName"`
	Category    string `json:"category"`
	Provider    string `json:"provider,omitempty"`
	Title       string `json:"title"`
	URL         string `json:"url"`
	Summary     string `json:"summary,omitempty"`
	PublishedAt string `json:"publishedAt,omitempty"`
	DateOnly    bool   `json:"dateOnly,omitempty"`
	FetchedAt   string `json:"fetchedAt"`
	Rank        int    `json:"rank"`
	Heat        string `json:"heat,omitempty"`
}

type Source struct {
	DefaultEnabled bool     `json:"-"`
	Authority      string   `json:"authority,omitempty"`
	ID             string   `json:"id"`
	Name           string   `json:"name"`
	Category       string   `json:"category"`
	Provider       string   `json:"provider,omitempty"`
	Description    string   `json:"description"`
	Kind           string   `json:"kind"`
	Home           string   `json:"home"`
	Color          string   `json:"color"`
	Icon           string   `json:"icon"`
	Interval       int      `json:"interval"`
	Experimental   bool     `json:"experimental"`
	Custom         bool     `json:"custom"`
	FeedURL        string   `json:"feedUrl,omitempty"`
	Endpoints      []string `json:"-"`
}

func builtins() []Source {
	var rows []struct {
		Source
		Endpoints []string `json:"endpoints"`
		Enabled   bool     `json:"enabled"`
	}
	if err := json.Unmarshal(sourceconfig.JSON(), &rows); err != nil {
		panic(err)
	}
	result := make([]Source, 0, len(rows))
	for _, row := range rows {
		source := row.Source
		source.Endpoints = row.Endpoints
		source.DefaultEnabled = row.Enabled
		result = append(result, source)
	}
	return result
}

var httpClient = &http.Client{Timeout: 9 * time.Second, Transport: &http.Transport{Proxy: http.ProxyFromEnvironment, MaxIdleConns: 30, MaxIdleConnsPerHost: 3, IdleConnTimeout: 60 * time.Second, TLSHandshakeTimeout: 6 * time.Second, ResponseHeaderTimeout: 7 * time.Second}, CheckRedirect: func(req *http.Request, via []*http.Request) error {
	if len(via) > 4 {
		return fmt.Errorf("重定向次数过多")
	}
	if req.URL.Scheme != "https" {
		return fmt.Errorf("拒绝重定向到非 HTTPS 地址")
	}
	return nil
}}

func fetchSource(ctx context.Context, s Source) ([]Article, string, error) {
	endpoints := s.Endpoints
	if s.Custom {
		endpoints = []string{s.FeedURL}
	}
	var errors []string
	for n, endpoint := range endpoints {
		if ctx.Err() != nil {
			break
		}
		requestCtx, cancel := context.WithTimeout(ctx, 9*time.Second)
		req, err := http.NewRequestWithContext(requestCtx, http.MethodGet, endpoint, nil)
		if err != nil {
			cancel()
			return nil, "", err
		}
		req.Header.Set("User-Agent", "Mozilla/5.0 (compatible; NewsDesk/1.0; personal news reader)")
		req.Header.Set("Accept", "application/json, application/rss+xml, application/xml, text/html;q=0.8, */*;q=0.5")
		if s.ID == "weibo" {
			req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
			req.Header.Set("Accept", "application/json, text/plain, */*")
			req.Header.Set("Referer", "https://weibo.com/")
			req.Header.Set("sec-fetch-dest", "empty")
			req.Header.Set("sec-fetch-mode", "cors")
			req.Header.Set("sec-fetch-site", "same-origin")
		}
		resp, err := httpClient.Do(req)
		var body []byte
		if err == nil {
			if resp.StatusCode != 200 {
				err = fmt.Errorf("HTTP %d", resp.StatusCode)
			} else {
				body, err = io.ReadAll(io.LimitReader(resp.Body, 5*1024*1024+1))
				if len(body) > 5*1024*1024 {
					err = fmt.Errorf("响应超过 5 MB 限制")
				}
			}
			resp.Body.Close()
		}
		cancel()
		if err != nil {
			errors = append(errors, shortError(err))
			continue
		}
		items, err := parseSource(s, body)
		if err == nil {
			err = validateFreshness(s, items, time.Now())
		}
		if err != nil || len(items) == 0 {
			if err == nil {
				err = fmt.Errorf("未返回有效条目，可能需要登录或接口已变更")
			}
			errors = append(errors, shortError(err))
			continue
		}
		channel := "主渠道"
		if n > 0 {
			channel = "备用渠道（同平台，非独立上游）"
		}
		return items, channel, nil
	}
	if len(errors) == 0 {
		return nil, "", fmt.Errorf("本轮采集已截止")
	}
	return nil, "", fmt.Errorf("%s", strings.Join(errors, "；"))
}

func validateFreshness(s Source, items []Article, now time.Time) error {
	if s.Kind != "rss" || s.Custom || s.Category == "ai" {
		return nil
	}
	var newest time.Time
	for _, a := range items {
		if date, e := time.Parse(time.RFC3339, a.PublishedAt); e == nil && date.After(newest) {
			newest = date
		}
	}
	if !newest.IsZero() && now.Sub(newest) > 7*24*time.Hour {
		return fmt.Errorf("上游可能已停更，最新发布时间为 %s，未计入本轮成功", newest.Format("2006-01-02"))
	}
	if newest.After(now.Add(24 * time.Hour)) {
		return fmt.Errorf("上游发布时间异常，超过当前时间一天以上")
	}
	return nil
}

func shortError(err error) string {
	s := err.Error()
	switch {
	case strings.Contains(s, "context deadline"), strings.Contains(s, "Client.Timeout"), strings.Contains(s, "i/o timeout"):
		return "连接超时"
	case strings.Contains(s, "certificate"), strings.Contains(s, "tls:"):
		return "TLS 连接失败（未降低证书校验）"
	case strings.Contains(s, "EOF"):
		return "连接被远端提前关闭，请稍后重试"
	case strings.Contains(s, "no such host"):
		return "域名解析失败"
	case strings.Contains(s, "connection refused"):
		return "连接被拒绝"
	}
	if len([]rune(s)) > 130 {
		s = string([]rune(s)[:130]) + "…"
	}
	return s
}

var stripTags = regexp.MustCompile(`<[^>]*>`)
var spacePattern = regexp.MustCompile(`\s+`)
var baiduData = regexp.MustCompile(`(?s)<!--s-data:(.*?)-->`)

func clean(s string) string {
	return strings.TrimSpace(spacePattern.ReplaceAllString(html.UnescapeString(stripTags.ReplaceAllString(s, " ")), " "))
}
func trimSummary(s string) string {
	r := []rune(clean(s))
	if len(r) > 350 {
		return string(r[:350]) + "…"
	}
	return string(r)
}
func parseDate(s string) string {
	for _, layout := range []string{time.RFC3339, time.RFC3339Nano, time.RFC1123Z, time.RFC1123, time.RFC822Z, time.RFC822, "2006-01-02 15:04:05", "2006-01-02", "Jan 2, 2006", "January 2, 2006"} {
		if t, e := time.Parse(layout, strings.TrimSpace(s)); e == nil {
			return t.Format(time.RFC3339)
		}
	}
	if n, e := strconv.ParseInt(s, 10, 64); e == nil && n > 1000000000 {
		if n > 1000000000000 {
			n /= 1000
		}
		return time.Unix(n, 0).Format(time.RFC3339)
	}
	return ""
}
func finish(s Source, items []Article) []Article {
	now := time.Now().Format(time.RFC3339)
	out := []Article{}
	seen := map[string]bool{}
	for _, a := range items {
		a.Title = clean(a.Title)
		a.Summary = trimSummary(a.Summary)
		u, e := url.Parse(a.URL)
		if e != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || len([]rune(a.Title)) < 2 {
			continue
		}
		if seen[a.URL] {
			continue
		}
		seen[a.URL] = true
		h := sha256.Sum256([]byte(s.ID + "|" + a.URL))
		a.ID = hex.EncodeToString(h[:12])
		a.Source = s.ID
		a.SourceName = s.Name
		a.Category = s.Category
		a.Provider = s.Provider
		a.FetchedAt = now
		a.Rank = len(out) + 1
		out = append(out, a)
		if len(out) == 40 {
			break
		}
	}
	return out
}

type rssDoc struct {
	Channel struct {
		Items []struct {
			Title       string `xml:"title"`
			Link        string `xml:"link"`
			Description string `xml:"description"`
			PubDate     string `xml:"pubDate"`
			Date        string `xml:"date"`
		} `xml:"item"`
	} `xml:"channel"`
	Entries []struct {
		Title string `xml:"title"`
		Links []struct {
			Href string `xml:"href,attr"`
			Rel  string `xml:"rel,attr"`
		} `xml:"link"`
		Summary   string `xml:"summary"`
		Content   string `xml:"content"`
		Updated   string `xml:"updated"`
		Published string `xml:"published"`
	} `xml:"entry"`
}

func parseSource(s Source, body []byte) ([]Article, error) {
	if s.Kind == "releases" {
		return parseReleases(s, body)
	}
	if s.Kind == "anthropic" || s.Kind == "deepseek" || s.Kind == "zcode" {
		return parseAIHTML(s, body)
	}
	out := []Article{}
	if s.Kind == "rss" || s.Kind == "rss-ai" {
		var doc rssDoc
		if e := xml.Unmarshal(body, &doc); e != nil {
			return nil, fmt.Errorf("RSS/Atom 格式无效")
		}
		for _, a := range doc.Channel.Items {
			date := a.PubDate
			if date == "" {
				date = a.Date
			}
			out = append(out, Article{Title: a.Title, URL: strings.TrimSpace(a.Link), Summary: a.Description, PublishedAt: parseDate(date)})
		}
		for _, a := range doc.Entries {
			link := ""
			for _, l := range a.Links {
				if l.Rel == "alternate" || l.Rel == "" {
					link = l.Href
					break
				}
			}
			date := a.Published
			if date == "" {
				date = a.Updated
			}
			summary := a.Summary
			if summary == "" {
				summary = a.Content
			}
			out = append(out, Article{Title: a.Title, URL: link, Summary: summary, PublishedAt: parseDate(date)})
		}
		if s.Kind == "rss-ai" {
			filtered := []Article{}
			for _, item := range out {
				if aiKeywordRE.MatchString(item.Title) {
					filtered = append(filtered, item)
				}
			}
			out = filtered
		}
		return finish(s, out), nil
	}
	if s.ID == "baidu" {
		match := baiduData.FindSubmatch(body)
		if len(match) != 2 {
			return nil, fmt.Errorf("页面数据结构已变化或返回访问验证")
		}
		body = match[1]
	}
	var root any
	decoder := json.NewDecoder(strings.NewReader(string(body)))
	decoder.UseNumber()
	if err := decoder.Decode(&root); err != nil {
		return nil, fmt.Errorf("未返回有效 JSON，可能返回了登录或验证页面")
	}
	for _, v := range listFor(s.ID, root) {
		m, ok := v.(map[string]any)
		if !ok {
			continue
		}
		a := Article{}
		switch s.ID {
		case "xinhuanet":
			a.Title = str(m["title"])
			a.URL = str(m["publishUrl"])
			a.Summary = str(m["summary"])
			if t, err := time.ParseInLocation("2006-01-02 15:04:05", str(m["publishTime"]), time.FixedZone("Asia/Shanghai", 8*3600)); err == nil {
				a.PublishedAt = t.Format(time.RFC3339)
			}
		case "baidu":
			if boolean(m["isTop"]) {
				continue
			}
			a.Title = str(m["word"])
			a.URL = str(m["rawUrl"])
			a.Summary = str(m["desc"])
			a.Heat = str(m["hotScore"])
		case "toutiao":
			a.Title = str(m["Title"])
			a.URL = "https://www.toutiao.com/trending/" + str(m["ClusterIdStr"]) + "/"
			a.Heat = str(m["HotValue"])
		case "douyin":
			a.Title = str(m["word"])
			a.URL = "https://www.douyin.com/search/" + url.PathEscape(a.Title)
			a.Heat = str(m["hot_value"])
		case "bilibili":
			a.Title = str(m["show_name"])
			if a.Title == "" {
				a.Title = str(m["keyword"])
			}
			a.URL = "https://search.bilibili.com/all?keyword=" + url.QueryEscape(str(m["keyword"]))
			a.Heat = str(m["heat_score"])
		case "bilibili-video":
			a.Title = str(m["title"])
			a.URL = "https://www.bilibili.com/video/" + str(m["bvid"])
			a.Summary = str(m["desc"])
			a.PublishedAt = parseDate(str(m["pubdate"]))
			a.Heat = str(path(m, "stat", "view")) + " 播放"
		case "thepaper":
			a.Title = str(m["name"])
			a.URL = "https://www.thepaper.cn/newsDetail_forward_" + str(m["contId"])
			a.PublishedAt = parseDate(str(m["pubTimeLong"]))
		case "wallstreetcn":
			a.Title = str(m["title"])
			if a.Title == "" {
				a.Title = str(m["content_text"])
			}
			a.Summary = str(m["content_text"])
			a.URL = str(m["uri"])
			a.PublishedAt = parseDate(str(m["display_time"]))
		case "weibo":
			a.Title = str(m["word"])
			if a.Title == "" {
				a.Title = str(m["note"])
			}
			a.URL = "https://s.weibo.com/weibo?q=" + url.QueryEscape("#"+a.Title+"#")
			a.Heat = str(m["num"])
		case "zhihu":
			a.Title = str(path(m, "target", "title_area", "text"))
			a.URL = str(path(m, "target", "link", "url"))
			a.Summary = str(path(m, "target", "excerpt_area", "text"))
			a.Heat = str(path(m, "target", "metrics_area", "text"))
		case "v2ex":
			a.Title = str(m["title"])
			a.URL = str(m["url"])
			a.Summary = str(m["content"])
			a.PublishedAt = parseDate(str(m["created"]))
			a.Heat = str(m["replies"]) + " 回复"
		case "hackernews":
			a.Title = str(m["title"])
			a.URL = "https://news.ycombinator.com/item?id=" + str(m["objectID"])
			a.PublishedAt = parseDate(str(m["created_at"]))
			a.Heat = str(m["points"]) + " points"
		case "tieba":
			a.Title = str(m["topic_name"])
			a.URL = str(m["topic_url"])
			a.PublishedAt = parseDate(str(m["create_time"]))
			a.Heat = str(m["discuss_num"])
		}
		out = append(out, a)
	}
	return finish(s, out), nil
}
func path(v any, keys ...string) any {
	for _, k := range keys {
		m, ok := v.(map[string]any)
		if !ok {
			return nil
		}
		v = m[k]
	}
	return v
}
func listFor(id string, root any) []any {
	var v any
	switch id {
	case "xinhuanet":
		v = path(root, "datasource")
	case "baidu":
		cards, _ := path(root, "data", "cards").([]any)
		if len(cards) > 0 {
			v = path(cards[0], "content")
		}
	case "bilibili":
		v = path(root, "list")
	case "toutiao", "zhihu":
		v = path(root, "data")
	case "douyin":
		v = path(root, "word_list")
	case "bilibili-video":
		v = path(root, "data", "list")
	case "thepaper":
		v = path(root, "data", "hotNews")
	case "wallstreetcn":
		v = path(root, "data", "items")
	case "weibo":
		v = path(root, "data", "realtime")
		if v == nil {
			v = path(root, "data", "band_list")
		}
	case "v2ex":
		v = root
	case "hackernews":
		v = path(root, "hits")
	case "tieba":
		v = path(root, "data", "bang_topic", "topic_list")
	}
	a, _ := v.([]any)
	return a
}
func str(v any) string {
	if v == nil {
		return ""
	}
	switch x := v.(type) {
	case string:
		return x
	case json.Number:
		return x.String()
	default:
		return fmt.Sprint(x)
	}
}
func boolean(v any) bool { b, _ := v.(bool); return b }
