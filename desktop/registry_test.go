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
		if !reflect.DeepEqual(actual, expected[i]) || record.Enabled == expected[i].Experimental {
			t.Fatalf("source registry differs at %s; update config/sources.json with the source definition", expected[i].ID)
		}
	}
}
