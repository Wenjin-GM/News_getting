// Package catalog contains the single maintained AI tools catalog shared by both apps.
package catalog

import _ "embed"

//go:embed ai_catalog.json
var AIJSON []byte
