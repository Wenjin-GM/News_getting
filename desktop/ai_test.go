package main

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func TestAICatalogIntegrity(t *testing.T) {
	c := aiCatalog()
	if e := validateCatalog(c); e != nil {
		t.Fatal(e)
	}
	if len(c.Providers) < 15 || len(c.Tools) < 30 {
		t.Fatal("directory coverage unexpectedly reduced")
	}
	sources := map[string]Source{}
	for _, s := range builtins() {
		if _, ok := sources[s.ID]; ok {
			t.Fatalf("duplicate source %s", s.ID)
		}
		sources[s.ID] = s
	}
	for _, tool := range c.Tools {
		if tool.SourceID != "" {
			s, ok := sources[tool.SourceID]
			if !ok || s.Provider != tool.Provider {
				t.Fatalf("wrong source mapping for %s", tool.ID)
			}
		}
	}
	if c.Tools[0].Provider != "openai" {
		t.Fatal("catalog order unexpectedly changed")
	}
}
func TestAIPageParsersAndDatePrecision(t *testing.T) {
	news := Source{ID: "ai-anthropic", Kind: "anthropic", Category: "ai", Provider: "anthropic"}
	body := `<time>Oct 27, 2026</time><a href="/news/test"><h3>Official model update</h3><p>News summary.</p></a><a href="/news/announcements">Announcements</a>`
	items, e := parseSource(news, []byte(body))
	if e != nil || len(items) != 1 || items[0].PublishedAt != "2026-10-27T00:00:00Z" || !items[0].DateOnly || items[0].Provider != "anthropic" {
		t.Fatalf("bad newsroom result: %+v %v", items, e)
	}
	news.Kind = "deepseek"
	news.Home = "https://api-docs.deepseek.com/updates/"
	body = `<h2 id="date-2026-10-17">Date: 2026-10-17</h2><h3>DeepSeek update</h3><p>New capabilities.</p><h2 id="date-2026-09-10">Date: 2026-09-10</h2><h3>Previous release</h3>`
	items, e = parseSource(news, []byte(body))
	if e != nil || len(items) != 2 || items[0].Title != "DeepSeek update" || !strings.HasSuffix(items[0].URL, "#date-2026-10-17") {
		t.Fatal(items, e)
	}
	news.Kind = "zcode"
	news.Home = "https://zcode.z.ai/en/changelog"
	body = `<span>Released Oct 23, 2026</span><h2>Release v3.16.3</h2><h2>New Features</h2><ul><li>Improved sessions</li></ul><h2>Bug Fixes</h2><p>Fixed issues.</p><h2>Release v3.16.2</h2>`
	items, e = parseSource(news, []byte(body))
	if e != nil || len(items) != 2 || !strings.Contains(items[0].Summary, "Improved sessions") {
		t.Fatal(items, e)
	}
}
func TestReleaseFeedsFallbackAndContent(t *testing.T) {
	s := Source{ID: "ai-test", Kind: "releases", Category: "ai", Provider: "openai"}
	atom := `<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>v1.2.0-beta</title><link href="https://github.com/example/repo/releases/tag/v1.2.0-beta"/><updated>2026-10-27T10:00:00Z</updated><content type="html">&lt;p&gt;Fixed tool execution&lt;/p&gt;</content></entry></feed>`
	items, e := parseSource(s, []byte(atom))
	if e != nil || len(items) != 1 || items[0].Summary != "Fixed tool execution" {
		t.Fatal(items, e)
	}
	api := `[{"tag_name":"v1","html_url":"https://github.com/example/repo/releases/tag/v1","body":"Release notes","published_at":"2026-10-27T10:00:00Z","prerelease":true},{"tag_name":"draft","draft":true}]`
	items, e = parseSource(s, []byte(api))
	if e != nil || len(items) != 1 || items[0].Heat != "预发布" {
		t.Fatal(items, e)
	}
	if e = validateFreshness(Source{Kind: "rss", Category: "ai"}, []Article{{PublishedAt: "2025-01-01T00:00:00Z"}}, time.Now()); e != nil {
		t.Fatal("low-frequency release records falsely rejected", e)
	}
}
func TestAIRefreshIsScopedAndExportWorks(t *testing.T) {
	a := testApp(t)
	for _, s := range a.states {
		s.Enabled = false
	}
	ai := a.states["ai-codex"]
	ai.Enabled = true
	ai.LastAttempt = nowString()
	general := a.states["chinanews"]
	general.Enabled = true
	general.LastAttempt = nowString()
	run, e := a.refreshCategory(true, "", "ai")
	if e != nil || run.Total != 1 || run.Scope != "ai" {
		t.Fatal(run, e)
	}
	a.jobs.Wait()
	if general.Attempts != 0 {
		t.Fatal("AI refresh requested general news")
	}
	w := request(a, "GET", "/api/ai/catalog", "", false)
	if w.Code != 401 {
		t.Fatal("catalog bypassed auth")
	}
	w = request(a, "GET", "/api/ai/catalog", "", true)
	if w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	for _, format := range []string{"json", "md", "html"} {
		w = request(a, "POST", "/api/export", `{"format":"`+format+`","scope":"ai-catalog"}`, true)
		if w.Code != 200 {
			t.Fatal(w.Body.String())
		}
		var result map[string]any
		if json.Unmarshal(w.Body.Bytes(), &result) != nil || int(result["count"].(float64)) != len(aiCatalog().Tools) {
			t.Fatal("incomplete catalog export")
		}
	}
}
func TestAIMediaFilterDoesNotAdmitOrdinaryTechnologyNews(t *testing.T) {
	s := Source{ID: "ai-media", Kind: "rss-ai", Category: "ai"}
	body := `<rss><channel><item><title>New AI coding tools from OpenAI</title><link>https://example.com/ai</link></item><item><title>A new phone battery</title><link>https://example.com/phone</link></item></channel></rss>`
	items, e := parseSource(s, []byte(body))
	if e != nil || len(items) != 1 || items[0].URL != "https://example.com/ai" {
		t.Fatal(items, e)
	}
}
