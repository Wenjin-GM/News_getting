package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"path/filepath"
	"time"

	_ "modernc.org/sqlite"
)

type Store struct{ db *sql.DB }

func openStore(dir string) (*Store, error) {
	db, err := sql.Open("sqlite", filepath.Join(dir, "newsdesk.db"))
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	_, err = db.Exec(`PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;
	CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT NOT NULL);
	CREATE TABLE IF NOT EXISTS sources (id TEXT PRIMARY KEY, body TEXT NOT NULL);
	CREATE TABLE IF NOT EXISTS articles (id TEXT PRIMARY KEY, source TEXT NOT NULL, fetched_at TEXT NOT NULL, body TEXT NOT NULL);
	CREATE INDEX IF NOT EXISTS articles_time ON articles(fetched_at);
	CREATE TABLE IF NOT EXISTS favorites (id TEXT PRIMARY KEY, saved_at TEXT NOT NULL, body TEXT NOT NULL);
	CREATE TABLE IF NOT EXISTS runs (id TEXT PRIMARY KEY, started_at TEXT NOT NULL, body TEXT NOT NULL);`)
	if err != nil {
		db.Close()
		return nil, err
	}
	return &Store{db}, nil
}
func (s *Store) get(key string, target any) bool {
	var body string
	if s.db.QueryRow("SELECT value FROM kv WHERE key=?", key).Scan(&body) != nil {
		return false
	}
	return json.Unmarshal([]byte(body), target) == nil
}
func (s *Store) set(key string, v any) error {
	b, e := json.Marshal(v)
	if e != nil {
		return e
	}
	_, e = s.db.Exec("INSERT INTO kv(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", key, string(b))
	return e
}
func (s *Store) loadSources() (map[string]*SourceState, error) {
	rows, e := s.db.Query("SELECT id,body FROM sources")
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	out := map[string]*SourceState{}
	for rows.Next() {
		var id, body string
		if e = rows.Scan(&id, &body); e != nil {
			return nil, e
		}
		var state SourceState
		if json.Unmarshal([]byte(body), &state) == nil {
			out[id] = &state
		}
	}
	return out, rows.Err()
}
func (s *Store) saveSource(state SourceState) error {
	body, e := json.Marshal(state)
	if e != nil {
		return e
	}
	tx, e := s.db.Begin()
	if e != nil {
		return e
	}
	defer tx.Rollback()
	if _, e = tx.Exec("INSERT INTO sources(id,body) VALUES(?,?) ON CONFLICT(id) DO UPDATE SET body=excluded.body", state.Source.ID, string(body)); e != nil {
		return e
	}
	if state.Status == "success" || state.Status == "unchanged" {
		for _, a := range state.Items {
			b, _ := json.Marshal(a)
			if _, e = tx.Exec("INSERT INTO articles(id,source,fetched_at,body) VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET fetched_at=excluded.fetched_at,body=excluded.body", a.ID, a.Source, a.FetchedAt, string(b)); e != nil {
				return e
			}
		}
	}
	return tx.Commit()
}
func (s *Store) saveRun(run Run) error {
	b, _ := json.Marshal(run)
	_, e := s.db.Exec("INSERT INTO runs(id,started_at,body) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET body=excluded.body", run.ID, run.StartedAt, string(b))
	return e
}
func (s *Store) runs() ([]Run, error) {
	rows, e := s.db.Query("SELECT body FROM runs ORDER BY started_at DESC LIMIT 100")
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	out := []Run{}
	for rows.Next() {
		var b string
		if e = rows.Scan(&b); e != nil {
			return nil, e
		}
		var r Run
		if json.Unmarshal([]byte(b), &r) == nil {
			out = append(out, r)
		}
	}
	return out, rows.Err()
}
func (s *Store) articles(source, date string) ([]Article, error) {
	query := "SELECT body FROM articles WHERE 1=1"
	args := []any{}
	if source != "" {
		query += " AND source=?"
		args = append(args, source)
	}
	if date != "" {
		if _, e := time.Parse("2006-01-02", date); e != nil {
			return nil, fmt.Errorf("日期格式应为 YYYY-MM-DD")
		}
		query += " AND substr(fetched_at,1,10)=?"
		args = append(args, date)
	}
	query += " ORDER BY fetched_at DESC LIMIT 1000"
	rows, e := s.db.Query(query, args...)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	out := []Article{}
	for rows.Next() {
		var b string
		if e = rows.Scan(&b); e != nil {
			return nil, e
		}
		var a Article
		if json.Unmarshal([]byte(b), &a) == nil {
			out = append(out, a)
		}
	}
	return out, rows.Err()
}
func (s *Store) favorites() ([]Article, error) {
	rows, e := s.db.Query("SELECT body FROM favorites ORDER BY saved_at DESC")
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	out := []Article{}
	for rows.Next() {
		var b string
		if e = rows.Scan(&b); e != nil {
			return nil, e
		}
		var a Article
		if json.Unmarshal([]byte(b), &a) == nil {
			out = append(out, a)
		}
	}
	return out, rows.Err()
}
func (s *Store) favorite(a Article, on bool) error {
	if !on {
		_, e := s.db.Exec("DELETE FROM favorites WHERE id=?", a.ID)
		return e
	}
	b, _ := json.Marshal(a)
	_, e := s.db.Exec("INSERT INTO favorites(id,saved_at,body) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET body=excluded.body", a.ID, time.Now().Format(time.RFC3339), string(b))
	return e
}
func (s *Store) prune() {
	s.db.Exec("DELETE FROM articles WHERE fetched_at<?", time.Now().AddDate(0, 0, -30).Format(time.RFC3339))
	s.db.Exec("DELETE FROM runs WHERE id NOT IN (SELECT id FROM runs ORDER BY started_at DESC LIMIT 1000)")
}
