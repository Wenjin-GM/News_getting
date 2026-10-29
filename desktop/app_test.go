package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

func testApp(t *testing.T) *App {
	t.Helper()
	dir := t.TempDir()
	s, e := openStore(dir)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { s.db.Close() })
	a, e := newApp(s, dir, log.New(io.Discard, "", 0))
	if e != nil {
		t.Fatal(e)
	}
	return a
}
func request(a *App, method, path, body string, auth bool) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, strings.NewReader(body))
	if auth {
		r.Header.Set("Authorization", "Bearer "+a.token)
	}
	w := httptest.NewRecorder()
	a.routes().ServeHTTP(w, r)
	return w
}
func TestRSSAndAtomValidation(t *testing.T) {
	s := Source{ID: "test", Name: "测试", Category: "news", Kind: "rss"}
	rss := `<rss><channel><item><title><![CDATA[新闻 <b>标题</b>]]></title><link>https://example.com/a</link><description><![CDATA[<p>这是摘要</p>]]></description><pubDate>Wed, 28 Oct 2026 18:56:20 +0800</pubDate></item><item><title>不安全链接</title><link>javascript:alert(1)</link></item><item><title>重复链接</title><link>https://example.com/a</link></item></channel></rss>`
	items, e := parseSource(s, []byte(rss))
	if e != nil || len(items) != 1 {
		t.Fatalf("items=%v err=%v", items, e)
	}
	if items[0].Title != "新闻 标题" || items[0].Summary != "这是摘要" || items[0].PublishedAt == "" {
		t.Fatalf("invalid normalized RSS: %+v", items[0])
	}
	atom := `<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>Atom 标题</title><link rel="self" href="https://example.com/self"/><link rel="alternate" href="https://example.com/article"/><published>2026-10-28T10:00:00Z</published></entry></feed>`
	items, e = parseSource(s, []byte(atom))
	if e != nil || len(items) != 1 || items[0].URL != "https://example.com/article" {
		t.Fatalf("atom failed: %v %v", items, e)
	}
}
func TestJSONPreservesLargeIDs(t *testing.T) {
	s := Source{ID: "toutiao", Name: "头条", Kind: "json"}
	items, e := parseSource(s, []byte(`{"data":[{"Title":"热点标题","ClusterIdStr":"7701651687387332662","HotValue":"92139502"}]}`))
	if e != nil || len(items) != 1 || !strings.Contains(items[0].URL, "7701651687387332662") {
		t.Fatal(items, e)
	}
	s.ID = "baidu"
	if _, e = parseSource(s, []byte(`<html>请登录</html>`)); e == nil {
		t.Fatal("accepted challenge page")
	}
}
func TestAuthAndPersistentPreferences(t *testing.T) {
	a := testApp(t)
	if w := request(a, "GET", "/api/state", "", false); w.Code != 401 {
		t.Fatal(w.Code)
	}
	w := request(a, "POST", "/api/source", `{"id":"chinanews","enabled":false}`, true)
	if w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	again, e := newApp(a.store, a.dataDir, a.logger)
	if e != nil || again.states["chinanews"].Enabled {
		t.Fatal("preference did not persist", e)
	}
	if again.token != a.token {
		t.Fatal("access key changed on restart")
	}
	if w := request(a, "GET", "/", "", false); w.Code != 200 || !strings.Contains(w.Body.String(), "NewsDesk") {
		t.Fatal("embedded page missing")
	}
}

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
func TestFailedRefreshKeepsSnapshotAndDoesNotCountSuccess(t *testing.T) {
	a := testApp(t)
	for _, s := range a.states {
		s.Enabled = false
	}
	s := a.states["chinanews"]
	s.Enabled = true
	s.Items = []Article{{ID: "old", Title: "之前的新闻", URL: "https://example.com/old"}}
	s.LastSuccess = time.Now().Add(-time.Hour).Format(time.RFC3339)
	oldClient := httpClient
	httpClient = &http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) { return nil, fmt.Errorf("connection refused") })}
	defer func() { httpClient = oldClient }()
	run, e := a.refresh(true, "")
	if e != nil {
		t.Fatal(e)
	}
	a.jobs.Wait()
	if s.Status != "cached" || len(s.Items) != 1 || s.Successes != 0 || s.Attempts != 1 {
		t.Fatalf("wrong fallback: %+v", s)
	}
	if a.run.Failed != 1 || a.run.Success != 0 || a.run.Running {
		t.Fatalf("wrong run: %+v initial %+v", a.run, run)
	}
	if _, e = a.refresh(true, ""); e != nil {
		t.Fatal(e)
	}
	a.jobs.Wait()
	if a.run.Cached != 1 || a.run.Success != 0 || s.Attempts != 1 {
		t.Fatal("cache counted as a new success")
	}
}
func TestSuccessPersistenceFavoritesExport(t *testing.T) {
	a := testApp(t)
	for _, s := range a.states {
		s.Enabled = false
	}
	s := a.states["chinanews"]
	s.Enabled = true
	oldClient := httpClient
	httpClient = &http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(`<rss><channel><item><title><![CDATA[<img src=x onerror=alert(1)>测试新闻]]></title><link>https://example.com/a</link></item></channel></rss>`)), Header: make(http.Header)}, nil
	})}
	defer func() { httpClient = oldClient }()
	if _, e := a.refresh(true, ""); e != nil {
		t.Fatal(e)
	}
	a.jobs.Wait()
	if s.Status != "success" || len(s.Items) != 1 {
		t.Fatal(s)
	}
	body := fmt.Sprintf(`{"id":%q,"on":true}`, s.Items[0].ID)
	if w := request(a, "POST", "/api/favorites", body, true); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	again, e := newApp(a.store, a.dataDir, a.logger)
	if e != nil {
		t.Fatal(e)
	}
	f, _ := again.store.favorites()
	h, _ := again.store.articles("", "")
	if len(f) != 1 || len(h) != 1 {
		t.Fatal("missing persistent favorite/history")
	}
	w := request(a, "POST", "/api/export", `{"format":"html","scope":"favorites"}`, true)
	if w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	var result map[string]any
	json.Unmarshal(w.Body.Bytes(), &result)
	content, e := os.ReadFile(result["path"].(string))
	if e != nil || strings.Contains(string(content), "onerror=") {
		t.Fatal("unsafe export", e)
	}
	if !strings.HasPrefix(result["path"].(string), a.dataDir) {
		t.Fatal("export escaped data directory")
	}
}
func TestConcurrentRefreshReusesJob(t *testing.T) {
	a := testApp(t)
	for _, s := range a.states {
		s.Enabled = false
	}
	a.states["chinanews"].Enabled = true
	oldClient := httpClient
	gate := make(chan struct{})
	httpClient = &http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
		select {
		case <-gate:
		case <-r.Context().Done():
			return nil, context.Canceled
		}
		return nil, fmt.Errorf("connection refused")
	})}
	defer func() { httpClient = oldClient }()
	first, _ := a.refresh(true, "")
	second, _ := a.refresh(true, "")
	if first.ID != second.ID {
		t.Fatal("duplicate refresh job")
	}
	close(gate)
	a.jobs.Wait()
}

func TestStaleOfficialFeedIsNotFreshSuccess(t *testing.T) {
	s := Source{ID: "people", Kind: "rss"}
	now := time.Date(2026, 10, 28, 12, 0, 0, 0, time.UTC)
	items := []Article{{PublishedAt: "2025-06-05T00:00:00Z"}}
	if validateFreshness(s, items, now) == nil {
		t.Fatal("accepted stale official news feed")
	}
	s.Custom = true
	if e := validateFreshness(s, items, now); e != nil {
		t.Fatal("custom slow-updating feeds should be allowed")
	}
}
