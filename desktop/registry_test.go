package main

import (
	"encoding/json"
	"os"
	"reflect"
	"testing"
)

// Protect the source snapshot used by the standalone APK against desktop registry drift.
func TestAndroidSourceRegistryMatchesDesktop(t *testing.T) {
	var records []struct {
		Source
		Endpoints []string `json:"endpoints"`
		Enabled   bool     `json:"enabled"`
	}
	body, err := os.ReadFile("../config/sources.json")
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(body, &records); err != nil {
		t.Fatal(err)
	}
	expected := builtins()
	if len(records) != len(expected) {
		t.Fatalf("Android %d sources, desktop %d", len(records), len(expected))
	}
	for i, record := range records {
		actual := record.Source
		actual.Endpoints = record.Endpoints
		actual.DefaultEnabled = record.Enabled
		if !reflect.DeepEqual(actual, expected[i]) {
			t.Fatalf("source registry differs at %s; update config/sources.json with the source definition", expected[i].ID)
		}
	}
}

func TestSharedDefaultsAndNewParser(t *testing.T) {
	app := testApp(t)
	if !app.states["weibo"].Source.Experimental || !app.states["weibo"].Enabled || app.states["linuxdo"].Enabled {
		t.Fatal("default enabled and experimental metadata must remain independent")
	}
	if _, ok := app.states["toms-hardware"]; ok {
		t.Fatal("removed source returned in a fresh registry")
	}
	for _, id := range []string{"ai-aihot", "qbitai", "ai-media-ithome", "ai-media-ifanr"} {
		if app.states[id].Source.Authority == "official" {
			t.Fatal("media classified as vendor", id)
		}
	}
	items, err := parseSource(app.states["douyin"].Source, []byte(`{"word_list":[{"word":"AI agents","hot_value":12345}]}`))
	if err != nil || len(items) != 1 || items[0].Heat != "12345" || items[0].URL != "https://www.douyin.com/search/AI%20agents" {
		t.Fatalf("shared source must have a usable desktop adapter: %+v %v", items, err)
	}
}
