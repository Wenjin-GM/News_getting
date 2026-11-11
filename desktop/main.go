package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

const version = "1.1.0"

type SourceState struct {
	Source              Source    `json:"source"`
	Enabled             bool      `json:"enabled"`
	Status              string    `json:"status"`
	Items               []Article `json:"items"`
	LastAttempt         string    `json:"lastAttempt,omitempty"`
	LastSuccess         string    `json:"lastSuccess,omitempty"`
	Error               string    `json:"error,omitempty"`
	Channel             string    `json:"channel,omitempty"`
	DurationMS          int64     `json:"durationMs"`
	Attempts            int       `json:"attempts"`
	Successes           int       `json:"successes"`
	ConsecutiveFailures int       `json:"consecutiveFailures"`
	NewCount            int       `json:"newCount"`
	RetryAt             string    `json:"retryAt,omitempty"`
}
type Run struct {
	Scope      string `json:"scope,omitempty"`
	ID         string `json:"id"`
	StartedAt  string `json:"startedAt"`
	FinishedAt string `json:"finishedAt,omitempty"`
	Total      int    `json:"total"`
	Done       int    `json:"done"`
	Success    int    `json:"success"`
	Failed     int    `json:"failed"`
	Cached     int    `json:"cached"`
	NewCount   int    `json:"newCount"`
	Running    bool   `json:"running"`
}
type App struct {
	mu       sync.RWMutex
	states   map[string]*SourceState
	order    []string
	store    *Store
	token    string
	run      Run
	port     int
	bind     string
	dataDir  string
	started  time.Time
	ctx      context.Context
	logger   *log.Logger
	jobs     sync.WaitGroup
	shutdown context.CancelFunc
}

