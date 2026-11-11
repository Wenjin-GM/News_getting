// Package sourceconfig exposes the same maintained registry used by Android.
package sourceconfig

import _ "embed"

//go:embed sources.json
var sources string

// JSON returns a copy so callers cannot mutate the embedded registry.
func JSON() []byte { return []byte(sources) }