func nowString() string { return time.Now().Format(time.RFC3339) }
func randomID() string {
	b := make([]byte, 24)
	if _, e := rand.Read(b); e != nil {
		panic(e)
	}
	return hex.EncodeToString(b)
}
func newApp(store *Store, dir string, logger *log.Logger) (*App, error) {
	states, e := store.loadSources()
	if e != nil {
		return nil, e
	}
	a := &App{states: states, store: store, dataDir: dir, started: time.Now(), logger: logger, ctx: context.Background()}
	if !store.get("token", &a.token) || len(a.token) < 32 {
		a.token = randomID()
		if e = store.set("token", a.token); e != nil {
			return nil, e
		}
	}
	for _, src := range builtins() {
		a.order = append(a.order, src.ID)
		if old, ok := a.states[src.ID]; ok {
			old.Source = src
			if old.Status == "loading" {
				old.Status = "cached"
			}
		} else {
			a.states[src.ID] = &SourceState{Source: src, Enabled: src.DefaultEnabled, Status: "idle", Items: []Article{}}
		}
	}
	var custom []Source
	store.get("customSources", &custom)
	for _, src := range custom {
		a.order = append(a.order, src.ID)
		if old, ok := a.states[src.ID]; ok {
			old.Source = src
			if old.Status == "loading" {
				old.Status = "cached"
			}
		} else {
			a.states[src.ID] = &SourceState{Source: src, Enabled: true, Status: "idle", Items: []Article{}}
		}
	}
	return a, nil
}
func (a *App) refresh(force bool, only string) (Run, error) {
	return a.refreshCategory(force, only, "")
}
func (a *App) refreshCategory(force bool, only, category string) (Run, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.run.Running {
		return a.run, nil
	}
	if only != "" {
		if _, ok := a.states[only]; !ok {
			return Run{}, fmt.Errorf("数据源不存在")
		}
	}
	selected := []string{}
	for _, id := range a.order {
		s := a.states[id]
		if (category == "ai" && s.Source.Category != "ai") || (category == "general" && s.Source.Category == "ai") {
			continue
		}
		if !s.Enabled || (only != "" && id != only) {
			continue
		}
		selected = append(selected, id)
	}
	if len(selected) == 0 {
		return Run{}, fmt.Errorf("请先启用至少一个数据源")
	}
	a.run = Run{ID: randomID()[:16], StartedAt: nowString(), Total: len(selected), Running: true, Scope: category}
	for _, id := range selected {
		s := a.states[id]
		s.NewCount = 0
	}
	a.jobs.Add(1)
	go func() { defer a.jobs.Done(); a.runRefresh(selected, force) }()
	return a.run, nil
}
func (a *App) runRefresh(ids []string, force bool) {
	ctx, cancel := context.WithTimeout(a.ctx, 35*time.Second)
	defer cancel()
	sem := make(chan struct{}, 5)
	var wg sync.WaitGroup
	for _, id := range ids {
		wg.Add(1)
		go func(id string) {
			defer wg.Done()
			select {
			case sem <- struct{}{}:
				defer func() { <-sem }()
			case <-ctx.Done():
				a.finishSkipped(id, "本轮采集已截止")
				return
			}
			a.mu.Lock()
			s := a.states[id]
			interval := time.Duration(s.Source.Interval) * time.Second
			if force {
				interval = 60 * time.Second
			}
			last, _ := time.Parse(time.RFC3339, s.LastAttempt)
			retry, _ := time.Parse(time.RFC3339, s.RetryAt)
			if time.Since(last) < interval || (!force && time.Now().Before(retry)) {
				a.run.Done++
				a.run.Cached++
				a.mu.Unlock()
				return
			}
			s.Status = "loading"
			s.Error = ""
			s.LastAttempt = nowString()
			s.Attempts++
			src := s.Source
			a.mu.Unlock()
			started := time.Now()
			items, channel, err := fetchSource(ctx, src)
			a.mu.Lock()
			s = a.states[id]
			s.DurationMS = time.Since(started).Milliseconds()
			s.NewCount = 0
			if err != nil {
				s.Error = shortError(err)
				s.ConsecutiveFailures++
				s.Status = "error"
				if len(s.Items) > 0 {
					s.Status = "cached"
				}
				a.run.Failed++
				if s.ConsecutiveFailures >= 3 {
					backoff := min(s.ConsecutiveFailures*300, 3600)
					s.RetryAt = time.Now().Add(time.Duration(backoff) * time.Second).Format(time.RFC3339)
				}
			} else {
				old := map[string]bool{}
				for _, v := range s.Items {
					old[v.ID] = true
				}
				for _, v := range items {
					if !old[v.ID] {
						s.NewCount++
					}
				}
				s.Items = items
				s.LastSuccess = nowString()
				s.Status = "success"
				if s.NewCount == 0 {
					s.Status = "unchanged"
				}
				s.Successes++
				s.ConsecutiveFailures = 0
				s.RetryAt = ""
				s.Channel = channel
				a.run.Success++
				a.run.NewCount += s.NewCount
			}
			a.run.Done++
			snapshot := *s
			a.mu.Unlock()
			if e := a.store.saveSource(snapshot); e != nil {
				a.logger.Printf("persist source %s: %v", id, e)
			}
			if err != nil {
				a.logger.Printf("source %s failed: %s", id, snapshot.Error)
			} else {
				a.logger.Printf("source %s: %d items (%d ms)", id, len(items), snapshot.DurationMS)
			}
		}(id)
	}
	wg.Wait()
	a.mu.Lock()
	a.run.Running = false
	a.run.FinishedAt = nowString()
	run := a.run
	a.mu.Unlock()
	if e := a.store.saveRun(run); e != nil {
		a.logger.Printf("persist run: %v", e)
	}
	a.store.prune()
}
func (a *App) finishSkipped(id, msg string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	s := a.states[id]
	s.Error = msg
	s.Status = "error"
	if len(s.Items) > 0 {
		s.Status = "cached"
	}
	a.run.Done++
	a.run.Failed++
}
func localIPs() []string {
	ips := []string{}
	ifs, _ := net.Interfaces()
	score := func(name string) int {
		n := strings.ToLower(name)
		if strings.Contains(n, "wlan") || strings.Contains(n, "wi-fi") || strings.Contains(n, "wifi") {
			return 0
		}
		if strings.Contains(n, "vmware") || strings.Contains(n, "virtual") || strings.Contains(n, "vethernet") || strings.Contains(n, "vmnet") {
			return 2
		}
		return 1
	}
	sort.SliceStable(ifs, func(i, j int) bool { return score(ifs[i].Name) < score(ifs[j].Name) })
	for _, i := range ifs {
		if i.Flags&net.FlagUp == 0 || i.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := i.Addrs()
		for _, addr := range addrs {
			ip, _, e := net.ParseCIDR(addr.String())
			if e == nil && ip.To4() != nil && ip.IsPrivate() {
				ips = append(ips, ip.String())
			}
		}
	}
	return ips
}
func main() {
	exe, _ := os.Executable()
	defaultDir := filepath.Join(filepath.Dir(exe), "data")
	data := flag.String("data", defaultDir, "data directory (all persistent files stay here)")
	port := flag.Int("port", 8765, "HTTP port")
	bind := flag.String("bind", "127.0.0.1", "listen IP; use 0.0.0.0 for LAN access")
	auto := flag.Bool("refresh", true, "refresh sources on start")
	probe := flag.Bool("probe", false, "probe all sources and exit")
	probeCategory := flag.String("probe-category", "", "only probe a category, e.g. ai")
	flag.Parse()
	dir, e := filepath.Abs(*data)
	if e != nil {
		log.Fatal(e)
	}
	if e = os.MkdirAll(dir, 0700); e != nil {
		log.Fatal(e)
	}
	logFile, e := os.OpenFile(filepath.Join(dir, "newsdesk.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600)
	if e != nil {
		log.Fatal(e)
	}
	defer logFile.Close()
	logger := log.New(logFile, "", log.LstdFlags)
	if *probe {
		ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
		defer cancel()
		var wg sync.WaitGroup
		for _, s := range builtins() {
			if *probeCategory != "" && s.Category != *probeCategory {
				continue
			}
			wg.Add(1)
			go func(s Source) {
				defer wg.Done()
				start := time.Now()
				items, ch, err := fetchSource(ctx, s)
				if err != nil {
					fmt.Printf("FAIL %-18s %5dms %v\n", s.ID, time.Since(start).Milliseconds(), err)
				} else {
					fmt.Printf("OK   %-18s %5dms %2d items %s\n", s.ID, time.Since(start).Milliseconds(), len(items), ch)
				}
			}(s)
		}
		wg.Wait()
		return
	}
	store, e := openStore(dir)
	if e != nil {
		log.Fatal(e)
	}
	defer store.db.Close()
	app, e := newApp(store, dir, logger)
	if e != nil {
		log.Fatal(e)
	}
	app.bind = *bind
	listener, e := net.Listen("tcp", net.JoinHostPort(*bind, fmt.Sprint(*port)))
	if e != nil {
		log.Fatal(e)
	}
	app.port = listener.Addr().(*net.TCPAddr).Port
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt)
	defer cancel()
	app.ctx = ctx
	app.shutdown = cancel
	server := &http.Server{Handler: app.routes(), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 15 * time.Second, WriteTimeout: 20 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16384}
	info := app.networkInfo()
	info["pid"] = os.Getpid()
	info["executable"] = exe
	if e = writeRuntime(dir, info); e != nil {
		logger.Printf("runtime info: %v", e)
	}
	defer os.Remove(filepath.Join(dir, "runtime.json"))
	fmt.Printf("\n  NewsDesk %s | 一览 · 新闻与热点\n\n  Local: %s\n  Data:  %s\n", version, info["localUrl"], dir)
	if *bind == "0.0.0.0" {
		fmt.Printf("  LAN:   %v\n", info["lanUrls"])
	}
	fmt.Println("\n  Keep this process running. Ctrl+C to stop.\n  No firewall or startup settings are modified.")
	if *auto {
		app.refresh(false, "")
	}
	shutdownDone := make(chan struct{})
	go func() {
		<-ctx.Done()
		shutdown, done := context.WithTimeout(context.Background(), 10*time.Second)
		defer done()
		server.Shutdown(shutdown)
		close(shutdownDone)
	}()
	if e = server.Serve(listener); e != nil && e != http.ErrServerClosed {
		logger.Fatal(e)
	}
	if ctx.Err() != nil {
		<-shutdownDone
	}
	app.jobs.Wait()
}
